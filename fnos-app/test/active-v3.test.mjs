import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Store } from '../src/store.mjs';
import { Catalog } from '../src/catalog.mjs';
import { BackupManager } from '../src/backup.mjs';
import { Budget } from '../src/v3-store.mjs';
import { DatabaseSync } from 'node:sqlite';

const edit = title => ({ kind: 'entry', action: 'upsert', value: { id: 'course', title, day: 1, startMinute: 480, endMinute: 540, location: '' } });
const remote = () => {
  const objects = new Map(); let puts = 0;
  return { objects, get puts() { return puts; },
    read: async p => objects.get(p) ?? null,
    putOnce: async (p, b) => { assert(!objects.has(p)); puts++; objects.set(p, Buffer.from(b)); },
    list: async scope => [...new Set([...objects.keys()].filter(p => p.startsWith(scope)).map(p => {
      const tail = p.slice(scope.length), i = tail.indexOf('/'); return scope + (i < 0 ? tail : tail.slice(0, i + 1));
    }))].sort() };
};
async function client(t, shared) {
  const directory = await mkdtemp(join(tmpdir(), 'pr55-catalog-')), store = await new Store(directory).init();
  const options = { store, configPath: join(directory, 'config.json'), v3RemoteFactory: () => shared };
  const c = { options, catalog: await Catalog.open(options) };
  t.after(async () => { if (!c.catalog.invalidated) await c.catalog.close(); });
  c.manage = input => c.catalog.manage({ catalogVersion: c.catalog.manifest.revision, ...input });
  const { connectionId } = await c.manage({ action: 'connection.save', name: 'isolated', type: 'webdav', url: 'https://isolated.test/schedules/', username: 'test', password: 'test' });
  const { workspaceId } = await c.manage({ action: 'workspace.add', connectionId, name: 'v3 test', protocol: 'v3' });
  c.id = workspaceId; c.db = () => c.catalog.db(c.id);
  c.sync = options => c.catalog.network(c.id, e => e.synchronize(options));
  c.settle = async () => { let result; for (let n = 0; n < 8; n++) { try { result = await c.sync(); } catch (e) { if (!/budget|incomplete/.test(e.message)) throw e; } } return result; };
  c.reopen = async () => { await c.catalog.close(); c.catalog = await Catalog.open(options); await c.catalog.load(c.id); };
  return c;
}

test('Catalog v3: 正式编辑、明确身份发现/加入、双向同步与进程重开使用唯一 SQLite Workspace', async t => {
  const r = remote(), a = await client(t, r), b = await client(t, r);
  await a.db().edit(edit('base'), a.db().frame.revision);
  await a.sync({ create: true }); await a.settle();
  const candidates = await b.sync({ discover: true }); assert.equal(candidates.candidates.length, 1);
  assert.equal(b.db().frame.datasetId, null);
  await assert.rejects(b.sync({ bind: true }), /Descriptor/); assert.equal(b.db().frame.datasetId, null);
  await b.sync({ bind: true, ...candidates.candidates[0] }); await b.settle();
  assert.equal(b.db().frame.local.entries[0].title, 'base');
  await b.db().edit(edit('from B'), b.db().frame.revision); await b.settle(); await a.settle();
  assert.equal(a.db().frame.local.entries[0].title, 'from B');
  assert.equal(a.db().activeV3.journal.value.writerId, a.catalog.store.identity.writerId);
  assert.equal(a.db().store.identity.writerId, a.catalog.store.identity.writerId);
  const stale = a.db(), dataset = stale.frame.datasetId, puts = r.puts;
  await a.reopen(); assert.equal(a.db().frame.datasetId, dataset); assert.equal(a.db().frame.local.entries[0].title, 'from B');
  await assert.rejects(stale.edit(edit('stale')), /失效/); await a.settle(); assert.equal(r.puts, puts);
  assert.equal(a.db().activeV3.history().oldHistoryAuditComplete, false);
  assert.equal((await a.catalog.summary()).workspaces.find(w => w.id === a.id).datasetId, dataset);
  const expired = new Budget({ millis: 0 }); a.db().activeV3.journal.meta.budget = expired;
  assert.equal(a.db().frame.datasetId, dataset); assert(a.db().activeV3.history().frontier.length);
  assert.equal(a.db().activeV3.journal.meta.budget, expired);
  await a.db().edit(edit('offline'), a.db().frame.revision);
  assert.equal(a.db().frame.local.entries[0].title, 'offline');
});

