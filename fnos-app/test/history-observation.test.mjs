import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm, readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { Store } from '../src/store.mjs';
import { Workspace } from '../src/workspace.mjs';
import { SyncEngine } from '../src/sync.mjs';
import { emptyState } from '../src/merge.mjs';
import { encode, hash } from '../src/protocol.mjs';
import { loadGraph } from '../src/graph.mjs';
import { LocalGrants } from '../src/local.mjs';
import { HistoryManager, freshGraph, cachedGraph, historySummary } from '../src/history.mjs';
import { json } from '../src/store.mjs';

const course = (id, title = id) => ({ id, title, day:1, startMinute:480, endMinute:540, location:'' });
const business = f => json({ local:f.local, baseline:f.baseline, frontier:f.frontier, datasetId:f.datasetId, pending:f.pending, abandoned:f.abandoned, conflict:f.conflict, receipt:f.receipt });
async function fixture(t) {
  const dir = await mkdtemp(join(tmpdir(),'dp-history-observation-')); t.after(()=>rm(dir,{recursive:true,force:true}));
  const store = await new Store(join(dir,'state')).init();
  const root = { datasetId:randomUUID(), commitId:randomUUID(), writerId:store.identity.writerId, parents:[], state:{...emptyState(),entries:[course('a')]} };
  const tip = {...root,commitId:randomUUID(),parents:[root.commitId],state:{...emptyState(),entries:[course('a','已修改')]}};
  const rows = new Map([root,tip].map(c=>[c.commitId,encode(c)])), reads=[];
  const remote = {key:'test',list:async()=>[...rows.keys()],read:async id=>{reads.push(id);return rows.get(id)??null;},put:async(id,b)=>{assert(!rows.has(id));rows.set(id,b);}};
  const db = await Workspace.open(store,{...await loadGraph(remote),bindingKey:'test'});
  const catalog = {manifest:{revision:0,activeId:'workspace'},store,grants:new LocalGrants([{id:'approved',path:dir}]),remote:()=>remote,connection:()=>({}),row:()=>({connectionId:'test'})};
  const manager = new HistoryManager(catalog), engine = new SyncEngine(db,remote);
  const input = () => ({workspaceId:'workspace',version:db.frame.revision,target:root.commitId,rootId:'approved'});
  return {dir,store,root,tip,rows,reads,remote,db,catalog,manager,engine,input};
}

for (const action of ['check','preview','rollback','archive','compact']) test(`历史 ${action} 保留异常观察，漏列、缓存读取和重启均不能遗忘，修复后核对已确认哈希`, async t=>{
  const f=await fixture(t), before=business(f.db.frame);
  const preview=action==='rollback'?await f.manager.preview(f.db,f.engine,f.input(),'admin'):null;
  const broken={...f.tip,commitId:randomUUID(),parents:[f.tip.commitId]}, original=encode(broken);
  f.rows.set(broken.commitId,Buffer.alloc(250));
  if(action==='check') assert.equal((await f.engine.observe()).status,'incomplete');
  else if(action==='preview') await assert.rejects(f.manager.preview(f.db,f.engine,f.input(),'admin'),/完整|验证/);
  else if(action==='rollback') await assert.rejects(f.manager.rollback(f.db,f.engine,{...f.input(),token:preview.token,confirm:preview.target},'admin'),/变化|完整|验证/);
  else await assert.rejects(f.manager.archive(f.db,f.engine,f.input(),action==='compact'),/完整|验证/);
  assert(f.db.frame.checkpoint.deferred.includes(broken.commitId));
  assert.equal(business(f.db.frame),before);
  await cachedGraph(f.db.frame); // 页面读取缓存不会清除持久化的异常路径。
  const reopened=await Workspace.open(f.store), engine=new SyncEngine(reopened,f.remote), manager=new HistoryManager(f.catalog);
  assert(reopened.frame.checkpoint.deferred.includes(broken.commitId));
  f.remote.list=async()=>[f.root.commitId,f.tip.commitId];
  const input=()=>({...f.input(),version:reopened.frame.revision});
  f.reads.length=0;
  await assert.rejects(manager.preview(reopened,engine,input(),'admin'),/完整|验证/);
  assert(f.reads.includes(broken.commitId));
  f.reads.length=0;
  await assert.rejects(manager.archive(reopened,engine,input(),true),/完整|验证/);
  assert(f.reads.includes(broken.commitId));
  assert.equal(business(reopened.frame),before);
  f.rows.set(broken.commitId,original);
  const p=await manager.preview(reopened,engine,input(),'admin');
  assert(!reopened.frame.checkpoint.deferred.includes(broken.commitId));
  assert.equal(reopened.frame.checkpoint.hashes[broken.commitId],hash(original).toString('hex'));
  assert(p.token);
  f.rows.set(broken.commitId,encode({...broken,writerId:randomUUID()}));
  await assert.rejects(manager.preview(reopened,engine,input(),'admin'),/改变/);
  assert.equal(business(reopened.frame),before);
});

test('失败观察也保存已经验证的新哈希和未开始读取的路径，重启后遗漏列举仍拒绝篡改',async t=>{
  const f=await fixture(t), known={...f.tip,commitId:'00000000-0000-4000-8000-000000000001',parents:[f.tip.commitId]}, broken='ffffffff-ffff-4fff-8fff-ffffffffffff';
  const bytes=encode(known);f.rows.set(known.commitId,bytes);f.rows.set(broken,Buffer.alloc(250));
  const read=f.remote.read;f.remote.read=async id=>{if(id===broken) throw Error('模拟不可恢复读取失败');return read(id);};
  await assert.rejects(f.manager.preview(f.db,f.engine,f.input(),'admin'),/读取失败/);
  assert.equal(f.db.frame.checkpoint.hashes[known.commitId],hash(bytes).toString('hex'));
  assert(f.db.frame.checkpoint.observed.includes(broken));
  const db=await Workspace.open(f.store);f.remote.list=async()=>[f.root.commitId,f.tip.commitId];f.remote.read=read;
  f.rows.set(known.commitId,encode({...known,writerId:randomUUID()}));
  await assert.rejects(freshGraph(db,f.remote),/改变/);
  assert(db.frame.checkpoint.observed.includes(broken));
});

