// Isolated production Catalog / WebDAV probe. Credentials arrive only on stdin.
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { mkdtemp, readFile, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Store } from '../src/store.mjs';
import { Catalog } from '../src/catalog.mjs';
import { WebDav } from '../src/webdav.mjs';
import { V3WebDavStore,Budget } from '../src/v3-store.mjs';
import { IndexedV3Cache } from '../src/v3-index.mjs';
import { V3History } from '../src/v3-history.mjs';
import { stateBytes,sha } from '../src/v3-protocol.mjs';
let input=''; for await (const b of process.stdin) { input+=b; assert(input.length<32768); }
const args=JSON.parse(input), mode=args.mode ?? 'init';
let config, root, workspaceId;
if (mode==='init') {
  const parent=new URL(args.url); assert(parent.pathname.endsWith('/dormpanel/'));
  const probe=new URL(`pr55-runtime-${randomUUID()}/`,parent), transport=new WebDav(args); transport.base=probe;
  await transport.request('MKCOL',probe,65536);
  config={ url:probe.href,username:args.username,password:args.password,...(args.allowInsecureHttp===true?{allowInsecureHttp:true}:{}),...(args.certificatePem?{certificatePem:args.certificatePem}:{}) };
  root=await mkdtemp(join(tmpdir(),'pr55-active-catalog-'));
} else ({config,root,workspaceId}=JSON.parse(await readFile(args.privatePath,'utf8')));
assert(/\/pr55-runtime-[0-9a-f-]{36}\/$/.test(new URL(config.url).pathname));
const store=await new Store(root).init(), catalog=await Catalog.open({store,configPath:join(root,'unused-config.json')});
try {
  if (mode==='init') {
    const manage=input=>catalog.manage({catalogVersion:catalog.manifest.revision,...input});
    const {connectionId}=await manage({action:'connection.save',type:'webdav',name:'PR55 isolated',...config});
    ({workspaceId}=await manage({action:'workspace.add',connectionId,name:'PR55 v3',protocol:'v3'}));
    await catalog.db(workspaceId).edit({kind:'entry',action:'upsert',value:{id:'node',title:'Node initial',day:1,startMinute:480,endMinute:540,location:''}});
    await catalog.network(workspaceId,e=>e.synchronize({create:true}));
  } else await catalog.load(workspaceId);
  const db=catalog.db(workspaceId);
  const settle=async()=>{let last; for(let n=0;n<32;n++){last=await catalog.network(workspaceId,e=>e.synchronize()); if(last.status==='OBSERVED')return last;} throw Error('bounded rounds did not settle');};
  await settle();
  if(mode==='rotate') {
    const runtime=db.activeV3,budget=new Budget(),remote=new V3WebDavStore(config,budget),binding=runtime.journal.value.binding;
    const history=await new V3History(runtime.journal,new IndexedV3Cache(runtime.journal.directory,budget),remote,binding.descriptorPath,binding.descriptorHash,budget).open();
    const tips=history.tips();assert.equal(tips.length,1);await history.verify(tips[0]);
    await runtime.engine.rotate(history,remote,history.node(tips[0]).epochId,tips);await settle();
    assert(runtime.journal.meta.count('epoch')>=2);
  }
  if (mode==='node-edit') {
    await db.edit({kind:'entry',action:'upsert',value:{id:'node',title:'Node concurrent edit',day:1,startMinute:480,endMinute:540,location:''}}); await settle();
  }
  if (mode==='final') {
    assert(['node','android-phone','android-x08e'].every(id=>db.frame.local.entries.some(e=>e.id===id)));
    assert.equal(db.frame.local.entries.find(e=>e.id==='node').title,'Phone conflict decision');
    await db.edit({kind:'event',action:'upsert',value:{id:'node-final',title:'fnOS receive verified',start:1790000000000,end:1790003600000,note:''}}); await settle();
  }
  const binding=db.activeV3.journal.value.binding;
  if(mode==='init') await writeFile(args.privatePath,JSON.stringify({config,root,workspaceId,binding}),{mode:0o600});
  console.log(JSON.stringify({mode,result:'PASS',datasetId:binding.datasetId,descriptorHash:binding.descriptorHash,
    entries:db.frame.local.entries.map(e=>({id:e.id,title:e.title})),rawHash:sha(stateBytes(db.frame.local)),epochs:db.activeV3.journal.meta.count('epoch'),oldHistoryAuditComplete:false}));
} finally { await catalog.close(); }
