import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {Workspace,applyEdit} from '../src/workspace.mjs';
import {decode,overrideKey,termKey} from '../src/protocol.mjs';
import {projectWeek,addDays,monday} from '../src/projection.mjs';
import {businessHash} from '../src/v3-protocol.mjs';
import {Temporal} from '@js-temporal/polyfill';
async function fixture(){const s=decode(await readFile(new URL('../../schedule-core/src/test/resources/sync-v2/complex-root.dpc',import.meta.url))).state;s.sharedRecords=[];s.classOverrides=[];s.dayAdjustments=[];return s;}
const workspace=s=>new Workspace({}, {local:s,revision:1,v3:{binding:{format:4}}});
const edit=(s,patch,common={})=>({target:{sourceId:s.imported[0].sourceId,series:[s.imported[0].seriesId]},overrides:[{seriesId:s.imported[0].seriesId,action:'upsert',patch}],common});
test('partial override preserves fields, null/reset explicit, common wins, old applyEdit unchanged',async()=>{
 const s=await fixture(),row=s.imported[0],source=row.sourceId,id=row.seriesId;
 applyEdit(s,{kind:'override',action:'upsert',sourceId:source,seriesId:id,anchorDate:null,patch:{title:'Title',teacher:'Teacher',location:'Room',note:'Note',timingMode:'custom',startMinute:810,endMinute:920}});
 const w=workspace(s),old=s.classOverrides[0];
 let next=w.previewCourseEdit(edit(s,{timingMode:'custom',startMinute:600,endMinute:660}),1).state;
 for(const key of ['title','teacher','location','note'])assert.equal(next.classOverrides[0][key],old[key]);
 assert.equal(next.classOverrides[0].startMinute,600);
 const displayed=projectWeek(next,'2026-10-12').find(r=>r.seriesId===id);assert.deepEqual([displayed.title,displayed.teacher,displayed.location,displayed.note],['Title','Teacher','Room','Note']);
 next=w.previewCourseEdit(edit(s,{teacher:'New'}),1).state;assert.equal(next.classOverrides[0].startMinute,810);assert.equal(next.classOverrides[0].location,'Room');
 next=w.previewCourseEdit(edit(s,{teacher:null}),1).state;assert.equal(next.classOverrides[0].teacher,null);assert.equal(next.classOverrides[0].note,'Note');
 assert.equal(projectWeek(next,'2026-10-12').find(r=>r.seriesId===id).teacher,'');
 next=w.previewCourseEdit(edit(s,{teacher:'Individual'},{teacher:'Common'}),1).state;assert.equal(next.classOverrides[0].teacher,'Common');assert.equal(next.classOverrides[0].location,'Room');
 const reset=edit(s,{});reset.overrides[0].action='reset';assert.equal(w.previewCourseEdit(reset,1).state.classOverrides.length,0);
 for(const patch of [{startMinute:700},{timingMode:'custom',startMinute:700},{startMinute:null},{timingMode:null},{timingMode:'custom',startMinute:600,endMinute:660,periodStart:1}])assert.throws(()=>w.previewCourseEdit(edit(s,patch),1));
 assert.equal(businessHash(w.previewCourseEdit(edit(s,{}),1).state),businessHash(s));
 const legacy=structuredClone(s);applyEdit(legacy,{kind:'override',action:'upsert',sourceId:source,seriesId:id,anchorDate:null,patch:{teacher:'Only'}});assert.equal(legacy.classOverrides[0].location,null);
});
export async function phaseFixture(){
 const s=await fixture(),r=s.imported[0],owner=s.sources.find(v=>v.id===r.sourceId),p=s.profiles.find(p=>p.termKey===termKey(owner));
 p.timezone='America/New_York';p.week1Monday='2026-09-07';
 p.phases=[{effectiveFrom:'2026-09-07',periods:[{periodNumber:9,start:'08:00',end:'08:45'},{periodNumber:10,start:'08:55',end:'09:40'}]}, {effectiveFrom:'2026-09-14',periods:[{periodNumber:1,start:'08:00',end:'08:45'}]}, {effectiveFrom:'2026-09-21',periods:[{periodNumber:9,start:'10:00',end:'10:45'},{periodNumber:10,start:'10:55',end:'11:40'}]}];
 s.imported=[0,14].map((offset,n)=>{const d=addDays(p.week1Monday,offset),start=BigInt(Temporal.PlainDate.from(d).toPlainDateTime('08:00').toZonedDateTime(p.timezone).epochMilliseconds);return {...r,id:'phase-'+n,start,end:start+3600000n,originalStart:start,timezone:p.timezone,periodLabel:'1'};});
 s.sources=[{...owner,occurrenceCount:2,firstStart:s.imported[0].start,lastEnd:s.imported[1].end}];return s;
}
test('linked periods validate actual sparse dates and timezone, not unused middle phase',async()=>{
 const s=await phaseFixture(),request=edit(s,{timingMode:'linked',periodStart:9,periodEnd:10});
 const result=workspace(s).previewCourseEdit(request,1).state;
 const rows=['2026-09-07','2026-09-21'].flatMap(w=>projectWeek(result,w,'Asia/Shanghai')).filter(r=>r.sourceId===s.sources[0].id);
 assert.deepEqual(rows.map(r=>Temporal.Instant.fromEpochMilliseconds(r.start).toZonedDateTimeISO('America/New_York').hour),[8,10]);
 const bad=structuredClone(s);bad.profiles.find(p=>p.timezone==='America/New_York').phases[2].periods=bad.profiles.find(p=>p.timezone==='America/New_York').phases[1].periods;assert.throws(()=>workspace(bad).previewCourseEdit(request,1),/有效日期/);
 const unknown=structuredClone(s);unknown.sources[0].termKey=null;unknown.sources[0].filename='unknown';assert.throws(()=>workspace(unknown).previewCourseEdit(request,1),/有限生效范围/);
});

