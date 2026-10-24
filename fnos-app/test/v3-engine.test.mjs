import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, unlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { encode, ZERO, ZERO_HASH, sha } from '../src/v3-protocol.mjs';
import { objectPath, Budget } from '../src/v3-store.mjs';
import { emptyState } from '../src/merge.mjs';
import { IndexedV3Cache } from '../src/v3-index.mjs';
import { V3Journal, V3Publisher } from '../src/v3-transaction.mjs';
import { V3Engine } from '../src/v3-engine.mjs';
import { V3History, V3Discovery, reference } from '../src/v3-history.mjs';
import { scopeInfo } from '../src/v3-store.mjs';

const dir = () => mkdtemp(join(tmpdir(), 'dp-pr54-'));
const object = () => ({ type: 1, datasetId: randomUUID(), objectId: randomUUID(), writerId: randomUUID(), epochId: ZERO, epochNumber: 0, descriptorHash: ZERO_HASH, refs: [], state: null });
test('index: 索引冷启动不列举历史；超过 4096 项，按路径验证、缺失和 witness', async () => {
  const root = await dir(); let cache = new IndexedV3Cache(root, new Budget());
  const values = Array.from({ length: 2100 }, () => encode(object()));
  for (const raw of values) await cache.put(objectPath((await import('../src/v3-protocol.mjs')).decode(raw)), raw);
  cache = new IndexedV3Cache(root, new Budget({ bytes: 2048 }));
  await cache.open(); // no history I/O; 4200 receipt/blob files
  const { decode } = await import('../src/v3-protocol.mjs');
  const raw = values.at(-1), path = objectPath(decode(raw));
  assert.deepEqual(await cache.get(path), raw);
  await unlink(cache.blob(path));
  assert.equal(await cache.get(path), null);
  await assert.rejects(cache.put(path, encode({ ...decode(raw), writerId: randomUUID() })), /identity/);
  await cache.pin(path, 'pending');
  assert.deepEqual((await cache.witness(path)).pins, ['pending']);
  await writeFile(cache.blob(path), 'damaged');
  await assert.rejects(new IndexedV3Cache(root, new Budget()).get(path), /cache hash/);
  await assert.rejects(new IndexedV3Cache(root, new Budget({ bytes: 1 })).get(path), /budget/);
});
test('publish: 精确发布：响应丢失后重开只 GET；404 保持 UNCERTAIN，不重 PUT', async () => {
  const root = await dir(), journal = new V3Journal(root); await journal.open();
  const raw = encode(object()), path = objectPath((await import('../src/v3-protocol.mjs')).decode(raw));
  let saved = null, puts = 0;
  const remote = { read: async () => saved, putOnce: async (p, b) => { puts++; saved = b; throw Error('lost response'); } };
  let publisher = new V3Publisher(journal, remote);
  await publisher.stage(path, raw, { state: emptyState(), generation: 0 });
  await assert.rejects(publisher.resume(), /lost response/);
  const reopened = new V3Journal(root); await reopened.open(); publisher = new V3Publisher(reopened, remote);
  assert.equal(await publisher.resume(), 'READBACK_CONFIRMED'); assert.equal(puts, 1);
  await publisher.receipt(s => { s.local = { edits: 'new local edit' }; });
  assert.equal(reopened.value.pending.phase, 'RECEIPTED'); assert.equal(reopened.value.local.edits, 'new local edit');
  const j2 = new V3Journal(await dir()); await j2.open(); saved = null;
  const p2 = new V3Publisher(j2, remote); await p2.stage(path, raw, {}); await assert.rejects(p2.resume()); saved = null;
  assert.equal(await p2.resume(), 'UNCERTAIN'); assert.equal(puts, 2);
});
test('journal: 持久化在 rename 后报错时禁止旧内存帧覆盖 ATTEMPTED', async () => {
  const root = await dir(), journal = new V3Journal(root); await journal.open();
  const { decode } = await import('../src/v3-protocol.mjs'); const raw = encode(object()), path = objectPath(decode(raw)); let puts = 0;
  const remote = { read: async () => null, putOnce: async () => { puts++; } }, p = new V3Publisher(journal, remote);
  await p.stage(path, raw, {}); const save = journal.store.save.bind(journal.store);
  journal.store.save = async (name, value) => { await save(name, value); if (value.pending.phase === 'ATTEMPTED') throw Error('directory fsync failure'); };
  await assert.rejects(p.resume(), /fsync/); await assert.rejects(journal.transaction(s => { s.pending.phase = 'STAGED'; }), /reopen/);
  const reopened = new V3Journal(root); await reopened.open(); assert.equal(reopened.value.pending.phase, 'ATTEMPTED');
  assert.equal(await new V3Publisher(reopened, remote).resume(), 'UNCERTAIN'); assert.equal(puts, 0);
});

