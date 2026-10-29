import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, unlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { encode, ZERO, ZERO_HASH, sha } from '../src/v3-protocol.mjs';
import { objectPath, Budget } from '../src/v3-store.mjs';
import { emptyState } from '../src/merge.mjs';
import { IndexedV3Cache } from '../src/v3-index.mjs';
import { V3Journal, V3Publisher } from '../src/v3-transaction.mjs';
import { V3Engine } from '../src/v3-engine.mjs';
import { V3History, V3Discovery, reference } from '../src/v3-history.mjs';
import { scopeInfo } from '../src/v3-store.mjs';

const dir = () => mkdtemp(join(tmpdir(), 'dp-pr54-'));
const object = () => ({ type: 1, datasetId: randomUUID(), objectId: randomUUID(), writerId: randomUUID(), epochId: ZERO, epochNumber: 0, descriptorHash: ZERO_HASH, refs: [], state: null });
class FakeRemote {
  constructor() { this.objects = new Map(); this.puts = 0; this.reads = 0; }
  async read(p) { this.reads++; return this.objects.get(p) ?? null; }
  async putOnce(p, b) { assert(!this.objects.has(p)); this.puts++; this.objects.set(p, Buffer.from(b)); }
  async list(scope) {
    const children = new Set();
    for (const p of this.objects.keys()) if (p.startsWith(scope)) { const tail = p.slice(scope.length), slash = tail.indexOf('/'); children.add(scope + (slash < 0 ? tail : tail.slice(0, slash + 1))); }
    assert(children.size <= 256); return [...children].sort();
  }
}
async function client(binding, remote) {
  const root = await dir(), journal = new V3Journal(root); await journal.open(); const engine = new V3Engine(journal, randomUUID());
  if (binding) { const { decode } = await import('../src/v3-protocol.mjs'); await engine.bind(decode(await remote.read(binding.descriptorPath)), binding.descriptorHash); }
  const c = { root, journal, engine }; c.round = () => { const budget = new Budget(); return c.engine.round(remote, new IndexedV3Cache(root, budget), budget, { discoverySteps: 32 }); }; return c;
}
const entry = (id, title = id) => ({ id, title, day: 1, startMinute: 480, endMinute: 540, location: '' });
async function settle(c, count = 3) {
  let result;
  for (let n = 0; n < count; n++) { try { result = await c.round(); } catch (e) { if (!/incomplete|budget/.test(e.message)) throw e; } }
  return result;
}

test('DPV3/4: unknown optional survives edits, publish, reopen and peer receipt',async()=>{
 const remote=new FakeRemote(),a=await client(null,remote);
 const extension={typeId:'test.note',recordId:'one',schemaVersion:1,required:false,dependencies:[],payload:'6f7061717565'};
 const initial={...emptyState(),entries:[entry('x')],sharedRecords:[extension]};
 const binding=await a.engine.initialize(remote,initial);await settle(a);
 const b=await client(binding,remote);await settle(b);
 assert.deepEqual(b.journal.value.local.sharedRecords,[extension]);
 await b.engine.edit({...b.journal.value.local,entries:[entry('x','renamed')]});await settle(b);await settle(a);
 assert.deepEqual(a.journal.value.local.sharedRecords,[extension]);assert.equal(a.journal.value.local.entries[0].title,'renamed');
 const {decode,businessHash}=await import('../src/v3-protocol.mjs');
 assert.equal(decode(await remote.read(binding.descriptorPath)).format,4);
 assert.notEqual(businessHash(initial),businessHash({...initial,sharedRecords:[]}));
 const root=b.root,writer=b.engine.writerId;await b.journal.close();const j=new V3Journal(root);await j.open();
 assert.deepEqual(j.value.local.sharedRecords,[extension]);await j.close();await a.journal.close();
});
test('DPV3/4: required unknown stops unrelated edit',async()=>{
 const c=await client(null,new FakeRemote());
 await c.journal.transaction(s=>{s.local={...emptyState(),sharedRecords:[{typeId:'future.type',recordId:'one',schemaVersion:1,required:true,dependencies:[],payload:''}]};});
 await assert.rejects(c.engine.edit({...c.journal.value.local,entries:[entry('x')]}),/required/);await c.journal.close();
});

