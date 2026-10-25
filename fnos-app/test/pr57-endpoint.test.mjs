import test from 'node:test';
import assert from 'node:assert/strict';
import { fixture } from './fixtures/v3-catalog.mjs';
import { V3EndpointMove } from '../src/v3-endpoint.mjs';
import { Catalog } from '../src/catalog.mjs';
import { join } from 'node:path';
import { decode,encode } from '../src/v3-protocol.mjs';
import { objectPath } from '../src/v3-store.mjs';
import { reference } from '../src/v3-history.mjs';
import { randomUUID } from 'node:crypto';
import https from 'node:https';
import {execFileSync} from 'node:child_process';
import {readFile,mkdtemp} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {V3Publisher} from '../src/v3-transaction.mjs';

async function ready(f,user='admin') {
  const service=new V3EndpointMove(f.catalog,f.id);
  let p=await service.preview({type:'webdav',name:'new access',url:'https://new.test/',username:'new',password:'secret'},user);
  for(let n=0;p.status!=='READY' && n<30;n++)p=await service.resume(p.token,user);
  assert.equal(p.status,'READY');return {service,p,user};
}
test('PR57 read-only candidate, atomic manifest switch, original Writer/RAW/error and idempotence',async t=>{
  const f=await fixture(t);await f.sync({create:true});await f.settle();
  await f.db.edit({kind:'entry',action:'upsert',value:{id:'offline',title:'saved',day:1,startMinute:480,endMinute:540,location:''}});
  await f.db.tx(s=>{s.error='preserve evidence';});
  const before=f.db.snapshot(), manifest=structuredClone(f.catalog.manifest),puts=f.remote.puts;
  const {service,p}=await ready(f);
  assert.equal(p.candidate.url,'https://new.test/');assert.equal(p.candidate.username,'new');assert.equal(p.candidate.trust,'SYSTEM_CA');
  assert.deepEqual(f.db.snapshot(),before);assert.deepEqual(f.catalog.manifest,manifest);assert.equal(f.remote.puts,puts);
  assert(!JSON.stringify(p).includes('secret'));
  const input={token:p.token,confirm:p.datasetId,catalogVersion:p.catalogVersion,version:p.version};
  const result=await service.confirm(input,'admin');assert.equal(result.status,'COMMITTED');
  assert.deepEqual(await service.confirm(input,'admin'),result);assert.deepEqual(f.db.snapshot(),before);
  assert.notEqual(f.catalog.row(f.id).connectionId,manifest.workspaces.find(w=>w.id===f.id).connectionId);
  assert.deepEqual(f.catalog.connection(manifest.workspaces.find(w=>w.id===f.id).connectionId),manifest.connections[0]);
  assert.equal(f.remote.puts,puts);await f.settle();assert.equal(f.db.frame.local.entries[0].id,'offline');
});
test('PR57 rejects changed/missing known object and different Descriptor without altering active state',async t=>{
  for(const fault of ['missing','changed','descriptor']) {
    const f=await fixture(t);await f.sync({create:true});await f.settle();const before=f.db.snapshot();
    const service=new V3EndpointMove(f.catalog,f.id),read=f.remote.read;
    const binding=f.db.activeV3.journal.value.binding;
    f.remote.read=async p=>p===(fault==='descriptor'?binding.descriptorPath:f.db.activeV3.journal.value.baselinePath)?fault==='changed'||fault==='descriptor'?Buffer.from('wrong'):null:read(p);
    await assert.rejects(async()=>{let p=await service.preview({type:'webdav',name:'bad',url:'https://bad.test/',username:'u',password:'p'},'admin');for(let n=0;n<30 && p.status!=='READY';n++)p=await service.resume(p.token,'admin');},/身份|缺失|变化/);
    assert.deepEqual(f.db.snapshot(),before);
  }
});
test('PR57 stale local edit, owner mismatch, cancel and attempted PUT retain all evidence',async t=>{
  const f=await fixture(t);await f.sync({create:true});await f.settle();const {service,p}=await ready(f);
  await assert.rejects(service.resume(p.token,'other'),/归属/);
  await f.db.edit({kind:'entry',action:'upsert',value:{id:'edit',title:'after preview',day:1,startMinute:480,endMinute:540,location:''}});
  await assert.rejects(service.confirm({token:p.token,confirm:p.datasetId,catalogVersion:p.catalogVersion,version:p.version},'admin'),/过期/);
  assert.equal((await service.cancel(p.token,'admin')).status,'CANCELLED');
  const put=f.remote.putOnce;f.remote.putOnce=async(p,b)=>{await put(p,b);throw Error('lost response');};
  await assert.rejects(f.sync());const pending=structuredClone(f.db.activeV3.journal.value.pending),before=f.db.snapshot();
  await assert.rejects(ready(f),/原端点|PUT/);assert.deepEqual(f.db.snapshot(),before);assert.deepEqual(f.db.activeV3.journal.value.pending,pending);
});
test('PR57 STAGED candidate/capture retained, edits after capture replay through original receipt',async t=>{
  const f=await fixture(t);await f.sync({create:true});await f.settle();
  await f.db.edit({kind:'entry',action:'upsert',value:{id:'capture',title:'staged',day:1,startMinute:480,endMinute:540,location:''}});
  const journal=f.db.activeV3.journal,s=journal.value,base=decode(f.remote.objects.get(s.baselinePath));
  const commit={...base,type:3,objectId:randomUUID(),writerId:s.writerId,refs:[reference(base,s.baselineHash,1)],state:s.local};
  await new V3Publisher(journal,f.remote).stage(objectPath(commit),encode(commit),{kind:'commit',revision:s.revision,local:s.local});
  await f.db.edit({kind:'entry',action:'upsert',value:{id:'capture',title:'after capture',day:1,startMinute:480,endMinute:540,location:''}});
  const before=f.db.snapshot(),pending=structuredClone(journal.value.pending),puts=f.remote.puts,{service,p}=await ready(f);
  await service.confirm({token:p.token,confirm:p.datasetId,version:p.version,catalogVersion:p.catalogVersion},'admin');
  assert.deepEqual(f.db.snapshot(),before);assert.deepEqual(journal.value.pending,pending);assert.equal(f.remote.puts,puts);
  await f.settle();assert.equal(f.db.frame.local.entries[0].title,'after capture');assert(f.remote.objects.has(objectPath(commit)));
});
test('PR57 extra concurrent branch verified separately, normal sync conflicts after move',async t=>{
  const f=await fixture(t);await f.sync({create:true});await f.settle();
  await f.db.edit({kind:'entry',action:'upsert',value:{id:'shared',title:'base',day:1,startMinute:480,endMinute:540,location:''}});await f.settle();
  const source=f.db.activeV3.journal.value,base=decode(f.remote.objects.get(source.baselinePath));
  await f.db.edit({kind:'entry',action:'upsert',value:{...base.state.entries[0],title:'local'}});
  const copy=new Map(f.remote.objects),extra={...base,objectId:randomUUID(),writerId:randomUUID(),refs:[reference(base,source.baselineHash,1)],state:{...base.state,entries:[{...base.state.entries[0],title:'remote'}]}};
  copy.set(objectPath(extra),encode(extra));
  const originalFactory=f.catalog.v3RemoteFactory;
  const candidate={read:async p=>copy.get(p)??null,list:async s=>[...new Set([...copy.keys()].filter(p=>p.startsWith(s)).map(p=>{const tail=p.slice(s.length),i=tail.indexOf('/');return s+(i<0?tail:tail.slice(0,i+1));}))].sort(),putOnce:async()=>{throw Error('must conflict before PUT');}};
  f.catalog.v3RemoteFactory=(c,b)=>c.config.url==='https://new.test/'?candidate:originalFactory(c,b);
  const {service,p}=await ready(f);assert(p.facts.extraObjects>0);assert(!f.db.activeV3.journal.meta.get('node',objectPath(extra)));
  await service.confirm({token:p.token,confirm:p.datasetId,catalogVersion:p.catalogVersion,version:p.version},'admin');
  let conflict=false;for(let n=0;n<8;n++)conflict=(await f.sync()).status==='CONFLICT'||conflict;
  assert(conflict);assert.equal(f.db.frame.local.entries[0].title,'local');assert(f.db.activeV3.journal.value.conflict);
});
test('PR57 all eight RAW collections, original global connection users and expired candidate',async t=>{
  const f=await fixture(t);const complex=decode(await readFile(new URL('../../schedule-core/src/test/resources/sync-v3/commit-complex.dpv',import.meta.url))).state;
  await f.db.activeV3.engine.edit(complex);await f.sync({create:true});await f.settle();
  const shared=structuredClone(f.catalog.manifest),defaultRow=shared.workspaces.find(w=>w.id!==f.id);defaultRow.connectionId=f.catalog.row(f.id).connectionId;await f.catalog.commit(shared);
  const before=f.db.snapshot(),{service,p}=await ready(f);
  const s=f.db.activeV3.journal.value,base=decode(f.remote.objects.get(s.baselinePath)),extra={...base,type:3,objectId:randomUUID(),writerId:randomUUID(),refs:[reference(base,s.baselineHash,1)]};
  f.remote.objects.set(objectPath(extra),encode(extra));
  const input={token:p.token,confirm:p.datasetId,catalogVersion:p.catalogVersion,version:p.version};
  await assert.rejects(service.confirm(input,'admin'),/过期/);await assert.rejects(service.confirm(input,'admin'),/过期/);
  const good=await ready(f);await good.service.confirm({token:good.p.token,confirm:good.p.datasetId,catalogVersion:good.p.catalogVersion,version:good.p.version},'admin');
  assert.equal(f.catalog.row(defaultRow.id).connectionId,defaultRow.connectionId);assert.deepEqual(f.db.snapshot(),before);assert.deepEqual(f.db.frame.local,complex);
});
for(const afterSave of [false,true])test(`PR57 Catalog interruption ${afterSave?'after':'before'} durable decision, reopen repeats same result`,async t=>{
  const f=await fixture(t);await f.sync({create:true});await f.settle();const {service,p}=await ready(f),before=f.db.snapshot(),original=f.catalog.row(f.id).connectionId;
  const input={token:p.token,confirm:p.datasetId,catalogVersion:p.catalogVersion,version:p.version};
  const save=f.catalog.store.save.bind(f.catalog.store);f.catalog.store.save=async(name,value)=>{if(name==='catalog.json'){if(afterSave)await save(name,value);throw Error('power interruption');}return save(name,value);};
  await assert.rejects(service.confirm(input,'admin'),/interruption/);assert(f.catalog.invalidated);f.catalog.store.save=save;
  await assert.rejects(f.sync());await f.catalog.close();
  const c=await Catalog.open({store:f.catalog.store,configPath:join(f.dir,'config.json'),v3RemoteFactory:()=>f.remote});t.after(()=>c.close());await c.load(f.id);
  assert.deepEqual(c.db(f.id).snapshot(),before);const resumed=new V3EndpointMove(c,f.id);
  if(!afterSave)assert.equal(c.row(f.id).connectionId,original);
  assert.equal((await resumed.confirm(input,'admin')).status,'COMMITTED');assert.notEqual(c.row(f.id).connectionId,original);
});
test('PR57 real HTTPS: independently provided certificates, bad pin, changed URL/account and inaccessible old endpoint', {skip:!process.env.PR57_TLS_HOST},async t=>{
  const f=await fixture(t);await f.sync({create:true});await f.settle();const before=f.db.snapshot(),known=new Map(f.remote.objects),dir=await mkdtemp(join(tmpdir(),'pr57-tls-'));
  const material=[];
  for(let n=0;n<2;n++){
    execFileSync('openssl',['req','-x509','-newkey','rsa:2048','-nodes','-days','1','-subj','/CN=pr57-isolated','-keyout',join(dir,`key${n}.pem`),'-out',join(dir,`cert${n}.pem`)],{stdio:'ignore'});
    material.push({key:await readFile(join(dir,`key${n}.pem`)),cert:await readFile(join(dir,`cert${n}.pem`))});
  }
  let auth='Basic '+Buffer.from('new:secret').toString('base64'),puts=0;
  const server=https.createServer(material[0],async(req,res)=>{
    if(req.headers.authorization!==auth){res.writeHead(401);res.end();return;}
    const prefix='/new/dormpanel-sync-v3/',path=req.url.startsWith(prefix)?req.url.slice(prefix.length):null;
    if(req.method==='MKCOL'){res.writeHead(201);res.end();return;}
    if(path===null){res.writeHead(404);res.end();return;}
    if(req.method==='GET'){const bytes=f.remote.objects.get(path);res.writeHead(bytes?200:404);res.end(bytes);return;}
    if(req.method==='PUT'){const data=[];for await(const b of req)data.push(b);assert(!f.remote.objects.has(path));f.remote.objects.set(path,Buffer.concat(data));puts++;res.writeHead(201);res.end();return;}
    if(req.method==='PROPFIND'){
      const row=p=>`<d:response><d:href>${prefix+p}</d:href><d:propstat><d:prop><d:resourcetype>${p.endsWith('/')?'<d:collection/>':''}</d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>`;
      res.writeHead(207);res.end('<d:multistatus xmlns:d="DAV:">'+row(path)+(await f.remote.list(path)).map(row).join('')+'</d:multistatus>');return;
    }
    res.writeHead(405);res.end();
  });await new Promise(r=>server.listen(0,'0.0.0.0',r));t.after(()=>new Promise(r=>server.close(r)));
  f.catalog.v3RemoteFactory=null;
  const service=new V3EndpointMove(f.catalog,f.id),candidate={name:'verified TLS',type:'webdav',url:`https://${process.env.PR57_TLS_HOST}:${server.address().port}/new/`,username:'new',password:'secret',certificatePem:material[1].cert.toString()};
  await assert.rejects(service.preview(candidate,'admin'));assert.deepEqual(f.db.snapshot(),before);
  candidate.certificatePem=material[0].cert.toString();
  async function move(){let p=await service.preview(candidate,'admin');for(let n=0;p.status!=='READY' && n<20;n++)p=await service.resume(p.token,'admin');assert.equal(p.status,'READY');return service.confirm({token:p.token,confirm:p.datasetId,version:p.version,catalogVersion:p.catalogVersion},'admin');}
  await move();assert.equal(puts,0);assert.deepEqual(f.db.snapshot(),before);
  // Same URL now presents a different independently supplied certificate/secret.
  server.setSecureContext(material[1]);auth='Basic '+Buffer.from('rotated:changed').toString('base64');
  candidate.username='rotated';candidate.password='changed';candidate.certificatePem=material[1].cert.toString();
  await move();assert.equal(puts,0);assert.deepEqual(f.db.snapshot(),before);
  await f.db.edit({kind:'entry',action:'upsert',value:{id:'tls-offline',title:'retained',day:1,startMinute:480,endMinute:540,location:''}});await f.settle();assert.equal(puts,1);
  for(const [path,bytes] of known)assert.deepEqual(f.remote.objects.get(path),bytes);
});
