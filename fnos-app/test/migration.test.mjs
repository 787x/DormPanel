import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm, readFile, writeFile, chmod } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { Store } from '../src/store.mjs';
import { Catalog } from '../src/catalog.mjs';
import { SyncEngine } from '../src/sync.mjs';
import { HistoryManager, graphIdentity } from '../src/history.mjs';
import { DatasetMigration } from '../src/migration.mjs';
import { LocalCommitStore } from '../src/local.mjs';
import { loadGraph } from '../src/graph.mjs';
import { encode, decode, stateBytes } from '../src/protocol.mjs';

const linux = { skip: process.platform !== 'linux' };
test('图身份不依赖检查点与归档清单的哈希枚举顺序', () => {
  const a = { datasetId: randomUUID(), hashes: { b: 'b', a: 'a' }, tips: ['b', 'a'], deferred: ['d', 'c'] };
  assert.equal(graphIdentity(a), graphIdentity({ ...a, hashes: { a: 'a', b: 'b' }, tips: ['a', 'b'], deferred: ['c', 'd'] }));
});
async function fixture(t) {
  const dir = await mkdtemp(join(tmpdir(), 'dp47-')); t.after(() => rm(dir, { recursive: true, force: true }));
  const store = await new Store(join(dir, 'state')).init(); const remotes = new Map();
  const options = { store, configPath: join(dir, 'missing.json'), authorizedRoots: [{ id: 'export', path: dir }], remoteFactory: c => remotes.get(c.url) };
  let c = await Catalog.open(options); const id = c.manifest.activeId; let db = c.db(id);
  const rows = new Map(); let omit = [];
  const old = { key: 'old', list: async () => [...rows.keys()].filter(id => !omit.includes(id)), read: async id => rows.get(id) ?? null, put: async (id, b) => { assert(!rows.has(id)); rows.set(id, b); } };
  remotes.set('https://old.test/', old);
  const manage = input => c.manage({ catalogVersion: c.manifest.revision, ...input });
  const { connectionId } = await manage({ action: 'connection.save', type: 'webdav', name: '旧', url: 'https://old.test/', username: 'w', password: 'synthetic' });
  await manage({ action: 'workspace.endpoint', targetId: id, targetVersion: db.frame.revision, connectionId });
  const complex = decode(await readFile(new URL('../../schedule-core/src/test/resources/sync-v2/complex-root.dpc', import.meta.url)));
  await db.tx(f => { f.local = complex.state; });
  await db.edit({ kind: 'entry', action: 'upsert', value: { id: 'stable-identity', title: '合成课程', day: 1, startMinute: 480, endMinute: 530, location: '教室' } });
  await new SyncEngine(db, old).synchronize({ create: true });
  await db.edit({ kind: 'event', action: 'upsert', value: { id: 'stable-event', title: '合成事件', start: 1700000000000, end: 1700003600000, note: '完整身份' } });
  await new SyncEngine(db, old).synchronize();
  const original = new Map(rows), history = new HistoryManager(c);
  const output = await history.archive(db, new SyncEngine(db, old), { workspaceId: id, rootId: 'export', relative: '' }, true);
  const location = { rootId: 'export', relative: output.candidate };
  const next = { ...new LocalCommitStore(location, c.grants), key: 'next' };
  let targetOmit = false;
  const target = { key: 'next', list: async () => targetOmit ? Object.keys((await loadGraph(new LocalCommitStore(location, c.grants))).hashes).slice(0, 1) : new LocalCommitStore(location, c.grants).list(), read: id => new LocalCommitStore(location, c.grants).read(id), put: (id, b) => new LocalCommitStore(location, c.grants).put(id, b) };
  remotes.set('https://new.test/', target);
  const targetConnectionId = (await manage({ action: 'connection.save', type: 'webdav', name: '新', url: 'https://new.test/', username: 'w', password: 'synthetic' })).connectionId;
  let manager = new DatasetMigration(c);
  const prepare = () => manager.action(db, { action: 'prepare', workspaceId: id, rootId: 'export', candidateRelative: output.candidate, targetConnectionId }, 'admin');
  const record = () => c.migrations.at(-1);
  const action = (action, fields = {}) => manager.action(c.db(c.manifest.activeId), { action, workspaceId: c.manifest.activeId, migrationId: record().id, migrationVersion: record().revision, ...fields }, 'admin');
  return { dir, rows, original, old, target, output, prepare, action, record, get c() { return c; }, get db() { return db; }, reopen: async () => { c = await Catalog.open(options); db = await c.load(id); manager = new DatasetMigration(c); }, omit: ids => { omit = ids; }, omitTarget: () => { targetOmit = true; } };
}
async function freeze(f) {
  await f.action('device', { name: '床头', type: 'X08E', stopped: true });
  await f.action('device', { name: '手机', type: 'Phone', stopped: true });
  await f.action('freeze', { confirm: f.record().sourceDatasetId });
}
test('完整归档→单根→独立工作区，重启保持停写和安装 Writer；旧字节全保留', linux, async t => {
  const f = await fixture(t), before = f.db.snapshot(), writer = f.c.store.identity.writerId;
  await f.prepare(); await freeze(f); await f.reopen();
  await assert.rejects(new SyncEngine(f.db, f.old).synchronize(), /保护/);
  await assert.rejects(f.c.network(f.record().sourceId, engine => engine.synchronize()), /保护/);
  assert.equal(f.db.frame.error, null);
  await assert.rejects(new SyncEngine(f.db, f.old).resolve('local', 'invalid', f.db.frame.revision), /保护/);
  await assert.rejects(f.db.tx(x => { x.local.entries = []; }), /保护/);
  await assert.rejects(f.db.tx(x => f.db.stage(x, x.local, x.frontier)), /保护/);
  await assert.rejects(f.c.manage({ action: 'workspace.remove', catalogVersion: f.c.manifest.revision, targetId: f.record().sourceId, targetVersion: f.db.frame.revision }), /保护/);
  await f.action('switch', { confirm: f.record().targetDatasetId });
  const next = f.c.db(f.c.manifest.activeId);
  assert.equal(next.frame.datasetId, f.record().targetDatasetId); assert.equal(f.c.store.identity.writerId, writer);
  assert(stateBytes(before.local).equals(stateBytes(next.frame.local))); assert.equal(Object.keys(next.frame.checkpoint.hashes).length, 1);
  assert.deepEqual(f.db.frame.local, before.local); assert.deepEqual(f.db.frame.frontier, before.frontier);
  assert.equal((await loadGraph(f.target)).tips.length, 1);
  await assert.rejects(new SyncEngine(next, f.target).synchronize(), /保护/);
  await assert.rejects(f.action('retire', { confirm: f.record().sourceDatasetId }), /未验收/);
  for (const [name, type] of [['床头','X08E'],['手机','Phone']]) await f.action('device', { name, type, stopped: true, accepted: true, evidence: 'Android 本机人工核验合成结果（不是 LIVE PASS）' });
  await f.action('retire', { confirm: f.record().sourceDatasetId });
  await f.reopen(); await assert.rejects(new SyncEngine(f.db, f.old).synchronize(), /保护/);
  const active = f.c.db(f.c.manifest.activeId); await new SyncEngine(active, f.target).synchronize();
  await active.edit({ kind: 'entry', action: 'upsert', value: { id: 'post-migration', title: '新库继续编辑', day: 2, startMinute: 500, endMinute: 550, location: '新库' } });
  await new SyncEngine(active, f.target).synchronize();
  await f.action('recheck'); assert.equal(f.record().phase, 'retired');
  assert.equal((await loadGraph(f.target)).state.entries.some(e => e.id === 'post-migration'), true);
  assert.equal(f.rows.size, f.original.size); for (const [id, b] of f.original) assert(f.rows.get(id).equals(b));
  assert.equal(new DatasetMigration(f.c).summary(active === f.db ? f.record().sourceId : f.record().targetId).reclaimedBytes, 0);
});
test('源晚到提交拒绝候选，观察证据跨重启且漏列不能绕过', linux, async t => {
  const f = await fixture(t); await f.prepare(); await freeze(f);
  const old = decode([...f.rows.values()].at(-1)), late = { ...old, commitId: randomUUID(), parents: [old.commitId] };
  f.rows.set(late.commitId, encode(late)); await assert.rejects(f.action('switch', { confirm: f.record().targetDatasetId }));
  assert.equal(f.record().phase, 'needs-recheck'); await f.reopen(); f.omit([late.commitId]);
  await assert.rejects(f.action('recheck')); assert(f.db.frame.checkpoint.hashes[late.commitId]);
  assert.equal(f.c.manifest.workspaces.length, 1);
});
test('归档字节 / 完成标记 / 候选字段 / WebDAV 不可达或指错都不签发迁移', linux, async t => {
  for (const kind of ['archive', 'marker', 'state', 'offline', 'wrong-directory']) {
    const f = await fixture(t);
    if (kind === 'archive') { const id = [...f.original.keys()][0], path = join(f.dir, f.output.archive, 'dormpanel-sync-v2', `commit-${id}.dpc`); await chmod(path, 0o600); await writeFile(path, Buffer.from('bad')); }
    if (kind === 'marker') await writeFile(join(f.dir, f.output.candidate, 'verified.json'), '{}');
    if (kind === 'state') { const g = await loadGraph(f.target), id = g.tips[0], c = decode(Buffer.from(g.raw[id], 'base64')); c.state.entries[0].title = '不同'; const path = join(f.dir, f.output.candidate, 'dormpanel-sync-v2', `commit-${id}.dpc`); await chmod(path, 0o600); await writeFile(path, encode(c)); }
    if (kind === 'offline') f.target.list = async () => { throw Error('offline'); };
    if (kind === 'wrong-directory') { f.target.list = f.old.list; f.target.read = f.old.read; }
    await assert.rejects(f.prepare(), undefined, kind); assert.equal(f.c.migrations.length, 0); assert.equal(f.rows.size, f.original.size);
  }
});
test('切换 catalog 提交后中断可恢复，取消保留两库；独立目标编辑阻止反向覆盖', linux, async t => {
  const f = await fixture(t); await f.prepare(); await freeze(f);
  const commit = f.c.commit.bind(f.c); let failed = false;
  f.c.commit = async next => { await commit(next); if (next.workspaces.length === 2 && !failed) { failed = true; throw Error('injected crash'); } };
  await assert.rejects(f.action('switch', { confirm: f.record().targetDatasetId }), /crash/);
  await f.reopen(); await f.action('recheck'); await f.action('switch', { confirm: f.record().targetDatasetId });
  assert.equal(f.c.manifest.workspaces.length, 2);
  await f.action('cancel', { confirm: f.record().sourceDatasetId, writersStopped: true });
  assert.equal(f.record().phase, 'cancelled'); assert.equal(f.rows.size, f.original.size);
  const blocked = f.c.db(f.record().targetId); await assert.rejects(new SyncEngine(blocked, f.target).synchronize(), /保护/);
});
test('目标新增提交、漏列与重启不能把人工确认变成完成', linux, async t => {
  const f = await fixture(t); await f.prepare(); await freeze(f);
  const root = decode(Buffer.from((await loadGraph(f.target)).raw[(await loadGraph(f.target)).tips[0]], 'base64'));
  const child = { ...root, commitId: randomUUID(), parents: [root.commitId] }; await f.target.put(child.commitId, encode(child));
  await assert.rejects(f.action('switch', { confirm: f.record().targetDatasetId }));
  f.omitTarget(); await f.reopen(); await assert.rejects(f.action('recheck'));
  assert.equal(f.record().phase, 'needs-recheck'); assert.equal(f.rows.size, f.original.size);
});

