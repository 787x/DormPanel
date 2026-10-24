import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { openV3Workspace } from '../src/v3-workspace.mjs';
import { V3Engine } from '../src/v3-engine.mjs';
import { V3Publisher } from '../src/v3-transaction.mjs';
import { Budget, objectPath } from '../src/v3-store.mjs';
import { IndexedV3Cache } from '../src/v3-index.mjs';
import { emptyState } from '../src/merge.mjs';
import { encode, sha, ZERO, ZERO_HASH } from '../src/v3-protocol.mjs';
import { randomUUID } from 'node:crypto';

const course = title => ({ id: 'x', title, day: 1, startMinute: 480, endMinute: 540, location: '' });
const state = title => ({ ...emptyState(), entries: [course(title)] });
async function client() {
  const root = await mkdtemp(join(tmpdir(), 'v3-workspace-tx-')); const c = { root, allowed: true };
  c.guard = () => assert(c.allowed, 'denied'); c.adapter = await openV3Workspace(root, c.guard);
  c.engine = new V3Engine(c.adapter.journal, c.adapter.writerId);
  c.reopen = async () => { await c.adapter.journal.close(); c.adapter = await openV3Workspace(root, c.guard); c.engine = new V3Engine(c.adapter.journal, c.adapter.writerId); };
  c.round = async remote => { const b = new Budget(); return c.engine.round(remote, new IndexedV3Cache(root, b), b, { discoverySteps: 32 }); };
  c.settle = async remote => { let r; for (let n = 0; n < 3; n++) { try { r = await c.round(remote); } catch (e) { if (!/incomplete|budget/.test(e.message)) throw e; } } return r; };
  return c;
}
async function conflictFixture(t) {
  const a = await client(), b = await client(); t.after(async () => { await a.adapter.journal.close(); await b.adapter.journal.close(); });
  const objects = new Map(), puts = new Map();
  const remote = {
    async read(path) { assert.equal(a.adapter.journal.meta.depth, 0); assert.equal(b.adapter.journal.meta.depth, 0); return objects.get(path) ?? null; },
    async putOnce(path, raw) { assert.equal(a.adapter.journal.meta.depth, 0); assert.equal(b.adapter.journal.meta.depth, 0); assert(!objects.has(path)); puts.set(path, (puts.get(path) ?? 0) + 1); objects.set(path, raw); },
    async list(scope) { assert.equal(a.adapter.journal.meta.depth, 0); assert.equal(b.adapter.journal.meta.depth, 0); return [...new Set([...objects.keys()].filter(p => p.startsWith(scope)).map(p => { const tail = p.slice(scope.length), slash = tail.indexOf('/'); return scope + (slash < 0 ? tail : tail.slice(0, slash + 1)); }))].sort(); }
  };
  const binding = await a.engine.initialize(remote, state('base')); await a.settle(remote);
  const { decode } = await import('../src/v3-protocol.mjs'); await b.engine.bind(decode(objects.get(binding.descriptorPath)), binding.descriptorHash); await b.settle(remote);
  await a.engine.edit(state('left')); await b.engine.edit(state('right')); await a.settle(remote);
  assert.equal((await b.round(remote)).status, 'CONFLICT'); const c = b.adapter.journal.value.conflict;
  assert.equal(c.base.entries[0].title, 'base'); assert.equal(c.local.entries[0].title, 'right'); assert.equal(c.remote.entries[0].title, 'left'); assert(c.paths.length && c.conflicts[0].keys.includes('class:x'));
  return { a, b, remote, c, puts };
}
const persistedRows = meta => meta.db.prepare('SELECT ns,k,v,sum FROM v3_records ORDER BY seq').all();

test('workspace-tx: 真正的双 Writer 冲突决定、课程与索引提交后重开一致', async t => {
  const { a, b, remote, c } = await conflictFixture(t); const before = b.adapter.workspace.snapshot().revision;
  const expired = new Budget({ millis: 0 }); b.adapter.journal.meta.budget = expired; b.adapter.journal.meta.verifiedSnapshots.clear();
  await b.engine.resolve(c.id, 'local'); assert.equal(b.adapter.journal.meta.budget, expired); b.adapter.journal.meta.budget = null; assert.equal(b.adapter.workspace.snapshot().revision, before + 1);
  assert.equal(b.adapter.workspace.snapshot().local.entries[0].title, 'right'); assert.equal(b.adapter.journal.meta.get('decision', c.id).input.remote.entries[0].title, 'left');
  await b.reopen(); assert.equal(b.adapter.journal.value.decision.id, c.id); assert.equal(b.adapter.journal.value.local.entries[0].title, 'right'); assert.equal(b.adapter.journal.meta.get('decision', c.id).choice, 'local');
  await b.settle(remote); await a.settle(remote); assert.equal(a.adapter.journal.value.local.entries[0].title, 'right');
  const receipt = b.adapter.journal.value.receipt; assert.equal(b.adapter.journal.meta.get('node', receipt.path).hash, receipt.hash);
});