test('Catalog v3: 非空加入不覆盖；备份、旧历史和连接切换有明确边界', async t => {
  const r = remote(), a = await client(t, r), b = await client(t, r);
  await a.sync({ create: true }); await a.settle();
  const candidate = (await b.sync({ discover: true })).candidates[0];
  await b.db().edit(edit('retained'), b.db().frame.revision);
  await assert.rejects(b.sync({ bind: true, ...candidate }), /本地非空/);
  assert.equal(b.db().frame.datasetId, null); assert.equal(b.db().frame.local.entries[0].title, 'retained');
  const c = b.catalog.connection(b.catalog.row(b.id).connectionId);
  await assert.rejects(b.manage({ action: 'connection.save', connectionId: c.id, url: 'https://other.test/' }), /v3 连接变更/);
  const backup = new BackupManager({ catalog: b.catalog, configPath: b.options.configPath });
  await assert.rejects(backup.create({}), /不支持 v3/);
  await assert.rejects(backup.restore({}), /活动 v3/);
});

test('Catalog v3: 正式 Workspace 编辑后的冲突与过期决定重开后保留', async t => {
  const r = remote(), a = await client(t, r), b = await client(t, r);
  await a.db().edit(edit('base')); await a.sync({ create: true }); await a.settle();
  await b.sync({ bind: true, ...(await b.sync({ discover: true })).candidates[0] }); await b.settle();
  await a.db().edit(edit('left')); await b.db().edit(edit('right')); await a.settle();
  assert.equal((await b.sync()).status, 'CONFLICT'); const token = b.db().conflictToken(), version = b.db().frame.revision;
  await b.reopen(); assert.equal(b.db().conflictToken(), token);
  await assert.rejects(b.catalog.network(b.id, e => e.resolve('local', token, version - 1)), /过期/);
  await b.sync();
  await b.catalog.network(b.id, e => e.resolve('local', b.db().conflictToken(), b.db().frame.revision));
  await b.settle(); await a.settle(); assert.equal(a.db().frame.local.entries[0].title, 'right');
});
test('Catalog v3: 释放非活动 Writer 后只读身份检查不重开业务帧；旧引用不能写',async t=>{
  const c=await client(t,remote()); await c.sync({create:true}); await c.settle();
  const old=c.db(),dataset=old.frame.datasetId; c.catalog.prune();
  await assert.rejects(old.edit(edit('stale')),/失效/);
  const identities=await c.catalog.identities(); assert.equal(identities.get(c.id),dataset); assert(!c.catalog.workspaces.has(c.id));
  await c.catalog.load(c.id); assert.equal(c.db().frame.datasetId,dataset);
});
test('Catalog v3: 已注册 SQLite 丢失 Workspace 帧时拒绝静默创建空帧',async t=>{
  const c=await client(t,remote()); await c.sync({create:true}); await c.settle();
  const path=join(c.catalog.workspaceStore(c.id).dir,'v3','v3-state-v2.sqlite');
  c.db().activeV3.journal.meta.atomic(()=>c.db().activeV3.journal.meta.remove('workspace','workspace.json'));
  c.catalog.prune(); await Promise.all(c.catalog.closing.values());
  await assert.rejects(c.catalog.load(c.id),/帧缺失/);
  const db=new DatabaseSync(path,{readOnly:true});
  try { assert.equal(db.prepare("SELECT count(*) AS n FROM v3_records WHERE ns='workspace' AND k='workspace.json'").get().n,0); }
  finally {db.close();}
});
