import {overrideKey} from '../src/protocol.mjs';
import {businessHash} from '../src/v3-protocol.mjs';
import test from 'node:test';
import assert from 'node:assert/strict';
import {scheduleRecord,decodeSchedule,courseScheduleAdapter} from '../src/shared-course-schedule.mjs';
import {Registry,merge,durableRecords} from '../src/shared-records.mjs';
import {projectWeek} from '../src/projection.mjs';
import {readFileSync} from 'node:fs';
const slot={id:'slot-1',sourceId:null,ownerSeries:'x',replacesSeries:null,week1:'2026-09-07',weeks:[1,3,5],weekday:2,startMinute:480,endMinute:540,periodStart:0,periodEnd:0,enabled:true,title:'Math',location:'A',teacher:'T',note:'N'};
test('strict canonical schedule payload and required capability',()=>{
 const r=scheduleRecord(slot);assert.equal(r.payload.toString('hex'),readFileSync(new URL('../../test-vectors/course-schedule/payload.hex',import.meta.url),'utf8').trim());assert.deepEqual(decodeSchedule(r),slot);
 assert.throws(()=>new Registry([]).writable([r]));new Registry([courseScheduleAdapter]).writable([r]);
 assert.throws(()=>scheduleRecord({...slot,weeks:[3,1]}));assert.throws(()=>scheduleRecord({...slot,id:"x"}));
 assert.throws(()=>decodeSchedule({...r,payload:Buffer.concat([r.payload,Buffer.from([0])])}));
 assert.equal(merge([r],[],[scheduleRecord({...slot,location:'B'})]).conflicts.length,1);
});
test('noncontinuous weeks and unchanged baseline projection',()=>{
 const state={entries:[{id:'x',title:'Math',day:1,startMinute:480,endMinute:540,location:''}],sources:[],imported:[],profiles:[],classOverrides:[],dayAdjustments:[],events:[],sharedRecords:durableRecords([scheduleRecord(slot)])};
 assert.equal(businessHash({...state,customProfileKeys:[]}),readFileSync(new URL('../../test-vectors/course-schedule/business-hash.txt',import.meta.url),'utf8').trim());
 assert.equal(projectWeek(state,'2026-09-07').length,2);assert.equal(projectWeek(state,'2026-09-14').length,1);
});

test('replacement preserves original weekly anchor; copied slots do not inherit exceptions',()=>{
 const replace={...slot,replacesSeries:'x'},copy={...slot,id:'copy',weekday:3};
 const state={entries:[{id:'x',title:'Math',day:1,startMinute:480,endMinute:540,location:''}],sources:[],imported:[],profiles:[],classOverrides:[{key:overrideKey(null,'x','2026-09-07'),sourceId:null,seriesId:'x',anchorDate:'2026-09-07',location:'weekly'}],dayAdjustments:[],events:[],sharedRecords:durableRecords([scheduleRecord(replace),scheduleRecord(copy)])};
 const rows=projectWeek(state,'2026-09-07');assert.equal(rows.length,2);assert.equal(rows.find(r=>r.seriesId==='x').location,'weekly');assert.equal(rows.find(r=>r.seriesId==='copy').location,'A');
 state.sharedRecords=durableRecords([scheduleRecord({...replace,enabled:false})]);assert.equal(projectWeek(state,'2026-09-07').length,0);
 state.sharedRecords=durableRecords([scheduleRecord({...replace,startMinute:1380,endMinute:60})]);const night=projectWeek(state,'2026-09-07')[0];assert.equal(night.end-night.start,7200000);
});

import {validateScheduleParents} from '../src/shared-course-schedule.mjs';
test('source lifecycle rejects missing or renamed series, preserves exact surviving identity',()=>{
 const base={entries:[],sources:[{id:'s'}],imported:[{id:'raw',sourceId:'s',seriesId:'x',start:Date.parse('2026-09-08T00:00:00Z'),timezone:'Asia/Shanghai'}],profiles:[],sharedRecords:durableRecords([scheduleRecord({...slot,sourceId:'s'}),scheduleRecord({...slot,id:'replacement',sourceId:'s',replacesSeries:'x'})])};
 validateScheduleParents(base);validateScheduleParents({...base,imported:base.imported.map(r=>({...r,title:'new'}))});
 assert.throws(()=>validateScheduleParents({...base,imported:base.imported.map(r=>({...r,seriesId:'unknown'}))}),/请先/);
 assert.throws(()=>validateScheduleParents({...base,sources:[],imported:[]}),/请先/);
 validateScheduleParents({...base,sources:[],imported:[],sharedRecords:[]});
});