test('finite replacement validates only selected weeks and mode switch clears mutually exclusive fields',async()=>{
 const s=await phaseFixture(),row=s.imported[0];
 const {scheduleRecord}=await import('../src/shared-course-schedule.mjs'),{durableRecords}=await import('../src/shared-records.mjs');
 const slot={id:'finite',sourceId:row.sourceId,ownerSeries:row.seriesId,replacesSeries:row.seriesId,week1:'2026-09-07',weeks:[1,3],weekday:1,startMinute:480,endMinute:540,periodStart:0,periodEnd:0,enabled:true,title:'Finite',location:'L',teacher:'T',note:'N'};
 s.sharedRecords=durableRecords([scheduleRecord(slot)]);
 const request=edit(s,{timingMode:'linked',periodStart:9,periodEnd:10});const next=workspace(s).previewCourseEdit(request,1).state;
 assert.deepEqual(next.sharedRecords,s.sharedRecords);
 const shared=workspace(s).previewCourseEdit({...request,common:{teacher:'Unified'}},1).state;const {scheduleSlots}=await import('../src/shared-course-schedule.mjs');assert.equal(scheduleSlots(shared)[0].teacher,'Unified');assert.equal(scheduleSlots(shared)[0].note,'N');assert.equal(scheduleSlots(shared)[0].location,'L');
 assert.throws(()=>workspace(s).previewCourseEdit({...request,common:{teacher:null}},1),/继承/);
 const bad=structuredClone(s);bad.sharedRecords=durableRecords([scheduleRecord({...slot,weeks:[1,2,3]})]);assert.throws(()=>workspace(bad).previewCourseEdit(request,1),/有效日期/);
 const linked=workspace(next).previewCourseEdit(edit(next,{timingMode:'custom',startMinute:600,endMinute:660}),1).state.classOverrides[0];assert.equal(linked.periodStart,null);assert.equal(linked.periodEnd,null);
 const clear=workspace(next).previewCourseEdit(edit(next,{timingMode:null,periodStart:null,periodEnd:null}),1).state;assert.equal(clear.classOverrides.length,0);
 assert.equal(workspace(await fixture()).previewCourseEdit(edit(await fixture(),{}, {teacher:null}),1).state.classOverrides.length,0);
});
if(process.env.PR70_PHASE_VECTOR){
 const {writeFile}=await import('node:fs/promises'),{join}=await import('node:path'),{encode}=await import('../src/v3-protocol.mjs');
 const base=await phaseFixture(),valid=workspace(base).previewCourseEdit(edit(base,{timingMode:'linked',periodStart:9,periodEnd:10}),1).state;
 const invalid=structuredClone(base);invalid.profiles.find(p=>p.timezone==='America/New_York').phases[2].periods=invalid.profiles.find(p=>p.timezone==='America/New_York').phases[1].periods;
 for(const [name,state]of [['base',base],['valid',valid],['invalid',invalid]])await writeFile(join(process.env.PR70_PHASE_VECTOR,name+'.dpv'),encode({format:4,type:2,datasetId:'11111111-1111-1111-1111-111111111111',objectId:'22222222-2222-2222-2222-222222222222',writerId:'33333333-3333-3333-3333-333333333333',epochId:'44444444-4444-4444-4444-444444444444',epochNumber:1,descriptorHash:'1'.repeat(64),refs:[],state}));
 await writeFile(join(process.env.PR70_PHASE_VECTOR,'hash.txt'),businessHash(valid));
 await writeFile(join(process.env.PR70_PHASE_VECTOR,'projection.json'),JSON.stringify(['2026-09-07','2026-09-21'].flatMap(w=>projectWeek(valid,w,'Asia/Shanghai')).map(r=>[r.seriesId,r.start,r.end,r.location,r.teacher,r.note,r.sharedRgb])));
}