import {previewSharedUpgrade,cancelSharedUpgrade,confirmSharedUpgrade} from '../src/shared-upgrade.mjs';
import {decode,businessHash} from '../src/v3-protocol.mjs';
import {rgb} from '../src/shared-course-colors.mjs';
test('isolated upgrade preview cancel, failed publication recovery and source unchanged',async()=>{
 const remote=new FakeRemote(),source=await client(null,remote);await source.engine.initialize(remote,{...emptyState(),entries:[entry('x')]});await settle(source);
 const capture=Buffer.from(await remote.read(source.journal.value.baselinePath));const sourceBefore=JSON.stringify(source.journal.value);
 const target=await client(null,remote);const p=previewSharedUpgrade(target.engine,capture,source.engine);cancelSharedUpgrade(target.engine,p.id);
 await assert.rejects(confirmSharedUpgrade(target.engine,p.id,capture,remote,source.engine));assert.equal(target.journal.value.binding,undefined);
 const p2=previewSharedUpgrade(target.engine,capture,source.engine);const put=remote.putOnce.bind(remote);let once=true;
 remote.putOnce=async(p,b)=>{await put(p,b);if(once){once=false;throw Error('lost response');}};
 await assert.rejects(confirmSharedUpgrade(target.engine,p2.id,capture,remote,source.engine));
 const writer=target.engine.writerId;await target.journal.close();target.journal=await new V3Journal(target.root).open();target.engine=new V3Engine(target.journal,writer);
 await confirmSharedUpgrade(target.engine,p2.id,capture,remote,source.engine);await settle(target);
 assert.deepEqual(target.journal.value.local.sharedRecords,[]);assert.equal(JSON.stringify(source.journal.value),sourceBefore);
 await source.journal.close();await target.journal.close();
});
test('color conflicts and parent deletion are explicit; checkpoint preserves opaque record',async()=>{
 const remote=new FakeRemote(),a=await client(null,remote);const opaque={typeId:'test.note',recordId:'one',schemaVersion:1,required:false,dependencies:[],payload:'6f7061717565'};
 const binding=await a.engine.initialize(remote,{...emptyState(),entries:[entry('x')],sharedRecords:[opaque]});await settle(a);
 const b=await client(binding,remote);await settle(b);
 await a.engine.setCourseColor(null,'x',0x123456);await b.engine.setCourseColor(null,'x',0xabcdef);await settle(a);assert.equal((await settle(b)).status,'CONFLICT');
 const conflict=b.journal.value.conflict;await b.engine.resolve(conflict.id,'remote');await settle(b);await settle(a);
 assert.equal(rgb(b.journal.value.local,null,'x'),0x123456);
 const budget=new Budget(),cache=new IndexedV3Cache(a.root,budget),h=await new V3History(a.journal,cache,remote,binding.descriptorPath,binding.descriptorHash,budget).open();
 const path=a.journal.value.baselinePath;await h.verify(path);const epoch=h.node(path).epochId;
 await a.engine.rotate(h,remote,epoch,h.epochTips(epoch));await settle(a);await settle(b);
 assert.deepEqual(b.journal.value.local.sharedRecords.find(r=>r.typeId==='test.note'),opaque);
 await a.journal.close();await b.journal.close();
});
import {Registry,courseColor,wireRecords,durableRecords} from '../src/shared-records.mjs';
import {change as changeColor} from '../src/shared-course-colors.mjs';
import {merge as mergeState} from '../src/merge.mjs';
test('registered second type writes through engine; older client edits course without dropping bytes',async()=>{
 const remote=new FakeRemote(),a=await client(null,remote);
 const note={typeId:'test.note',schemaVersion:1,maxPayload:32,validate:r=>assert(r.payload.length>0)};
 a.engine=new V3Engine(a.journal,a.engine.writerId,new Registry([courseColor,note]));
 const binding=await a.engine.initialize(remote,{...emptyState(),entries:[entry('x')],sharedRecords:[]});await settle(a);
 const rec={typeId:'test.note',recordId:'one',schemaVersion:1,required:false,dependencies:[],payload:Buffer.from('opaque')};
 await a.engine.edit({...a.journal.value.local,sharedRecords:durableRecords(new Registry([courseColor,note]).replace([],rec))});await settle(a);
 const b=await client(binding,remote);await settle(b);await b.engine.edit({...b.journal.value.local,entries:[entry('x','older client edit')]});await settle(b);await settle(a);
 assert.equal(a.journal.value.local.sharedRecords[0].payload,'6f7061717565');
 const base=changeColor({...emptyState(),entries:[entry('x')],sharedRecords:[]},null,'x',0x123456);
 assert.equal(mergeState(base,{...base,entries:[]},changeColor(base,null,'x',0xabcdef)).conflicts.length,1);
 await a.journal.close();await b.journal.close();
});
test('extended Pending lost response reopens without overwrite and receipt retains new local color',async()=>{
 const remote=new FakeRemote(),a=await client(null,remote),opaque={typeId:'test.note',recordId:'one',schemaVersion:1,required:false,dependencies:[],payload:'6f7061717565'};
 await a.engine.initialize(remote,{...emptyState(),entries:[entry('x')],sharedRecords:[opaque]});await settle(a);
 await a.engine.setCourseColor(null,'x',0x123456);const put=remote.putOnce.bind(remote);let inject=true;
 remote.putOnce=async(p,b)=>{await put(p,b);if(inject){inject=false;await a.engine.setCourseColor(null,'x',0xabcdef);throw Error('lost response');}};
 await assert.rejects(a.round(),/lost response/);const count=remote.puts,writer=a.engine.writerId;
 await a.journal.close();a.journal=await new V3Journal(a.root).open();a.engine=new V3Engine(a.journal,writer);await settle(a);
 assert.equal(rgb(a.journal.value.local,null,'x'),0xabcdef);assert.deepEqual(a.journal.value.local.sharedRecords.find(r=>r.typeId==='test.note'),opaque);
 assert(remote.puts<=count+1);await a.journal.close();
});
import {readFile} from 'node:fs/promises';
test('complete DPV3/4 fixed object and full business hash',async()=>{
 const bytes=await readFile(new URL('../../test-vectors/sync-v3-shared/genesis.dpv',import.meta.url));
 const m=JSON.parse(await readFile(new URL('../../test-vectors/sync-v3-shared/manifest.json',import.meta.url),'utf8'));
 const o=decode(bytes);assert.equal(o.format,4);assert(encode(o).equals(bytes));assert.equal(sha(bytes),m.objectHash);assert.equal(businessHash(o.state),m.businessHash);
});
import {sqliteSnapshot,verifyV3Backup} from '../src/v3-backup.mjs';
import {mkdir} from 'node:fs/promises';
import {openV3Workspace} from '../src/v3-workspace.mjs';
test('extended Workspace SQLite backup validates and restores opaque records and color',async()=>{
 const root=await dir(),id=randomUUID(),identity={writerId:randomUUID(),installationId:randomUUID()};
 const source=join(root,'source'),a=await openV3Workspace(source,()=>{},identity),engine=new V3Engine(a.journal,identity.writerId),remote=new FakeRemote();
 const opaque={typeId:'test.note',recordId:'one',schemaVersion:1,required:false,dependencies:[],payload:'6f7061717565'};
 await engine.initialize(remote,{...emptyState(),entries:[entry('x')],sharedRecords:[opaque]});
 for(let n=0;n<3;n++){const b=new Budget();try{await engine.round(remote,new IndexedV3Cache(source,b),b,{discoverySteps:32});}catch(e){if(!/incomplete/.test(e.message))throw e;}}
 await a.workspace.setSharedCourseColor(null,'x',0x123456,a.workspace.frame.revision);
 const target=join(root,'backup'),relative=`workspaces/${id}/v3/v3-state-v2.sqlite`,targetDir=join(target,`workspaces/${id}/v3`);await mkdir(targetDir,{recursive:true});
 await sqliteSnapshot(join(source,'v3-state-v2.sqlite'),join(target,relative));
 const proof=await verifyV3Backup(target,id,identity,[{name:relative}]);assert(proof.privateStateComplete);
 const restored=await openV3Workspace(targetDir,()=>{},identity);assert.equal(rgb(restored.journal.value.local,null,'x'),0x123456);assert.deepEqual(restored.journal.value.local.sharedRecords.find(r=>r.typeId==='test.note'),opaque);
 assert.equal(restored.workspace.frame.version,2);await restored.journal.close();await a.journal.close();
});
test('extended LATE integration preserves independent unknown bytes and current course color',async()=>{
 const remote=new FakeRemote(),a=await client(null,remote),opaque={typeId:'test.note',recordId:'one',schemaVersion:1,required:false,dependencies:[],payload:'6f7061717565'};
 const binding=await a.engine.initialize(remote,{...emptyState(),entries:[entry('x')],sharedRecords:[opaque]});await settle(a);
 const old=a.journal.value.baselinePath,oldBytes=await remote.read(old),source=decode(oldBytes),budget=new Budget();
 const h=await new V3History(a.journal,new IndexedV3Cache(a.root,budget),remote,binding.descriptorPath,binding.descriptorHash,budget).open();await h.verify(old);await a.engine.rotate(h,remote,source.epochId,[old]);await settle(a);
 await a.engine.setCourseColor(null,'x',0x123456);await settle(a);
 const extra={...opaque,recordId:'late',payload:'6c617465'},late={...source,type:3,objectId:randomUUID(),refs:[reference(source,sha(oldBytes),1)],state:{...source.state,sharedRecords:[opaque,extra]}};
 await remote.putOnce(objectPath(late),encode(late));await settle(a,8);
 assert.equal(rgb(a.journal.value.local,null,'x'),0x123456);assert.deepEqual(a.journal.value.local.sharedRecords.find(r=>r.recordId==='late'),extra);
 assert([...remote.objects.values()].map(decode).some(o=>o.refs.some(r=>r.role===3)));
 const count=remote.puts;await settle(a,5);assert.equal(remote.puts,count);await a.journal.close();
});

