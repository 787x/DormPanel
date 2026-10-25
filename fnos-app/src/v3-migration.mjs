import { randomUUID } from 'node:crypto';
import { ensure, decode } from './protocol.mjs';
import { loadGraph, graphPlan } from './graph.mjs';
import { mergePlan, same } from './merge.mjs';
import { stateBytes, sha, businessHash,decode as decodeV3 } from './v3-protocol.mjs';
import { json } from './store.mjs';
import { openV3Workspace } from './v3-workspace.mjs';
import { V3Engine } from './v3-engine.mjs';
import { remoteV3,remoteV3Connection,discoverV3 } from './active-v3.mjs';
import { inspectV3Target } from './v3-inspection.mjs';
import { Budget } from './v3-store.mjs';
import { join } from 'node:path';
import { stat } from 'node:fs/promises';

const rawHash = state => sha(stateBytes(state));
const sourceSnapshot=db => {
  const f=db.frame, { graph,...rest }=f;
  return structuredClone({ ...rest,graph:graph ? { datasetId:graph.datasetId,hashes:graph.hashes,deferred:graph.deferred,observed:graph.observed } : null });
};
const scalarEvidence=({merged,raw,...evidence})=>evidence;
const stamp = f => sha(Buffer.from(json({ datasetId:f.datasetId, bindingKey:f.bindingKey, frontier:f.frontier,
  baseline:f.baseline ? rawHash(f.baseline) : null, local:rawHash(f.local), pending:f.pending, conflict:f.conflict,
  abandoned:f.abandoned, checkpoint:f.checkpoint })));
export async function inspectV2ForV3(frame, remote, requiredHashes = {}) {
  if(!frame.datasetId && !frame.baseline) {
    ensure(!frame.pending && !frame.conflict && !Object.keys(frame.abandoned).length && !frame.frontier.length &&
      !frame.graph?.datasetId && !Object.keys(frame.graph?.hashes ?? {}).length && !frame.checkpoint.datasetId &&
      !Object.keys(frame.checkpoint.hashes ?? {}).length && !frame.checkpoint.observed?.length && !frame.checkpoint.deferred?.length,'本地工作区有已观察历史或恢复状态，不能隐式切换');
    ensure(!frame.bindingKey || frame.bindingKey===remote.key,'本地访问端点身份变化');
    return { sourceProtocol:'LOCAL',datasetId:null,hashes:{},tips:[],merged:frame.local,rawHash:rawHash(frame.local),businessHash:businessHash(frame.local),
      sourceStamp:stamp(frame),sourceKey:remote.key,knownWriterIds:[] };
  }
  ensure(frame.datasetId && frame.baseline && frame.frontier.length, '源 v2 基线或前沿缺失');
  ensure(!frame.pending && !frame.conflict && !Object.keys(frame.abandoned).length && !frame.checkpoint.deferred?.length, '待确认 PUT、冲突或不可验证历史尚未恢复');
  ensure(!frame.bindingKey || frame.bindingKey === remote.key, '源端点身份变化');
  const graph = await loadGraph(remote, { ...frame.graph, ...frame.checkpoint,
    datasetId:frame.datasetId, hashes:{ ...frame.graph?.hashes, ...frame.checkpoint.hashes,...requiredHashes } });
  ensure(graph.datasetId === frame.datasetId && !graph.deferred.length && graph.tips.length, '源历史不完整');
  const old = graphPlan({ ...graph, tips:frame.frontier }, frame.baseline, frame.frontier);
  ensure(same(mergePlan({ base:old.base, localBase:old.base, branches:old.branches }, old.base).remote, frame.baseline), '源基线不能由前沿重建');
  const plan = graphPlan(graph, frame.baseline, frame.frontier), result = mergePlan(plan, frame.local);
  ensure(!result.conflicts.length, '存在真实冲突；保留本地和源历史，停止迁移');
  return { sourceProtocol:'DPC2',datasetId:graph.datasetId, hashes:graph.hashes, tips:graph.tips, merged:result.state,
    rawHash:rawHash(result.state), businessHash:businessHash(result.state), sourceStamp:stamp(frame), sourceKey:remote.key,
    knownWriterIds:[...new Set(Object.entries(graph.raw).map(([id,raw]) => decode(Buffer.from(raw,'base64'), id).writerId))].sort() };
}
const equalEvidence = (a,b) => Object.keys(a.hashes).length===Object.keys(b.hashes).length && Object.entries(a.hashes).every(([id,h])=>b.hashes[id]===h) &&
  json(a.tips) === json(b.tips) && a.rawHash === b.rawHash && a.sourceKey === b.sourceKey && a.sourceStamp === b.sourceStamp;

