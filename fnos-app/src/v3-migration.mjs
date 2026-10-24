import { randomUUID } from 'node:crypto';
import { ensure, decode } from './protocol.mjs';
import { loadGraph, graphPlan } from './graph.mjs';
import { mergePlan, same } from './merge.mjs';
import { stateBytes, sha, businessHash } from './v3-protocol.mjs';
import { json } from './store.mjs';
import { openV3Workspace } from './v3-workspace.mjs';
import { V3Engine } from './v3-engine.mjs';
import { remoteV3 } from './active-v3.mjs';
import { Budget } from './v3-store.mjs';
import { join } from 'node:path';

const rawHash = state => sha(stateBytes(state));
const stamp = f => sha(Buffer.from(json({ datasetId:f.datasetId, bindingKey:f.bindingKey, frontier:f.frontier,
  baseline:f.baseline ? rawHash(f.baseline) : null, local:rawHash(f.local), pending:f.pending, conflict:f.conflict,
  abandoned:f.abandoned, checkpoint:f.checkpoint })));
export async function inspectV2ForV3(frame, remote) {
  ensure(frame.datasetId && frame.baseline && frame.frontier.length, '源 v2 基线或前沿缺失');
  ensure(!frame.pending && !frame.conflict && !Object.keys(frame.abandoned).length && !frame.checkpoint.deferred?.length, '待确认 PUT、冲突或不可验证历史尚未恢复');
  ensure(!frame.bindingKey || frame.bindingKey === remote.key, '源端点身份变化');
  const graph = await loadGraph(remote, { ...frame.graph, ...frame.checkpoint,
    datasetId:frame.datasetId, hashes:{ ...frame.graph?.hashes, ...frame.checkpoint.hashes } });
  ensure(graph.datasetId === frame.datasetId && !graph.deferred.length && graph.tips.length, '源历史不完整');
  const old = graphPlan({ ...graph, tips:frame.frontier }, frame.baseline, frame.frontier);
  ensure(same(mergePlan({ base:old.base, localBase:old.base, branches:old.branches }, old.base).remote, frame.baseline), '源基线不能由前沿重建');
  const plan = graphPlan(graph, frame.baseline, frame.frontier), result = mergePlan(plan, frame.local);
  ensure(!result.conflicts.length, '存在真实冲突；保留本地和源历史，停止迁移');
  return { datasetId:graph.datasetId, hashes:graph.hashes, tips:graph.tips, raw:graph.raw, merged:result.state,
    rawHash:rawHash(result.state), businessHash:businessHash(result.state), sourceStamp:stamp(frame), sourceKey:remote.key,
    knownWriterIds:[...new Set(Object.entries(graph.raw).map(([id,raw]) => decode(Buffer.from(raw,'base64'), id).writerId))].sort() };
}
const equalEvidence = (a,b) => json(a.hashes) === json(b.hashes) && json(a.tips) === json(b.tips) && a.rawHash === b.rawHash && a.sourceKey === b.sourceKey && a.sourceStamp === b.sourceStamp;

/** Host service only: called under its existing admin/CSRF/revision/history mutex.
 * All state and exact v2 bytes are retained. No v2 stage/publish or privileged NAS API. */
