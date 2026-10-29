// Explicit synthetic Android testbed fixture only; no WebDAV or daily workspace access.
import {readFile,writeFile,mkdtemp} from 'node:fs/promises';
import {join} from 'node:path';import {tmpdir} from 'node:os';import {randomUUID} from 'node:crypto';
import assert from 'node:assert/strict';
import {decode,encode,businessHash} from '../src/v3-protocol.mjs';
import {openV3Workspace} from '../src/v3-workspace.mjs';
import {V3Engine} from '../src/v3-engine.mjs';
import {scheduleSlots} from '../src/shared-course-schedule.mjs';
import {rgb} from '../src/shared-course-colors.mjs';
import {projectWeek} from '../src/projection.mjs';
const [input,hashFile,output]=process.argv.slice(2);assert(input&&hashFile&&output);
const object=decode(await readFile(input)),expected=(await readFile(hashFile,'utf8')).trim();assert.equal(businessHash(object.state),expected);
const root=await mkdtemp(join(tmpdir(),'pr70-room-')),identity={writerId:randomUUID(),installationId:randomUUID()};
const store=await openV3Workspace(root,()=>{},identity),engine=new V3Engine(store.journal,identity.writerId);
const remote={objects:new Map(),async read(path){return this.objects.get(path)??null;},async putOnce(path,bytes){assert(!this.objects.has(path));this.objects.set(path,bytes);}};
try{
 await engine.initialize(remote,object.state);
 const slots=scheduleSlots(object.state);assert(slots.length>0);assert.equal(rgb(object.state,null,'x'),0x336699);
 const changed=slots.map(s=>({...s,location:'Node 共享教室',note:'Node 事务确认'}));
 await store.workspace.saveCourseSchedules(changed,{sourceId:null,seriesId:'x',rgb:0x8844cc},store.workspace.frame.revision,{sourceId:null,ownerSeries:'x',deleteIds:[]});
 const state=store.workspace.frame.local;assert.equal(scheduleSlots(state)[0].location,'Node 共享教室');
 assert(projectWeek(state,'2026-09-07').some(r=>r.location==='Node 共享教室'));
 await writeFile(output,encode({...object,state}));
 console.log(JSON.stringify({phoneHash:expected,nodeHash:businessHash(state),slots:changed.length,workspace:root}));
}finally{await store.journal.close();}