/** Host service only: called under its existing admin/CSRF/revision/history mutex.
 * All state and exact v2 bytes are retained. No v2 stage/publish or privileged NAS API. */
export class V3DatasetMigration {
  constructor(catalog) { this.catalog = catalog; }
  async save(record) {
    const c = this.catalog, records = c.v3Migrations.filter(m => m.id !== record.id).concat(record);
    try { await c.store.save('v3-migrations.json', records); }
    catch(error) { c.invalidated=true; throw error; } // A rename/fsync receipt may be uncertain. Reopen before ANY Writer continues.
    c.v3Migrations = records;
  }
  remote(id) { const c = this.catalog; return c.remote(c.connection(c.row(id).connectionId)); }
  async discover(id,connectionId) {
    const c=this.catalog; c.row(id);
    return discoverV3(remoteV3Connection(c,connectionId ?? c.row(id).connectionId,new Budget()));
  }
  async preview(id) {
    const c = this.catalog, db = await c.load(id);
    ensure(!db.activeV3 && !c.protection(id) && !c.restoreGuards[id], '源工作区已有保护或不是 v2');
    const frame = sourceSnapshot(db), evidence = await inspectV2ForV3(frame, this.remote(id));
    ensure(stamp(db.frame) === evidence.sourceStamp, '预检期间本地变化');
    return { frame, evidence };
  }
  async prepare(id, expected, targetConnectionId, selection = null) {
    const c = this.catalog;
    ensure(!c.configuring && !c.busy.size && !c.maintenance, '存在并发操作');
    c.configuring = true;
    try {
      const p = await this.preview(id); ensure(equalEvidence(p.evidence, expected.evidence), '迁移预览过期');
      ensure(c.manifest.workspaces.length < 64, '工作区数量超限');
      const source = c.row(id), connection = c.connection(targetConnectionId ?? source.connectionId);
      ensure(!c.manifest.workspaces.some(w => w.id !== id && w.connectionId === connection.id), '目标连接已被其他工作区使用');
      let descriptorRaw=null;
      if(selection) {
        descriptorRaw=await remoteV3Connection(c,connection.id,new Budget()).read(selection.descriptorPath);
        ensure(descriptorRaw && sha(descriptorRaw)===selection.descriptorHash,'请选择已验证的目标 Descriptor 身份');
        const descriptor=decodeV3(descriptorRaw); ensure(descriptor.type===1 && descriptor.datasetId!==p.evidence.datasetId,'v3 目标身份无效');
        const identities=await c.identities(); ensure(![...identities.values()].includes(descriptor.datasetId),'目标已绑定本机其他工作区，请保留原工作区');
      }
      const record = { id:randomUUID(), sourceId:id, targetId:randomUUID(), targetConnectionId:connection.id, phase:'PREPARED', intent:selection ? 'JOIN_EXISTING' : 'CREATE',
        ...(selection ? { joinDescriptor:descriptorRaw.toString('base64') } : {}),
        sourceProtocol:p.evidence.sourceProtocol, sourceConnectionId:source.connectionId, evidence:scalarEvidence(p.evidence), proofFormat:2,
        independentBackupVerified:false, sourceHistoryRetained:p.evidence.sourceProtocol!=='LOCAL', unknownWritersStopped:false, serverWriteProhibited:false };
      const directory = join(c.workspaceStore(record.targetId).dir, 'v3');
      const adapter = await openV3Workspace(directory, () => ensure(!c.maintenance && !c.invalidated), c.store.identity);
      try {
        // Complete RAW captures use PR54 immutable SQLite snapshot references. The
        // small Catalog ledger never loads every migration's course graph at startup.
        await adapter.journal.transaction(() => {
          adapter.journal.meta.put('hostMigration',record.id,{ sourceFrame:p.frame,merged:p.evidence.merged });
          adapter.journal.meta.put('hostMigrationHeader',record.id,{ sourceStamp:p.evidence.sourceStamp,rawHash:p.evidence.rawHash });
        },true);
        await this.save(record); // Pause only after the referenced capture is durable.
        await c.db(id).queue;
        ensure(stamp(c.db(id).frame) === p.evidence.sourceStamp, '暂停前本地变化；保留证据，请取消后重新预检');
        const engine=new V3Engine(adapter.journal,c.store.identity.writerId);
        if(selection) await engine.bind(decodeV3(descriptorRaw),sha(descriptorRaw));
        else await engine.edit(p.evidence.merged);
      }
      finally { await adapter.journal.close(); }
      const manifest = structuredClone(c.manifest);
      manifest.workspaces.push({ id:record.targetId, name:source.name + ' · v3', connectionId:connection.id, protocol:'v3' });
      manifest.revision++; await c.commit(manifest);
      return record;
    } finally { c.configuring = false; }
  }
  async resume(recordId) {
    const c = this.catalog, m = structuredClone(c.v3Migrations.find(m => m.id === recordId));
    ensure(m && m.phase !== 'CANCELLED', '迁移记录不存在');
    ensure(!c.configuring && !c.busy.size && !c.maintenance, '存在并发操作');
    c.configuring = true;
    try {
      if (m.phase === 'ACTIVE') return this.summary(m);
      const source = await c.load(m.sourceId);
      ensure(stamp(source.frame) === m.evidence.sourceStamp, '源本地状态变化；保留两端并停止切换');
      let fresh;
      const check = async () => { fresh=await inspectV2ForV3(sourceSnapshot(source),this.remote(m.sourceId),m.evidence.hashes);
        ensure(equalEvidence(m.evidence,fresh),'源历史变化；保留目标候选及本地数据，停止切换'); };
      await check();
      // A crash before manifest registration resumes the SAME target private store and ID.
      if (!c.manifest.workspaces.some(w => w.id === m.targetId)) {
        ensure((await stat(join(c.workspaceStore(m.targetId).dir,'v3','v3-state-v2.sqlite')).catch(()=>null))?.isFile(),'迁移私有证明库缺失；拒绝创建空库覆盖');
        const adapter = await openV3Workspace(join(c.workspaceStore(m.targetId).dir,'v3'), () => ensure(!c.maintenance), c.store.identity);
        try {
          if(m.proofFormat===2) ensure(adapter.journal.meta.get('hostMigrationHeader',m.id)?.sourceStamp===m.evidence.sourceStamp,'迁移 capture 证明缺失');
          const engine=new V3Engine(adapter.journal,c.store.identity.writerId);
          if(m.intent==='JOIN_EXISTING') {
            const raw=Buffer.from(m.joinDescriptor,'base64');
            if(!adapter.journal.value.binding) await engine.bind(decodeV3(raw),sha(raw));
            ensure(adapter.journal.value.binding.descriptorHash===sha(raw),'未注册目标身份变化');
          } else {
            ensure(!adapter.journal.value.binding, '未注册目标出现绑定，停止自动恢复');
            if (rawHash(adapter.journal.value.local) !== m.evidence.rawHash) await engine.edit(fresh.merged);
          }
        } finally { await adapter.journal.close(); }
        const manifest = structuredClone(c.manifest); manifest.workspaces.push({ id:m.targetId, name:c.row(m.sourceId).name + ' · v3', connectionId:m.targetConnectionId, protocol:'v3' }); manifest.revision++; await c.commit(manifest);
      }
      const target = await c.load(m.targetId), runtime = target.activeV3, budget=new Budget(), remote = remoteV3(c, m.targetId, budget);
      if(m.proofFormat===2) { const header=runtime.migrationHeader(m.id); ensure(header?.sourceStamp===m.evidence.sourceStamp && header.rawHash===m.evidence.rawHash,'迁移 capture 证明缺失或改变'); }
      if(m.intent==='JOIN_EXISTING') {
        const verified=await inspectV3Target(runtime.journal,c.store.identity.writerId,remote,budget,Buffer.from(m.joinDescriptor,'base64'));
        ensure(verified,'目标历史预检尚未完成；请继续同一迁移事务');
        ensure(rawHash(verified.state)===m.evidence.rawHash,'源与目标 RAW 不等价，不能证明共同基线；保留双方，禁止覆盖加入');
        await check();
        runtime.migrationSeedId=m.id;
        try { await runtime.journal.transaction(s => {
          s.local=verified.state; s.baseline=verified.state; s.baselinePath=verified.path; s.baselineHash=verified.hash;
          s.activeEpoch=decodeV3(verified.raw).epochId; s.unbasedLocal=false; s.revision=0;
          s.joinReadback={ path:verified.path,hash:verified.hash };
        },true); } finally { runtime.migrationSeedId=null; }
        m.targetDescriptor=runtime.journal.value.binding; m.targetBaselinePath=verified.path; m.targetBaselineHash=verified.hash;
        m.exactReadbackConfirmed=true; m.rawEquivalent=true; m.sourceWriterPaused=true; m.phase='VERIFIED'; await this.save(m);
        const manifest=structuredClone(c.manifest); manifest.activeId=m.targetId; manifest.revision++; await c.commit(manifest);
        m.phase='ACTIVE'; await this.save(m); return this.summary(m);
      }
      const value = runtime.journal.value;
      if (!value.binding || value.initialization?.length) await runtime.engine.initialize(remote);
      const s = runtime.journal.value;
      ensure(s.baselinePath && s.receipt && !s.initialization?.length && rawHash(s.baseline) === m.evidence.rawHash, '目标 Genesis 尚未精确回读或 RAW 不等价');
      await check();
      m.targetDescriptor = s.binding; m.targetGenesisPath = s.baselinePath; m.targetGenesisHash = s.baselineHash;
      m.exactReadbackConfirmed = true; m.rawEquivalent = true; m.sourceWriterPaused = true; m.phase = 'VERIFIED'; await this.save(m);
      const manifest = structuredClone(c.manifest); manifest.activeId = m.targetId; manifest.revision++; await c.commit(manifest);
      m.phase = 'ACTIVE'; await this.save(m);
      return this.summary(m);
    } finally { c.configuring = false; }
  }
  async cancel(recordId) {
    const c = this.catalog, m = structuredClone(c.v3Migrations.find(m => m.id === recordId));
    ensure(m?.phase === 'PREPARED', '目标已验证或已激活，禁止简单切回 v2');
    ensure(!c.configuring && !c.busy.size && !c.maintenance,'存在并发操作'); c.configuring=true;
    try {
    const target = c.manifest.workspaces.find(w => w.id === m.targetId) ? await c.load(m.targetId) : null;
    ensure(m.intent==='JOIN_EXISTING' || !target?.activeV3.journal.value.binding, '目标已创建候选，保留两端并使用迁移恢复');
    if(target) {
      ensure(!target.activeV3.journal.value.pending && !target.activeV3.journal.value.receipt,'目标已有本机发布状态，禁止简单切回');
      const manifest=structuredClone(c.manifest); manifest.workspaces=manifest.workspaces.filter(w=>w.id!==m.targetId);
      if(manifest.activeId===m.targetId) manifest.activeId=m.sourceId;
      manifest.revision++; await c.commit(manifest);
      c.workspaces.delete(m.targetId); await target.activeV3.close();
    }
    m.phase = 'CANCELLED'; await this.save(m); return this.summary(m);
    } finally { c.configuring=false; }
  }
  async audit(recordId) {
    const c=this.catalog, m=structuredClone(c.v3Migrations.find(m => m.id === recordId));
    ensure(m?.phase === 'ACTIVE' && m.sourceProtocol!=='LOCAL' && !c.configuring && !c.busy.size && !c.maintenance, '迁移尚未激活、没有 v2 源或存在并发操作');
    c.configuring=true;
    try {
      const graph=await loadGraph(this.remote(m.sourceId),{ datasetId:m.evidence.datasetId,hashes:{...m.evidence.hashes,...m.sourceAudit?.hashes} });
      ensure(!graph.deferred.length,'旧源有不可验证路径；不能声称未变化');
      const added=Object.keys(graph.hashes).filter(id => !m.evidence.hashes[id]);
      const target=await c.load(m.targetId), meta=target.activeV3.journal.meta, previous=meta.budget; meta.budget=new Budget({requests:0});
      try { meta.atomic(()=>{ for(const id of added) {
        const key=m.id+':'+id, old=meta.get('hostV2Fork',key);
        ensure(!old || old.hash===graph.hashes[id] && old.raw===graph.raw[id],'已记录旧源分叉内容变化');
        if(!old) meta.put('hostV2Fork',key,{hash:graph.hashes[id],raw:graph.raw[id]});
      } }); } finally { meta.budget=previous; }
      m.sourceAudit={ status:added.length ? 'CROSS_PROTOCOL_FORK' : 'OBSERVED_UNCHANGED',checkedAt:Date.now(),
        hashes:graph.hashes,tips:graph.tips,extraCommits:added.length };
      await this.save(m); return this.summary(m);
    } finally { c.configuring=false; }
  }
  summary(m) {
    return { id:m.id, sourceId:m.sourceId, targetId:m.targetId, phase:m.phase, sourceProtocol:m.sourceProtocol,sourceDatasetId:m.evidence.datasetId,
      sourceCommits:Object.keys(m.evidence.hashes).length, sourceTips:m.evidence.tips, knownWriterIds:m.evidence.knownWriterIds,
      intent:m.intent, targetDescriptor:m.targetDescriptor, targetGenesisHash:m.targetGenesisHash, targetBaselineHash:m.targetBaselineHash, exactReadbackConfirmed:!!m.exactReadbackConfirmed,
      rawEquivalent:!!m.rawEquivalent, sourceWriterPaused:m.phase !== 'CANCELLED', knownClientsConfirmed:false,
      sourceAudit:m.sourceAudit ? { status:m.sourceAudit.status,checkedAt:m.sourceAudit.checkedAt,extraCommits:m.sourceAudit.extraCommits ?? Object.keys(m.sourceAudit.raw ?? {}).length } : null,
      independentBackupVerified:false, sourceHistoryRetained:m.sourceHistoryRetained, unknownWritersStopped:false, serverWriteProhibited:false };
  }
}