test('upgrade rejects stale source and unpublished local edits despite identical caller bytes',async()=>{
 const remote=new FakeRemote(),source=await client(null,remote);await source.engine.initialize(remote,{...emptyState(),entries:[entry('x')]});await settle(source);
 const capture=await remote.read(source.journal.value.baselinePath),target=await client(null,remote);
 const p=previewSharedUpgrade(target.engine,capture,source.engine);
 await source.engine.edit({...source.journal.value.local,entries:[entry('x','unpublished')]});
 assert.throws(()=>previewSharedUpgrade(target.engine,capture,source.engine));
 await assert.rejects(confirmSharedUpgrade(target.engine,p.id,capture,remote,source.engine));assert.equal(target.journal.value.binding,undefined);
 await settle(source);await assert.rejects(confirmSharedUpgrade(target.engine,p.id,capture,remote,source.engine));
 assert.equal(target.journal.meta.get('sharedUpgrade',p.id).targetDatasetId,p.targetDatasetId);
 await target.journal.close();await source.journal.close();
});
test('malformed remote color does not replace local baseline or Pending evidence',async()=>{
 const remote=new FakeRemote(),a=await client(null,remote);const binding=await a.engine.initialize(remote,{...emptyState(),entries:[entry('x')],sharedRecords:[]});await settle(a);
 await a.engine.setCourseColor(null,'x',0x010203);await settle(a);
 const before=structuredClone(a.journal.value),parentBytes=await remote.read(before.baselinePath),parent=decode(parentBytes);
 const child={...parent,type:3,objectId:randomUUID(),refs:[reference(parent,sha(parentBytes),1)]};const bytes=encode(child),body=Buffer.from(bytes.subarray(0,-32));
 const marker=Buffer.from([0,0,0,4,1,1,2,3]),pos=body.indexOf(marker);assert(pos>=0);body[pos+4]=2;
 const raw=Buffer.concat([body,Buffer.from(sha(body),'hex')]);await remote.putOnce(objectPath(child),raw);
 let rejected=false;for(let n=0;n<6;n++){try{await a.round();}catch{rejected=true;break;}}
 assert(rejected);assert.deepEqual(a.journal.value.local,before.local);assert.deepEqual(a.journal.value.baseline,before.baseline);assert.deepEqual(a.journal.value.pending,before.pending);await a.journal.close();
});
import {merge as mergeBusiness,mergePlan} from '../src/merge.mjs';
const linkedNote={typeId:'test.note',schemaVersion:1,maxPayload:32,validate:r=>assert(r.payload.length>0,'empty note'),relatedKeys:()=>['class:x']};
test('custom Registry rejects remote invalid payload while unregistered peer preserves optional bytes',async()=>{
 const remote=new FakeRemote(),seed=await client(null,remote);const binding=await seed.engine.initialize(remote,{...emptyState(),entries:[entry('x')],sharedRecords:[]});await settle(seed);
 const registered=await client(binding,remote);registered.engine=new V3Engine(registered.journal,registered.engine.writerId,new Registry([courseColor,linkedNote]));await settle(registered);
 const before=structuredClone(registered.journal.value),parent=decode(await remote.read(before.baselinePath));
 const record={typeId:'test.note',recordId:'one',schemaVersion:1,required:false,dependencies:[],payload:''};
 const bad={...parent,type:3,objectId:randomUUID(),refs:[reference(parent,sha(encode(parent)),1)],state:{...parent.state,sharedRecords:[record]}};
 await remote.putOnce(objectPath(bad),encode(bad));let refused=false;for(let n=0;n<5&&!refused;n++)try{await registered.round();}catch{refused=true;}
 assert(refused);for(const k of ['local','baseline','pending'])assert.deepEqual(registered.journal.value[k],before[k]);
 const old=await client(binding,remote);await settle(old,5);assert.deepEqual(old.journal.value.local.sharedRecords,[record]);
 const root=registered.root,writer=registered.engine.writerId;await registered.journal.close();registered.journal=await new V3Journal(root).open();registered.engine=new V3Engine(registered.journal,writer,new Registry([courseColor,linkedNote]));
 await assert.rejects(registered.round());await registered.journal.close();await seed.journal.close();await old.journal.close();
});
test('custom relatedKeys applies to direct merge, multi-branch history and mergePlan',async()=>{
 const registry=new Registry([courseColor,linkedNote]),remote=new FakeRemote(),a=await client(null,remote);
 const note={typeId:'test.note',recordId:'one',schemaVersion:1,required:false,dependencies:[],payload:'61'};
 const base={...emptyState(),entries:[entry('x'),entry('y')],sharedRecords:[note]};const binding=await a.engine.initialize(remote,base);await settle(a);
 const p=decode(await remote.read(a.journal.value.baselinePath));const make=state=>({...p,type:3,objectId:randomUUID(),refs:[reference(p,sha(encode(p)),1)],state});
 const left={...base,entries:[entry('y')]},right={...base,sharedRecords:[{...note,payload:'62'}]};
 assert.equal(mergeBusiness(base,left,right,null,new Set(),[courseColor,linkedNote]).conflicts.length,1);
 const l=make(left),r=make(right);for(const o of [l,r])await remote.putOnce(objectPath(o),encode(o));
 const budget=new Budget(),h=await new V3History(a.journal,new IndexedV3Cache(a.root,budget),remote,binding.descriptorPath,binding.descriptorHash,budget,registry).open();
 for(const o of [l,r])await h.verify(objectPath(o));await assert.rejects(h.merged([objectPath(l),objectPath(r)]),/conflict/);
 const plan={base,localBase:base,branches:[{commitId:l.objectId,state:left},{commitId:r.objectId,state:right}]};const planned=mergePlan(plan,base,null,[courseColor,linkedNote]);assert(planned.conflicts.some(c=>c.keys.includes('class:x')&&c.keys.includes('shared:test.note/one')));assert(planned.conflicts.every(c=>!c.keys.includes('class:y')));
 const independent=mergeBusiness(base,{...base,entries:[entry('x'),entry('y','changed')]},right,null,new Set(),[courseColor,linkedNote]);assert.equal(independent.conflicts.length,0);
 a.engine=new V3Engine(a.journal,a.engine.writerId,registry);assert.equal((await settle(a)).status,'CONFLICT');
 const writer=a.engine.writerId;await a.journal.close();a.journal=await new V3Journal(a.root).open();a.engine=new V3Engine(a.journal,writer,registry);
 await a.engine.resolve(a.journal.value.conflict.id,'local');await settle(a);assert.equal(a.journal.value.local.sharedRecords[0].payload,'61');assert(a.journal.value.local.entries.some(e=>e.id==='x'));assert.equal(businessHash(a.journal.value.local),'b4496b6ccdaed04e6bd25b6494ec72bd9a22f21018e844dafecfb21f92eac7de');await a.journal.close();
});