class FakeRemote {
  constructor() { this.objects = new Map(); this.puts = 0; this.reads = 0; }
  async read(p) { this.reads++; return this.objects.get(p) ?? null; }
  async putOnce(p, b) { assert(!this.objects.has(p)); this.puts++; this.objects.set(p, Buffer.from(b)); }
  async list(scope) {
    const children = new Set();
    for (const p of this.objects.keys()) if (p.startsWith(scope)) { const tail = p.slice(scope.length), slash = tail.indexOf('/'); children.add(scope + (slash < 0 ? tail : tail.slice(0, slash + 1))); }
    assert(children.size <= 256); return [...children].sort();
  }
}
async function client(binding, remote) {
  const root = await dir(), journal = new V3Journal(root); await journal.open(); const engine = new V3Engine(journal, randomUUID());
  if (binding) { const { decode } = await import('../src/v3-protocol.mjs'); await engine.bind(decode(await remote.read(binding.descriptorPath)), binding.descriptorHash); }
  return { root, journal, engine, round: () => { const budget = new Budget(); return engine.round(remote, new IndexedV3Cache(root, budget), budget, { discoverySteps: 32 }); } };
}
const entry = (id, title = id) => ({ id, title, day: 1, startMinute: 480, endMinute: 540, location: '' });
async function settle(c, count = 3) {
  let result;
  for (let n = 0; n < count; n++) { try { result = await c.round(); } catch (e) { if (!/incomplete|budget/.test(e.message)) throw e; } }
  return result;
}
test('merge: 真实引擎多 Writer 独立修改、冲突持久化和精确选择', async () => {
  const remote = new FakeRemote(), a = await client(null, remote), binding = await a.engine.initialize(remote);
  await settle(a); const b = await client(binding, remote); await settle(b);
  await a.engine.edit({ ...emptyState(), entries: [entry('a')] }); await b.engine.edit({ ...emptyState(), entries: [entry('b')] });
  await settle(a); await settle(b); await settle(a);
  assert.deepEqual(a.journal.value.local.entries.map(e => e.id).sort(), ['a', 'b']);
  await a.engine.edit({ ...a.journal.value.local, entries: a.journal.value.local.entries.map(e => e.id === 'a' ? entry('a', 'left') : e) });
  await b.engine.edit({ ...b.journal.value.local, entries: b.journal.value.local.entries.map(e => e.id === 'a' ? entry('a', 'right') : e) });
  await settle(a); assert.equal((await settle(b, 1)).status, 'CONFLICT');
  const reopened = new V3Journal(b.root); await reopened.open(); assert(reopened.value.conflict.id);
  await b.engine.resolve(b.journal.value.conflict.id, 'local'); await settle(b); await settle(a);
  assert.equal(a.journal.value.local.entries.find(e => e.id === 'a').title, 'right');
});
test('growth: 累计 1024 次发布自动跨代；增量读取不 GET 全部祖先', { timeout: 300000 }, async () => {
  const remote = new FakeRemote(), a = await client(null, remote); await a.engine.initialize(remote); await settle(a);
  for (let n = 0; n < 1024; n++) {
    await a.engine.edit({ ...emptyState(), entries: [entry('a', `edit-${n}`)] });
    let result = await a.round(); if (result.status === 'ROTATED') result = await a.round();
    assert.equal(result.status, 'PUBLISHED', `iteration ${n}`);
  }
  assert(a.journal.value.history && Object.keys(a.journal.value.history.roots).length >= 6);
  const reads = remote.reads; await a.round(); assert(remote.reads - reads < 20);
  assert.equal(a.journal.value.local.entries[0].title, 'edit-1023');
});

