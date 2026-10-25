// Real HTTPS probe: only a new random child of the explicitly designated test folder.
// No existing dataset is opened, migrated, removed or overwritten. stdin holds secrets.
import assert from 'node:assert/strict';
import {randomUUID,X509Certificate} from 'node:crypto';
import tls from 'node:tls';
import {mkdtemp,writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {Store} from '../src/store.mjs';
import {Catalog} from '../src/catalog.mjs';
import {WebDav} from '../src/webdav.mjs';
import {V3EndpointMove} from '../src/v3-endpoint.mjs';
import {V3WebDavStore,Budget} from '../src/v3-store.mjs';
import {sha,stateBytes} from '../src/v3-protocol.mjs';
let input='';for await(const b of process.stdin){input+=b;assert(input.length<65536);}const args=JSON.parse(input);
const parent=new URL(args.url);assert(parent.protocol==='https:' && parent.pathname==='/dormpanel/');
const folder=new URL(`pr57-endpoint-${randomUUID()}/`,parent),config={...args,url:folder.href};delete config.candidateUrl;
const bootstrap=new WebDav(config);bootstrap.base=folder;await bootstrap.request('MKCOL',folder,65536);
const dir=await mkdtemp(join(tmpdir(),'pr57-real-')),store=await new Store(dir).init();
let catalog=await Catalog.open({store,configPath:join(dir,'unused.json')});let id;
try {
  const manage=i=>catalog.manage({catalogVersion:catalog.manifest.revision,...i});
  const {connectionId}=await manage({action:'connection.save',type:'webdav',name:'PR57 isolated HTTPS',...config});
  ({workspaceId:id}=await manage({action:'workspace.add',connectionId,name:'PR57 real',protocol:'v3'}));
  await manage({action:'workspace.switch',targetId:id});
  const sync=opts=>catalog.network(id,e=>e.synchronize(opts));await sync({create:true});for(let n=0;n<5;n++)await sync();
  const db=catalog.db(id),s=db.activeV3.journal.value;
  const remote=new V3WebDavStore(config,new Budget()),objects=new Map();
  for(const row of db.activeV3.journal.meta.page('node',0,256))objects.set(row.key,sha(await remote.read(row.key)));
  await db.edit({kind:'entry',action:'upsert',value:{id:'offline',title:'PR57 saved offline',day:1,startMinute:480,endMinute:540,location:''}});
  const before=db.snapshot(),rawHash=sha(stateBytes(db.frame.local));
  const candidate={type:'webdav',name:'PR57 verified alternate access',...config,url:args.candidateUrl?new URL(folder.pathname,args.candidateUrl).href:folder.href};
  if(args.pinVerifiedCertificate) {
    // Test-only explicit CA-to-pin change: unknown/unverified TLS is never accepted.
    const url=new URL(candidate.url);
    candidate.certificatePem=await new Promise((resolve,reject)=>{
      const socket=tls.connect({host:url.hostname,port:Number(url.port||443),servername:url.hostname,rejectUnauthorized:true},()=>{
        try{assert(socket.authorized);resolve(new X509Certificate(socket.getPeerCertificate().raw).toString());}catch(e){reject(e);}finally{socket.end();}
      });socket.on('error',reject);
    });
  }
  let service=new V3EndpointMove(catalog,id),p=await service.preview(candidate,'isolated-admin');
  for(let n=0;n<32 && p.status!=='READY';n++)p=await service.resume(p.token,'isolated-admin');assert.equal(p.status,'READY');
  const confirm={token:p.token,confirm:p.datasetId,catalogVersion:p.catalogVersion,version:p.version};
  await service.confirm(confirm,'isolated-admin');assert.deepEqual(db.snapshot(),before);
  await catalog.close();catalog=await Catalog.open({store,configPath:join(dir,'unused.json')});await catalog.load(id);
  service=new V3EndpointMove(catalog,id);assert.equal((await service.confirm(confirm,'isolated-admin')).status,'COMMITTED');
  const newRemote=new V3WebDavStore(candidate,new Budget());for(const [path,hash] of objects)assert.equal(sha(await newRemote.read(path)),hash);
  for(let n=0;n<6;n++)await sync();assert.equal(sha(stateBytes(catalog.db(id).frame.local)),rawHash);
  assert.equal(catalog.db(id).activeV3.journal.value.writerId,s.writerId);
  if(args.probeOutput){assert(args.probeOutput==='android-probe.json');await writeFile(args.probeOutput,JSON.stringify({config,candidate,binding:p.descriptor}),{mode:0o600});}
  console.log(JSON.stringify({result:'PASS',probeFolder:folder.pathname,datasetId:p.datasetId,descriptorHash:p.descriptor.descriptorHash,objectsUnchanged:objects.size,
    sameWriter:true,offlineRawPreserved:true,reopenIdempotent:true,oldEndpointNewChanges:'UNPROVEN',completeDirectoryView:false}));
} finally {await catalog.close();}
