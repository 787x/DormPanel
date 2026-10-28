import test from 'node:test';
import assert from 'node:assert/strict';
import {encode,decode,sha} from '../src/v3-protocol.mjs';
import {readFile} from 'node:fs/promises';
import {rgb,display,identity,change} from '../src/shared-course-colors.mjs';
import {emptyState} from '../src/merge.mjs';
test('valid SHA remote object with malformed known color is rejected',async()=>{
 const bytes=await readFile(new URL('../../test-vectors/sync-v3-shared/genesis.dpv',import.meta.url));
 const body=Buffer.from(bytes.subarray(0,-32));const marker=Buffer.from([0,0,0,4,1,1,2,3]);const pos=body.indexOf(marker);assert(pos>=0);body[pos+4]=2;
 const raw=Buffer.concat([body,Buffer.from(sha(body),'hex')]);assert.throws(()=>decode(raw));
});
test('future optional color displays fallback without becoming automatic or writable',()=>{
 const s={...emptyState(),entries:[{id:'x',title:'x',day:1,startMinute:480,endMinute:540,location:''}],sharedRecords:[]};
 const recordId=identity(s,null,'x');s.sharedRecords=[{typeId:'course.color',recordId,schemaVersion:2,required:false,dependencies:[],payload:''}];
 assert.equal(display(s,null,'x').status,'UNSUPPORTED');assert.equal(typeof rgb(s,null,'x'),'number');assert.throws(()=>change(s,null,'x',null));
});