import {scheduleSlots} from '../src/shared-course-schedule.mjs';
test('course schedule preview and protected atomic Workspace color commit survive reopen',async()=>{
 const root=await dir(),identity={writerId:randomUUID(),installationId:randomUUID()},remote=new FakeRemote();
 const a=await openV3Workspace(root,()=>{},identity),engine=new V3Engine(a.journal,identity.writerId);
 await engine.initialize(remote,{...emptyState(),entries:[entry('x'),entry('y')],sharedRecords:[]});
 for(let n=0;n<3;n++){const b=new Budget();try{await engine.round(remote,new IndexedV3Cache(root,b),b,{discoverySteps:32});}catch(e){if(!/incomplete/.test(e.message))throw e;}}
 const slot={id:'slot',sourceId:null,ownerSeries:'x',replacesSeries:null,week1:'2026-09-07',weeks:[1,3,5],weekday:2,startMinute:480,endMinute:540,periodStart:0,periodEnd:0,enabled:true,title:'Math',location:'A',teacher:'T',note:'N'};
 const bslot={...slot,id:'bslot',ownerSeries:'y'};
 await a.workspace.saveCourseSchedules([bslot],null,a.workspace.frame.revision,{sourceId:null,ownerSeries:'y',deleteIds:[]});
 const bBefore=JSON.stringify(a.workspace.frame.local.sharedRecords);
 const revision=a.workspace.frame.revision,color={sourceId:null,seriesId:'x',rgb:0x123456};
 const preview=a.workspace.previewCourseSchedules([slot],color,revision,{sourceId:null,ownerSeries:'x',deleteIds:[]});assert.equal(scheduleSlots(a.workspace.frame.local).length,1);assert.equal(scheduleSlots(preview.state).length,2);
 await assert.rejects(a.workspace.saveCourseSchedules([{...slot,ownerSeries:'missing'}],color,revision,{sourceId:null,ownerSeries:'x',deleteIds:[]}));
 assert.equal(a.workspace.frame.revision,revision);assert.equal(scheduleSlots(a.workspace.frame.local).length,1);
 await a.workspace.saveCourseSchedules([slot],color,revision,{sourceId:null,ownerSeries:'x',deleteIds:[]});assert.equal(rgb(a.workspace.frame.local,null,'x'),0x123456);assert.equal(businessHash(a.workspace.frame.local),businessHash(preview.state));assert.deepEqual(scheduleSlots(a.workspace.frame.local).find(s=>s.id==='bslot'),bslot);
 await assert.rejects(a.workspace.saveCourseSchedules([],null,revision,{sourceId:null,ownerSeries:'x',deleteIds:['slot']}));assert.equal(scheduleSlots(a.workspace.frame.local).length,2);
 await a.workspace.saveCourseSchedules([],null,a.workspace.frame.revision,{sourceId:null,ownerSeries:'x',deleteIds:['slot']});assert.deepEqual(scheduleSlots(a.workspace.frame.local),[bslot]);
 const digest=businessHash(a.workspace.frame.local);await a.journal.close();
 const reopened=await openV3Workspace(root,()=>{},identity);assert.equal(businessHash(reopened.workspace.frame.local),digest);assert.equal(reopened.workspace.courseSchedules().length,1);await reopened.journal.close();
});