export class V3DatasetMigration {
  constructor(catalog) { this.catalog = catalog; }
  async save(record) {
    const c = this.catalog, records = c.v3Migrations.filter(m => m.id !== record.id).concat(record);
    await c.store.save('v3-migrations.json', records); c.v3Migrations = records;
  }
  remote(id) { const c = this.catalog; return c.remote(c.connection(c.row(id).connectionId)); }
  async preview(id) {
    const c = this.catalog, db = await c.load(id);
    ensure(!db.activeV3 && !c.protection(id) && !c.restoreGuards[id], '源工作区已有保护或不是 v2');
    const frame = db.snapshot(), evidence = await inspectV2ForV3(frame, this.remote(id));
    ensure(stamp(db.snapshot()) === evidence.sourceStamp, '预检期间本地变化');
    return { frame, evidence };
  }
  async prepare(id, expected, targetConnectionId) {
    const c = this.catalog;
    ensure(!c.configuring && !c.busy.size && !c.maintenance, '存在并发操作');
    c.configuring = true;
    try {
      const p = await this.preview(id); ensure(equalEvidence(p.evidence, expected.evidence), '迁移预览过期');
      ensure(c.manifest.workspaces.length < 64, '工作区数量超限');
      const source = c.row(id), connection = c.connection(targetConnectionId ?? source.connectionId);
      ensure(!c.manifest.workspaces.some(w => w.id !== id && w.connectionId === connection.id), '目标连接已被其他工作区使用');
      const record = { id:randomUUID(), sourceId:id, targetId:randomUUID(), targetConnectionId:connection.id, phase:'PREPARED',
        sourceProtocol:'DPC2', sourceConnectionId:source.connectionId, sourceFrame:p.frame, evidence:p.evidence,
        independentBackupVerified:false, sourceHistoryRetained:true, unknownWritersStopped:false, serverWriteProhibited:false };
      await this.save(record); // Durable protection BEFORE another source edit or any publication.
      await c.db(id).queue;
      ensure(stamp(c.db(id).snapshot()) === p.evidence.sourceStamp, '暂停前本地变化；保留证据，请取消后重新预检');
      const directory = join(c.workspaceStore(record.targetId).dir, 'v3');
      const adapter = await openV3Workspace(directory, () => ensure(!c.maintenance && !c.invalidated), c.store.identity);
      try { await new V3Engine(adapter.journal, c.store.identity.writerId).edit(p.evidence.merged); }
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
      ensure(stamp(source.snapshot()) === m.evidence.sourceStamp, '源本地状态变化；保留两端并停止切换');
      const check = async () => ensure(equalEvidence(m.evidence, await inspectV2ForV3(source.snapshot(), this.remote(m.sourceId))), '源历史变化；保留目标候选及本地数据，停止切换');
      await check();
      // A crash before manifest registration resumes the SAME target private store and ID.
      if (!c.manifest.workspaces.some(w => w.id === m.targetId)) {
        const adapter = await openV3Workspace(join(c.workspaceStore(m.targetId).dir,'v3'), () => ensure(!c.maintenance), c.store.identity);
        try {
          ensure(!adapter.journal.value.binding, '未注册目标出现绑定，停止自动恢复');
          if (rawHash(adapter.journal.value.local) !== m.evidence.rawHash) await new V3Engine(adapter.journal,c.store.identity.writerId).edit(m.evidence.merged);
        } finally { await adapter.journal.close(); }
        const manifest = structuredClone(c.manifest); manifest.workspaces.push({ id:m.targetId, name:c.row(m.sourceId).name + ' · v3', connectionId:m.targetConnectionId, protocol:'v3' }); manifest.revision++; await c.commit(manifest);
      }
      const target = await c.load(m.targetId), runtime = target.activeV3, remote = remoteV3(c, m.targetId, new Budget());
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
    const target = c.manifest.workspaces.find(w => w.id === m.targetId) ? await c.load(m.targetId) : null;
    ensure(!target?.activeV3.journal.value.binding, '目标已创建候选，保留两端并使用迁移恢复');
    m.phase = 'CANCELLED'; await this.save(m); return this.summary(m);
  }
  async audit(recordId) {
    const c=this.catalog, m=structuredClone(c.v3Migrations.find(m => m.id === recordId));
    ensure(m?.phase === 'ACTIVE' && !c.configuring && !c.busy.size && !c.maintenance, '迁移尚未激活或存在并发操作');
    c.configuring=true;
    try {
      const graph=await loadGraph(this.remote(m.sourceId),{ datasetId:m.evidence.datasetId,hashes:m.evidence.hashes });
      ensure(!graph.deferred.length,'旧源有不可验证路径；不能声称未变化');
      const added=Object.keys(graph.hashes).filter(id => !m.evidence.hashes[id]);
      m.sourceAudit={ status:added.length ? 'CROSS_PROTOCOL_FORK' : 'OBSERVED_UNCHANGED',checkedAt:Date.now(),
        hashes:graph.hashes,tips:graph.tips,raw:Object.fromEntries(added.map(id => [id,graph.raw[id]])) };
      await this.save(m); return this.summary(m);
    } finally { c.configuring=false; }
  }
  summary(m) {
    return { id:m.id, sourceId:m.sourceId, targetId:m.targetId, phase:m.phase, sourceDatasetId:m.evidence.datasetId,
      sourceCommits:Object.keys(m.evidence.hashes).length, sourceTips:m.evidence.tips, knownWriterIds:m.evidence.knownWriterIds,
      targetDescriptor:m.targetDescriptor, targetGenesisHash:m.targetGenesisHash, exactReadbackConfirmed:!!m.exactReadbackConfirmed,
      rawEquivalent:!!m.rawEquivalent, sourceWriterPaused:m.phase !== 'CANCELLED', knownClientsConfirmed:false,
      sourceAudit:m.sourceAudit ? { status:m.sourceAudit.status,checkedAt:m.sourceAudit.checkedAt,extraCommits:Object.keys(m.sourceAudit.raw).length } : null,
      independentBackupVerified:false, sourceHistoryRetained:true, unknownWritersStopped:false, serverWriteProhibited:false };
  }
}