test('only first phase and timezone boundary use original academic date',async()=>{
 const s=await phaseFixture();s.imported=s.imported.slice(0,1);s.sources[0].occurrenceCount=1;s.sources[0].lastEnd=s.imported[0].end;
 workspace(s).previewCourseEdit(edit(s,{timingMode:'linked',periodStart:9,periodEnd:10}),1);
 const r=s.imported[0],start=BigInt(Temporal.ZonedDateTime.from('2026-09-13T23:30-04:00[America/New_York]').epochMilliseconds);
 Object.assign(r,{start,end:start+3600000n,originalStart:start});Object.assign(s.sources[0],{firstStart:r.start,lastEnd:r.end});
 const next=workspace(s).previewCourseEdit(edit(s,{timingMode:'linked',periodStart:9,periodEnd:10}),1).state;
 const rows=projectWeek(next,'2026-09-07','Asia/Shanghai').filter(v=>v.sourceId===r.sourceId);assert.equal(rows.length,1);assert.equal(rows[0].academicDate,'2026-09-13');
 assert.equal(Temporal.Instant.fromEpochMilliseconds(rows[0].start).toZonedDateTimeISO('America/New_York').hour,8);
});

export async function inheritedMoveFixture(validTarget=false){
 const s=await phaseFixture(),r=s.imported[0],owner=s.sources[0],p=s.profiles.find(p=>p.termKey===termKey(owner));
 s.imported=[{...r,end:r.start+6000000n,periodLabel:'9-10'}];Object.assign(owner,{occurrenceCount:1,firstStart:r.start,lastEnd:s.imported[0].end});
 p.phases=[p.phases[0],{effectiveFrom:'2026-09-09',periods:validTarget?[{periodNumber:9,start:'11:00',end:'11:45'},{periodNumber:10,start:'11:55',end:'12:40'}]:p.phases[1].periods}];
 applyEdit(s,{kind:'override',action:'upsert',sourceId:r.sourceId,seriesId:r.seriesId,anchorDate:null,patch:{teacher:'Kept teacher',location:'Kept room'}});return s;
}
test('weekday-only inherited linked rejects missing target periods instead of saving fallback',async()=>{
 const s=await inheritedMoveFixture(),w=workspace(s),before=businessHash(s);
 assert.throws(()=>w.previewCourseEdit(edit(s,{weekday:4}),1),/有效日期.*节次/);
 assert.equal(businessHash(w.frame.local),before);
 const valid=await inheritedMoveFixture(true),next=workspace(valid).previewCourseEdit(edit(valid,{weekday:4}),1).state;
 const row=projectWeek(next,'2026-09-07','Asia/Shanghai').find(r=>r.sourceId===valid.sources[0].id);
 assert.equal(row.academicDate,'2026-09-10');assert.equal(Temporal.Instant.fromEpochMilliseconds(row.start).toZonedDateTimeISO('America/New_York').hour,11);
 assert.equal(row.teacher,'Kept teacher');assert.equal(row.location,'Kept room');
 const custom=await inheritedMoveFixture();applyEdit(custom,{kind:'override',action:'upsert',sourceId:custom.imported[0].sourceId,seriesId:custom.imported[0].seriesId,anchorDate:null,patch:{timingMode:'custom',startMinute:600,endMinute:660}});
 workspace(custom).previewCourseEdit(edit(custom,{weekday:4}),1);
 const rawCustom=await inheritedMoveFixture();rawCustom.imported[0].periodLabel=null;workspace(rawCustom).previewCourseEdit(edit(rawCustom,{weekday:4}),1);
 const explicit=await inheritedMoveFixture();applyEdit(explicit,{kind:'override',action:'upsert',sourceId:explicit.imported[0].sourceId,seriesId:explicit.imported[0].seriesId,anchorDate:null,patch:{timingMode:'linked',periodStart:9,periodEnd:10}});assert.throws(()=>workspace(explicit).previewCourseEdit(edit(explicit,{weekday:4}),1),/有效日期/);
});

