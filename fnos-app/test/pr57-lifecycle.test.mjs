import test from 'node:test';
import assert from 'node:assert/strict';
import {fixture} from './fixtures/v3-catalog.mjs';
import {V3EndpointMove} from '../src/v3-endpoint.mjs';
import {V3Maintenance} from '../src/v3-maintenance.mjs';
import {sqliteSnapshot,verifyV3Backup} from '../src/v3-backup.mjs';
import {Catalog} from '../src/catalog.mjs';
import {mkdtemp,mkdir} from 'node:fs/promises';
import {join} from 'node:path';
import {tmpdir} from 'node:os';
import {randomUUID} from 'node:crypto';
const candidate={type:'webdav',name:'lifecycle',url:'https://new.test/',username:'u',password:'candidate-secret'};
async function setup(t){const f=await fixture(t);await f.sync({create:true});await f.settle();return {...f,service:new V3EndpointMove(f.catalog,f.id)};}
async function ready(f){let p=await f.service.preview(candidate,'reader');for(let n=0;n<20&&p.status!=='READY';n++)p=await f.service.resume(p.token,'reader');assert.equal(p.status,'READY');return p;}
test('PR57 lifecycle: >64 cancelled graphs free slots, active history and shared witnesses preserved',async t=>{
  const f=await setup(t),before=f.db.snapshot(),meta=f.db.activeV3.journal.meta;
  const active=()=>meta.db.prepare("SELECT ns,k,sum FROM v3_records WHERE ns IN ('node','identity','frontier','workspace') ORDER BY seq").all();const rows=active();
  for(let n=0;n<70;n++){const p=await f.service.preview(candidate,'reader');await f.service.cancel(p.token,'reader');}
  assert(meta.count('endpointJob')<64);assert(meta.count('endpointRetired')<=32);
  assert.equal(meta.db.prepare("SELECT COUNT(*) n FROM v3_records WHERE ns LIKE 'endpoint:%'").get().n,0);
  const p=await ready(f),input={token:p.token,confirm:p.datasetId,version:p.version,catalogVersion:p.catalogVersion};
  const result=await f.service.confirm(input,'reader');await f.service.reap();
  assert.deepEqual(await f.service.confirm(input,'reader'),result);assert.equal(f.service.status(p.token,'reader').status,'COMMITTED');
  assert.deepEqual(f.db.snapshot(),before);assert.deepEqual(active(),rows);
  assert.doesNotThrow(()=>new V3Maintenance(f.catalog,f.id).snapshotPlan());
});
test('PR57 lifecycle: fixed expiry, protected approvals and owner quota; cleanup crash cannot revive READY',async t=>{
  const f=await setup(t),p=await ready(f),job=f.service.record(p.token,'reader');job.expiresAt=Date.now()-1;f.service.save(job);
  f.catalog.endpointApprovalState=()=>({approvals:[{operation:'endpoint.confirm',expires:Date.now()+60000,summary:{preview:{token:p.token}}}],receipts:[]});
  await f.service.reap();assert(f.service.meta.get('endpointJob',p.token));await assert.rejects(f.service.cancel(p.token,'reader'),/批准/);
  f.catalog.endpointApprovalState=()=>({approvals:[],receipts:[]});
  f.service.cleanupFault=point=>{if(point==='marked')throw Error('interrupted');};await assert.rejects(f.service.reap(),/interrupted/);
  assert.equal(f.service.meta.get('endpointJob',p.token).status,'CLEANING');assert.equal(f.service.status(p.token,'reader').status,'EXPIRED');
  f.service.cleanupFault=null;await f.service.reap();assert.equal(f.service.status(p.token,'reader').status,'EXPIRED');await assert.rejects(f.service.resume(p.token,'other'),/归属/);
});
test('PR57 lifecycle: partially cleaned graph survives backup/maintenance and Catalog reopen',async t=>{
  const f=await setup(t),p=await ready(f),before=f.db.snapshot(),prefix='endpoint:'+p.token+':';
  f.service.cleanupFault=point=>{if(point==='marked')throw Error('stop after decision');};await assert.rejects(f.service.cancel(p.token,'reader'));
  const identity=f.service.meta.page(prefix+'identity',0,1)[0];f.service.meta.atomic(()=>f.service.meta.remove(prefix+'identity',identity.key));
  assert.doesNotThrow(()=>new V3Maintenance(f.catalog,f.id).snapshotPlan());
  const root=await mkdtemp(join(tmpdir(),'pr57-gc-backup-')),base=`workspaces/${f.id}/v3/`;await mkdir(join(root,base),{recursive:true});
  await sqliteSnapshot(join(f.service.journal.directory,'v3-state-v2.sqlite'),join(root,base,'v3-state-v2.sqlite'));
  await verifyV3Backup(root,f.id,f.catalog.store.identity,[{name:base+'v3-state-v2.sqlite'}]);
  await f.catalog.close();const c=await Catalog.open({store:f.catalog.store,configPath:join(f.dir,'config.json'),v3RemoteFactory:()=>f.remote});t.after(()=>c.close());await c.load(f.id);
  const service=new V3EndpointMove(c,f.id);await service.reap();assert.equal(service.status(p.token,'reader').status,'CANCELLED');assert.deepEqual(c.db(f.id).snapshot(),before);
});
test('PR57 lifecycle: unknown graph namespace stops collection; live tasks are not evicted to satisfy quota',async t=>{
  const f=await setup(t),p=await ready(f),meta=f.service.meta;meta.atomic(()=>meta.put('endpoint:'+p.token+':future','private',{preserve:true}));
  await assert.rejects(f.service.cancel(p.token,'reader'),/未知/);assert(meta.get('endpoint:'+p.token+':future','private'));
  meta.atomic(()=>meta.remove('endpoint:'+p.token+':future','private'));await f.service.reap();
  const tokens=[];for(let n=0;n<8;n++)tokens.push((await f.service.preview(candidate,'reader')).token);
  await assert.rejects(f.service.preview(candidate,'reader'),/有效预检/);for(const token of tokens)assert(meta.get('endpointJob',token));
  await assert.rejects(f.service.cancel(tokens[0],'other'),/归属/);
});
test('PR57 lifecycle: durable uncertain Agent intent, sync and restore exclusions retain expired evidence',async t=>{
  const f=await setup(t),p=await ready(f),j=f.service.record(p.token,'reader');j.expiresAt=0;f.service.save(j);
  await f.catalog.store.save('agent-control.json',{approvals:[],receipts:[{operation:'endpoint.confirm',workspaceId:f.id,endpointToken:p.token,time:Date.now()}]});
  await f.service.reap();assert(f.service.meta.get('endpointJob',p.token));
  f.catalog.busy.add(f.id);await assert.rejects(f.service.reap(),/正在/);f.catalog.busy.delete(f.id);
  f.catalog.restoreGuards[f.id]={protocol:'v3'};await assert.rejects(f.service.reap(),/保护/);delete f.catalog.restoreGuards[f.id];
  await f.catalog.store.save('agent-control.json',{approvals:[],receipts:[]});await f.service.reap();assert.equal(f.service.status(p.token,'reader').status,'EXPIRED');
});
test('PR57 lifecycle: stored RAW references must stop cleanup before expansion',async t=>{
  const f=await setup(t),p=await ready(f),meta=f.service.meta,ns='endpoint:'+p.token+':control';
  meta.atomic(()=>meta.put(ns,'unexpected-raw',{nested:f.db.frame.local}));
  const snapshots=meta.count('snapshot');await assert.rejects(f.service.cancel(p.token,'reader'),/RAW/);
  assert(meta.get(ns,'unexpected-raw'));assert.equal(meta.count('snapshot'),snapshots);assert.equal(meta.get('endpointJob',p.token).status,'CLEANING');
});
test('PR57 lifecycle: latest remains owner-scoped beyond the receipt page and terminal window',async t=>{
  const f=await setup(t),meta=f.service.meta;let last;
  meta.atomic(()=>{for(let n=0;n<130;n++){
    const token=randomUUID(),result={token,workspaceId:f.id,datasetId:f.db.frame.datasetId,status:'COMMITTED',connectionId:f.catalog.row(f.id).connectionId,committedAt:n};
    f.catalog.manifest.endpointReceipts={...f.catalog.manifest.endpointReceipts,[token]:result};
    meta.put('endpointReceipt',token,{token,user:'reader',workspaceId:f.id,createdAt:n,result});last=token;
  }for(let n=0;n<32;n++){const token=randomUUID();meta.put('endpointRetired',token,{token,user:'other',workspaceId:f.id,status:'CANCELLED',createdAt:1000+n});}});
  assert.equal(f.service.latest('reader').token,last);assert.equal(f.service.latest('reader').status,'COMMITTED');assert.equal(f.service.latest('absent').status,'NOT_STARTED');
});
