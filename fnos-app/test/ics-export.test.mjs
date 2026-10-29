import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile,writeFile} from 'node:fs/promises';
import ICAL from 'ical.js';
import {decode} from '../src/protocol.mjs';
import {exportIcs} from '../src/ics-export.mjs';
const fixture=async()=>decode(await readFile(new URL('../../schedule-core/src/test/resources/sync-v2/complex-root.dpc',import.meta.url))).state;
const options={start:'2026-10-11',end:'2026-10-26',zone:'Asia/Shanghai',includeEvents:true};
test('共享固定向量：标准解析、精确字节与只读',async()=>{
 const state=await fixture(),before=JSON.stringify(state,(_,v)=>typeof v==='bigint'?String(v):v),r=exportIcs(state,options,0);
 const expected=await readFile(new URL('../../schedule-core/src/test/resources/ics-export.ics',import.meta.url));
 assert.deepEqual(r.bytes,expected);assert.equal(JSON.stringify(state,(_,v)=>typeof v==='bigint'?String(v):v),before);
 const events=new ICAL.Component(ICAL.parse(r.bytes.toString())).getAllSubcomponents('vevent');
 assert.equal(events.length,r.courseCount+r.eventCount);
 assert.equal(new Set(events.map(e=>e.getFirstPropertyValue('uid'))).size,events.length);
 for(const line of r.bytes.toString().split('\r\n'))assert.ok(Buffer.byteLength(line)<=75);
});
test('注入、Unicode 折行、稳定身份、重叠课程和范围拒绝',async()=>{
 const s=await fixture();s.sources=[];s.imported=[];s.classOverrides=[];s.dayAdjustments=[];s.sharedRecords=[];
 s.entries=[{id:'a',title:'中文😀'.repeat(50)+'\r\nX-EVIL:1;,\\',day:1,startMinute:600,endMinute:660,location:'教室;一,二\\三'}];
 const r=exportIcs(s,options,0),parse=b=>new ICAL.Component(ICAL.parse(b.toString())).getAllSubcomponents('vevent');
 const events=parse(r.bytes);assert.equal(events[0].getFirstPropertyValue('summary'),s.entries[0].title.replace('\r\n','\n'));
 assert.equal(events[0].hasProperty('x-evil'),false);
 s.entries.push({...s.entries[0],id:'b'});const both=exportIcs(s,options,0);assert.equal(both.courseCount,r.courseCount*2);
 const ids=parse(both.bytes).map(e=>e.getFirstPropertyValue('uid'));assert.equal(new Set(ids).size,ids.length);
 const old=events[0].getFirstPropertyValue('uid');s.entries[0].title='改名';assert.ok(parse(exportIcs(s,options,0).bytes).some(e=>e.getFirstPropertyValue('uid')===old));
 assert.throws(()=>exportIcs(s,{...options,end:'2028-01-01'}));assert.throws(()=>exportIcs(s,{...options,end:'2026-01-01'}));
});
// The same explicit slot scenario is constructed in IcsScheduleExporterTest.
import {scheduleRecord} from '../src/shared-course-schedule.mjs';
import {durableRecords} from '../src/shared-records.mjs';
function mixed(){
 const slot={id:'slot',sourceId:null,ownerSeries:'x',replacesSeries:'x',week1:'2026-09-07',weeks:[1,3,5],weekday:7,startMinute:1410,endMinute:90,periodStart:0,periodEnd:0,enabled:true,title:'中文😀;课,程\\\nX-EVIL:1',location:'教室;A',teacher:'教师',note:'备注'};
 return {events:[],entries:[{id:'x',title:'旧安排',day:1,startMinute:480,endMinute:540,location:''},{id:'overlap',title:'重叠',day:7,startMinute:1410,endMinute:1439,location:'B'}],sources:[],imported:[],profiles:[],classOverrides:[],dayAdjustments:[{date:'2026-09-09',mode:'weekday',weekday:7,label:''}],sharedRecords:durableRecords([scheduleRecord(slot),scheduleRecord({...slot,id:'copy',replacesSeries:null}),scheduleRecord({...slot,id:'disabled',replacesSeries:null,enabled:false})])};
}
test('复杂安排跨语言字节 oracle：非连续周、替换、复制、停用、补课、跨周午夜',async()=>{
 for(const zone of ['Asia/Shanghai','America/New_York']){
 const s=mixed(),r=exportIcs(s,{start:'2026-09-07',end:'2026-10-12',zone},0);
 const expected=await readFile(new URL(`../../schedule-core/src/test/resources/ics-mixed-${zone==='Asia/Shanghai'?'sh':'ny'}.ics`,import.meta.url));assert.deepEqual(r.bytes,expected);assert.equal(r.courseCount,14);
 }
});
export {mixed};

test('DST 缺口保留实际 Instant',()=>{const s=mixed();s.sharedRecords=[];s.dayAdjustments=[];s.entries=[{id:'dst',title:'DST',day:7,startMinute:150,endMinute:240,location:''}];const r=exportIcs(s,{start:'2026-03-08',end:'2026-03-08',zone:'America/New_York'},0).bytes.toString();assert.ok(r.includes('DTSTART:20260308T073000Z'));assert.ok(r.includes('DTEND:20260308T080000Z'));});
