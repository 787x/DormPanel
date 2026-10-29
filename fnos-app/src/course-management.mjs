// 产品查询只返回有界业务视图；正式候选与事务由 Workspace 负责。
import {Registry,wireRecords} from './shared-records.mjs';
import {sharedTypes} from './shared-types.mjs';
import {identity,display} from './shared-course-colors.mjs';
import {scheduleSlots} from './shared-course-schedule.mjs';
import {businessHash} from './v3-protocol.mjs';
import {projectWeek,addDays} from './projection.mjs';
import {Temporal} from '@js-temporal/polyfill';
import {ensure} from './protocol.mjs';
export function courses(db,input={}) {
 const state=db.frame.local, groups=new Map(), slots=scheduleSlots(state);
 const add=(sourceId,seriesId,row)=>{const key=identity(state,sourceId,seriesId);let g=groups.get(key);
 if(!g){g={key,sourceId,series:[],title:row.title,source:state.sources.find(s=>s.id===sourceId)?.displayName??'本地课程',color:display(state,sourceId,seriesId),slotCount:0};groups.set(key,g);}if(!g.series.includes(seriesId))g.series.push(seriesId);};
 for(const e of state.entries)add(null,e.id,e);
 const seen=new Set();for(const r of state.imported){const k=JSON.stringify([r.sourceId,r.seriesId]);if(!seen.has(k)){seen.add(k);add(r.sourceId,r.seriesId,r);}}
 const supported=db.frame.v3?.binding?.format===4;let writable=supported;try{new Registry(sharedTypes).writable(wireRecords(state.sharedRecords??[]));}catch{writable=false;}
 let rows=[...groups.values()].map(g=>({...g,slotCount:g.series.length+slots.filter(s=>s.sourceId===g.sourceId&&g.series.includes(s.ownerSeries)&&s.replacesSeries===null).length,editable:writable&&g.color.status!=='UNSUPPORTED'}));
 if(input.filter)rows=rows.filter(g=>(g.title+g.source).includes(input.filter));rows.sort((a,b)=>a.title.localeCompare(b.title,'zh')||a.key.localeCompare(b.key));
 const offset=Number(input.offset??0),limit=Number(input.limit??40);ensure(Number.isInteger(offset)&&offset>=0&&offset<=50000&&Number.isInteger(limit)&&limit>=1&&limit<=100,'分页超限');
 const status={version:db.frame.revision,datasetId:db.frame.datasetId,businessHash:businessHash(state),supported};
 if(input.key){const g=groups.get(input.key);ensure(g,'课程不存在');const owned=s=>s.sourceId===g.sourceId&&g.series.includes(s.ownerSeries);const all=slots.filter(owned);
 return {...status,course:rows.find(r=>r.key===g.key)??g,slots:all.slice(offset,offset+limit),total:all.length,next:offset+limit<all.length?offset+limit:null,
 baselines:g.series.map(seriesId=>{const r=g.sourceId===null?state.entries.find(e=>e.id===seriesId):state.imported.find(e=>e.sourceId===g.sourceId&&e.seriesId===seriesId);return {seriesId,...r};}).map(({id,sourceId,seriesId,title,day,startMinute,endMinute,location,teacher,note})=>({seriesId:seriesId??id,title,day,startMinute,endMinute,location,teacher,note})),
 overrides:state.classOverrides.filter(o=>o.anchorDate===null&&o.sourceId===g.sourceId&&g.series.includes(o.seriesId))};}
 return {...status,rows:rows.slice(offset,offset+limit),total:rows.length,next:offset+limit<rows.length?offset+limit:null};
}
export function previewCourse(db,edit,version) {
 const before=db.frame.local,p=db.previewCourseEdit(edit,version),after=p.state;
 const changes=[];
 for(const table of ['classOverrides','sharedRecords']){const key=r=>r.key??r.typeId+':'+r.recordId;const a=new Map(before[table].map(r=>[key(r),r])),b=new Map(after[table].map(r=>[key(r),r]));for(const id of new Set([...a.keys(),...b.keys()]))if(JSON.stringify(a.get(id))!==JSON.stringify(b.get(id)))changes.push({kind:table,action:!a.has(id)?'新增':!b.has(id)?'移除':'修改',id});}
 const projection=[];let total=0;
 const starts=new Set([...before.profiles.map(p=>p.week1Monday),...before.imported.filter(r=>r.sourceId===edit.target.sourceId&&edit.target.series.includes(r.seriesId)).map(r=>{const d=Temporal.Instant.fromEpochMilliseconds(Number(r.start)).toZonedDateTimeISO(r.timezone).toPlainDate();return d.subtract({days:d.dayOfWeek-1}).toString();}),...(edit.slots??[]).map(s=>s.week1),...scheduleSlots(before).filter(s=>s.sourceId===edit.target.sourceId&&edit.target.series.includes(s.ownerSeries)).map(s=>s.week1)]);
 // 每个显式学期最多 104 周；业务明细最多返回 100 条。
 const ordered=[...starts].sort();const windows=ordered.filter((d,i)=>!ordered.slice(0,i).some(a=>d<addDays(a,728)));ensure(windows.length<=8,'预览学期数量超限');
 for(const start of windows)for(let week=0;week<104;week++){const d=addDays(start,week*7),a=projectWeek(before,d),b=projectWeek(after,d);const key=r=>JSON.stringify([r.id,r.start,r.end,r.title,r.location,r.teacher,r.note,r.rgb]);const aa=new Set(a.map(key)),bb=new Set(b.map(key));for(const [rows,other,action]of [[a,bb,'移除'],[b,aa,'新增']])for(const row of rows)if(!other.has(key(row))){total++;if(projection.length<100)projection.push({action,...row});}}
 return {businessHash:p.businessHash,baseHash:businessHash(before),version,changes:changes.slice(0,100),changeCount:changes.length,projection,total,colors:edit.colors??[],common:edit.common??{},slots:edit.slots??[],deleteIds:edit.deleteIds??[],projectionScope:windows.map(start=>({start,end:addDays(start,727)})),unboundedLocal:edit.target.sourceId===null,warning:'本地无限重复课程的范围外结果不作穷举。移除替换规则可能恢复原始课程；停用替换仍抑制原始系列。有限替换范围外不再上课。指定周例外保持原锚点。'};
}
