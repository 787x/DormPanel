import {readFile,writeFile,mkdtemp} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import assert from 'node:assert/strict';
import {openV3Workspace} from '../src/v3-workspace.mjs';
import {V3Engine} from '../src/v3-engine.mjs';
import {V3WebDavStore,Budget} from '../src/v3-store.mjs';
import {IndexedV3Cache} from '../src/v3-index.mjs';
import {decode,businessHash} from '../src/v3-protocol.mjs';
import {rgb} from '../src/shared-course-colors.mjs';
const file=process.env.DORMPANEL_SHARED_PROBE,c=JSON.parse(await readFile(file,'utf8'));
assert.match(c.url,/\/pr69-isolated-[0-9a-f-]{36}\/$/);
const root=c.nodeRoot??await mkdtemp(join(tmpdir(),'pr69-node-'));c.nodeRoot=root;await writeFile(file,JSON.stringify(c));
const a=await openV3Workspace(root,()=>{}),engine=new V3Engine(a.journal,a.writerId);
const config={url:c.url,username:c.username,password:c.password,certificatePem:c.certificatePem,sharedFormat:1};
const remote=b=>new V3WebDavStore(config,b);
if(!a.journal.value.binding){const r=remote(new Budget());await engine.bind(decode(await r.read(c.binding.descriptorPath)),c.binding.descriptorHash);}
async function settle(){for(let n=0;n<4;n++){const b=new Budget();try{await engine.round(remote(b),new IndexedV3Cache(root,b),b,{discoverySteps:32});}catch(e){if(!/incomplete/.test(e.message))throw e;}}}
await settle();assert.equal(rgb(a.journal.value.local,null,'x'),c.phase==='node-edit'?0x123456:(c.androidRGB??0xabcdef));
assert.equal(a.journal.value.local.sharedRecords.find(r=>r.typeId==='test.note').payload,'6f7061717565');
if(c.phase==='node-edit'){await engine.setCourseColor(null,'x',0xabcdef);await settle();}
c.nodeHash=businessHash(a.journal.value.local);await a.journal.close();await writeFile(file,JSON.stringify(c));console.log('fnOS Workspace: shared color and opaque record PASS',c.nodeHash);
