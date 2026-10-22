// 以真实 HistoryManager 回退链路生成合成向量，供未修改的 Android Sync v2 核心验证。
import { readFile, writeFile, mkdir, mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Store } from '../src/store.mjs';
import { Workspace } from '../src/workspace.mjs';
import { SyncEngine } from '../src/sync.mjs';
import { loadGraph } from '../src/graph.mjs';
import { decode } from '../src/protocol.mjs';
import { HistoryManager } from '../src/history.mjs';
const target = new URL('../../schedule-core/src/test/resources/pr46/', import.meta.url), dir = await mkdtemp(join(tmpdir(), 'pr46-vector-'));
try {
  const rows = new Map();
  for (const name of ['complex-root','left']) { const b = await readFile(new URL(`../../schedule-core/src/test/resources/sync-v2/${name}.dpc`, import.meta.url)); rows.set(decode(b).commitId,b); }
  const remote = { key:'vector', list:async()=>[...rows.keys()], read:async id=>rows.get(id)??null, put:async(id,b)=>rows.set(id,b) };
  const store = await new Store(dir).init(), graph = await loadGraph(remote), db = await Workspace.open(store,graph), root = [...rows.keys()].find(id=>!decode(rows.get(id)).parents.length);
  const catalog = { manifest:{revision:0,activeId:'vector'}, remote:()=>remote, row:()=>({connectionId:'vector'}), connection:()=>({}) };
  const manager = new HistoryManager(catalog), engine = new SyncEngine(db,remote), input = { workspaceId:'vector', version:db.frame.revision,target:root };
  const p = await manager.preview(db,engine,input,'vector-admin');
  await manager.rollback(db,engine,{...input,version:db.frame.revision,token:p.token,confirm:root},'vector-admin');
  await mkdir(target,{recursive:true});
  await writeFile(new URL('root.dpc',target),rows.get(root));
  await writeFile(new URL('current.dpc',target),rows.get(graph.tips[0]));
  await writeFile(new URL('rollback.dpc',target),rows.get(db.frame.receipt.commitId));
  console.log('PR46 真实回退链路合成向量已生成');
} finally { await rm(dir,{recursive:true,force:true}); }
