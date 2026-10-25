import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm, readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { randomUUID } from 'node:crypto';
import { Store, json } from '../src/store.mjs';
import { Workspace } from '../src/workspace.mjs';
import { Catalog } from '../src/catalog.mjs';
import { SyncEngine } from '../src/sync.mjs';
import { decode, encode, hash } from '../src/protocol.mjs';

const operation = (title, id = 'course') => ({ kind: 'entry', action: 'upsert', value: { id, title, day: 1, startMinute: 480, endMinute: 530, location: '' } });
test('HTTP 必须显式允许，连接与重启保留同一明文授权；禁止伪装证书保护', async t => {
  const f=await setup(t);t.after(()=>f.catalog.close());
  const input={action:'connection.save',type:'webdav',name:'isolated HTTP',url:'http://192.168.3.153:5005/dormpanel/',username:'test',password:'test'};
  await assert.rejects(f.manage(input),/显式允许/);
  const {connectionId}=await f.manage({...input,allowInsecureHttp:true});
  assert.equal(f.catalog.connection(connectionId).config.allowInsecureHttp,true);
  assert.equal((await f.catalog.summary()).connections.find(c=>c.id===connectionId).allowInsecureHttp,true);
  await assert.rejects(f.manage({...input,connectionId,allowInsecureHttp:false}),/显式允许/);
  await assert.rejects(f.manage({...input,connectionId,certificatePem:'test'}),/HTTP 不支持证书/);
  await f.catalog.close();f.catalog=await Catalog.open(f.options);
  assert.equal(f.catalog.connection(connectionId).config.allowInsecureHttp,true);
});
function remote(key, rows = new Map()) {
  return { key, rows, list: async () => [...rows.keys()], read: async id => rows.get(id) ?? null,
    put: async (id, bytes) => { assert(!rows.has(id)); rows.set(id, bytes); } };
}
async function setup(t, hook = null) {
  const dir = await mkdtemp(join(tmpdir(), 'pr39-catalog-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const store = await new Store(dir).init(), remotes = new Map();
  const options = { store, configPath: join(dir, 'webdav.json'), remoteFactory: c => {
    if (!remotes.has(c.url)) remotes.set(c.url, remote(c.url + 'dormpanel-sync-v2/'));
    return remotes.get(c.url);
  } };
  if (hook) await hook({ dir, store, options });
  const catalog = await Catalog.open(options);
  const manage = input => catalog.manage({ catalogVersion: catalog.manifest.revision, ...input });
  async function add(name) {
    const { connectionId } = await manage({ action: 'connection.save', type: 'webdav', name, url: `https://${name}.test/`, username: 'user', password: 'secret-' + name });
    const { workspaceId } = await manage({ action: 'workspace.add', name, connectionId });
    return { id: workspaceId, db: catalog.db(workspaceId), c: catalog.connection(connectionId), remote: catalog.remote(catalog.connection(connectionId)) };
  }
  return { dir, store, options, catalog, manage, add, remotes };
}
for (const interruption of ['before', 'after']) test(`PR38 非空完整迁移，manifest ${interruption} 落盘中断后幂等恢复`, async t => {
  let original, configBytes, legacyIdentity;
  const f = await setup(t, async ({ dir, store, options }) => {
    const db = await Workspace.open(store), r = remote('https://old.test/dormpanel-sync-v2/');
    await db.edit(operation('原有课程'), db.frame.revision);
    await new SyncEngine(db, r).synchronize({ create: true });
    await db.edit(operation('精确候选'), db.frame.revision);
    await db.tx(frame => { db.stage(frame, frame.local, frame.frontier); frame.pending.attempted = true; frame.abandoned[randomUUID()] = 'a'.repeat(64); frame.error = '原有异常'; });
    await db.edit(operation('capture 之后编辑', 'later'), db.frame.revision);
    // 同时保留无法识别的新恢复字段；迁移只做完整复制，不重建精确候选。
    await db.tx(frame => { frame.conflict = { marker: '原有冲突输入', branches: [] }; frame.recoveryExtension = { receipt: '原有扩展信息' }; });
    original = await readFile(join(dir, 'workspace.json'), 'utf8');
    legacyIdentity = await readFile(join(dir, 'identity.json'), 'utf8');
    configBytes = json({ url: 'https://old.test/', username: 'old-user', password: 'old-secret', certificatePem: await readFile(new URL('./fixtures/test-certificate.pem', import.meta.url), 'utf8') });
    await writeFile(options.configPath, configBytes);
    const save = store.save.bind(store); let fail = true;
    store.save = async (name, value) => {
      if (name === 'catalog.json' && fail) {
        fail = false; if (interruption === 'after') await save(name, value);
        throw Error('模拟迁移中断');
      }
      return save(name, value);
    };
    await assert.rejects(Catalog.open(options), /中断/); store.save = save;
  });
  const id = f.catalog.manifest.activeId;
  assert.equal(json(f.catalog.db(id).frame), original);
  assert.equal(await readFile(join(f.dir, 'workspaces', id, 'workspace.json'), 'utf8'), original);
  assert.equal(await readFile(join(f.dir, 'workspace.json'), 'utf8'), original);
  assert.equal(await readFile(join(f.dir, 'identity.json'), 'utf8'), legacyIdentity);
  assert.equal(await readFile(f.options.configPath, 'utf8'), configBytes);
  assert.equal(f.catalog.connection(f.catalog.row(id).connectionId).config.password, 'old-secret');
  const again = await Catalog.open(f.options);
  assert.equal(json(again.db(id).frame), original);
  assert.equal(again.manifest.workspaces.length, 1);
});
test('A/B 独立编辑，候选 capture、冲突、异常与 writer 身份跨切换及重启完整保留', async t => {
  const f = await setup(t), a = await f.add('a'), b = await f.add('b');
  await a.db.edit(operation('A'), a.db.frame.revision);
  await f.catalog.network(a.id, e => e.synchronize({ create: true }));
  await a.db.edit(operation('A 待发布'), a.db.frame.revision);
  await a.db.tx(frame => a.db.stage(frame, frame.local, frame.frontier));
  await b.db.edit(operation('B'), b.db.frame.revision);
  await f.catalog.network(b.id, e => e.synchronize({ create: true }));
  const root = decode([...b.remote.rows.values()][0]);
  await b.db.edit(operation('B 本地冲突'), b.db.frame.revision);
  const branch = { ...root, commitId: randomUUID(), writerId: randomUUID(), parents: [root.commitId], state: structuredClone(root.state) };
  branch.state.entries[0].title = 'B 远端冲突'; b.remote.rows.set(branch.commitId, encode(branch));
  await f.catalog.network(b.id, e => e.synchronize()); assert(b.db.frame.conflict);
  const beforeA = json(a.db.frame), beforeB = json(b.db.frame);
  for (const targetId of [a.id, b.id, a.id]) await f.manage({ action: 'workspace.switch', targetId });
  const restarted = await Catalog.open(f.options);
  assert.equal(json((await restarted.load(a.id)).frame), beforeA); assert.equal(json((await restarted.load(b.id)).frame), beforeB);
  for (const w of [a, b]) assert.equal(w.db.store.identity.writerId, f.store.identity.writerId);
  assert.notEqual(a.db.frame.datasetId, b.db.frame.datasetId);
  await assert.rejects(f.manage({ action: 'workspace.remove', targetId: b.id, targetVersion: b.db.frame.revision, confirm: 'b' }), /修改|冲突|恢复/);
});
test('上传期间切换并编辑另一工作区，回执与 capture replay 始终留在 A', async t => {
  const f = await setup(t), a = await f.add('a'), b = await f.add('b');
  await f.catalog.network(a.id, e => e.synchronize({ create: true }));
  await a.db.edit(operation('上传中'), a.db.frame.revision);
  let release, entered;
  const gate = new Promise(r => release = r), waiting = new Promise(r => entered = r), put = a.remote.put;
  a.remote.put = async (id, bytes) => { entered(); await gate; return put(id, bytes); };
  const sync = f.catalog.network(a.id, e => e.synchronize()); await waiting;
  await assert.rejects(f.catalog.network(a.id, e => e.synchronize()), /正在/);
  await assert.rejects(f.manage({ action: 'connection.save', connectionId: a.c.id, name: '更新名称' }), /正在同步/);
  await f.manage({ action: 'workspace.switch', targetId: b.id });
  await b.db.edit(operation('B 离线'), b.db.frame.revision);
  await a.db.edit(operation('上传后新增', 'later'), a.db.frame.revision);
  release(); await sync;
  assert(a.db.frame.receipt); assert(!b.db.frame.receipt); assert(!b.db.frame.datasetId);
  assert.equal(b.db.frame.local.entries[0].title, 'B 离线');
  assert(a.db.frame.local.entries.some(r => r.title === '上传后新增'));
  assert.equal(f.catalog.manifest.activeId, b.id);
});
test('同数据集两个地址拒绝重复绑定；端点迁移验证已知内容与闭包，保留本地修改', async t => {
  const f = await setup(t), a = await f.add('a'), b = await f.add('b');
  await a.db.edit(operation('A 根'), a.db.frame.revision);
  await f.catalog.network(a.id, e => e.synchronize({ create: true }));
  b.remote.rows = a.remote.rows;
  f.remotes.set(b.c.config.url, remote(b.c.config.url + 'dormpanel-sync-v2/', a.remote.rows));
  await assert.rejects(f.catalog.network(b.id, e => e.synchronize({ bind: true })), /已绑定/);
  assert.equal(b.db.frame.datasetId, null); assert.equal(b.db.frame.local.entries.length, 0);
  assert.deepEqual(b.db.frame.checkpoint, {});
  await a.db.edit(operation('离线 A'), a.db.frame.revision);
  const candidate = await f.manage({ action: 'connection.save', type: 'webdav', name: '端点 C', url: 'https://c.test/', username: 'u', password: 'p' });
  const c = f.catalog.connection(candidate.connectionId);
  f.remotes.set(c.config.url, remote(c.config.url + 'dormpanel-sync-v2/', a.remote.rows));
  await f.manage({ action: 'workspace.endpoint', targetId: a.id, targetVersion: a.db.frame.revision, connectionId: c.id });
  assert.equal(a.db.frame.local.entries[0].title, '离线 A');
  const before = json(a.db.frame), config = json(f.catalog.connection(c.id));
  f.remotes.set('https://bad.test/', remote('https://bad.test/dormpanel-sync-v2/'));
  await assert.rejects(f.manage({ action: 'connection.save', connectionId: c.id, url: 'https://bad.test/' }));
  assert.equal(json(a.db.frame), before); assert.equal(json(f.catalog.connection(c.id)), config);
});
test('连接更新日志中断后自动完成，迁移不能清空课程或恢复状态', async t => {
  const f = await setup(t), a = await f.add('a');
  await a.db.edit(operation('保留课程'), a.db.frame.revision);
  await f.catalog.network(a.id, e => e.synchronize({ create: true }));
  const key = 'https://new.test/'; f.remotes.set(key, remote(key + 'dormpanel-sync-v2/', a.remote.rows));
  const save = a.db.store.save; a.db.store.save = async () => { throw Error('磁盘中断'); };
  await assert.rejects(f.manage({ action: 'connection.save', connectionId: a.c.id, url: key }), /磁盘/);
  assert(f.catalog.manifest.transition); a.db.store.save = save;
  const restarted = await Catalog.open(f.options);
  assert(!restarted.manifest.transition);
  assert.equal(restarted.connection(a.c.id).config.url, key);
  assert.equal((await restarted.load(a.id)).frame.bindingKey, key + 'dormpanel-sync-v2/');
  assert.equal((await restarted.load(a.id)).frame.local.entries[0].title, '保留课程');
});
test('已注册工作区文件缺失时拒绝静默创建空文件；管理版本和目标版本防重放', async t => {
  const f = await setup(t), a = await f.add('a');
  await assert.rejects(f.catalog.manage({ catalogVersion: -1, action: 'workspace.switch', targetId: a.id }), /过期/);
  await assert.rejects(f.manage({ action: 'workspace.rename', targetId: a.id, targetVersion: -1, name: '过期' }), /过期/);
  await rm(join(a.db.store.dir, 'workspace.json'));
  const reopened = await Catalog.open(f.options);
  await assert.rejects(reopened.load(a.id), /缺失/);
  await assert.rejects(readFile(join(a.db.store.dir, 'workspace.json')), { code: 'ENOENT' });
});

test('已观察但尚未确认绑定的数据集也不能通过改凭据或 URL 绕过验证', async t => {
  const f = await setup(t), a = await f.add('a');
  await a.db.tx(frame => { frame.checkpoint.datasetId = randomUUID(); frame.checkpoint.hashes = { [randomUUID()]: 'a'.repeat(64) }; });
  const before = json(f.catalog.connection(a.c.id));
  await assert.rejects(f.manage({ action: 'connection.save', connectionId: a.c.id, url: 'https://other.test/' }), /观察数据集/);
  await assert.rejects(f.manage({ action: 'connection.save', connectionId: a.c.id, password: 'rotated' }), /观察数据集/);
  assert.equal(json(f.catalog.connection(a.c.id)), before);
});

test('空工作区重复绑定失败后可确认移除，干净已发布工作区移除配置仍保留全部文件', async t => {
  const f = await setup(t), a = await f.add('a'), b = await f.add('b');
  await f.catalog.network(a.id, e => e.synchronize({ create: true }));
  f.remotes.set(b.c.config.url, remote(b.c.config.url + 'dormpanel-sync-v2/', a.remote.rows));
  await assert.rejects(f.catalog.network(b.id, e => e.synchronize({ bind: true })), /已绑定/);
  const beforeB = await readFile(join(b.db.store.dir, 'workspace.json'), 'utf8');
  await f.manage({ action: 'workspace.remove', targetId: b.id, targetVersion: b.db.frame.revision, confirm: 'b' });
  assert.equal(await readFile(join(b.db.store.dir, 'workspace.json'), 'utf8'), beforeB);
  const beforeA = await readFile(join(a.db.store.dir, 'workspace.json'), 'utf8'), count = a.remote.rows.size;
  await f.manage({ action: 'workspace.remove', targetId: a.id, targetVersion: a.db.frame.revision, confirm: 'a' });
  assert.equal(await readFile(join(a.db.store.dir, 'workspace.json'), 'utf8'), beforeA); assert.equal(a.remote.rows.size, count);
  await f.manage({ action: 'connection.remove', connectionId: a.c.id });
});