test('workspace-tx: decision 已写入但帧保存失败时全部回滚，内存帧不提前生效', async t => {
  const { b, c } = await conflictFixture(t), j = b.adapter.journal, frame = b.adapter.workspace.snapshot(), rows = persistedRows(j.meta);
  const put = j.meta.put.bind(j.meta); let sawDecision = false;
  j.meta.put = (ns, key, value) => { if (ns === 'workspace' && key === 'workspace.json') { sawDecision = !!j.meta.get('decision', c.id); throw Error('injected frame save'); } return put(ns, key, value); };
  await assert.rejects(b.engine.resolve(c.id, 'local'), /injected frame save/); j.meta.put = put;
  assert(sawDecision); assert.deepEqual(b.adapter.workspace.snapshot(), frame); assert.deepEqual(persistedRows(j.meta), rows); assert.equal(j.meta.get('decision', c.id), null);
  await b.reopen(); assert.equal(b.adapter.journal.value.conflict.id, c.id); assert.equal(b.adapter.journal.value.decision, null); await b.engine.resolve(c.id, 'local'); assert.equal(b.adapter.journal.meta.get('decision', c.id).choice, 'local');
});

test('workspace-tx: conflictHistory 与更新的冲突帧在 COMMIT 前异常时一起回滚', async t => {
  const { a, b, c, remote } = await conflictFixture(t); await a.engine.edit(state('third')); await a.settle(remote);
  const j = b.adapter.journal, frame = b.adapter.workspace.snapshot(), rows = persistedRows(j.meta); let reached = false;
  const save = () => b.engine.saveConflict(c.base, c.local, a.adapter.journal.value.local, c.conflicts, [a.adapter.journal.value.baselinePath], j.value.revision);
  j.meta.fault = phase => { if (phase === 'before-commit' && j.meta.get('conflictHistory', c.id)) { assert.notEqual(j.meta.get('workspace', 'workspace.json').v3.conflict.id, c.id); reached = true; throw Error('injected history commit'); } };
  await assert.rejects(save(), /injected history commit/); j.meta.fault = null; assert(reached); assert.deepEqual(b.adapter.workspace.snapshot(), frame); assert.deepEqual(persistedRows(j.meta), rows);
  await save(); const id = j.value.conflict.id; assert.notEqual(id, c.id); await b.reopen(); assert.equal(b.adapter.journal.value.conflict.id, id); assert.equal(b.adapter.journal.meta.get('conflictHistory', c.id).remote.entries[0].title, 'left');
});

test('workspace-tx: 元数据 put/remove、revision、领域及权限检查共享回滚边界', async t => {
  const c = await client(); t.after(() => c.adapter.journal.close()); const j = c.adapter.journal;
  await c.engine.edit(state('saved')); j.meta.atomic(() => j.meta.put('probe', 'retained', { value: 'saved' }));
  const frame = c.adapter.workspace.snapshot(), rows = persistedRows(j.meta);
  await assert.rejects(j.transaction(s => { j.meta.put('conflictHistory', 'attempt', { input: 'saved' }); j.meta.remove('probe', 'retained'); s.local.entries[0].endMinute = 0; }, true));
  assert.deepEqual(c.adapter.workspace.snapshot(), frame); assert.deepEqual(persistedRows(j.meta), rows);
  let ran = false;
  await assert.rejects(c.adapter.workspace.tx(() => { ran = true; j.meta.put('decision', 'stale', {}); }, frame.revision - 1), /版本过期/); assert.equal(ran, false);
  c.allowed = false; await assert.rejects(j.transaction(s => { j.meta.put('decision', 'denied', {}); s.local = state('denied'); }, true), /denied/); c.allowed = true;
  assert.deepEqual(c.adapter.workspace.snapshot(), frame); assert.deepEqual(persistedRows(j.meta), rows);
});

test('workspace-budget: 共享同步预算不被课程对象误判，本地预算在回调之前生效且随后恢复', async t => {
  const c = await client(); t.after(() => c.adapter.journal.close()); const j = c.adapter.journal; await c.engine.edit(state('saved'));
  const shared = new Budget({ bytes: 0 }), frame = c.adapter.workspace.snapshot(); j.meta.budget = shared; j.meta.verifiedSnapshots.clear();
  await assert.rejects(j.transaction(s => { assert.equal(j.meta.budget, shared); s.networkControl = 'must rollback'; }), /budget/);
  assert.deepEqual(c.adapter.workspace.snapshot(), frame); assert.equal(j.meta.budget, shared);
  j.meta.atomic(() => j.meta.put('probe', 'read', { value: 'local' }));
  await j.transaction(s => { assert.notEqual(j.meta.budget, shared); assert.equal(j.meta.get('probe', 'read').value, 'local'); s.local = state('local'); j.meta.put('decision', 'local-budget', { value: 'saved' }); }, true);
  assert.equal(j.meta.budget, shared); assert.equal(shared.bytes, 0);
  j.meta.budget = new Budget({ millis: 0 }); const expired = j.meta.budget; j.meta.verifiedSnapshots.clear(); await c.engine.edit(state('offline')); assert.equal(j.meta.budget, expired); assert.equal(j.value.local.entries[0].title, 'offline');
  await assert.rejects(j.transaction(() => { j.meta.get('probe', 'read'); }), /budget/);
});