test('candidates: 同代并发候选通过更高代 LATE 调和；旧代迟到分支重复巡检幂等', async () => {
  const { decode } = await import('../src/v3-protocol.mjs');
  const remote = new FakeRemote(), a = await client(null, remote), binding = await a.engine.initialize(remote); await settle(a);
  const b = await client(binding, remote); await settle(b);
  const history = async c => { const budget = new Budget(), h = await new V3History(c.journal, new IndexedV3Cache(c.root, budget), remote, binding.descriptorPath, binding.descriptorHash, budget).open(); for (const p of remote.objects.keys()) if (p !== binding.descriptorPath) await h.verify(p); return h; };
  const ha = await history(a), hb = await history(b), old = a.journal.value.baselinePath, source = ha.nodes[old].epochId;
  await a.engine.rotate(ha, remote, source, [old]); await b.engine.rotate(hb, remote, source, [old]);
  const ga = decode(Buffer.from(a.journal.value.pending.bytes, 'base64')).refs.find(r => r.role === 5), gb = decode(Buffer.from(b.journal.value.pending.bytes, 'base64')).refs.find(r => r.role === 5);
  for (const [g, key] of [[ga, 'left'], [gb, 'right']]) {
    const o = { type: 3, datasetId: binding.datasetId, objectId: randomUUID(), writerId: randomUUID(), epochId: g.epochId, epochNumber: 2, descriptorHash: binding.descriptorHash, refs: [{ ...g, role: 1 }], state: { ...emptyState(), entries: [entry(key)] } };
    await remote.putOnce(objectPath(o), encode(o));
  }
  await settle(a, 8);
  assert.deepEqual(a.journal.value.local.entries.map(e => e.id).sort(), ['left', 'right']);
  const bridges = [...remote.objects.values()].map(decode).filter(o => o.type === 3 && o.refs.some(r => r.role === 3)); assert(bridges.length);
  assert(bridges.every(o => o.refs.filter(r => r.role === 3).every(r => r.epochNumber < o.epochNumber)));
  const oldObject = decode(await remote.read(old)), late = { ...oldObject, type: 3, objectId: randomUUID(), refs: [reference(oldObject, sha(await remote.read(old)), 1)], state: { ...emptyState(), entries: [entry('late')] } };
  await remote.putOnce(objectPath(late), encode(late)); await settle(a, 8);
  assert.deepEqual(a.journal.value.local.entries.map(e => e.id).sort(), ['late', 'left', 'right']);
  const puts = remote.puts; await settle(a, 8); assert.equal(remote.puts, puts);
});