test('source lifecycle protected transactions reject dangling references and reopen complete state',async()=>{
 const {decode:decodeLegacy}=await import('../src/protocol.mjs');
 const {scheduleRecord}=await import('../src/shared-course-schedule.mjs');
 const {durableRecords}=await import('../src/shared-records.mjs');
 const seed=decodeLegacy(await readFile(new URL('../../schedule-core/src/test/resources/sync-v2/complex-root.dpc',import.meta.url))).state;
 const row=seed.imported[0],source=row.sourceId,root=await dir(),identity={writerId:randomUUID(),installationId:randomUUID()},remote=new FakeRemote();
 const slot={id:'life-added',sourceId:source,ownerSeries:row.seriesId,replacesSeries:null,week1:'2026-09-07',weeks:[1,3],weekday:2,startMinute:480,endMinute:540,periodStart:0,periodEnd:0,enabled:true,title:'Math',location:'A',teacher:'T',note:'N'};
 seed.sharedRecords=durableRecords([scheduleRecord(slot),scheduleRecord({...slot,id:'life-replaced',replacesSeries:row.seriesId})]);
 const a=await openV3Workspace(root,()=>{},identity),engine=new V3Engine(a.journal,identity.writerId);await engine.initialize(remote,seed);
 for(let n=0;n<3;n++){const b=new Budget();try{await engine.round(remote,new IndexedV3Cache(root,b),b,{discoverySteps:32});}catch(e){if(!/incomplete/.test(e.message))throw e;}}
 const before=businessHash(a.workspace.frame.local),revision=a.workspace.frame.revision;
 await assert.rejects(a.workspace.edit({kind:'source',action:'delete',id:source},revision),/请先/);assert.equal(businessHash(a.workspace.frame.local),before);
 await assert.rejects(a.workspace.tx(f=>{f.local.imported=f.local.imported.map(r=>({...r,seriesId:'missing'}));},revision),/请先/);assert.equal(businessHash(a.workspace.frame.local),before);
 await assert.rejects(a.workspace.tx(f=>{f.local.imported=[];throw Error('injected failure');},revision),/injected/);assert.equal(businessHash(a.workspace.frame.local),before);
 await a.workspace.tx(f=>{f.local.imported=f.local.imported.map(r=>({...r,title:'replaced title'}));},revision);
 assert.equal(scheduleSlots(a.workspace.frame.local).length,2);
 await assert.rejects(a.workspace.edit({kind:'source',action:'delete',id:source},revision));
 await a.workspace.saveCourseSchedules([],null,a.workspace.frame.revision,{sourceId:source,ownerSeries:row.seriesId,deleteIds:['life-added','life-replaced']});
 await a.workspace.edit({kind:'source',action:'delete',id:source},a.workspace.frame.revision);
 const digest=businessHash(a.workspace.frame.local);await a.journal.close();const reopened=await openV3Workspace(root,()=>{},identity);
 assert.equal(businessHash(reopened.workspace.frame.local),digest);assert(!reopened.workspace.frame.local.sources.some(s=>s.id===source));assert.equal(scheduleSlots(reopened.workspace.frame.local).length,0);await reopened.journal.close();
});

