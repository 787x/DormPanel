import {termKey} from './protocol.mjs';
import {Temporal} from '@js-temporal/polyfill';
import {wireRecords} from './shared-records.mjs';
const check=v=>{if(!v)throw Error('Invalid course.schedule/1');};
const identity=s=>typeof s==='string'&&/^[A-Za-z0-9][A-Za-z0-9._:-]{0,199}$/.test(s);
export function scheduleRecord(s){
 check(identity(s.id)&&identity(s.ownerSeries)&&s.id!==s.ownerSeries&&(s.sourceId===null||identity(s.sourceId)));
 check(s.replacesSeries===null||s.replacesSeries===s.ownerSeries);
 const d=Temporal.PlainDate.from(s.week1);check(/^[0-9]{4}-[0-9]{2}-[0-9]{2}$/.test(s.week1)&&d.year>=1&&d.year<=9997&&d.toString()===s.week1&&d.dayOfWeek===1);
 check(Array.isArray(s.weeks)&&s.weeks.length<=104&&s.weeks.every((w,i)=>Number.isInteger(w)&&w>=1&&w<=104&&(i===0||w>s.weeks[i-1])));
 check(Number.isInteger(s.weekday)&&s.weekday>=1&&s.weekday<=7);
 check([s.startMinute,s.endMinute].every(v=>Number.isInteger(v)&&v>=0&&v<=1439)&&s.startMinute!==s.endMinute);
 check(s.periodStart===0&&s.periodEnd===0||Number.isInteger(s.periodStart)&&Number.isInteger(s.periodEnd)&&s.periodStart>=1&&s.periodEnd>=s.periodStart&&s.periodEnd<=30);
 check(typeof s.enabled==='boolean'&&typeof s.title==='string'&&s.title.trim().length);
 const parts=[],int=n=>{const b=Buffer.alloc(4);b.writeInt32BE(n);parts.push(b);},text=v=>{check(typeof v==='string');const b=Buffer.from(v,'utf8');check(b.length<=4096);int(b.length);parts.push(b);};
 [s.sourceId??'',s.ownerSeries,s.replacesSeries??'',s.week1].forEach(text);int(s.weeks.length);s.weeks.forEach(int);
 [s.weekday,s.startMinute,s.endMinute,s.periodStart,s.periodEnd,s.enabled?1:0].forEach(int);
 [s.title,s.location,s.teacher,s.note].forEach(text);const payload=Buffer.concat(parts);check(payload.length<=16384);
 return {typeId:'course.schedule',recordId:s.id,schemaVersion:1,required:true,dependencies:[],payload};
}
export function decodeSchedule(r){
 check(r.typeId==='course.schedule'&&r.schemaVersion===1&&r.required&&r.dependencies.length===0);
 const b=typeof r.payload==='string'?Buffer.from(r.payload,'hex'):Buffer.from(r.payload);check(b.length<=16384);let pos=0;
 const int=()=>{check(pos+4<=b.length);const n=b.readInt32BE(pos);pos+=4;return n;},text=()=>{const n=int();check(n>=0&&n<=4096&&pos+n<=b.length);const v=b.subarray(pos,pos+n).toString('utf8');pos+=n;return v;};
 const sourceId=text()||null,ownerSeries=text(),replacesSeries=text()||null,week1=text(),n=int();check(n>=0&&n<=104);const weeks=Array.from({length:n},int);
 const weekday=int(),startMinute=int(),endMinute=int(),periodStart=int(),periodEnd=int(),flag=int();check(flag===0||flag===1);
 const s={id:r.recordId,sourceId,ownerSeries,replacesSeries,week1,weeks,weekday,startMinute,endMinute,periodStart,periodEnd,enabled:flag===1,title:text(),location:text(),teacher:text(),note:text()};
 check(pos===b.length&&scheduleRecord(s).payload.equals(b));return s;
}
export const scheduleSlots=state=>wireRecords(state.sharedRecords??[]).filter(r=>r.typeId==='course.schedule'&&r.schemaVersion===1).map(decodeSchedule);
export function validateScheduleParents(state){
 const local=new Set(state.entries.map(e=>e.id)),sources=new Set(state.sources.map(e=>e.id)),series=new Set(state.imported.map(e=>JSON.stringify([e.sourceId,e.seriesId]))),replacements=new Set();
 const importedIds=new Set(state.imported.map(r=>r.id));
 for(const s of scheduleSlots(state)){
  check(!local.has(s.id)&&!importedIds.has(s.id)&&!series.has(JSON.stringify([s.sourceId,s.id])));
  check(s.sourceId===null?local.has(s.ownerSeries):sources.has(s.sourceId)&&series.has(JSON.stringify([s.sourceId,s.ownerSeries])));
  if(s.enabled&&s.periodStart>0){
   const source=state.sources.find(v=>v.id===s.sourceId),profile=source&&state.profiles.find(p=>p.termKey===termKey(source));check(profile);
   for(const week of s.weeks){const d=Temporal.PlainDate.from(s.week1).add({weeks:week-1,days:s.weekday-1});
    const phase=[...profile.phases].filter(p=>p.effectiveFrom<=d.toString()).sort((a,b)=>a.effectiveFrom.localeCompare(b.effectiveFrom)).at(-1);
    const a=phase?.periods.find(p=>p.periodNumber===s.periodStart)?.start,z=phase?.periods.find(p=>p.periodNumber===s.periodEnd)?.end;check(a&&z);
    check(d.toPlainDateTime(z).toZonedDateTime(profile.timezone).epochMilliseconds>d.toPlainDateTime(a).toZonedDateTime(profile.timezone).epochMilliseconds);
   }
  }
  if(s.replacesSeries!==null){
   if(s.sourceId!==null)check(new Set(state.imported.filter(r=>r.sourceId===s.sourceId&&r.seriesId===s.replacesSeries).map(r=>Temporal.Instant.fromEpochMilliseconds(Number(r.start)).toZonedDateTimeISO(r.timezone).dayOfWeek)).size===1);
   const key=JSON.stringify([s.sourceId,s.replacesSeries]);check(!replacements.has(key));replacements.add(key);}
 }
}
export const courseScheduleAdapter={typeId:'course.schedule',schemaVersion:1,maxPayload:16384,validateState:validateScheduleParents,validate:decodeSchedule,relatedKeys(r){const s=decodeSchedule(r);return [s.sourceId===null?`class:${s.ownerSeries}`:`source:${s.sourceId}`];}};