test('inherited finite replacement validates only active weeks and all participating dates',async()=>{
 const s=await inheritedMoveFixture(true),r=s.imported[0],p=s.profiles.find(p=>p.timezone==='America/New_York');
 p.phases.push({effectiveFrom:'2026-09-23',periods:[{periodNumber:1,start:'08:00',end:'08:45'}]});
 const second={...r,id:'second-week',start:r.start+1209600000n,end:r.end+1209600000n,originalStart:r.originalStart+1209600000n};s.imported.push(second);Object.assign(s.sources[0],{occurrenceCount:2,lastEnd:second.end});
 assert.throws(()=>workspace(s).previewCourseEdit(edit(s,{weekday:4}),1),/2026-09-24/);
 const {scheduleRecord}=await import('../src/shared-course-schedule.mjs'),{durableRecords}=await import('../src/shared-records.mjs');
 const slot={id:'replacement-move',sourceId:r.sourceId,ownerSeries:r.seriesId,replacesSeries:r.seriesId,week1:'2026-09-07',weeks:[1],weekday:1,startMinute:480,endMinute:580,periodStart:9,periodEnd:10,enabled:true,title:'Finite',location:'L',teacher:'T',note:'N'};
 s.sharedRecords=durableRecords([scheduleRecord(slot)]);
 const moved=workspace(s).previewCourseEdit(edit(s,{weekday:4}),1).state;assert.equal(projectWeek(moved,'2026-09-07').filter(v=>v.sourceId===r.sourceId).length,1);
 s.sharedRecords=durableRecords([scheduleRecord({...slot,weeks:[1,3]})]);assert.throws(()=>workspace(s).previewCourseEdit(edit(s,{weekday:4}),1),/2026-09-24/);
});
if(process.env.PR70_MOVE_VECTOR){
 const {writeFile}=await import('node:fs/promises'),{join}=await import('node:path'),{encode}=await import('../src/v3-protocol.mjs');
 const invalid=await inheritedMoveFixture(),base=await inheritedMoveFixture(true),valid=workspace(base).previewCourseEdit(edit(base,{weekday:4}),1).state;
 for(const [name,state]of [['base',base],['valid',valid],['invalid',invalid]])await writeFile(join(process.env.PR70_MOVE_VECTOR,name+'.dpv'),encode({format:4,type:2,datasetId:'11111111-1111-1111-1111-111111111111',objectId:'22222222-2222-2222-2222-222222222222',writerId:'33333333-3333-3333-3333-333333333333',epochId:'44444444-4444-4444-4444-444444444444',epochNumber:1,descriptorHash:'1'.repeat(64),refs:[],state}));
 await writeFile(join(process.env.PR70_MOVE_VECTOR,'hash.txt'),businessHash(valid));
 await writeFile(join(process.env.PR70_MOVE_VECTOR,'projection.json'),JSON.stringify(projectWeek(valid,'2026-09-07','Asia/Shanghai').map(r=>[r.seriesId,r.start,r.end,r.location,r.teacher,r.note,r.sharedRgb])));
}

test('same-named cross-source series use their own timezone and phase',async()=>{
 const s=await inheritedMoveFixture(),r=s.imported[0],owner=s.sources[0],profile=s.profiles.find(p=>p.timezone==='America/New_York');
 const secondProfile={...structuredClone(profile),termKey:'move-tokyo',timezone:'Asia/Tokyo',phases:[{effectiveFrom:'2026-09-07',periods:[{periodNumber:9,start:'10:00',end:'10:45'},{periodNumber:10,start:'10:55',end:'11:40'}]}]};
 const start=BigInt(Temporal.ZonedDateTime.from('2026-09-07T10:00+09:00[Asia/Tokyo]').epochMilliseconds),other={...r,id:'tokyo-instance',sourceId:'tokyo-source',timezone:'Asia/Tokyo',start,end:start+6000000n,originalStart:start};
 s.sources.push({...owner,id:other.sourceId,termKey:secondProfile.termKey,timezone:other.timezone,firstStart:other.start,lastEnd:other.end});s.imported.push(other);s.profiles.push(secondProfile);
 assert.throws(()=>workspace(s).previewCourseEdit(edit(s,{weekday:4}),1),/有效日期/);
 const result=workspace(s).previewCourseEdit({target:{sourceId:other.sourceId,series:[other.seriesId]},overrides:[{seriesId:other.seriesId,action:'upsert',patch:{weekday:4}}]},1).state;
 const rows=projectWeek(result,'2026-09-07','Asia/Shanghai');assert.equal(rows.find(v=>v.sourceId===owner.id).academicDate,'2026-09-07');assert.equal(rows.find(v=>v.sourceId===other.sourceId).academicDate,'2026-09-10');
});
