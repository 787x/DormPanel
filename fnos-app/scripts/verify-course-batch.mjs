import assert from 'node:assert/strict';
import {readFile,writeFile} from 'node:fs/promises';
import {join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {Store} from '../src/store.mjs';
import {Catalog} from '../src/catalog.mjs';
import {stateBytes,sha} from '../src/v3-protocol.mjs';
let raw='';for await(const b of process.stdin)raw+=b;
const args=JSON.parse(raw);
assert(['v2','v3'].includes(args.protocol));assert(['join','edit','final'].includes(args.mode));
const isolated=process.env.PR65_ISOLATED_ROOT;
assert(isolated && /^\/tmp\/dormpanel-pr65-network-[0-9a-f-]{36}$/.test(isolated));
assert.equal(fileURLToPath(new URL('../',import.meta.url)),isolated+'/');
if(args.mode==='join') assert(new RegExp('/pr65-'+args.protocol+'-[0-9a-f-]{36}/$').test(new URL(args.config.url).pathname));
const privatePath=new URL('./private-'+args.protocol+'.json',import.meta.url);
let saved;
if(args.mode==='join')saved={config:args.config,root:new URL('./state-'+args.protocol,import.meta.url).pathname};
else saved=JSON.parse(await readFile(privatePath,'utf8'));
const store=await new Store(saved.root).init();const catalog=await Catalog.open({store,configPath:join(saved.root,'no-legacy.json')});
try {
 const network=op=>catalog.network(saved.workspaceId,e=>e.synchronize(op));
 if(args.mode==='join') {
  const manage=x=>catalog.manage({catalogVersion:catalog.manifest.revision,...x});
  const {connectionId}=await manage({action:'connection.save',type:'webdav',name:'PR65 isolated',...saved.config});
  ({workspaceId:saved.workspaceId}=await manage({action:'workspace.add',connectionId,name:'PR65 '+args.protocol,protocol:args.protocol}));
  if(args.protocol==='v3') {const discovered=await catalog.discover(saved.workspaceId);assert.equal(discovered.candidates.length,1);await network({bind:true,...discovered.candidates[0]});}
  else await network({bind:true});
  await writeFile(privatePath,JSON.stringify(saved),{mode:0o600});
 } else await catalog.load(saved.workspaceId);
 const settle=async()=>{for(let n=0;n<32;n++){const r=await network();if(args.protocol==='v2'||r.status==='OBSERVED')return;}throw Error('fnOS did not settle');};
 await settle();let db=catalog.db(saved.workspaceId);
 assert.equal(db.frame.local.classOverrides.filter(x=>x.anchorDate===null&&x.title==='PR65整门课程'&&x.teacher==='统一教师').length,2);
 assert.equal(db.frame.local.classOverrides.find(x=>x.anchorDate!==null).location,'指定周教室');
 if(args.mode==='edit') {
  const old=db.frame.local.classOverrides.find(x=>x.seriesId==='b'&&x.anchorDate===null);
  const {key,sourceId,seriesId,anchorDate,...patch}=old;
  await db.edit({kind:'override',action:'upsert',sourceId,seriesId,anchorDate,patch:{...patch,note:'fnOS备注'}},db.frame.revision);await settle();
 }
 if(args.mode==='final') {
  assert.equal(db.frame.local.classOverrides.find(x=>x.seriesId==='a'&&x.anchorDate===null).location,'X08E教室');
  assert.equal(db.frame.local.classOverrides.find(x=>x.seriesId==='b'&&x.anchorDate===null).note,'fnOS备注');
 }
 console.log(JSON.stringify({result:'PASS',protocol:args.protocol,mode:args.mode,rawHash:sha(stateBytes(db.frame.local))}));
} finally {await catalog.close();}