test('workspace-tx: 过期决定与拒绝权限不会留下 decision 或新帧', async t => {
  const { b, c } = await conflictFixture(t); await b.engine.edit(state('new-local')); const j = b.adapter.journal, frame = b.adapter.workspace.snapshot(), rows = persistedRows(j.meta);
  await assert.rejects(b.engine.resolve(c.id, 'local'), /stale/); assert.deepEqual(b.adapter.workspace.snapshot(), frame); assert.deepEqual(persistedRows(j.meta), rows);
  b.allowed = false; await assert.rejects(b.engine.resolve(c.id, 'remote'), /denied/); b.allowed = true; assert.deepEqual(persistedRows(j.meta), rows);
});

test('workspace-tx: COMMIT 已成功但确认失败时内存不冒充成功，重开恢复完整提交', async t => {
  const c = await client(); t.after(() => c.adapter.journal.close()); const j = c.adapter.journal; await c.engine.edit(state('before'));
  const before = c.adapter.workspace.snapshot();
  j.meta.fault = phase => { if (phase === 'after-commit' && j.meta.get('decision', 'committed')) throw Error('injected commit acknowledgement'); };
  await assert.rejects(j.transaction(s => { j.meta.put('decision', 'committed', { input: 'exact' }); s.local = state('committed'); s.receipt = { path: 'committed' }; }, true), /acknowledgement/);
  assert.deepEqual(c.adapter.workspace.snapshot(), before); await assert.rejects(j.transaction(() => {}), /reopen/);
  await c.reopen(); assert.equal(c.adapter.journal.value.local.entries[0].title, 'committed'); assert.equal(c.adapter.journal.value.receipt.path, 'committed'); assert.equal(c.adapter.journal.meta.get('decision', 'committed').input, 'exact'); assert.equal(c.adapter.workspace.snapshot().revision, before.revision + 1);
});

test('workspace-tx: Pending/回读恢复与 receipt 索引同提交，异常后不重复 PUT', async t => {
  const c = await client(); t.after(() => c.adapter.journal.close()); let j = c.adapter.journal; await c.engine.edit(state('saved'));
  const o = { type: 1, datasetId: randomUUID(), objectId: randomUUID(), writerId: c.adapter.writerId, epochId: ZERO, epochNumber: 0, descriptorHash: ZERO_HASH, refs: [], state: null }, raw = encode(o), path = objectPath(o);
  let content = null, puts = 0;
  const remote = { read: async () => { assert.equal(j.meta.depth, 0); return content; }, putOnce: async (p, bytes) => { assert.equal(j.meta.depth, 0); puts++; content = bytes; throw Error('lost response'); } };
  let p = new V3Publisher(j, remote); await p.stage(path, raw, { state: state('saved'), revision: j.value.revision }); await assert.rejects(p.resume(), /lost response/);
  await c.reopen(); j = c.adapter.journal; p = new V3Publisher(j, remote); assert.equal(await p.resume(), 'READBACK_CONFIRMED'); assert.equal(puts, 1);
  const frame = c.adapter.workspace.snapshot(), rows = persistedRows(j.meta);
  j.meta.fault = phase => { if (phase === 'before-commit' && j.meta.get('receiptProof', path)) throw Error('injected receipt commit'); };
  const receipt = () => p.receipt(s => { j.meta.put('receiptProof', path, { hash: sha(raw) }); s.local = state('receipted'); });
  await assert.rejects(receipt(), /receipt commit/); j.meta.fault = null; assert.deepEqual(c.adapter.workspace.snapshot(), frame); assert.deepEqual(persistedRows(j.meta), rows);
  await receipt(); await c.reopen(); assert.equal(c.adapter.journal.value.pending.phase, 'RECEIPTED'); assert.equal(c.adapter.journal.value.local.entries[0].title, 'receipted'); assert.equal(c.adapter.journal.meta.get('receiptProof', path).hash, sha(raw)); assert.equal(puts, 1);
});

test('workspace-tx: 排队后权限撤销与过期 revision 都在执行回调前拒绝', async t => {
  const c = await client(); t.after(() => c.adapter.journal.close()); await c.engine.edit(state('saved')); const w = c.adapter.workspace, j = c.adapter.journal;
  const frame = w.snapshot(), rows = persistedRows(j.meta); let release;
  w.queue = new Promise(r => { release = r; }); let ran = false;
  const queued = w.tx(f => { ran = true; j.meta.put('decision', 'queued', {}); f.local = state('denied'); }, frame.revision);
  c.allowed = false; release(); await assert.rejects(queued, /denied/); c.allowed = true; assert.equal(ran, false); assert.deepEqual(w.snapshot(), frame); assert.deepEqual(persistedRows(j.meta), rows);
  const revision = w.snapshot().revision;
  const first = w.tx(f => { f.local.entries[0].title = 'first'; }, revision), stale = w.tx(() => { j.meta.put('decision', 'stale-queue', {}); }, revision);
  await first; await assert.rejects(stale, /版本过期/); assert.equal(j.meta.get('decision', 'stale-queue'), null); assert.equal(w.snapshot().revision, revision + 1);
});