test('logical multi-series edit is one transaction with common fields, color and scoped deletion',async()=>{
 const {decode:legacy}=await import('../src/protocol.mjs'),{scheduleRecord}=await import('../src/shared-course-schedule.mjs'),{durableRecords}=await import('../src/shared-records.mjs');
 const seed=legacy(await readFile(new URL('../../schedule-core/src/test/resources/sync-v2/complex-root.dpc',import.meta.url))).state;
 const raw=seed.imported[0],source=seed.sources.find(s=>s.id===raw.sourceId);
 seed.imported=['A','B','C','other'].map((id,n)=>({...raw,id:'raw-'+id,seriesId:id,title:id==='other'?'Other':'Math',start:raw.start+BigInt(n*86400000),end:raw.end+BigInt(n*86400000)}));seed.sources=[{...source,occurrenceCount:4,firstStart:seed.imported[0].start,lastEnd:seed.imported[3].end}];seed.classOverrides=[];seed.dayAdjustments=[];
 const slot=(id,owner)=>({id,sourceId:source.id,ownerSeries:owner,replacesSeries:null,week1:'2026-09-07',weeks:[1,3],weekday:2,startMinute:480,endMinute:540,periodStart:0,periodEnd:0,enabled:true,title:'Math',location:'A',teacher:'T',note:'N'});
 seed.sharedRecords=durableRecords([scheduleRecord(slot('keep','other')),scheduleRecord(slot('delete-A','A')),scheduleRecord(slot('stay-A','A')),{typeId:'test.note',recordId:'opaque',schemaVersion:1,required:false,dependencies:[],payload:Buffer.from('opaque')}]);
 const root=await dir(),identity={writerId:randomUUID(),installationId:randomUUID()},remote=new FakeRemote(),a=await openV3Workspace(root,()=>{},identity),engine=new V3Engine(a.journal,identity.writerId);
 await engine.initialize(remote,seed);for(let n=0;n<3;n++){const b=new Budget();try{await engine.round(remote,new IndexedV3Cache(root,b),b,{discoverySteps:32});}catch(e){if(!/incomplete/.test(e.message))throw e;}}
 const edit={target:{sourceId:source.id,series:['A','B','C']},slots:[slot('new-B','B')],deleteIds:['delete-A'],overrides:[{seriesId:'A',action:'upsert',patch:{timingMode:'custom',startMinute:600,endMinute:660}},{seriesId:'B',action:'upsert',patch:{location:'New room'}},{seriesId:'C',action:'upsert',patch:{teacher:'New teacher'}}],common:{note:'Common'},colors:[{seriesId:'A',rgb:0x123456}]};
 const revision=a.workspace.frame.revision,before=businessHash(a.workspace.frame.local),preserved=a.workspace.frame.local.sharedRecords.filter(r=>r.recordId==='keep'||r.typeId==='test.note');
 const bad=structuredClone(edit);bad.overrides[1].patch={timingMode:'custom',startMinute:600,endMinute:500};await assert.rejects(a.workspace.saveCourseEdit(bad,revision));assert.equal(businessHash(a.workspace.frame.local),before);
 await assert.rejects(a.workspace.saveCourseEdit({...edit,target:{sourceId:source.id,series:['A','other']}},revision));assert.equal(businessHash(a.workspace.frame.local),before);
 const originalCommit=a.workspace.store.commitWorkspace;
 a.workspace.store.commitWorkspace=(prepare,context)=>originalCommit.call(a.workspace.store,()=>{prepare();throw Error('injected course commit failure');},context);
 try {await assert.rejects(a.workspace.saveCourseEdit(edit,revision),/injected course commit/);} finally {a.workspace.store.commitWorkspace=originalCommit;}
 assert.equal(businessHash(a.workspace.frame.local),before);assert.equal(a.workspace.frame.revision,revision);
 const preview=a.workspace.previewCourseEdit(edit,revision),result=await a.workspace.saveCourseEdit(edit,revision);assert.equal(result.businessHash,preview.businessHash);assert.equal(a.workspace.frame.revision,revision+1);
 assert.deepEqual(a.workspace.frame.local.sharedRecords.filter(r=>r.recordId==='keep'||r.typeId==='test.note'),preserved);assert.equal(a.workspace.frame.local.classOverrides.length,3);assert.equal(rgb(a.workspace.frame.local,source.id,'C'),0x123456);
 assert(!scheduleSlots(a.workspace.frame.local).some(s=>s.id==='delete-A'));assert(scheduleSlots(a.workspace.frame.local).some(s=>s.id==='stay-A'&&s.note==='Common'));await assert.rejects(a.workspace.saveCourseEdit(edit,revision));
 if(!process.env.PR70_MULTI_VECTOR) assert.equal(businessHash(a.workspace.frame.local),(await readFile(new URL('../../test-vectors/pr70-multi/hash.txt',import.meta.url),'utf8')).trim());
 if(process.env.PR70_MULTI_VECTOR){
  const {projectWeek}=await import('../src/projection.mjs');const state=a.workspace.frame.local;
  await writeFile(join(process.env.PR70_MULTI_VECTOR,'state.dpv'),encode({format:4,type:2,datasetId:'11111111-1111-1111-1111-111111111111',objectId:'22222222-2222-2222-2222-222222222222',writerId:'33333333-3333-3333-3333-333333333333',epochId:'44444444-4444-4444-4444-444444444444',epochNumber:1,descriptorHash:'1'.repeat(64),refs:[],state}));
  await writeFile(join(process.env.PR70_MULTI_VECTOR,'hash.txt'),businessHash(state));
  await writeFile(join(process.env.PR70_MULTI_VECTOR,'projection.json'),JSON.stringify(['2026-09-07','2026-10-12'].flatMap(week=>projectWeek(state,week).map(v=>[v.seriesId,v.start,v.end,v.location,v.teacher,v.note,v.sharedRgb]))));
 }
 const digest=businessHash(a.workspace.frame.local);await a.journal.close();const reopened=await openV3Workspace(root,()=>{},identity);assert.equal(businessHash(reopened.workspace.frame.local),digest);await reopened.journal.close();
});

