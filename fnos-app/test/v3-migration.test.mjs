import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { Store } from '../src/store.mjs';
import { Catalog } from '../src/catalog.mjs';
import { SyncEngine } from '../src/sync.mjs';
import { decode, encode } from '../src/protocol.mjs';
import { decode as decodeV3, stateBytes } from '../src/v3-protocol.mjs';
import { inspectV2ForV3, V3DatasetMigration } from '../src/v3-migration.mjs';

async function fixture(t) {
  const directory = await mkdtemp(join(tmpdir(),'pr55-migration-')), store = await new Store(directory).init();
  const rows = new Map(), source = { key:'https://isolated.test/dormpanel-sync-v2/', writes:0,
    list:async () => [...rows.keys()], read:async id => rows.get(id) ?? null,
    put:async (id,raw) => { assert(!rows.has(id)); source.writes++; rows.set(id,Buffer.from(raw)); } };
  const objects = new Map(), target = { writes:0, lose:false,
    list:async scope => [...new Set([...objects.keys()].filter(p => p.startsWith(scope)).map(p => { const s=p.slice(scope.length),i=s.indexOf('/'); return scope+(i<0?s:s.slice(0,i+1)); }))].sort(),
    read:async p => objects.get(p) ?? null,
    putOnce:async (p,b) => { assert(!objects.has(p)); target.writes++; objects.set(p,Buffer.from(b)); if (target.lose) { target.lose=false; throw Error('lost response'); } } };
  const options = { store,configPath:join(directory,'config.json'), remoteFactory:() => source, v3RemoteFactory:() => target };
  const f = { catalog:await Catalog.open(options),source,rows,target,objects,options };
  t.after(async () => { if (!f.catalog.invalidated) await f.catalog.close(); });
  const manage = input => f.catalog.manage({ catalogVersion:f.catalog.manifest.revision,...input });
  const { connectionId } = await manage({ action:'connection.save',name:'test',type:'webdav',url:'https://isolated.test/',username:'test',password:'test' });
  const { workspaceId } = await manage({ action:'workspace.add',name:'source',connectionId }); f.id=workspaceId;
  const raw = await readFile(new URL('../../schedule-core/src/test/resources/sync-v3/commit-complex.dpv',import.meta.url));
  f.original=decodeV3(raw).state; const db = f.catalog.db(f.id);
  await db.tx(frame => { frame.local=f.original; }); await new SyncEngine(db,source).synchronize({ create:true });
  f.service = () => new V3DatasetMigration(f.catalog);
  f.reopen = async () => { await f.catalog.close(); f.catalog=await Catalog.open(options); };
  return f;
}
test('v2→v3：256 提交、完整八类 RAW 与未发布本地修改，不发布新 v2 提交', async t => {
  const f=await fixture(t), db=f.catalog.db(f.id); let tip=decode([...f.rows.values()][0]);
  for (let n=1;n<256;n++) { tip={ ...tip,commitId:randomUUID(),parents:[tip.commitId] }; f.rows.set(tip.commitId,encode(tip)); }
  await db.edit({ kind:'entry',action:'upsert',value:{ id:'offline',title:'unpublished',day:1,startMinute:600,endMinute:660,location:'' } });
  const originalSource = await readFile(join(db.store.dir,'workspace.json'));
  const preview=await f.service().preview(f.id); assert.equal(Object.keys(preview.evidence.hashes).length,256);
  const prepared=await f.service().prepare(f.id,preview);
  await assert.rejects(db.edit({ kind:'entry',action:'delete',id:'offline' }), /保护/);
  f.target.lose=true; await assert.rejects(f.service().resume(prepared.id), /lost response/);
  const binding=f.catalog.db(prepared.targetId).activeV3.journal.value.binding;
  await f.reopen(); const result=await f.service().resume(prepared.id);
  assert.equal(result.phase,'ACTIVE'); assert.equal(result.targetDescriptor.datasetId,binding.datasetId);
  assert.equal(f.catalog.manifest.activeId,prepared.targetId); assert.equal(f.source.writes,1); assert.equal(f.rows.size,256); assert.equal(f.target.writes,2);
  assert.deepEqual(stateBytes(f.catalog.db(prepared.targetId).frame.local),stateBytes(preview.evidence.merged));
  assert.deepEqual(await readFile(join(db.store.dir,'workspace.json')),originalSource);
  assert.equal(result.independentBackupVerified,false); assert.equal(result.serverWriteProhibited,false);
  assert(result.exactReadbackConfirmed && result.rawEquivalent);
  await assert.rejects(f.service().cancel(prepared.id), /禁止简单切回/);
});
test('v2→v3：真实冲突、待确认 PUT、哈希变化与预览后源变化不能切换', async t => {
  const f=await fixture(t), db=f.catalog.db(f.id), frame=db.snapshot();
  await assert.rejects(inspectV2ForV3({ ...frame,pending:{ attempted:true } },f.source), /待确认/);
  const preview=await f.service().preview(f.id), old=decode([...f.rows.values()][0]);
  const next={ ...old,commitId:randomUUID(),parents:[old.commitId] }; f.rows.set(next.commitId,encode(next));
  await assert.rejects(f.service().prepare(f.id,preview), /过期/); assert.equal(f.target.writes,0);
  const p=await f.service().preview(f.id), m=await f.service().prepare(f.id,p);
  const changed={ ...next,commitId:randomUUID(),parents:[next.commitId] }; f.rows.set(changed.commitId,encode(changed));
  await assert.rejects(f.service().resume(m.id), /源历史变化/); assert.equal(f.target.writes,0); assert.notEqual(f.catalog.manifest.activeId,m.targetId);
  const saved=f.rows.get(old.commitId); f.rows.set(old.commitId,encode({ ...old,writerId:randomUUID() }));
  await assert.rejects(inspectV2ForV3(frame,f.source), /内容改变/); f.rows.set(old.commitId,saved);
});