test('checkpoint: Checkpoint 不得夹带编辑；只有 Genesis 的候选不生效；身份复用拒绝', async () => {
  const { decode } = await import('../src/v3-protocol.mjs');
  const remote = new FakeRemote(), a = await client(null, remote), binding = await a.engine.initialize(remote); await settle(a);
  const old = a.journal.value.baselinePath, source = decode(await remote.read(old)), r = reference(source, sha(await remote.read(old)), 2);
  const candidate = { ...source, epochId: randomUUID(), objectId: randomUUID(), epochNumber: 2, refs: [r] };
  await remote.putOnce(objectPath(candidate), encode(candidate));
  const budget = new Budget(), h = await new V3History(a.journal, new IndexedV3Cache(a.root, budget), remote, binding.descriptorPath, binding.descriptorHash, budget).open();
  await h.verify(objectPath(candidate)); assert(!h.activeEpochs().includes(candidate.epochId));
  const edited = { ...candidate, epochId: randomUUID(), objectId: randomUUID(), state: { ...emptyState(), entries: [entry('sneaky')] } };
  await remote.putOnce(objectPath(edited), encode(edited)); await assert.rejects(h.verify(objectPath(edited)), /checkpoint extra edit/);
  const duplicate = { ...candidate, epochId: randomUUID(), objectId: decode(await remote.read(binding.descriptorPath)).objectId };
  await remote.putOnce(objectPath(duplicate), encode(duplicate)); await assert.rejects(h.verify(objectPath(duplicate)), /duplicate objectId/);
});
test('offline: 跨多代离线编辑使用原 baseline 重放；新本地编辑拒绝过时冲突选择', async () => {
  const remote = new FakeRemote(), a = await client(null, remote), binding = await a.engine.initialize(remote); await settle(a); const b = await client(binding, remote); await settle(b);
  await b.engine.edit({ ...emptyState(), entries: [entry('offline')] });
  for (let n = 0; n < 3; n++) {
    const budget = new Budget(), h = await new V3History(a.journal, new IndexedV3Cache(a.root, budget), remote, binding.descriptorPath, binding.descriptorHash, budget).open();
    const p = a.journal.value.baselinePath; await h.verify(p); await a.engine.rotate(h, remote, h.nodes[p].epochId, [p]); await settle(a, 4);
  }
  await a.engine.edit({ ...emptyState(), entries: [entry('online')] }); await settle(a, 4); await settle(b, 12); await settle(a, 8);
  assert.deepEqual(b.journal.value.local.entries.map(e => e.id).sort(), ['offline', 'online']);
  await b.engine.saveConflict(emptyState(), b.journal.value.local, emptyState(), [{ key: 'class:offline', keys: ['class:offline'] }], ['test-path'], b.journal.value.revision);
  const id = b.journal.value.conflict.id; await b.engine.edit(b.journal.value.local); await assert.rejects(b.engine.resolve(id, 'remote'), /stale/);
});
test('receipt: PUT 期间的新编辑在 receipt 后仍待发布；换代两阶段丢响应均按原身份恢复', async () => {
  const { decode } = await import('../src/v3-protocol.mjs');
  const remote = new FakeRemote(), a = await client(null, remote); await a.engine.initialize(remote); await settle(a);
  await a.engine.edit({ ...emptyState(), entries: [entry('captured')] });
  const put = remote.putOnce.bind(remote); let started, release;
  const waiting = new Promise(r => { started = r; }), gate = new Promise(r => { release = r; });
  remote.putOnce = async (p, bytes) => { started(); await gate; return put(p, bytes); };
  const round = a.round(); await waiting;
  await a.engine.edit({ ...emptyState(), entries: [entry('captured'), entry('during-put')] }); release(); await round;
  assert.deepEqual(a.journal.value.local.entries.map(e => e.id).sort(), ['captured', 'during-put']); remote.putOnce = put; await a.round();
  assert(a.journal.value.baseline.entries.some(e => e.id === 'during-put'));
  for (const lostType of [2, 4]) {
    const r = new FakeRemote(), c = await client(null, r), binding = await c.engine.initialize(r); await settle(c);
    const budget = new Budget(), h = await new V3History(c.journal, new IndexedV3Cache(c.root, budget), r, binding.descriptorPath, binding.descriptorHash, budget).open();
    const base = c.journal.value.baselinePath; await h.verify(base); const oldEpoch = h.nodes[base].epochId, original = r.putOnce.bind(r); let failed = false;
    r.putOnce = async (p, bytes) => { await original(p, bytes); if (!failed && decode(bytes).type === lostType) { failed = true; throw Error('rotation response lost'); } };
    await assert.rejects(c.engine.rotate(h, r, oldEpoch, [base]), /response lost/);
    if (lostType === 2) assert.equal(c.journal.value.activeEpoch, oldEpoch);
    const reopened = new V3Journal(c.root); await reopened.open(); const engine = new V3Engine(reopened, c.engine.writerId), recoveryBudget = new Budget();
    await engine.round(r, new IndexedV3Cache(c.root, recoveryBudget), recoveryBudget, { discoverySteps: 32 });
    assert.notEqual(reopened.value.activeEpoch, oldEpoch); assert.equal(r.puts, 4); assert.equal(reopened.value.rotation, null);
  }
});
test('equivalent: 等价并发换代保留全部前沿且不制造自动换代风暴', async () => {
  const remote = new FakeRemote(), a = await client(null, remote), binding = await a.engine.initialize(remote); await settle(a); const b = await client(binding, remote); await settle(b);
  const histories = [];
  for (const c of [a, b]) { const budget = new Budget(), h = await new V3History(c.journal, new IndexedV3Cache(c.root, budget), remote, binding.descriptorPath, binding.descriptorHash, budget).open(); const p = c.journal.value.baselinePath; await h.verify(p); histories.push([h, p]); }
  for (let n = 0; n < 2; n++) { const [h, p] = histories[n]; await [a, b][n].engine.rotate(h, remote, h.nodes[p].epochId, [p]); }
  const puts = remote.puts; assert.equal((await settle(a, 8)).status, 'EQUIVALENT_FRONTIERS'); assert.equal((await settle(b, 8)).status, 'EQUIVALENT_FRONTIERS');
  assert.equal(remote.puts, puts); assert.equal(a.journal.value.equivalentFrontiers.length, 2);
});
test('resume: 冷启动深层跨代闭包超过单轮预算，验证栈跨重启继续而非从根重来', async () => {
  const remote = new FakeRemote(), descriptor = object(), descriptorRaw = encode(descriptor); await remote.putOnce(objectPath(descriptor), descriptorRaw);
  let previous = null, previousRaw = null, tip = null;
  for (let epochNumber = 1; epochNumber <= 4; epochNumber++) {
    const genesis = { type: 2, datasetId: descriptor.datasetId, objectId: randomUUID(), writerId: descriptor.writerId, epochId: randomUUID(), epochNumber, descriptorHash: sha(descriptorRaw), refs: previous ? [reference(previous, sha(previousRaw), 2)] : [], state: emptyState() };
    let raw = encode(genesis); await remote.putOnce(objectPath(genesis), raw);
    if (previous) { const transition = { ...previous, type: 4, objectId: randomUUID(), refs: [reference(previous, sha(previousRaw), 2), reference(genesis, sha(raw), 5)], state: null }; await remote.putOnce(objectPath(transition), encode(transition)); }
    previous = genesis; previousRaw = raw;
    for (let n = 0; n < 130; n++) { const next = { ...genesis, type: 3, objectId: randomUUID(), refs: [reference(previous, sha(previousRaw), 1)] }; raw = encode(next); await remote.putOnce(objectPath(next), raw); previous = next; previousRaw = raw; }
    tip = objectPath(previous);
  }
  const root = await dir(); let rounds = 0, journal;
  do {
    journal = new V3Journal(root); await journal.open(); const budget = new Budget(); const h = await new V3History(journal, new IndexedV3Cache(root, budget), remote, objectPath(descriptor), sha(descriptorRaw), budget).open();
    try { await h.verify(tip); } catch (e) { assert.match(e.message, /budget/); assert(journal.value.history.walk.stack.length > 0); }
    assert(++rounds <= 8);
  } while (!journal.value.history.nodes[tip]);
  assert(rounds > 1); assert.equal(journal.value.history.walk, null);
});
test('evidence: 目录中断保存子任务，漏列不遗忘路径，损坏证据元数据不能成为闭包证明', async () => {
  const remote = new FakeRemote(), a = await client(null, remote), binding = await a.engine.initialize(remote);
  const list = remote.list.bind(remote); let calls = 0;
  remote.list = async p => { if (++calls === 2) throw Error('injected list interruption'); return list(p); };
  await assert.rejects(new V3Discovery(a.journal, remote, binding.datasetId).scan(2), /interruption/);
  assert(a.journal.value.discovery.observed.includes(binding.descriptorPath)); assert(a.journal.value.discovery.queue.length);
  const reopened = new V3Journal(a.root); await reopened.open(); remote.list = list;
  await new V3Discovery(reopened, remote, binding.datasetId).scan(32);
  const observed = [...reopened.value.discovery.observed]; remote.list = async () => [];
  await new V3Discovery(reopened, remote, binding.datasetId).scan(8);
  assert(observed.every(p => reopened.value.discovery.observed.includes(p))); remote.list = list;
  const budget = new Budget(), h = await new V3History(reopened, new IndexedV3Cache(a.root, budget), remote, binding.descriptorPath, binding.descriptorHash, budget).open();
  for (const p of observed) if (p !== binding.descriptorPath) await h.verify(p);
  await reopened.transaction(s => { s.history.nodes[binding.descriptorPath].writerId = randomUUID(); });
  const next = new Budget(); await assert.rejects(new V3History(reopened, new IndexedV3Cache(a.root, next), remote, binding.descriptorPath, binding.descriptorHash, next).open(), /certificate corrupt/);
});