test('partial override edit preserves persisted fields, rejects incomplete time atomically and reopens',async()=>{
 const {decode:legacy}=await import('../src/protocol.mjs'),{applyEdit}=await import('../src/workspace.mjs');
 const s=legacy(await readFile(new URL('../../schedule-core/src/test/resources/sync-v2/complex-root.dpc',import.meta.url))).state;
 s.sharedRecords=[];s.classOverrides=[];const r=s.imported[0];
 applyEdit(s,{kind:'override',sourceId:r.sourceId,seriesId:r.seriesId,anchorDate:null,action:'upsert',patch:{title:'Preserved title',teacher:'Preserved teacher',location:'Preserved room',note:'Preserved note',timingMode:'custom',startMinute:810,endMinute:920}});
 const root=await dir(),identity={writerId:randomUUID(),installationId:randomUUID()},remote=new FakeRemote(),a=await openV3Workspace(root,()=>{},identity),engine=new V3Engine(a.journal,identity.writerId);
 await engine.initialize(remote,s);for(let n=0;n<3;n++){const b=new Budget();try{await engine.round(remote,new IndexedV3Cache(root,b),b,{discoverySteps:32});}catch(e){if(!/incomplete/.test(e.message))throw e;}}
 const revision=a.workspace.frame.revision,edit={target:{sourceId:r.sourceId,series:[r.seriesId]},overrides:[{seriesId:r.seriesId,action:'upsert',patch:{timingMode:'custom',startMinute:600,endMinute:660}}]};
 const before=businessHash(a.workspace.frame.local),bad=structuredClone(edit);bad.overrides[0].patch={timingMode:'custom',startMinute:600};bad.colors=[{seriesId:r.seriesId,rgb:0x445566}];
 await assert.rejects(a.workspace.saveCourseEdit(bad,revision));assert.equal(businessHash(a.workspace.frame.local),before);
 const preview=a.workspace.previewCourseEdit(edit,revision),saved=await a.workspace.saveCourseEdit(edit,revision);assert.equal(saved.businessHash,preview.businessHash);
 const value=saved.state.classOverrides.find(e=>e.seriesId===r.seriesId);assert.equal(value.teacher,'Preserved teacher');assert.equal(value.location,'Preserved room');assert.equal(value.startMinute,600);
 assert.equal(saved.state.sharedRecords.length,0);await assert.rejects(a.workspace.saveCourseEdit(edit,revision));
 await a.journal.close();const reopened=await openV3Workspace(root,()=>{},identity);assert.equal(businessHash(reopened.workspace.frame.local),saved.businessHash);await reopened.journal.close();
});
