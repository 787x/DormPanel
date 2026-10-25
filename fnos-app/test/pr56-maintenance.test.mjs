import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Store } from '../src/store.mjs';
import { Catalog } from '../src/catalog.mjs';
import { operations } from '../src/operations.mjs';
import { V3Maintenance } from '../src/v3-maintenance.mjs';
import { BackupManager, recoverRestore } from '../src/backup.mjs';

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
  await manage({action:'workspace.switch',targetId:id});
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
for(const point of ['restore-precommit','restore-committed','restore-file','restore-complete']) (process.platform==='linux'?test:test.skip)(`PR56 v3 UNCERTAIN/capture 备份恢复中断 ${point} 后重入同一决策`,async t=>{
  const f=await fixture(t);await f.sync({create:true});await f.settle();
  const put=f.remote.putOnce;let fail=true;
  f.remote.putOnce=async(p,b)=>{await put(p,b);if(fail)throw Error('lost-response');};
  await f.db.edit({kind:'entry',action:'upsert',value:{id:'saved',title:'capture',day:1,startMinute:480,endMinute:540,location:''}});
  await assert.rejects(f.sync());fail=false;
  const pending=f.db.activeV3.journal.value.pending;assert(['ATTEMPTED','UNCERTAIN'].includes(pending.phase));
  await f.db.edit({kind:'entry',action:'upsert',value:{id:'saved',title:'after capture',day:1,startMinute:480,endMinute:540,location:''}});
  const exports=await mkdtemp(join(tmpdir(),'pr56-fault-'));f.catalog.grants.roots.push({id:'exports',name:'isolated',path:exports});
  const configPath=join(f.dir,'config.json'),manager=new BackupManager({catalog:f.catalog,configPath,reopen:async()=>{throw Error('fault must interrupt first');}});t.after(()=>manager.close());
  const input={rootId:'exports',relative:'',password:'synthetic isolated fault passphrase'},created=await manager.create(input),verified=await manager.verify({...input,name:created.name},'admin'),before=f.db.snapshot(),puts=f.remote.puts;
  manager.fault=async p=>{if(p===point)throw Error('injected-'+point);};
  await assert.rejects(manager.restore({token:verified.token,confirm:'恢复应用数据'},'admin'),/injected/);
  await f.catalog.close();await recoverRestore(f.dir,configPath);
  const reopened=await Catalog.open({store:f.catalog.store,configPath,v3RemoteFactory:()=>f.remote});t.after(()=>reopened.close());await reopened.load(f.id);
  assert.deepEqual(reopened.db(f.id).snapshot(),before);assert.equal(f.remote.puts,puts);
  assert.equal(reopened.db(f.id).activeV3.journal.value.pending.bytes,pending.bytes);
  if(point!=='restore-precommit')assert(reopened.restoreGuards[f.id]);
  for(let i=0;i<5;i++)await reopened.network(f.id,e=>e.synchronize());
  assert.equal(reopened.db(f.id).frame.local.entries[0].title,'after capture');assert(f.remote.objects.has(pending.path));assert.equal(f.remote.puts,puts+1);
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
test('PR56 audit: 预算中断后重开从持久游标继续，业务和远端对象不变',async t=>{
  const f=await fixture(t);await f.sync({create:true});await f.settle();let m=new V3Maintenance(f.catalog,f.id);
  const before=f.db.snapshot(),puts=f.remote.puts,listing=f.remote.list;let calls=0;
  f.remote.list=async p=>{if(++calls===2)throw Error('v3 budget exhausted');return listing(p);};
  const paused=await m.audit();assert.equal(paused.status,'BUDGET');const cursor=m.meta.get('control','scopeAudit');assert(cursor);assert.deepEqual(f.db.snapshot(),before);
  f.remote.list=listing;await f.catalog.close();const reopened=await Catalog.open({store:f.catalog.store,configPath:join(f.dir,'config.json'),v3RemoteFactory:()=>f.remote});t.after(()=>reopened.close());await reopened.load(f.id);
  m=new V3Maintenance(reopened,f.id);m.local();assert.deepEqual(m.meta.get('control','scopeAudit'),cursor);
  const result=await m.audit();assert.equal(result.status,'BOUNDED');assert.equal(result.oldHistoryAuditComplete,false);assert.deepEqual(reopened.db(f.id).snapshot(),before);assert.equal(f.remote.puts,puts);
});
test('PR56 rollback: 审计观察到新前沿但业务未接收时拒绝预览，不能漏掉远端修改',async t=>{
  const f=await fixture(t);await f.sync({create:true});await f.settle();const m=new V3Maintenance(f.catalog,f.id),root=m.list().rows.find(r=>r.type===2);
  const {decode,encode}=await import('../src/v3-protocol.mjs'),{objectPath}=await import('../src/v3-store.mjs'),{reference}=await import('../src/v3-history.mjs'),{randomUUID}=await import('node:crypto');
  const object=decode(f.remote.objects.get(root.path));const state=structuredClone(object.state);state.entries.push({id:'remote-stable',title:'remote',day:1,startMinute:480,endMinute:540,location:''});
  const commit={...object,type:3,objectId:randomUUID(),writerId:randomUUID(),refs:[reference(object,root.hash,1)],state};f.remote.objects.set(objectPath(commit),encode(commit));
  const before=f.db.frame.local,puts=f.remote.puts;await assert.rejects(m.preview({target:root.id},'admin'),/前沿/);assert.deepEqual(f.db.frame.local,before);assert.equal(f.remote.puts,puts);
  await f.settle();const p=await m.preview({target:root.id},'admin');assert.equal(p.diff.rows[0].id,'remote-stable');assert.equal(p.diff.rows[0].action,'deleted');
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
  assert(r.reclaimedFileBytes>0);
  assert.equal((await m.space()).reclaimedBytes,r.reclaimedFileBytes);
  const pinPlan=await m.cleanupPreview({},'admin'),{IndexedV3Cache}=await import('../src/v3-index.mjs'),{Budget}=await import('../src/v3-store.mjs');
  const cache=new IndexedV3Cache(m.journal.directory,new Budget());await cache.pin(m.list().rows[0].path,'pr56-new-pin');
  await assert.rejects(m.cleanup({...pinPlan,confirm:'整理本地冗余快照'},'admin'),/变化/);
  meta.atomic(()=>meta.put('futureUnknown','protected',{version:99}));
  await assert.rejects(m.cleanupPreview({},'admin'),/未知/);
});
test('PR56 partial-index: 身份索引缺失时保护全部快照，不生成删除决策',async t=>{
  const f=await fixture(t);await f.sync({create:true});await f.settle();const m=new V3Maintenance(f.catalog,f.id),node=m.list().rows[0],count=m.meta.count('snapshot');
  m.meta.atomic(()=>m.meta.remove('identity',node.objectId));
  assert.equal(m.list().rows.find(r=>r.id===node.id).status,'INDEX_INCOMPLETE');
  await assert.rejects(m.cleanupPreview({},'admin'),/索引缺失/);assert.equal(m.meta.count('snapshot'),count);
});
test('PR56 八类 RAW 历史详情与差异保留完整稳定身份和 null/空字符串',async t=>{
  const f=await fixture(t),{readFile}=await import('node:fs/promises'),{decode}=await import('../src/v3-protocol.mjs');
  const raw=await readFile(new URL('../../schedule-core/src/test/resources/sync-v3/commit-complex.dpv',import.meta.url)),state=decode(raw).state;
  await f.db.activeV3.engine.edit(state);await f.sync({create:true});await f.settle();
  const m=new V3Maintenance(f.catalog,f.id),version=m.list().rows.find(r=>r.type===2);
  assert.deepEqual(await m.snapshot(version.id),state);
  for(const table of Object.keys(state)) {
    const page=await m.read('snapshot',{id:version.id,table},'admin');assert.equal(page.total,state[table].length);assert.deepEqual(page.rows,state[table].slice(0,40));
  }
  const diff=await m.read('diff',{from:version.id,to:'current'},'admin');assert.equal(diff.total,0);
});
test('PR56 清理事务中断：提交前不删除，提交后重开返回同一决策并继续压缩',async t=>{
  const f=await fixture(t);await f.sync({create:true});await f.settle();let m=new V3Maintenance(f.catalog,f.id);
  for(let i=0;i<3;i++)await f.db.edit({kind:'entry',action:'upsert',value:{id:'stable',title:String(i),day:1,startMinute:480,endMinute:540,location:''}});
  const p=await m.cleanupPreview({},'admin'),before=f.db.snapshot(),count=m.meta.count('snapshot');
  m.meta.fault=point=>{if(point==='before-commit')throw Error('injected-before');};
  await assert.rejects(m.cleanup({...p,confirm:'整理本地冗余快照'},'admin'),/injected-before/);
  m.meta.fault=null;assert.equal(m.meta.count('snapshot'),count);assert.deepEqual(f.db.snapshot(),before);
  m.meta.fault=point=>{if(point==='after-commit')throw Error('injected-after');};
  await assert.rejects(m.cleanup({...p,confirm:'整理本地冗余快照'},'admin'),/injected-after/);
  await f.catalog.close();const reopened=await Catalog.open({store:f.catalog.store,configPath:join(f.dir,'config.json'),v3RemoteFactory:()=>f.remote});t.after(()=>reopened.close());
  await reopened.load(f.id);m=new V3Maintenance(reopened,f.id);assert.deepEqual(m.db.snapshot(),before);
  const result=await m.cleanup({...p,confirm:'整理本地冗余快照'},'admin');assert.equal(result.status,'COMPLETED');assert.equal(result.rowsRemoved,p.candidates.length);
});
(process.platform==='linux'?test:test.skip)('PR56 archive: 独立归档分批实际回读、重入及缓存恢复，不写远端',async t=>{
  const f=await fixture(t);await f.sync({create:true});await f.settle();const m=new V3Maintenance(f.catalog,f.id);
  const exports=await mkdtemp(join(tmpdir(),'pr56-archive-'));f.catalog.grants.roots.push({id:'exports',name:'isolated',path:exports});
  const puts=f.remote.puts;let job=await m.archive({rootId:'exports',relative:''},'admin');assert.equal(job.formatVersion,2);assert.equal(job.status,'COPYING');assert(job.after>0);while(job.status!=='VERIFIED')job=await m.archive({jobId:job.id},'admin');
  assert.equal((await m.archive({jobId:job.id},'admin')).manifestHash,job.manifestHash);
  const {unlink}=await import('node:fs/promises'),{IndexedV3Cache}=await import('../src/v3-index.mjs'),{Budget}=await import('../src/v3-store.mjs');
  const cache=new IndexedV3Cache(m.journal.directory,new Budget()),path=m.list().rows.find(r=>r.type===2).path, bytes=await cache.get(path);await unlink(cache.blob(path));
  let restored=await m.restoreArchive(job.location,'admin');assert(restored.restoredObjects>0);while(!restored.complete)restored=await m.restoreArchive(job.location,'admin');assert(restored.knownReferenceClosureVerified);assert.deepEqual(await cache.get(path),bytes);assert.equal(f.remote.puts,puts);
  const {readFile,writeFile}=await import('node:fs/promises'),manifestPath=join(exports,job.location.relative,'verified.json'),manifest=JSON.parse(await readFile(manifestPath,'utf8'));manifest.frontier=[];
  await writeFile(manifestPath,JSON.stringify(manifest));
  await assert.rejects(async()=>{for(let i=0;i<8;i++)await m.restoreArchive(job.location,'admin');},/前沿/);
});
test('PR56 archive: 只有绑定而未索引 Descriptor 时拒绝生成空成功归档',async t=>{
  const f=await fixture(t),{readFile}=await import('node:fs/promises'),{decode,sha}=await import('../src/v3-protocol.mjs');
  const bytes=await readFile(new URL('../../schedule-core/src/test/resources/sync-v3/descriptor.dpv',import.meta.url));
  await f.db.activeV3.engine.bind(decode(bytes),sha(bytes));const m=new V3Maintenance(f.catalog,f.id);
  await assert.rejects(m.archive({rootId:'ungranted'},'admin'),/Descriptor/);assert.equal(m.meta.count('archiveJob'),0);
});
(process.platform==='linux'?test:test.skip)('PR56 混合 v2/v3 加密备份实际创建、验证、恢复及旧状态拒绝',async t=>{
  const f=await fixture(t);await f.sync({create:true});await f.settle();
  const exports=await mkdtemp(join(tmpdir(),'pr56-exports-'));f.catalog.grants.roots.push({id:'exports',name:'isolated',path:exports});
  const configPath=join(f.dir,'config.json');let restored;
  const manager=new BackupManager({catalog:f.catalog,configPath,reopen:async()=>{
    restored=await Catalog.open({store:f.catalog.store,configPath,v3RemoteFactory:()=>f.remote,authorizedRoots:[{id:'exports',name:'isolated',path:exports}]});manager.catalog=restored;
  }});t.after(async()=>{await manager.close();if(restored)await restored.close();});
  const input={rootId:'exports',relative:'',password:'synthetic isolated backup passphrase'};
  let created=await manager.create(input);assert.equal(created.formatVersion,2);assert(created.verified);
  let verified=await manager.verify({...input,name:created.name},'admin');assert(verified.verified);
  const {IndexedV3Cache}=await import('../src/v3-index.mjs'),{Budget}=await import('../src/v3-store.mjs');
  await new IndexedV3Cache(f.db.activeV3.journal.directory,new Budget()).pin(f.db.activeV3.history().rows[0].path,'new-backup-pin');
  await assert.rejects(manager.restore({token:verified.token,confirm:'恢复应用数据'},'admin'),/变化/);
  await manager.cancel({token:verified.token},'admin');created=await manager.create(input);verified=await manager.verify({...input,name:created.name},'admin');
  const before=f.db.snapshot();await manager.restore({token:verified.token,confirm:'恢复应用数据'},'admin');
  assert.deepEqual((await restored.load(f.id)).snapshot(),before);assert(restored.restoreGuards[f.id]);
  const puts=f.remote.puts;await restored.network(f.id,e=>e.synchronize());assert(!restored.restoreGuards[f.id]);assert.equal(f.remote.puts,puts);
  const withRecovery=await manager.create(input);assert(withRecovery.verified);assert(withRecovery.fileCount>created.fileCount);
  const active=await restored.load(f.id);
  await active.edit({kind:'entry',action:'upsert',value:{id:'later',title:'later',day:1,startMinute:600,endMinute:650,location:''}});
  const stale=await manager.verify({...input,name:created.name},'admin');
  await assert.rejects(manager.restore({token:stale.token,confirm:'恢复应用数据'},'admin'),/新数据|恢复证据/);
  assert.equal(active.frame.local.entries[0].id,'later');
});
(process.platform==='linux'?test:test.skip)('PR56 私有本地 v3 的完整深层 Epoch 路径可备份恢复，预览字段完整',async t=>{
  const f=await fixture(t);f.catalog.v3RemoteFactory=undefined;
  const manage=input=>f.catalog.manage({catalogVersion:f.catalog.manifest.revision,...input});
  const {connectionId}=await manage({action:'connection.save',name:'private v3',type:'local',rootId:'private',createPrivate:true});
  const {workspaceId}=await manage({action:'workspace.add',name:'native v3',connectionId,protocol:'v3'});await manage({action:'workspace.switch',targetId:workspaceId});
  await f.catalog.network(workspaceId,e=>e.synchronize({create:true}));for(let i=0;i<4;i++)await f.catalog.network(workspaceId,e=>e.synchronize());
  const exports=await mkdtemp(join(tmpdir(),'pr56-native-'));f.catalog.grants.roots.push({id:'exports',name:'isolated',path:exports});
  const configPath=join(f.dir,'config.json');let reopened;
  const manager=new BackupManager({catalog:f.catalog,configPath,reopen:async()=>{reopened=await Catalog.open({store:f.catalog.store,configPath});manager.catalog=reopened;}});
  t.after(async()=>{await manager.close();if(reopened)await reopened.close();});
  const input={rootId:'exports',relative:'',password:'synthetic native backup passphrase'},created=await manager.create(input);
  const d=created.dependencies.find(d=>d.workspaceId===workspaceId);assert.equal(d.workspaceName,'native v3');assert.equal(d.type,'local');assert.equal(d.included,true);assert.equal(d.courseCount,0);
  const verified=await manager.verify({...input,name:created.name},'admin');const before=f.catalog.db(workspaceId).snapshot();
  await manager.restore({token:verified.token,confirm:'恢复应用数据'},'admin');assert.deepEqual((await reopened.load(workspaceId)).snapshot(),before);
});
test('PR56 分页无累计 100 限制；稳定 ID 差异、可恢复新因果回退及过期预览',async t=>{
  const f=await fixture(t);await f.sync({create:true});await f.settle();
  const m=new V3Maintenance(f.catalog,f.id), first=m.list().rows.find(r=>r.type===2);
  for(let i=0;i<105;i++) { await f.db.edit({kind:'entry',action:'upsert',value:{id:'stable',title:'same title',day:1,startMinute:480,endMinute:540,location:String(i)}}); await f.sync(); }
  await f.settle();
  const ctx=m.context();await ctx.history.open();const tips=ctx.history.tips();
  await f.db.activeV3.engine.rotate(ctx.history,ctx.remote,ctx.history.node(tips[0]).epochId,tips);await f.settle();
  assert.equal(m.meta.count('epoch'),2);
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
