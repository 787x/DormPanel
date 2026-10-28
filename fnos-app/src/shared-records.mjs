// DPXR/1 candidate record block. Deliberately not accepted by legacy DPV3/3.
import { createHash } from 'node:crypto';
const check=(v,m='Invalid shared record')=>{if(!v)throw new Error(m);};
const name=s=>typeof s==='string' && /^[a-z][a-z0-9]*(?:[.-][a-z0-9]+)+$/.test(s) && s.length<=96;
const id=s=>typeof s==='string' && /^[A-Za-z0-9][A-Za-z0-9._:-]{0,199}$/.test(s);
const integer=n=>Number.isInteger(n)&&n>0&&n<=2147483647;
const i=n=>{const b=Buffer.alloc(4);b.writeInt32BE(n);return b;};
const blob=b=>Buffer.concat([i(b.length),b]);
const text=s=>blob(Buffer.from(s,'ascii'));
const key=r=>`${r.typeId}/${r.recordId}`;
const order=(a,b)=>a<b?-1:a>b?1:0;
function validate(r){
 check(name(r.typeId)&&id(r.recordId)&&integer(r.schemaVersion));
 check(typeof r.required==='boolean' && Array.isArray(r.dependencies)&&r.dependencies.length<=16);
 check(r.dependencies.every(name)&&new Set(r.dependencies).size===r.dependencies.length);
 check(Buffer.isBuffer(r.payload)&&r.payload.length<=65536);
}
function row(r){validate(r);return Buffer.concat([text(r.typeId),text(r.recordId),i(r.schemaVersion),i(r.required?1:0),i(r.dependencies.length),...r.dependencies.toSorted(order).map(text),blob(r.payload)]);}
export function encode(records){
 check(Array.isArray(records)&&records.length<=4096);
 check(new Set(records.map(key)).size===records.length);
 records.forEach(validate);
 check(12+records.reduce((n,r)=>n+24+r.typeId.length+r.recordId.length+r.dependencies.reduce((m,d)=>m+4+d.length,0)+r.payload.length,0)<=1048576);
 const bytes=Buffer.concat([Buffer.from('DPXR'),i(1),i(records.length),...records.toSorted((a,b)=>order(a.typeId,b.typeId)||order(a.recordId,b.recordId)).map(row)]);
 check(bytes.length<=1048576,'Shared records byte budget');return bytes;
}
export function decode(bytes){
 check(Buffer.isBuffer(bytes)&&bytes.length>=12&&bytes.length<=1048576);
 let p=0;
 const take=n=>{check(Number.isInteger(n)&&n>=0&&n<=bytes.length-p);const v=bytes.subarray(p,p+n);p+=n;return v;};
 const num=()=>take(4).readInt32BE();
 const count=max=>{const n=num();check(n>=0&&n<=max);return n;};
 const str=()=>{const b=take(count(200));check(b.every(v=>v<128));return b.toString('ascii');};
 check(take(4).toString()==='DPXR'&&num()===1);
 const records=Array.from({length:count(4096)},()=>{
  const typeId=str(),recordId=str(),schemaVersion=num(),flag=num();check(flag===0||flag===1);
  const dependencies=Array.from({length:count(16)},str),payload=Buffer.from(take(count(65536)));
  return {typeId,recordId,schemaVersion,required:flag===1,dependencies,payload};
 });
 check(p===bytes.length&&encode(records).equals(bytes),'Noncanonical shared records');return records;
}
export const businessHash=records=>createHash('sha256').update(encode(records)).digest('hex');
const same=(a,b)=>a===undefined||b===undefined?a===b:row(a).equals(row(b));
export function merge(base,local,remote){
 [base,local,remote].forEach(encode);
 const [b,l,r]=[base,local,remote].map(rows=>new Map(rows.map(v=>[key(v),v])));
 const records=[],conflicts=[];
 for(const k of [...new Set([...b.keys(),...l.keys(),...r.keys()])].sort()){
  const bv=b.get(k),lv=l.get(k),rv=r.get(k);
  const value=same(lv,rv)?lv:same(lv,bv)?rv:same(rv,bv)?lv:undefined;
  if(!same(lv,rv)&&!same(lv,bv)&&!same(rv,bv))conflicts.push({key:k,base:bv,local:lv,remote:rv});
  else if(value)records.push(value);
 }
 encode(records);return {records,conflicts};
}
export class Registry{
 constructor(adapters){this.adapters=new Map();for(const a of adapters){check(name(a.typeId)&&integer(a.schemaVersion)&&!this.adapters.has(a.typeId));check(Number.isInteger(a.maxPayload)&&a.maxPayload>=0&&a.maxPayload<=65536);check(Number.isInteger(a.maxRecords??4096)&&(a.maxRecords??4096)>=0&&(a.maxRecords??4096)<=4096);this.adapters.set(a.typeId,a);}}
 supported(r){return this.adapters.get(r.typeId)?.schemaVersion===r.schemaVersion;}
 validate(records){encode(records);for(const a of this.adapters.values())check(records.filter(r=>r.typeId===a.typeId).length<=(a.maxRecords??4096));for(const r of records){if(this.supported(r)){const a=this.adapters.get(r.typeId);check(r.payload.length<=a.maxPayload);a.validate(r,records);}}}
 writable(records){this.validate(records);for(const r of records){check(!r.required||this.supported(r),'Unsupported required capability');check(r.dependencies.every(t=>this.adapters.has(t)&&records.filter(v=>v.typeId===t).every(v=>this.supported(v))),'Unknown dependency');}}
 replace(records,record){this.writable(records);check(records.filter(r=>key(r)===key(record)).every(r=>this.supported(r)),'Unsupported existing schema');check(this.supported(record),'Unregistered business write');const next=[...records.filter(r=>key(r)!==key(record)),record];this.writable(next);return decode(encode(next));}
 remove(records,typeId,recordId){this.writable(records);const r=records.find(r=>r.typeId===typeId&&r.recordId===recordId);check(!r||this.supported(r),'Unregistered business delete');const next=records.filter(v=>v!==r);this.writable(next);return decode(encode(next));}
}
export const courseColor={typeId:'course.color',schemaVersion:1,maxPayload:4,validate:r=>check(/^[0-9a-f]{64}$/.test(r.recordId)&&(r.payload.length===1&&r.payload[0]===0||r.payload.length===4&&r.payload[0]===1))};
// SHA-256 of stable logical identity, independent of local palette allocation history.
export const automaticColor=recordId=>{check(id(recordId));return createHash('sha256').update(Buffer.from(recordId,'ascii')).digest().subarray(0,3);};


export const wireRecords=records=>records.map(r=>({...r,payload:Buffer.from(r.payload,'hex')}));
export const durableRecords=records=>decode(encode(records)).map(r=>({...r,payload:r.payload.toString('hex')}));
export function canonicalRecords(records){check(records.every(r=>typeof r.payload==='string'&&/^(?:[0-9a-f]{2})*$/.test(r.payload)));return durableRecords(wireRecords(records));}
