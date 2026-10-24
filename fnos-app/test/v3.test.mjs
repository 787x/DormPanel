import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile, readdir, mkdtemp, mkdir, writeFile, unlink, symlink, stat } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { decode, encode, sha, businessHash, check, checkReference } from '../src/v3-protocol.mjs';
import { Budget, objectPath, file, scope, scopeInfo, epochScope, V3LocalStore, V3WebDavStore, parseListing, match } from '../src/v3-store.mjs';
import { V3Cache, Observation } from '../src/v3-cache.mjs';
import { LocalGrants } from '../src/local.mjs';
import { fork } from 'node:child_process';
import { once } from 'node:events';

const root = new URL('../../schedule-core/src/test/resources/sync-v3/', import.meta.url);
const vector = n => readFile(new URL(n + '.dpv', root));
const manifest = Object.fromEntries((await readFile(new URL('manifest.properties', root), 'utf8')).trim().split('\n').map(s => s.split('=')));
test('独立路径向量：16 级 UUID 前缀、严格叶子和旧平铺路径拒绝', async () => {
  const paths = JSON.parse(await readFile(new URL('paths.json', root), 'utf8'));
  for (const [id, expected] of Object.entries(paths.epochs)) { assert.equal(epochScope('00000000-0000-4000-8000-000000000001', id), expected); assert.equal(scopeInfo(expected).epochId, id); }
  for (const [name, expected] of Object.entries(paths.objects)) { const raw = await vector(name); assert.equal(objectPath(decode(raw)), expected); file(expected); }
  paths.scopes.forEach(scope); paths.invalidScopes.forEach(p => assert.throws(() => scope(p))); paths.invalidFiles.forEach(p => assert.throws(() => file(p)));
  const o = decode(await vector('genesis-empty')); assert.throws(() => match(objectPath(o), encode({ ...o, epochId: 'ffffffff-ffff-ffff-ffff-ffffffffffff' })));
});
for (const name of await readdir(root)) if (name.endsWith('.dpv')) test(`v3 独立固定向量 ${name}`, async () => {
  const b = await readFile(new URL(name, root));
  if (name.startsWith('bad-')) { assert.throws(() => decode(b)); return; }
  const n = name.slice(0, -4), o = decode(b);
  assert.deepEqual(encode(o), b); assert.equal(sha(b), manifest[n + '.hash']); assert.equal(b.length, Number(manifest[n + '.size']));
  assert.equal(check(b, o.datasetId, o.objectId, sha(b)).type, o.type);
  assert.throws(() => check(b, o.writerId, o.objectId, sha(b))); assert.throws(() => check(b, o.datasetId, o.objectId, '0'.repeat(64)));
  if (o.state) assert.equal(businessHash(o.state), manifest[n + '.business']);
});
test('业务哈希忽略封装和 importedAt，保留 null/空字符串及内容差异', async () => {
  const o = decode(await vector('commit-complex')), s = structuredClone(o.state);
  s.sources[0].importedAt += 300n; assert.equal(businessHash(s), businessHash(o.state));
  assert.notDeepEqual(encode({ ...o, state: s }), encode(o));
  s.events[0].note += 'x'; assert.notEqual(businessHash(s), businessHash(o.state));
});
test('独立引用验证覆盖 Epoch、类型、descriptor 和完整文件哈希', async () => {
  const commit = decode(await vector('commit-complex')), genesis = await vector('genesis-empty'), ref = commit.refs[0];
  assert.equal(checkReference(genesis, commit.datasetId, commit.descriptorHash, ref).type, 2);
  for (const patch of [{ epochNumber: 2 }, { type: 3 }, { hash: '1'.repeat(64) }]) assert.throws(() => checkReference(genesis, commit.datasetId, commit.descriptorHash, { ...ref, ...patch }));
  assert.throws(() => checkReference(genesis, commit.datasetId, '1'.repeat(64), ref));
});
test('路径、scope 和跨代引用形状拒绝', async () => {
  for (const p of ['../x', 'dormpanel-sync-v2/', '/dataset-x/', 'dataset-AAAAAAAA-0000-0000-0000-000000000000/']) assert.throws(() => scope(p));
  for (const p of ['../x', 'commit-x.dpv', 'dataset-00000000-0000-4000-8000-000000000001/%2e%2e/a', 'dataset-00000000-0000-4000-8000-000000000001/epoch-00000000-0000-4000-8000-000000000010/descriptor-00000000-0000-4000-8000-000000000003.dpv']) assert.throws(() => file(p));
  const o = decode(await vector('bridge')); o.refs.find(r => r.role === 3).epochNumber = 2; assert.throws(() => encode(o));
  const g = decode(await vector('genesis-empty')); g.epochNumber = 2; assert.throws(() => encode(g));
});
test('缓存重启、命中免远端读取、缺失按原哈希回读、崩溃重建、身份冲突与证据隔离', async () => {
  const dir = join(await mkdtemp(join(tmpdir(), 'dp-v3-')), 'sync-v3-cache-v1'), raw = await vector('commit-complex'), o = decode(raw), path = objectPath(o);
  const crashed = new V3Cache(dir, new Budget(), async () => { throw Error('crash after blob'); }); await crashed.recover(); await assert.rejects(crashed.put(path, raw));
  const cache = new V3Cache(dir, new Budget()); assert.equal((await cache.recover()).length, 1);
  let reads = 0; const remote = { read: async () => { reads++; return raw; } };
  assert.deepEqual(await cache.load(path, remote), raw); assert.equal(reads, 0);
  await unlink(join(dir, sha(raw) + '.dpv')); const restarted = new V3Cache(dir, new Budget()); await restarted.recover();
  const other = encode({ ...o, writerId: o.datasetId }); await assert.rejects(restarted.put(path, other));
  assert.deepEqual(await restarted.load(path, remote), raw); assert.equal(reads, 1);
  const observation = new Observation('endpoint-A', 'round-1'); assert.deepEqual(observation.evidence(path, true), { localContentVerified: true, observedThisRound: false, bytesRecheckedThisRound: false, closureVerifiedThisRound: false });
  observation.observe(path); assert.equal(observation.evidence(path, true).bytesRecheckedThisRound, false);
  observation.recheck(path, raw, sha(raw)); assert.equal(observation.evidence(path, true).closureVerifiedThisRound, false);
  const localClosure = new Observation('endpoint-A', 'local-only'); localClosure.recordVerifiedClosure([path]); assert.equal(localClosure.evidence(path, true).observedThisRound, false); assert.equal(localClosure.evidence(path, true).closureVerifiedThisRound, true);
  assert.equal(new Observation('endpoint-B', 'round-2').evidence(path, true).observedThisRound, false);
  await writeFile(join(dir, '.partial.tmp'), 'partial'); await restarted.recover();
  const tiny = new V3Cache(dir, new Budget({ bytes: 100 })); await assert.rejects(tiny.recover(), /budget/);
  await writeFile(join(dir, sha(raw) + '.dpv'), 'broken'); await assert.rejects(restarted.get(path));
});
test('缓存孤儿重建及数量/字节/请求/时间预算', async () => {
  const dir = join(await mkdtemp(join(tmpdir(), 'dp-v3-')), 'sync-v3-cache-v1'); await mkdir(dir); const raw = await vector('genesis-empty');
  await writeFile(join(dir, sha(raw) + '.dpv'), raw); const c = new V3Cache(dir, new Budget()); assert.equal((await c.recover()).length, 1);
  assert.throws(() => new Budget({ requests: 0 }).charge()); assert.throws(() => new Budget({ bytes: 1 }).charge(2, 0));
  assert.throws(() => new Budget({ bytes: Infinity })); assert.throws(() => new Budget({ requests: 513 }));
  let now = 0; const b = new Budget({ millis: 10, clock: () => now }); now = 10; assert.throws(() => b.charge());
});
test('WebDAV 隔离 create 精确回读、先持久化意图、占用与不确定写入', async () => {
  const raw = await vector('genesis-empty'), path = objectPath(decode(raw)); let content = null, intent = false; const calls = [];
  const store = new V3WebDavStore({ url: 'https://example.org/test/', username: 'u', password: 'p' }, new Budget());
  store.request = async (method, p, max, bytes) => { calls.push(method); assert.equal(p, path); if (method === 'PUT') { assert.equal(intent, true); content = bytes; } return method === 'GET' ? content : Buffer.alloc(0); };
  await store.createForIsolatedTest(path, raw, async () => { intent = true; }); assert.deepEqual(calls, ['GET', 'PUT', 'GET']);
  await store.createForIsolatedTest(path, raw, () => assert.fail()); content = Buffer.from('occupied'); await assert.rejects(store.createForIsolatedTest(path, raw, () => assert.fail()));
  content = null; store.request = async m => m === 'GET' ? null : Buffer.alloc(0); await assert.rejects(store.createForIsolatedTest(path, raw, async () => {}), /uncertain/);
});
test('WebDAV 列举拒绝重复、越界、非协议文件和异常 propstat', () => {
  const parent = 'dataset-00000000-0000-4000-8000-000000000001/', base = new URL('https://example.org/test/dormpanel-sync-v3/' + parent);
  const row = (name, status = '200 OK') => `<d:response><d:href>${base.pathname}${name}</d:href><d:propstat><d:prop><d:resourcetype/></d:prop><d:status>HTTP/1.1 ${status}</d:status></d:propstat></d:response>`;
  const xml = rows => `<d:multistatus xmlns:d="DAV:">${rows}</d:multistatus>`;
  const valid = row('descriptor-00000000-0000-4000-8000-000000000003.dpv'); assert.equal(parseListing(xml(valid), base, parent).length, 1);
  for (const bad of [valid + valid, row('bad'), row('../outside'), row('%2e%2e/x'), row('./descriptor-00000000-0000-4000-8000-000000000003.dpv'), row('x', '403 Forbidden')]) assert.throws(() => parseListing(xml(bad), base, parent));
});
test('Linux 官方授权本地目录 list/read/create、拒绝覆盖与符号链接', { skip: process.platform !== 'linux' }, async () => {
  const dir = await mkdtemp(join(tmpdir(), 'dp-v3-local-')), grants = new LocalGrants([{ id: 'test', path: dir }]);
  const d = await grants.directory('test'); const stamp = d.stamp; await d.fd.close();
  const config = { rootId: 'test', relative: '', directoryIdentity: stamp }, store = new V3LocalStore(config, grants, new Budget());
  const raw = await vector('genesis-empty'), o = decode(raw), parent = epochScope(o.datasetId, o.epochId), path = objectPath(o);
  await store.prepareForIsolatedTest(parent); let intent = false; await store.createForIsolatedTest(path, raw, async () => { intent = true; }); assert.equal(intent, true);
  assert.deepEqual(await store.read(path), raw); assert.deepEqual(await store.list(parent), [path]);
  await assert.rejects(store.createForIsolatedTest(path, encode({ ...o, writerId: o.datasetId }), async () => {}));
  await symlink(join(dir, 'dormpanel-sync-v3', path), join(dir, 'dormpanel-sync-v3', parent, 'commit-00000000-0000-4000-8000-000000000099.dpv'));
  await assert.rejects(store.list(parent));
  await assert.rejects(new V3LocalStore({ ...config, directoryIdentity: 'changed' }, grants, new Budget()).read(path));
});

