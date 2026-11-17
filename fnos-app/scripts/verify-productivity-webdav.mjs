import assert from 'node:assert/strict';
import { readFile, mkdtemp } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { V3Engine } from '../src/v3-engine.mjs';
import { openV3Workspace } from '../src/v3-workspace.mjs';
import { V3WebDavStore, Budget } from '../src/v3-store.mjs';
import { IndexedV3Cache } from '../src/v3-index.mjs';
import { decode, businessHash } from '../src/v3-protocol.mjs';

const [url, seedFile] = process.argv.slice(2);
const endpoint = new URL(url);
assert.equal(endpoint.protocol, 'http:');
assert.match(endpoint.hostname, /^(?:10\.\d+|192\.168|172\.(?:1[6-9]|2\d|3[01]))\.\d+\.\d+$/);
assert.match(endpoint.pathname, /^\/pr87-isolated-[0-9a-f-]{36}\/$/);
const seed = JSON.parse(await readFile(seedFile, 'utf8'));
const root = await mkdtemp(join(tmpdir(), 'pr87-webdav-workspace-'));
const { workspace, journal, writerId } = await openV3Workspace(root, () => {});
try {
  const engine = new V3Engine(journal, writerId);
  const remote = budget => new V3WebDavStore({ url, username: 'fixture', password: 'fixture',
    sharedFormat: 1, allowInsecureHttp: true }, budget);
  const bytes = await remote(new Budget()).read(seed.binding.descriptorPath);
  await engine.bind(decode(bytes), seed.binding.descriptorHash);
  async function settle() {
    for (let attempt = 0; attempt < 4; attempt++) {
      const budget = new Budget();
      try {
        if ((await engine.round(remote(budget), new IndexedV3Cache(root, budget), budget, { discoverySteps: 32 })).status === 'OBSERVED') return;
      } catch (error) {
        if (!/incomplete/.test(error.message)) throw error;
      }
    }
    assert.fail('WebDAV did not settle');
  }
  await settle();
  const initial = workspace.productivity();
  assert.equal(initial.lists.length, 1);
  assert.equal(initial.tasks.length, 1);
  assert.equal(initial.tasks[0].text, 'Android 新任务');
  assert.equal(initial.tasks[0].completed, false);
  assert.equal(initial.memos[0].body, '由 Android 建立\r\n第二行');
  assert.equal(businessHash(journal.value.local), seed.hash);
  const taskId = initial.tasks[0].id;
  const memoId = initial.memos[0].id;
  const save = edit => workspace.saveProductivity(edit, workspace.productivity());
  await save({ action: 'editTask', id: taskId, text: 'Node 已编辑' });
  await save({ action: 'setTaskCompleted', id: taskId, completed: true });
  await save({ action: 'editMemo', id: memoId, body: 'Node 正文\n末尾\n' });
  await settle();
  const saved = await remote(new Budget()).read(journal.value.receipt.path);
  assert.equal(businessHash(decode(saved).state), businessHash(journal.value.local));
  console.log(businessHash(journal.value.local));
} finally {
  await journal.close();
}
