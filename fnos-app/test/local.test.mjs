import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, rm, readFile, writeFile, symlink, chmod, readdir, rename } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { LocalGrants, LocalCommitStore, segments } from '../src/local.mjs';
import { Store } from '../src/store.mjs';
import { Workspace } from '../src/workspace.mjs';
import { SyncEngine } from '../src/sync.mjs';
import { decode, encode, hash } from '../src/protocol.mjs';
import { loadGraph } from '../src/graph.mjs';
const linux = process.platform === 'linux';
async function fixture(t) {
  const dir = await mkdtemp(join(tmpdir(), 'pr39-local-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const parent = join(dir, 'authorized'); await mkdir(parent);
  const grants = new LocalGrants([{ id: 'approved', path: parent }]);
  const selected = await grants.directory('approved'); await selected.fd.close();
  const config = { rootId: 'approved', relative: '', directoryIdentity: selected.stamp };
  const remote = new LocalCommitStore(config, grants);
  return { dir, parent, grants, config, remote, protocol: join(parent, 'dormpanel-sync-v2') };
}
test('本地路径选择拒绝路径越界、编码绕过和未授权根', async () => {
  for (const p of ['..', '../x', '/etc', 'a/../b', 'a\\b', 'x//y', 'a\0b', '.', '.hidden', 'C:/Windows']) assert.throws(() => segments(p));
  await assert.rejects(new LocalGrants([]).directory('arbitrary-system-path'), /授权/);
});
test('未验证的平台拒绝本地访问和写入', { skip: linux }, async () => {
  await assert.rejects(new LocalGrants([{ id: 'x', path: tmpdir() }]).directory('x'), /Linux/);
});
test('真实 Linux 排他原子发布、DPC2 固定向量互操作与目录持久化', { skip: !linux }, async t => {
  const f = await fixture(t);
  await f.remote.test(); assert.deepEqual(await f.remote.list(), []);
  await f.remote.ensureDirectory();
  const bytes = await readFile(new URL('../../schedule-core/src/test/resources/sync-v2/complex-root.dpc', import.meta.url)), c = decode(bytes);
  await f.remote.put(c.commitId, bytes);
  assert.deepEqual(await f.remote.read(c.commitId), bytes);
  await assert.rejects(f.remote.put(c.commitId, bytes), { code: 'EEXIST' });
  assert.deepEqual(await readFile(join(f.protocol, `commit-${c.commitId}.dpc`)), bytes);
  const graph = await loadGraph(new LocalCommitStore(f.config, f.grants));
  assert.equal(graph.datasetId, c.datasetId); assert.deepEqual(encode(decode(Buffer.from(graph.raw[c.commitId], 'base64'))), bytes);
  assert.deepEqual(await readdir(f.protocol), [`commit-${c.commitId}.dpc`]);
  assert.deepEqual(await readdir(f.parent), ['dormpanel-sync-v2']);
});
test('两个本地数据集独立 SyncEngine，重启恢复精确候选和发布状态', { skip: !linux }, async t => {
  const f = await fixture(t), second = join(f.parent, 'second'); await mkdir(second);
  const selected = await f.grants.directory('approved', 'second'); await selected.fd.close();
  const r2 = new LocalCommitStore({ rootId: 'approved', relative: 'second', directoryIdentity: selected.stamp }, f.grants);
  const identity = await new Store(join(f.dir, 'app')).init();
  for (const [remote, name] of [[f.remote, 'A'], [r2, 'B']]) {
    const store = new Store(join(identity.dir, name)); store.identity = identity.identity;
    const db = await Workspace.open(store);
    await db.edit({ kind: 'entry', action: 'upsert', value: { title: name, day: 1, startMinute: 480, endMinute: 530, location: '' } }, 0);
    await new SyncEngine(db, remote).synchronize({ create: true });
    const restart = await Workspace.open(store);
    assert.equal(restart.frame.local.entries[0].title, name); assert(restart.frame.receipt);
    assert.equal(decode(await remote.read(restart.frame.receipt.commitId)).writerId, identity.identity.writerId);
  }
  assert.notEqual((await loadGraph(f.remote)).datasetId, (await loadGraph(r2)).datasetId);
});
test('符号链接文件/协议目录/授权子路径、目录替换和授权撤销安全失败', { skip: !linux }, async t => {
  const f = await fixture(t), outside = join(f.dir, 'outside'); await mkdir(outside);
  await symlink(outside, join(f.parent, 'escape'));
  await assert.rejects(f.grants.directory('approved', 'escape'));
  assert(!(await f.grants.browse('approved')).includes('escape'));
  await symlink(outside, f.protocol); await assert.rejects(f.remote.list());
  await rm(f.protocol); await f.remote.ensureDirectory();
  const id = randomUUID(); await writeFile(join(outside, 'secret'), 'secret');
  await symlink(join(outside, 'secret'), join(f.protocol, `commit-${id}.dpc`));
  await assert.rejects(f.remote.list()); await assert.rejects(f.remote.read(id));
  await rename(f.parent, f.parent + '-old'); await mkdir(f.parent);
  await assert.rejects(f.remote.list(), /授权目录/);
  f.grants.roots = []; await assert.rejects(f.remote.list(), /授权目录/);
  assert.equal(await readFile(join(outside, 'secret'), 'utf8'), 'secret');
});
test('只读目录、失去执行/读取权限安全失败，不创建提交', { skip: !linux || process.getuid?.() === 0 }, async t => {
  const f = await fixture(t); await f.remote.ensureDirectory();
  await chmod(f.parent, 0o500);
  try { await assert.rejects(f.remote.test()); }
  finally { await chmod(f.parent, 0o700); }
  await chmod(f.protocol, 0o500);
  const bytes = await readFile(new URL('../../schedule-core/src/test/resources/sync-v2/empty.dpc', import.meta.url)), c = decode(bytes);
  try { await assert.rejects(f.remote.put(c.commitId, bytes)); assert.deepEqual(await f.remote.list(), []); }
  finally { await chmod(f.protocol, 0o700); }
  await chmod(f.parent, 0o000);
  try { await assert.rejects(f.remote.list()); }
  finally { await chmod(f.parent, 0o700); }
});

test('官方授权深层目录可访问，无需获得其卷/用户祖先目录的列举权限', { skip: !linux || process.getuid?.() === 0 }, async t => {
  const f = await fixture(t), ancestor = join(f.dir, 'traverse-only'), path = join(ancestor, 'selected');
  await mkdir(path, { recursive: true }); await chmod(ancestor, 0o111);
  try {
    const grants = new LocalGrants([{ id: 'deep', path }]);
    const selected = await grants.directory('deep'); await selected.fd.close();
    const remote = new LocalCommitStore({ rootId: 'deep', relative: '', directoryIdentity: selected.stamp }, grants);
    await remote.test(); await remote.ensureDirectory(); assert.deepEqual(await remote.list(), []);
  } finally { await chmod(ancestor, 0o700); }
});
test('崩溃暂存残留不进入协议目录，损坏/混入文件和资源超限不重写历史', { skip: !linux }, async t => {
  const f = await fixture(t); await f.remote.ensureDirectory();
  const residual = join(f.parent, '.dormpanel-stage-crashed'); await mkdir(residual); await writeFile(join(residual, 'content'), 'partial');
  assert.deepEqual(await f.remote.list(), []);
  await writeFile(join(f.protocol, 'settings.json'), '{}'); await assert.rejects(f.remote.list(), /非协议/); await rm(join(f.protocol, 'settings.json'));
  const id = randomUUID(), path = join(f.protocol, `commit-${id}.dpc`); await writeFile(path, Buffer.from('damaged'));
  const graph = await loadGraph(f.remote); assert.deepEqual(graph.deferred, [id]);
  await assert.rejects(f.remote.put(id, Buffer.from('damaged')));
  const before = await readFile(path);
  await writeFile(path, Buffer.alloc(16 * 1024 * 1024 + 8193)); await assert.rejects(f.remote.read(id), /大小/);
  await writeFile(path, before);
  assert.equal(await readFile(join(residual, 'content'), 'utf8'), 'partial');
  f.remote.requests = 512; await assert.rejects(f.remote.list(), /预算/);
});

test('256 个提交的协议上限仍能在单轮本地请求预算内完整验证，第 257 个条目拒绝', { skip: !linux }, async t => {
  const f = await fixture(t); await f.remote.ensureDirectory();
  const template = decode(await readFile(new URL('../../schedule-core/src/test/resources/sync-v2/empty.dpc', import.meta.url)));
  let parent = null;
  for (let i = 0; i < 256; i++) {
    const c = { ...template, commitId: randomUUID(), parents: parent ? [parent] : [] };
    await writeFile(join(f.protocol, `commit-${c.commitId}.dpc`), encode(c)); parent = c.commitId;
  }
  const remote = new LocalCommitStore(f.config, f.grants), graph = await loadGraph(remote);
  assert.equal(Object.keys(graph.hashes).length, 256); assert.deepEqual(graph.tips, [parent]); assert(remote.requests <= 512);
  await writeFile(join(f.protocol, `commit-${randomUUID()}.dpc`), 'extra');
  await assert.rejects(new LocalCommitStore(f.config, f.grants).list(), /超限/);
});
