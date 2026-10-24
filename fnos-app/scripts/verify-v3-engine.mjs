// Explicit isolated HTTPS probe. stdin: credentials + private evidence path. Never reads v2.
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { mkdtemp, readFile, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { WebDav } from '../src/webdav.mjs';
import { V3WebDavStore, Budget } from '../src/v3-store.mjs';
import { V3Journal } from '../src/v3-transaction.mjs';
import { IndexedV3Cache } from '../src/v3-index.mjs';
import { V3Engine } from '../src/v3-engine.mjs';
import { emptyState } from '../src/merge.mjs';
let text = ''; for await (const b of process.stdin) { text += b; assert(text.length < 32768); }
const input = JSON.parse(text), mode = input.mode ?? 'init';
let config, binding;
if (mode === 'init') {
  const probe = new URL(`pr54-engine-${randomUUID()}/`, input.url), t = new WebDav(input); t.base = probe;
  await t.request('MKCOL', probe, 65536); config = { url: probe.href, username: input.username, password: input.password };
} else { ({ config, binding } = input.config ? input : JSON.parse(await readFile(input.privatePath, 'utf8'))); }
const root = await mkdtemp(join(tmpdir(), 'dp-pr54-probe-')), writer = randomUUID(); let journal = new V3Journal(root); await journal.open(); let engine = new V3Engine(journal, writer);
if (mode === 'init') binding = await engine.initialize(new V3WebDavStore(config, new Budget()), { ...emptyState(), entries: [{ id: 'node', title: 'Node initial', day: 1, startMinute: 480, endMinute: 540, location: '' }] });
else { const { decode } = await import('../src/v3-protocol.mjs'); await engine.bind(decode(await new V3WebDavStore(config, new Budget()).read(binding.descriptorPath)), binding.descriptorHash); }
for (let n = 0; n < 5; n++) {
  const budget = new Budget(); try { await engine.round(new V3WebDavStore(config, budget), new IndexedV3Cache(root, budget), budget, { discoverySteps: 8 }); }
  catch (e) { if (!/initial discovery incomplete/.test(e.message)) throw e; }
}
if (mode !== 'init') assert(journal.value.local.entries.some(e => e.id === 'kotlin'), 'Kotlin edit missing');
let recoveryPuts = 0;
if (mode === 'receive-publish') {
  await engine.edit({ ...journal.value.local, entries: [...journal.value.local.entries.filter(e => e.id !== 'fnos'), { id: 'fnos', title: 'fnOS Node 22 ' + randomUUID(), day: 3, startMinute: 660, endMinute: 720, location: '' }] });
  const budget = new Budget(), remote = new V3WebDavStore(config, budget), put = remote.putOnce.bind(remote);
  remote.putOnce = async (p, raw) => { recoveryPuts++; await put(p, raw); throw Error('injected lost PUT response'); };
  await assert.rejects(engine.round(remote, new IndexedV3Cache(root, budget), budget), /lost PUT response/);
  journal = new V3Journal(root); await journal.open(); engine = new V3Engine(journal, writer);
  const recoveryBudget = new Budget(); await engine.round(new V3WebDavStore(config, recoveryBudget), new IndexedV3Cache(root, recoveryBudget), recoveryBudget);
  assert.equal(recoveryPuts, 1); assert.equal(journal.value.pending.phase, 'RECEIPTED');
}
if (mode === 'init') await writeFile(input.privatePath, JSON.stringify({ config, binding, isolatedRoot: root }), { mode: 0o600 });
console.log(JSON.stringify({ mode, result: 'PASS', isolatedPath: new URL(config.url).pathname, entries: journal.value.local.entries.map(e => e.id), epochs: Object.keys(journal.value.history.roots).length, recoveryPuts }));
