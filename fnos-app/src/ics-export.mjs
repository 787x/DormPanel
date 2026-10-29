import {createHash} from 'node:crypto';
import {Temporal} from '@js-temporal/polyfill';
import {projectWeek,monday,addDays} from './projection.mjs';
const text=v=>{const s=String(v??'');if(/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/u.test(s))throw Error('课程文本包含 ICS 不支持的控制字符');return s.replace(/\\/g,'\\\\').replace(/\r\n|\r|\n/g,'\\n').replace(/;/g,'\\;').replace(/,/g,'\\,');};
const stamp=ms=>new Date(Number(ms)).toISOString().replace(/[-:]/g,'').replace(/\.\d{3}Z$/,'Z');
function fold(line){let out='',n=0;for(const c of line){const size=Buffer.byteLength(c);if(n+size>75){out+='\r\n ';n=1;}out+=c;n+=size;}return out;}
export function exportIcs(state,{start,end,zone='Asia/Shanghai',includeEvents=false},now=Date.now()){
 const began=performance.now();
 const a=Temporal.PlainDate.from(start),b=Temporal.PlainDate.from(end);
 if(a.year<1||b.year>9997||!/^\d{4}-\d{2}-\d{2}$/.test(start)||!/^\d{4}-\d{2}-\d{2}$/.test(end)||a.toString()!==start||b.toString()!==end||a.until(b).days<0||a.until(b).days>365)throw Error('日期范围必须为 1–366 天');
 const instant=d=>Number(d.toZonedDateTime({timeZone:zone,plainTime:'00:00'}).epochMilliseconds);
 const min=instant(a),max=instant(b.add({days:1})),rows=new Map();
 for(let first=monday(start);first<=end;first=addDays(first,7)){
  for(const c of projectWeek(state,first,zone))if(c.start<max&&c.end>min){
   const identity=JSON.stringify(['course',c.sourceId??null,c.seriesId,c.id,c.anchorDate]);
   rows.set(identity,{identity,start:c.start,end:c.end,title:c.title,location:c.location,description:[c.teacher,c.note,c.periodLabel].filter(Boolean).join('\n'),kind:'course'});
  }
  if(rows.size>20000||performance.now()-began>5000)throw Error('导出超过事件数或耗时预算，请缩小范围');
 }
 const courseCount=rows.size;
 if(includeEvents)for(const e of state.events)if(e.start<max&&e.end>min){const identity=JSON.stringify(['event',e.id]);rows.set(identity,{...e,identity,description:e.note,kind:'event'});}
 if(rows.size>20000)throw Error('导出超过 20000 个事件，请缩小范围');
 const lines=['BEGIN:VCALENDAR','VERSION:2.0','PRODID:-//DormPanel//Schedule Export 1//ZH','CALSCALE:GREGORIAN'];
 for(const r of [...rows.values()].sort((a,b)=>a.identity<b.identity?-1:a.identity>b.identity?1:0)){
  if(Number(r.end)<=Number(r.start))throw Error('最终课程或事件起止时间无效，无法完整导出');
  if(Number(r.start)%1000!==0||Number(r.end)%1000!==0)throw Error('课程或事件包含亚秒时间，标准 ICS 无法无损表达');
  lines.push('BEGIN:VEVENT',`UID:${createHash('sha256').update(r.identity).digest('hex')}@ics.dormpanel`, `DTSTAMP:${stamp(now)}`,`DTSTART:${stamp(r.start)}`,`DTEND:${stamp(r.end)}`,`SUMMARY:${text(r.title)}`);
  if(r.location)lines.push(`LOCATION:${text(r.location)}`);
  if(r.description)lines.push(`DESCRIPTION:${text(r.description)}`);
  lines.push('END:VEVENT');
 }
 lines.push('END:VCALENDAR');const bytes=Buffer.from(lines.map(fold).join('\r\n')+'\r\n');
 if(bytes.length>8*1024*1024||performance.now()-began>5000)throw Error('导出超过文件大小或耗时预算，请缩小范围');
 return {bytes,courseCount,eventCount:rows.size-courseCount,start,end,zone};
}