test('观察增量不使正常确认自失效；入口旧版本和业务编辑后复原仍拒绝，身份验证回调保留',async t=>{
  const f=await fixture(t), oldVersion=f.db.frame.revision, p=await f.manager.preview(f.db,f.engine,f.input(),'admin');
  assert(f.db.frame.revision>oldVersion);
  await assert.rejects(f.manager.rollback(f.db,f.engine,{...f.input(),version:oldVersion,token:p.token,confirm:p.target},'admin'),/过期/);
  await f.manager.rollback(f.db,f.engine,{...f.input(),token:p.token,confirm:p.target},'admin');
  assert.equal(f.rows.size,3);
  const p2=await f.manager.preview(f.db,f.engine,f.input(),'admin'), local=structuredClone(f.db.frame.local);
  await f.db.tx(frame=>{frame.local.entries.push(course('temporary'));});await f.db.tx(frame=>{frame.local=local;});
  await assert.rejects(f.manager.rollback(f.db,f.engine,{...f.input(),token:p2.token,confirm:p2.target},'admin'),/过期/);
  const engine=new SyncEngine(f.db,f.remote,()=>{throw Error('拒绝重复绑定身份');});
  await assert.rejects(f.manager.preview(f.db,engine,f.input(),'admin'),/重复绑定/);
});

test('多父合并逐父比较，仅两门变更课程不会成为整份课程新增；根和单父语义保持',async t=>{
  const f=await fixture(t), root={...f.root,state:{...emptyState(),entries:Array.from({length:20},(_,i)=>course('course-'+i))}};
  const left={...root,commitId:randomUUID(),parents:[root.commitId],state:structuredClone(root.state)};left.state.entries[0].title='左修改';
  const right={...root,commitId:randomUUID(),parents:[root.commitId],state:structuredClone(root.state)};right.state.entries[1].title='右修改';
  const merged={...root,commitId:randomUUID(),parents:[left.commitId,right.commitId].sort(),state:structuredClone(left.state)};merged.state.entries[1].title='右修改';
  const rows=new Map([root,left,right,merged].map(c=>[c.commitId,encode(c)]));
  const graph=await loadGraph({list:async()=>[...rows.keys()],read:async id=>rows.get(id)}), summary=historySummary(f.db,graph);
  const row=summary.rows.find(r=>r.id===merged.commitId);assert.deepEqual(row.parents,merged.parents);assert.equal(row.total,null);assert.equal(row.changes,null);
  assert.equal(row.comparisons.length,2);
  for(const d of row.comparisons){assert(merged.parents.includes(d.parentId));assert.equal(d.total,1);assert.equal(d.changes.entries.added,0);assert.equal(d.changes.entries.modified,1);}
  assert.equal(summary.rows.find(r=>r.id===root.commitId).changes.entries.added,20);
  assert.equal(summary.rows.find(r=>r.id===left.commitId).total,1);
});

test('观察中业务编辑后复原仍拒绝，拒绝前保存列举所得的新路径',async t=>{
  const f=await fixture(t), p=await f.manager.preview(f.db,f.engine,f.input(),'admin'), broken=randomUUID();
  const local=structuredClone(f.db.frame.local), list=f.remote.list;
  f.remote.list=async()=>{
    f.remote.list=list;
    await f.db.tx(frame=>{frame.local.entries.push(course('temporary'));});
    await f.db.tx(frame=>{frame.local=local;});
    f.rows.set(broken,Buffer.alloc(250));return list();
  };
  await assert.rejects(f.manager.rollback(f.db,f.engine,{...f.input(),token:p.token,confirm:p.target},'admin'),/变化/);
  assert(f.db.frame.checkpoint.observed.includes(broken));assert.equal(f.db.frame.pending,null);
  const db=await Workspace.open(f.store);f.remote.list=async()=>[f.root.commitId,f.tip.commitId];f.reads.length=0;
  await assert.rejects(f.manager.preview(db,new SyncEngine(db,f.remote),{...f.input(),version:db.frame.revision},'admin'),/完整|验证/);
  assert(f.reads.includes(broken));
});

test('归档最终复查也持久化新异常，不能签发完成标记；后续漏列仍阻止精简', {skip:process.platform!=='linux'},async t=>{
  for(const compact of [false,true]) {
    const f=await fixture(t), broken=randomUUID(), before=business(f.db.frame), folder=f.catalog.grants.protectedFolder.bind(f.catalog.grants), outputs=[];
    f.catalog.grants.protectedFolder=async(...args)=>{const config=await folder(...args);outputs.push(config.relative);f.rows.set(broken,Buffer.alloc(250));return config;};
    await assert.rejects(f.manager.archive(f.db,f.engine,f.input(),compact),/变化/);
    assert(f.db.frame.checkpoint.deferred.includes(broken));
    for(const relative of outputs) await assert.rejects(readFile(join(f.dir,relative,'verified.json')));
    const db=await Workspace.open(f.store);f.remote.list=async()=>[f.root.commitId,f.tip.commitId];f.reads.length=0;
    await assert.rejects(f.manager.archive(db,new SyncEngine(db,f.remote),{...f.input(),version:db.frame.revision},true),/完整/);
    assert(f.reads.includes(broken));assert.equal(business(db.frame),before);
  }
});
