import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm, mkdir, readFile, readdir, chmod, symlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { Store } from '../src/store.mjs';
import { Workspace } from '../src/workspace.mjs';
import { SyncEngine } from '../src/sync.mjs';
import { emptyState, same } from '../src/merge.mjs';
import { encode, decode, overrideKey } from '../src/protocol.mjs';
import { loadGraph } from '../src/graph.mjs';
import { LocalGrants, LocalCommitStore } from '../src/local.mjs';
import { HistoryManager, differences, historySummary, cachedGraph, versionDifferences } from '../src/history.mjs';

const course = (id, title = '同名课程') => ({ id, title, day: 1, startMinute: 480, endMinute: 540, location: '' });
async function fixture(t, size = 2) {
  const dir = await mkdtemp(join(tmpdir(), 'dp-history-')); t.after(() => rm(dir, { recursive: true, force: true }));
  const store = await new Store(join(dir, 'state')).init(), datasetId = randomUUID(), rows = new Map(); let parent;
  const ids = [];
  for (let i = 0; i < size; i++) {
    const c = { datasetId, writerId: store.identity.writerId, commitId: randomUUID(), parents: parent ? [parent] : [], state: { ...emptyState(), entries: [course('stable', '课程' + i)] } };
    rows.set(c.commitId, encode(c)); parent = c.commitId; ids.push(parent);
  }
  const remote = { key: 'test', list: async () => [...rows.keys()], read: async id => rows.get(id) ?? null, put: async (id, b) => { assert(!rows.has(id)); rows.set(id, b); } };
  const graph = await loadGraph(remote), db = await Workspace.open(store, { ...graph, bindingKey: remote.key });
  const catalog = { manifest: { revision: 0, activeId: 'workspace' }, store, grants: new LocalGrants([{ id: 'approved', path: dir }]), remote: () => remote, connection: () => ({}), row: () => ({ connectionId: 'connection' }) };
  const manager = new HistoryManager(catalog), engine = new SyncEngine(db, remote), input = () => ({ workspaceId: 'workspace', version: db.frame.revision, target: ids[0] });
  return { dir, rows, ids, db, remote, graph, manager, catalog, engine, input };
}
test('回退是新提交，引用当前前沿，原字节保留；可再次回退且相同状态不发布', async t => {
  const f = await fixture(t), originals = new Map(f.rows);
  const p = await f.manager.preview(f.db, f.engine, f.input(), 'admin');
  await f.manager.rollback(f.db, f.engine, { ...f.input(), token: p.token, confirm: p.target }, 'admin');
  assert.equal(f.rows.size, 3); const c = decode(f.rows.get(f.db.frame.receipt.commitId));
  assert.deepEqual(c.parents, [f.ids[1]]); assert.equal(c.state.entries[0].title, '课程0');
  for (const [id, bytes] of originals) assert(f.rows.get(id).equals(bytes));
  const p2 = await f.manager.preview(f.db, f.engine, f.input(), 'admin');
  const result = await f.manager.rollback(f.db, f.engine, { ...f.input(), token: p2.token, confirm: p2.target }, 'admin');
  assert(result.unchanged); assert.equal(f.rows.size, 3);
  const input = { ...f.input(), target: f.ids[1] }, p3 = await f.manager.preview(f.db, f.engine, input, 'admin');
  await f.manager.rollback(f.db, f.engine, { ...input, version: f.db.frame.revision, token: p3.token, confirm: p3.target }, 'admin');
  assert.equal(f.rows.size, 4); assert(same((await loadGraph(f.remote)).state, decode(originals.get(f.ids[1])).state));
});
test('token 绑定管理员、工作区、目标、确认、版本、目录历史和期限', async t => {
  for (const kind of ['user', 'workspace', 'target', 'confirm', 'edit', 'remote', 'switch', 'expiry', 'hash']) {
    const f = await fixture(t), p = await f.manager.preview(f.db, f.engine, f.input(), 'admin');
    const input = { ...f.input(), token:p.token, confirm:p.target }; let user = 'admin';
    if (kind === 'user') user = 'other';
    if (kind === 'workspace') input.workspaceId = 'other';
    if (kind === 'target') input.target = f.ids[1];
    if (kind === 'confirm') input.confirm = 'changed';
    if (kind === 'expiry') f.manager.tokens.get(p.token).expires = 0;
    if (kind === 'switch') f.catalog.manifest.revision++;
    if (kind === 'edit') await f.db.tx(frame => frame.local.entries.push(course('new')));
    if (kind === 'hash') f.rows.set(f.ids[0], encode({ ...decode(f.rows.get(f.ids[0])), writerId:randomUUID() }));
    if (kind === 'remote') { const c = { ...decode(f.rows.get(f.ids[1])), commitId:randomUUID(), parents:[f.ids[1]] }; f.rows.set(c.commitId, encode(c)); }
    await assert.rejects(f.manager.rollback(f.db, f.engine, input, user), undefined, kind); assert.equal(f.db.frame.pending, null);
  }
});
test('上传回执丢失保留精确候选，重启后按原同步恢复', async t => {
  const f = await fixture(t), p = await f.manager.preview(f.db, f.engine, f.input(), 'admin');
  f.remote.put = async (id, bytes) => { f.rows.set(id, bytes); throw Error('模拟回执丢失'); };
  await assert.rejects(f.manager.rollback(f.db, f.engine, { ...f.input(), token:p.token, confirm:p.target }, 'admin'), /回执丢失/);
  const pending = f.db.frame.pending; assert(pending.attempted); assert.equal(f.db.frame.local.entries[0].title, '课程0');
  const reopened = await Workspace.open(f.db.store); assert.equal(reopened.frame.pending.raw, pending.raw);
  await new SyncEngine(reopened, f.remote).synchronize(); assert.equal(reopened.frame.pending, null); assert.equal(f.rows.size, 3);
});
test('容量满仍可只读查看 256 个版本；等价多前沿回退引用全部父，分歧分支拒绝', async t => {
  const f = await fixture(t, 256); assert.equal(historySummary(f.db, await cachedGraph(f.db.frame)).count, 256);
  await assert.rejects(f.manager.preview(f.db, f.engine, f.input(), 'admin'), /容量/);
  const small = await fixture(t), c = { ...decode(small.rows.get(small.ids[1])), commitId:randomUUID(), parents:[small.ids[0]] };
  small.rows.set(c.commitId, encode(c));
  const p = await small.manager.preview(small.db, small.engine, small.input(), 'admin');
  await small.manager.rollback(small.db, small.engine, { ...small.input(), token:p.token, confirm:p.target }, 'admin');
  assert.deepEqual(decode(small.rows.get(small.db.frame.receipt.commitId)).parents, [small.ids[1],c.commitId].sort());
  const branch = await fixture(t), different = { ...decode(branch.rows.get(branch.ids[1])), commitId:randomUUID(), parents:[branch.ids[0]], state:emptyState() };
  branch.rows.set(different.commitId,encode(different));
  await assert.rejects(branch.manager.preview(branch.db, branch.engine, branch.input(), 'admin'), /分歧/);
});
test('稳定身份及全部业务集合差异，覆盖、调课和配置不折叠成名称', async () => {
  const a = emptyState(), b = emptyState(); a.entries = [course('a')]; b.entries = [course('b')];
  b.classOverrides = [{ key: overrideKey(null, 'b', '2026-10-22'), sourceId:null, seriesId:'b', anchorDate:'2026-10-22', date:'2026-10-23' }];
  b.dayAdjustments = [{ date:'2026-10-22', mode:'none', weekday:null, label:'停课' }];
  b.profiles = [{ termKey:'term', phases:[{ effectiveFrom:'2026-10-19', periods:[] }] }]; b.customProfileKeys = ['term'];
  b.events = [{ id:'event', title:'事件' }]; b.sources = [{ id:'source', displayName:'替换来源', termKey:'term' }]; b.imported = [{ id:'instance', sourceId:'source', seriesId:'series' }];
  const d = differences(a,b,0,2); assert.equal(d.total,9); assert.equal(d.rows.length,2); assert.equal(d.next,2);
  assert.equal(d.counts.entries.added,1); assert.equal(d.counts.entries.deleted,1);
  assert.equal(d.counts.classOverrides.added,1); assert.equal(d.counts.profiles.added,1);
  assert.equal(differences(a,b,2,80).rows.find(r => r.table === 'imported').group,'source:source');
  const root = {datasetId:randomUUID(),writerId:randomUUID(),commitId:randomUUID(),parents:[],state:{...emptyState(),entries:[course('retained')]}};
  const removed = {...root,commitId:randomUUID(),parents:[root.commitId],state:emptyState()}, restored = {...root,commitId:randomUUID(),parents:[removed.commitId]};
  const rows = new Map([root,removed,restored].map(c=>[c.commitId,encode(c)])), g = await loadGraph({list:async()=>[...rows.keys()],read:async id=>rows.get(id)});
  const recovered = versionDifferences(g,removed.commitId,restored.commitId); assert.equal(recovered.counts.entries.restored,1);assert.equal(recovered.counts.entries.added,0);assert.equal(recovered.rows[0].action,'restored');
});
test('完整归档与独立精简候选回读，源零变化，目录权限及有效标记', { skip:process.platform !== 'linux' }, async t => {
  const f = await fixture(t), before = new Map(f.rows), result = await f.manager.archive(f.db, f.engine, { ...f.input(), rootId:'approved', relative:'' }, true);
  assert(result.candidate); assert.equal(f.rows.size,2); for (const [id,b] of before) assert(f.rows.get(id).equals(b));
  const archive = JSON.parse(await readFile(join(f.dir,result.archive,'verified.json'))); assert.equal(archive.commits.length,2); assert(archive.complete);
  const config = { rootId:'approved', relative:result.candidate }, graph = await loadGraph(new LocalCommitStore(config,f.catalog.grants));
  assert.equal(Object.keys(graph.raw).length,1); assert.notEqual(graph.datasetId,f.graph.datasetId); assert(same(graph.state,f.db.frame.local));
  const marker = JSON.parse(await readFile(join(f.dir,result.candidate,'verified.json'))); assert.equal(marker.migrated,false);
  assert.equal(f.db.frame.datasetId,f.graph.datasetId);
});
test('归档权限撤销、协议目录、链接与源变化均安全失败', { skip:process.platform !== 'linux' }, async t => {
  const f = await fixture(t);
  await assert.rejects(f.manager.archive(f.db,f.engine,{...f.input(),rootId:'unknown'},false),/授权/);
  await assert.rejects(f.manager.archive(f.db,f.engine,{...f.input(),rootId:'approved',relative:'dormpanel-sync-v2'},false),/协议目录/);
  await mkdir(join(f.dir,'dormpanel-sync-v2'));
  f.catalog.grants.roots.push({id:'active-root',path:join(f.dir,'dormpanel-sync-v2')});
  await assert.rejects(f.manager.archive(f.db,f.engine,{...f.input(),rootId:'active-root'},false),/协议目录/);
  await mkdir(join(f.dir,'target')); await symlink(join(f.dir,'target'),join(f.dir,'linked'));
  await assert.rejects(f.manager.archive(f.db,f.engine,{...f.input(),rootId:'approved',relative:'linked'},false));
  const original = f.catalog.grants.writePrivate.bind(f.catalog.grants); let wrote = false;
  f.catalog.grants.writePrivate = async (...args) => { wrote = true; return original(...args); };
  f.catalog.remote = () => ({ ...f.remote, list:async () => [] ,read:async () => null });
  await assert.rejects(f.manager.archive(f.db,f.engine,{...f.input(),rootId:'approved'},true)); assert(!wrote);
  for (const name of (await readdir(f.dir)).filter(s => /^(history|compact)-/.test(s))) await assert.rejects(readFile(join(f.dir,name,'verified.json')));
});

