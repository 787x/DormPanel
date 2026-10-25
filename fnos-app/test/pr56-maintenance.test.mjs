import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Store } from '../src/store.mjs';
import { Catalog } from '../src/catalog.mjs';
import { operations } from '../src/operations.mjs';
import { V3Maintenance } from '../src/v3-maintenance.mjs';
import { BackupManager } from '../src/backup.mjs';

export async function fixture(t) {
  const objects = new Map(); let puts = 0;
  const remote = { objects, get puts() { return puts; }, read: async p => objects.get(p) ?? null,
    putOnce: async (p,b) => { assert(!objects.has(p)); objects.set(p,Buffer.from(b)); puts++; },
    list: async s => [...new Set([...objects.keys()].filter(p=>p.startsWith(s)).map(p=> { const tail=p.slice(s.length), i=tail.indexOf('/'); return s+(i<0?tail:tail.slice(0,i+1)); }))].sort() };
  const dir = await mkdtemp(join(tmpdir(),'pr56-')), store = await new Store(dir).init();
  const catalog = await Catalog.open({store,configPath:join(dir,'config.json'),v3RemoteFactory:()=>remote});
  t.after(()=>catalog.close());
  const manage = input=>catalog.manage({catalogVersion:catalog.manifest.revision,...input});
  const {connectionId}=await manage({action:'connection.save',name:'isolated',type:'webdav',url:'https://isolated.test/',username:'test',password:'test'});
  const {workspaceId:id}=await manage({action:'workspace.add',name:'test',connectionId,protocol:'v3'});
  const db=catalog.db(id), sync=opts=>catalog.network(id,e=>e.synchronize(opts));
  const settle=async()=>{for(let i=0;i<5;i++)await sync();};
  return {dir,catalog,id,db,remote,sync,settle};
}
test('PR56 Descriptor 发现成功及失败：所有持久行、revision、错误与远端逐字不变',async t=>{
  const f=await fixture(t); await f.sync({create:true}); await f.settle();
  await f.db.tx(s=>{s.error='保留同步异常';});
  const meta=f.db.activeV3.journal.meta;
  const rows=()=>meta.db.prepare('SELECT * FROM v3_records ORDER BY seq').all();
  const before=rows(), frame=f.db.snapshot(), remote=[...f.remote.objects], puts=f.remote.puts;
  assert.equal((await f.catalog.discover(f.id)).candidates.length,1);
  assert.deepEqual(rows(),before); assert.deepEqual(f.db.snapshot(),frame); assert.equal(f.remote.puts,puts); assert.deepEqual([...f.remote.objects],remote);
  f.remote.list=async()=>{throw Error('offline');};
  await assert.rejects(f.catalog.discover(f.id),/offline/);
  assert.deepEqual(rows(),before); assert.deepEqual(f.db.snapshot(),frame);
  for(const name of ['sync.discover','migration.v3-discover'])assert.equal(operations.find(o=>o.name===name).permission,'read');
});
test('PR56 WAL 一致性快照：全部行及 Pending/capture 精确保留',async t=>{
  const f=await fixture(t);await f.sync({create:true});await f.settle();
  const {sqliteSnapshot,verifyV3Backup}=await import('../src/v3-backup.mjs');
  const {mkdir,readFile}=await import('node:fs/promises');
  const stage=await mkdtemp(join(tmpdir(),'pr56-snapshot-')), prefix=`workspaces/${f.id}/v3/`;
  await mkdir(join(stage,prefix),{recursive:true});
  const source=join(f.db.activeV3.journal.directory,'v3-state-v2.sqlite'), target=join(stage,prefix,'v3-state-v2.sqlite');
  await sqliteSnapshot(source,target);
  const files=[{name:prefix+'v3-state-v2.sqlite'}];
  const d=await verifyV3Backup(stage,f.id,f.catalog.store.identity,files);
  assert.equal(d.revision,f.db.frame.revision);assert.equal(d.datasetId,f.db.frame.datasetId);
  const {DatabaseSync}=await import('node:sqlite'), copy=new DatabaseSync(target,{readOnly:true});
  try{assert.deepEqual(copy.prepare('SELECT * FROM v3_records ORDER BY seq').all(),f.db.activeV3.journal.meta.db.prepare('SELECT * FROM v3_records ORDER BY seq').all());}finally{copy.close();}
});
test('PR56 清理保护所有持久引用，候选变化拒绝；原子回执及业务不变',async t=>{
  const f=await fixture(t);await f.sync({create:true});await f.settle();const m=new V3Maintenance(f.catalog,f.id),meta=m.meta;
  for(let i=0;i<3;i++)await f.db.edit({kind:'entry',action:'upsert',value:{id:'course',title:String(i),day:1,startMinute:480,endMinute:540,location:''}});
  const frame=f.db.snapshot(), before=m.meta.count('snapshot'), p=await m.cleanupPreview({},'admin');assert(p.candidates.length>0);
  await f.db.edit({kind:'entry',action:'upsert',value:{id:'course',title:'new edit',day:1,startMinute:480,endMinute:540,location:''}});
  await assert.rejects(m.cleanup({...p,confirm:'整理本地冗余快照'},'admin'),/变化/);
  const good=await m.cleanupPreview({},'admin'), current=f.db.snapshot(), r=await m.cleanup({...good,confirm:'整理本地冗余快照'},'admin');
  assert(r.rowsRemoved>0);assert.deepEqual(f.db.snapshot(),current);assert.deepEqual(await m.cleanup({...good,confirm:'整理本地冗余快照'},'admin'),r);
  assert.equal(meta.count('snapshot'),before+1-r.rowsRemoved);assert.equal(f.db.frame.local.entries[0].title,'new edit');
});
(process.platform==='linux'?test:test.skip)('PR56 独立归档分批实际回读、重入及缓存恢复，不写远端',async t=>{
  const f=await fixture(t);await f.sync({create:true});await f.settle();const m=new V3Maintenance(f.catalog,f.id);
  const exports=await mkdtemp(join(tmpdir(),'pr56-archive-'));f.catalog.grants.roots.push({id:'exports',name:'isolated',path:exports});
  const puts=f.remote.puts;let job=await m.archive({rootId:'exports',relative:''},'admin');while(job.status!=='VERIFIED')job=await m.archive({jobId:job.id},'admin');
  assert.equal((await m.archive({jobId:job.id},'admin')).manifestHash,job.manifestHash);
  const {unlink}=await import('node:fs/promises'),{IndexedV3Cache}=await import('../src/v3-index.mjs'),{Budget}=await import('../src/v3-store.mjs');
  const cache=new IndexedV3Cache(m.journal.directory,new Budget()),path=m.list().rows.find(r=>r.type===2).path, bytes=await cache.get(path);await unlink(cache.blob(path));
  const restored=await m.restoreArchive(job.location,'admin');assert(restored.restoredObjects>0);assert.deepEqual(await cache.get(path),bytes);assert.equal(f.remote.puts,puts);
});
(process.platform==='linux'?test:test.skip)('PR56 混合 v2/v3 加密备份实际创建、验证、恢复及旧状态拒绝',async t=>{
  const f=await fixture(t);await f.sync({create:true});await f.settle();
  const exports=await mkdtemp(join(tmpdir(),'pr56-exports-'));f.catalog.grants.roots.push({id:'exports',name:'isolated',path:exports});
  const configPath=join(f.dir,'config.json');let restored;
  const manager=new BackupManager({catalog:f.catalog,configPath,reopen:async()=>{
    restored=await Catalog.open({store:f.catalog.store,configPath,v3RemoteFactory:()=>f.remote,authorizedRoots:[{id:'exports',name:'isolated',path:exports}]});manager.catalog=restored;
  }});t.after(async()=>{await manager.close();if(restored)await restored.close();});
  const input={rootId:'exports',relative:'',password:'synthetic isolated backup passphrase'};
  const created=await manager.create(input);assert.equal(created.formatVersion,2);assert(created.verified);
  const verified=await manager.verify({...input,name:created.name},'admin');assert(verified.verified);
  const before=f.db.snapshot();await manager.restore({token:verified.token,confirm:'恢复应用数据'},'admin');
  assert.deepEqual((await restored.load(f.id)).snapshot(),before);assert(restored.restoreGuards[f.id]);
});
test('PR56 分页无累计 100 限制；稳定 ID 差异、可恢复新因果回退及过期预览',async t=>{
  const f=await fixture(t);await f.sync({create:true});await f.settle();
  const m=new V3Maintenance(f.catalog,f.id), first=m.list().rows.find(r=>r.type===2);
  for(let i=0;i<105;i++) { await f.db.edit({kind:'entry',action:'upsert',value:{id:'stable',title:'same title',day:1,startMinute:480,endMinute:540,location:String(i)}}); await f.sync(); }
  await f.settle();
  const seen=[]; let after=0; do {const page=m.list({after,limit:30});seen.push(...page.rows);after=page.next;}while(after!==null);
  assert.equal(seen.length,m.meta.count('node'));assert(seen.length>100);assert.equal(new Set(seen.map(r=>r.path)).size,seen.length);
  const diff=await m.read('diff',{from:first.id,to:'current'},'admin');assert.equal(diff.rows[0].id,'stable');assert.equal(diff.rows[0].action,'added');
  const preview=await m.preview({target:first.id},'admin'), frontier=m.list().frontier, count=m.meta.count('node');
  const result=await m.rollback({...preview,confirm:first.id},'admin');await f.settle();
  assert.equal(f.db.frame.local.entries.length,0);assert(m.meta.count('node')>count);
  const tip=m.meta.get('node',m.list().frontier[0]);assert(tip.refs.some(r=>frontier.some(p=>p.includes(r.objectId))));
  const puts=f.remote.puts;await m.rollback({...preview,confirm:first.id},'admin');assert.equal(f.remote.puts,puts);assert.equal(result.status,'LOCAL_SAVED');
  const stale=await m.preview({target:first.id},'admin');await f.db.edit({kind:'entry',action:'upsert',value:{id:'new',title:'new',day:1,startMinute:600,endMinute:650,location:''}});
  await assert.rejects(m.rollback({...stale,confirm:first.id},'admin'),/过期|变化/);
  assert.equal(m.eligibility().status,'NOT_ELIGIBLE');
});
