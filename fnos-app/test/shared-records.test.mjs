import test from 'node:test';
import assert from 'node:assert/strict';
import { encode, decode, merge, Registry, courseColor } from '../src/shared-records.mjs';
const color = rgb => ({typeId:'course.color',recordId:'local-1',schemaVersion:1,required:false,dependencies:[],payload:Buffer.from([1,...rgb])});
const extra = {typeId:'test.note',recordId:'one',schemaVersion:1,required:false,dependencies:[],payload:Buffer.from('opaque')};
test('canonical framing, duplicate and limits',()=>{
 const bytes=encode([extra,color([1,2,3])]);
 assert.deepEqual(encode(decode(bytes)),bytes);
 assert.deepEqual(encode([color([1,2,3]),extra]),bytes);
 assert.throws(()=>encode([extra,extra]));
 assert.throws(()=>encode([{...extra,schemaVersion:0}]));
 assert.throws(()=>encode([{...extra,payload:Buffer.alloc(65537)}]));
 assert.throws(()=>decode(Buffer.concat([bytes,Buffer.from([0])])));
});
test('older registry preserves unknown optional bytes while editing another record',()=>{
 const registry=new Registry([courseColor]);
 const before=decode(encode([extra,color([1,2,3])]));
 const after=registry.replace(before,color([4,5,6]));
 assert.deepEqual(after.find(r=>r.typeId==='test.note').payload,extra.payload);
 assert.throws(()=>registry.replace([{...extra,required:true}],color([4,5,6])));
 assert.throws(()=>registry.replace([{...extra,dependencies:['future.type']}],color([4,5,6])));
 assert.throws(()=>registry.replace(before,{...extra,payload:Buffer.from('overwrite')}));
});
test('default merge handles independent edits, conflicts and deletion',()=>{
 const a=color([1,2,3]),b=color([4,5,6]),c=color([7,8,9]);
 assert.equal(merge([a],[b],[a,extra]).records.length,2);
 assert.equal(merge([a],[b],[c]).conflicts.length,1);
 assert.equal(merge([a],[],[b]).conflicts.length,1);
 assert.deepEqual(merge([a],[],[a]).records,[]);
 assert.equal(merge([extra],[{...extra,payload:Buffer.from('left')}],[{...extra,payload:Buffer.from('right')}]).conflicts.length,1);
});
test('a second type registers without editing codec',()=>{
 const note={typeId:'test.note',schemaVersion:1,maxPayload:32,validate:r=>assert.ok(r.payload.length>0)};
 const registry=new Registry([courseColor,note]);
 assert.deepEqual(decode(encode(registry.replace([],extra)))[0],extra);
});
import { businessHash, automaticColor } from '../src/shared-records.mjs';
const vector='4450585200000001000000020000000c636f757273652e636f6c6f72000000076c6f63616c2d31000000010000000000000000000000040101020300000009746573742e6e6f7465000000036f6e65000000010000000000000000000000066f7061717565';
test('cross-language fixed bytes and record-block hash',()=>{
 assert.equal(encode([extra,color([1,2,3])]).toString('hex'),vector);
 assert.equal(businessHash([extra,color([1,2,3])]),'426234c80e57aade8b5af6d5df87cc997c31c09cb7209790fef3906cd97aa055');
 assert.equal(automaticColor('local-1').toString('hex'),'59e27c');
});