test('未发布修改、pending/attempt、冲突、异常观察及未完成恢复全部拒绝准备', linux, async t => {
  for (const kind of ['dirty', 'pending', 'attempted', 'conflict', 'abandoned', 'deferred', 'observed', 'error', 'restore']) {
    const f = await fixture(t);
    if (kind === 'restore') f.c.restoreGuards[f.c.manifest.activeId] = { bindingKey: 'old' };
    else await f.db.tx(frame => {
      if (kind === 'dirty') frame.local.entries[0].title = '未发布修改';
      if (kind === 'pending' || kind === 'attempted') { f.db.stage(frame, frame.local, frame.frontier); frame.pending.attempted = kind === 'attempted'; }
      if (kind === 'conflict') frame.conflict = { saved: true };
      if (kind === 'abandoned') frame.abandoned[randomUUID()] = 'a'.repeat(64);
      if (kind === 'deferred') frame.checkpoint.deferred = [randomUUID()];
      if (kind === 'observed') frame.checkpoint.observed = [randomUUID()];
      if (kind === 'error') frame.error = '未恢复异常';
    });
    await assert.rejects(f.prepare(), undefined, kind); assert.equal(f.c.migrations.length, 0);
    assert.equal(f.rows.size, f.original.size); for (const [id, b] of f.original) assert(f.rows.get(id).equals(b));
  }
});
