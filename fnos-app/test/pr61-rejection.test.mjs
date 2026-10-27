import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Store } from '../src/store.mjs';
import { Workspace, rejectedBeforePersistence } from '../src/workspace.mjs';
import { fixture } from './fixtures/v3-catalog.mjs';

const entry = day => ({kind:'entry',action:'upsert',value:{id:'stable',title:'draft',day,startMinute:480,endMinute:540,location:''}});
async function v2(t) {
  const dir=await mkdtemp(join(tmpdir(),'dp-pr61-rejection-'));
  t.after(()=>rm(dir,{recursive:true,force:true}));
  return Workspace.open(await new Store(dir).init());
}
for(const protocol of ['v2','v3'])test(`二轮61 ${protocol} 事务准备拒绝可识别且不改变帧或持久化状态`,async t=>{
  const db=protocol==='v2'?await v2(t):(await fixture(t)).db;
  const before=db.snapshot();
  await assert.rejects(db.edit(entry(8),before.revision),error=>rejectedBeforePersistence(error));
  assert.deepEqual(db.snapshot(),before);
  const saved=await db.store.read('workspace.json');assert.deepEqual(saved.local.entries,[]);assert.equal(saved.revision,before.revision);
  await db.edit(entry(1),before.revision);assert.equal(db.frame.revision,before.revision+1);
});
test('二轮61 持久化前后存储失败均不能冒充输入验证拒绝',async t=>{
  const db=await v2(t),save=db.store.save.bind(db.store),before=db.snapshot();
  for(const committed of [false,true]) {
    db.store.save=async(name,value)=>{if(name==='workspace.json'){if(committed)await save(name,value);throw Error('injected storage failure');}return save(name,value);};
    await assert.rejects(db.edit(entry(1),before.revision),error=>!rejectedBeforePersistence(error));
    assert.deepEqual(db.snapshot(),before);
    const saved=await db.store.read('workspace.json');assert.equal(saved.revision,before.revision+(committed?1:0));
  }
});
test('二轮61 重复使用曾被拒绝的 Error 对象也不能误标本次存储失败',async t=>{
  const db=await v2(t);let reused;
  await assert.rejects(db.edit(entry(8),0),error=>{reused=error;return rejectedBeforePersistence(error);});
  db.store.save=async()=>{throw reused;};
  await assert.rejects(db.edit(entry(1),0),error=>error===reused && !rejectedBeforePersistence(error));
});
