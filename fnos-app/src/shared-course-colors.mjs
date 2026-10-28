import {createHash} from 'node:crypto';
import {Registry,courseColor,wireRecords,durableRecords,automaticColor} from './shared-records.mjs';
const check=v=>{if(!v)throw Error('Invalid course color operation');};
export function legacyKey(state,sourceId,seriesId){
 const frame=(...parts)=>parts.map(s=>`${s.length}:${s}`).join('');
 if(sourceId===null){check(state.entries.some(e=>e.id===seriesId));return frame('local',seriesId);}
 const rows=state.imported.filter(r=>r.sourceId===sourceId&&r.seriesId===seriesId);check(rows.length);
 return new Set(rows.map(r=>r.title)).size>1?frame('ambiguous',sourceId,seriesId):frame('import',sourceId,rows[0].title);
}
export const identity=(state,sourceId,seriesId)=>createHash('sha256').update(legacyKey(state,sourceId,seriesId),'utf8').digest('hex');
export function change(state,sourceId,seriesId,rgb){
 check(state.sharedRecords!==undefined && (rgb===null||Number.isInteger(rgb)&&rgb>=0&&rgb<=0xffffff));
 const recordId=identity(state,sourceId,seriesId),payload=rgb===null?Buffer.from([0]):Buffer.from([1,rgb>>16,rgb>>8,rgb]);
 const validated=new Registry([courseColor]).replace([],{typeId:'course.color',recordId,schemaVersion:1,required:false,dependencies:[],payload});
 const rows=[...wireRecords(state.sharedRecords).filter(r=>r.typeId!=='course.color'||r.recordId!==recordId),...validated];
 return {...state,sharedRecords:durableRecords(rows)};
}
export function rgb(state,sourceId,seriesId){
 const id=identity(state,sourceId,seriesId),r=wireRecords(state.sharedRecords??[]).find(r=>r.typeId==='course.color'&&r.recordId===id);
 if(r){check(r.schemaVersion===1);courseColor.validate(r);}
 const b=!r||r.payload[0]===0?automaticColor(id):r.payload.subarray(1);
 return b.readUIntBE(0,3);
}

export function parents(state){
 const result=new Map(),frame=(...parts)=>parts.map(s=>`${s.length}:${s}`).join('');
 const add=(key,parent)=>{const id=createHash('sha256').update(key,'utf8').digest('hex');if(!result.has(id))result.set(id,new Set());result.get(id).add(parent);};
 for(const e of state.entries)add(frame('local',e.id),`class:${e.id}`);
 const series=new Map();for(const r of state.imported){const key=JSON.stringify([r.sourceId,r.seriesId]);if(!series.has(key))series.set(key,[]);series.get(key).push(r);}
 for(const rows of series.values()){const r=rows[0];add(new Set(rows.map(v=>v.title)).size>1?frame('ambiguous',r.sourceId,r.seriesId):frame('import',r.sourceId,r.title),`source:${r.sourceId}`);}
 return result;
}
export const courseColorAdapter={...courseColor,prepareRelations:parents,relatedKeys(record,state,index){return [...(index??parents(state)).get(record.recordId)??[]];}};