test('未发布修改、待发布候选、冲突、恢复异常、离线及缺祖先不生成回退候选', async t => {
  for (const kind of ['dirty','pending','conflict','restore','offline','missing']) {
    const f=await fixture(t);
    if(kind==='dirty') await f.db.tx(frame=>frame.local.entries.push(course('dirty')));
    if(kind==='pending') await f.db.tx(frame=>f.db.stage(frame,frame.local,frame.frontier));
    if(kind==='conflict') await f.db.tx(frame=>{frame.conflict={};});
    if(kind==='restore') await f.db.tx(frame=>{frame.restoreGuard=true;});
    if(kind==='offline') f.remote.list=async()=>{throw Error('offline');};
    if(kind==='missing') f.rows.delete(f.ids[0]);
    const count=f.rows.size;
    await assert.rejects(f.manager.preview(f.db,f.engine,f.input(),'admin'),undefined,kind);
    assert.equal(f.rows.size,count);
  }
});

test('满 256 提交仍可完整归档和创建单根候选；中途权限撤销无有效标记', {skip:process.platform!=='linux'}, async t=>{
  const f=await fixture(t,256), result=await f.manager.archive(f.db,f.engine,{...f.input(),rootId:'approved'},true);
  assert.equal(result.count,256);assert.equal(f.rows.size,256);
  const archive=JSON.parse(await readFile(join(f.dir,result.archive,'verified.json')));assert.equal(archive.commits.length,256);
  const failed=await fixture(t), original=failed.catalog.grants.protectedFolder.bind(failed.catalog.grants);let created;
  failed.catalog.grants.protectedFolder=async(...args)=>{created=await original(...args);await chmod(join(failed.dir,created.relative),0o500);return created;};
  await assert.rejects(failed.manager.archive(failed.db,failed.engine,{...failed.input(),rootId:'approved'},false));
  await assert.rejects(readFile(join(failed.dir,created.relative,'verified.json')));await chmod(join(failed.dir,created.relative),0o700);
  assert.equal(failed.rows.size,2);
});

test('归档中断或实际回读损坏时不发布有效标记，旧字节完全不变', {skip:process.platform!=='linux'}, async t=>{
  for(const mode of ['interrupt','readback']) {
    const f=await fixture(t), before=new Map(f.rows), put=LocalCommitStore.prototype.put, read=LocalCommitStore.prototype.read;let calls=0;
    try {
      if(mode==='interrupt') LocalCommitStore.prototype.put=async function(...args){if(this.config.protected&&++calls===2) throw Error('模拟归档中断');return put.apply(this,args);};
      else LocalCommitStore.prototype.read=async function(...args){if(this.config.protected) return Buffer.alloc(250);return read.apply(this,args);};
      await assert.rejects(f.manager.archive(f.db,f.engine,{...f.input(),rootId:'approved'},false));
    } finally {LocalCommitStore.prototype.put=put;LocalCommitStore.prototype.read=read;}
    for(const name of (await readdir(f.dir)).filter(s=>s.startsWith('history-'))) await assert.rejects(readFile(join(f.dir,name,'verified.json')));
    for(const [id,b] of before) assert(f.rows.get(id).equals(b));
  }
});