async function localFixture() {
  const dir = await mkdtemp(join(tmpdir(), 'dp-v3-revision-')), grants = new LocalGrants([{ id: 'test', path: dir }]);
  const d = await grants.directory('test'); const config = { rootId: 'test', relative: '', directoryIdentity: d.stamp }; await d.fd.close();
  return { dir, grants, config, store: new V3LocalStore(config, grants, new Budget()) };
}
test('Linux 暂存中列举、故障清理和排他公开竞态均不污染协议目录', { skip: process.platform !== 'linux' }, async () => {
  const f = await localFixture(), raw = await vector('genesis-empty'), o = decode(raw), parent = epochScope(o.datasetId, o.epochId), path = objectPath(o);
  await f.store.prepareForIsolatedTest(parent);
  const failing = new V3LocalStore(f.config, f.grants, new Budget(), async name => {
    assert.equal((await stat(join(f.dir, name))).mode & 0o777, 0o700);
    assert.deepEqual(await f.store.list(parent), []); assert.deepEqual(await f.store.list(''), [path.split('/')[0]+'/']);
    assert((await readdir(f.dir)).includes(name)); throw Error('fault before link');
  });
  await assert.rejects(failing.createForIsolatedTest(path, raw, async () => assert.fail('must not publish')), /fault before link/);
  assert.deepEqual(await f.store.list(parent), []);
  assert(!(await readdir(f.dir)).some(n => n.startsWith('.v3-stage-')));
  const different = encode({ ...o, writerId: o.datasetId });
  const raced = new V3LocalStore(f.config, f.grants, new Budget(), async () => { await writeFile(join(f.dir, 'dormpanel-sync-v3', path), different); });
  await assert.rejects(raced.createForIsolatedTest(path, raw, async () => {}), { code: 'EEXIST' });
  assert.deepEqual(await f.store.read(path), different); assert.deepEqual(await f.store.list(parent), [path]);
});
test('Linux 进程 SIGKILL 后暂存残留隔离，新实例仍可列举和创建', { skip: process.platform !== 'linux' }, async t => {
  for (const phase of ['staged', 'published']) {
  const f = await localFixture(), raw = await vector('genesis-empty'), o = decode(raw), parent = epochScope(o.datasetId, o.epochId), path = objectPath(o);
  await f.store.prepareForIsolatedTest(parent);
  const worker = fork(new URL('./fixtures/v3-stage-worker.mjs', import.meta.url), [f.dir, phase], { stdio: ['ignore', 'pipe', 'pipe', 'ipc'] });
  t.after(() => worker.kill('SIGKILL'));
  const [message] = await once(worker, 'message', { signal: AbortSignal.timeout(10000) }); assert(message.stage.startsWith('.v3-stage-'));
  assert.equal(message.phase, phase); const expected = phase === 'staged' ? [] : [path];
  assert.deepEqual(await f.store.list(parent), expected);
  const exited = once(worker, 'exit'); worker.kill('SIGKILL'); const [, signal] = await exited; assert.equal(signal, 'SIGKILL');
  assert((await readdir(f.dir)).includes(message.stage));
  const recovered = new V3LocalStore(f.config, f.grants, new Budget()); assert.deepEqual(await recovered.list(parent), expected);
  await recovered.createForIsolatedTest(path, raw, async () => {}); assert.deepEqual(await recovered.read(path), raw); assert.deepEqual(await recovered.list(parent), [path]);
  assert((await readdir(f.dir)).includes(message.stage), 'unknown crash residue must not be deleted automatically');
  }
});
test('Linux 300 Epoch 在同一数据集按前缀跨预算轮次发现，无平铺目录上限', { skip: process.platform !== 'linux' }, async () => {
  const f = await localFixture(), template = decode(await vector('genesis-empty')), expected = new Set();
  let previous = null;
  for (let n = 1; n <= 300; n++) {
    const suffix = (n-1).toString(16).padStart(12, '0');
    const o = { ...template, epochId: `01000000-0000-0000-0000-${suffix}`, objectId: `10000000-0000-0000-0000-${suffix}`, epochNumber: n,
      refs: previous ? [{ role: 2, type: 2, epochId: previous.value.epochId, epochNumber: previous.value.epochNumber, objectId: previous.value.objectId, hash: sha(previous.raw) }] : [] };
    const parent = epochScope(o.datasetId, o.epochId), store = new V3LocalStore(f.config, f.grants, new Budget()), raw = encode(o);
    if (previous) checkReference(previous.raw, o.datasetId, o.descriptorHash, o.refs[0]);
    await store.prepareForIsolatedTest(parent); await store.createForIsolatedTest(objectPath(o), raw, async () => {}); expected.add(parent); previous = { value: o, raw };
  }
  let queue = [`dataset-${template.datasetId}/epochs/`], seen = [], rounds = 0, maxChildren = 0;
  while (queue.length) {
    const store = new V3LocalStore(f.config, f.grants, new Budget({ requests: 3 })); let next = [...queue];
    for (let n = 0; n < 3 && next.length; n++) {
      const current = next.shift(); const children = await store.list(current); assert(children.length <= 256); maxChildren = Math.max(maxChildren, children.length);
      for (const child of children) { if (scopeInfo(child).kind === 'epoch') seen.push(child); else next.push(child); }
    }
    // The next PR can persist exactly these validated scope strings between rounds/restarts.
    queue = JSON.parse(JSON.stringify(next)); rounds++;
  }
  assert.deepEqual(new Set(seen), expected); assert(rounds > 1); assert.equal(maxChildren, 256);
});
