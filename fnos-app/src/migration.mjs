import { randomUUID } from 'node:crypto';
import { ensure, stateBytes, hash } from './protocol.mjs';
import { json } from './store.mjs';
import { loadGraph } from './graph.mjs';
import { LocalCommitStore } from './local.mjs';
import { Workspace } from './workspace.mjs';
import { freshGraph, blockers, graphIdentity, commitAt } from './history.mjs';

const equivalent = (a, b) => stateBytes(a).equals(stateBytes(b));
const warning = '管理员登记不代表系统发现全部设备。无法阻止其他独立客户端 PUT；请停止它们。未由 NAS 管理员在服务端停止旧目录写入前，不得声称可安全删除。空间未物理回收。';
// 独立持久化日志先于任何 catalog 切换；旧帧永不作为目标帧复用。
export class DatasetMigration {
  constructor(catalog) { this.catalog = catalog; }
  async save(record) {
    const c = this.catalog, records = c.migrations.filter(m => m.id !== record.id).concat(record);
    await c.store.save('dataset-migrations.json', records); c.migrations = records;
  }
  summary(workspaceId) {
    return { records: this.catalog.migrations.filter(m => [m.sourceId, m.targetId].includes(workspaceId)).map(({ recovery, ...m }) => m), warning, automaticDelete: false, reclaimedBytes: 0 };
  }
  async location(rootId, relative) {
    ensure(rootId !== 'private', '必须使用独立授权归档目录');
    const d = await this.catalog.grants.directory(rootId, relative);
    try { return { rootId, relative, directoryIdentity: d.stamp }; } finally { await d.fd.close(); }
  }
  async verify(db, input, expected = null) {
    const c = this.catalog;
    ensure(!c.manifest.transition && !c.maintenance && !Object.keys(c.restoreGuards).length, '备份恢复或端点迁移尚未完成');
    const source = await freshGraph(db, c.remote(c.connection(c.row(input.sourceId).connectionId)));
    ensure(!blockers(db, source).length, blockers(db, source).join('；'));
    ensure(json([...db.frame.frontier].sort()) === json([...source.tips].sort()), '源前沿尚未同步，请先同步并重新归档准备');
    const candidate = await this.location(input.rootId, input.candidateRelative);
    const marker = await c.grants.readPrivate(candidate);
    ensure(marker.format === 'DormPanel-compact-1' && marker.migrated === false && marker.sourceDatasetId === source.datasetId && marker.datasetId !== source.datasetId, '精简候选标记或身份不符');
    const archive = await this.location(input.rootId, marker.archive), manifest = await c.grants.readPrivate(archive);
    ensure(archive.directoryIdentity !== candidate.directoryIdentity, '归档与目标目录必须独立');
    const saved = await loadGraph(new LocalCommitStore(archive, c.grants), { datasetId: manifest.datasetId, hashes: Object.fromEntries((manifest.commits ?? []).map(r => [r.id, r.sha256])) });
    ensure(manifest.format === 'DormPanel-history-1' && manifest.complete === true && Object.values(manifest.verification ?? {}).length === 4 && Object.values(manifest.verification).every(v => v === true), '完整历史归档未完成');
    ensure(manifest.datasetId === source.datasetId && json(manifest.tips) === json(saved.tips) && manifest.bytes === Object.values(saved.raw).reduce((n, b) => n + Buffer.from(b, 'base64').length, 0), '归档清单不符');
    ensure(manifest.commits.length === Object.keys(saved.raw).length && manifest.commits.every(r => saved.hashes[r.id] === r.sha256 && json(commitAt(saved, r.id).parents) === json(r.parents)), '归档哈希或 DAG 不符');
    ensure(graphIdentity(saved) === graphIdentity(source), '候选已过期；请重新同步、完整归档并准备精简数据');
    const target = await loadGraph(new LocalCommitStore(candidate, c.grants));
    ensure(target.datasetId === marker.datasetId && target.tips.length === 1 && Object.keys(target.raw).length === 1 && !target.deferred.length && !commitAt(target, target.tips[0]).parents.length && equivalent(source.state, target.state), '候选根或全部业务字段不等价');
    const connection = c.connection(input.targetConnectionId);
    ensure(connection.type === 'webdav', '请选择 Android 实际使用的目标 WebDAV 连接');
    const remote = c.remote(connection), old = c.remote(c.connection(c.row(input.sourceId).connectionId));
    ensure(remote.key !== old.key, '新旧存储目录必须不同');
    if (old.config) ensure(old.config.directoryIdentity !== candidate.directoryIdentity, '目标不能是源目录');
    // 真实通过配置中的 WebDAV GET/列举重建全图，不能用本地目录可读代替。
    const seen = expected && c.migrations.find(m => m.id === input.id)?.targetObservation;
    const reachable = await loadGraph(remote, { ...target, ...seen, hashes: { ...target.hashes, ...seen?.hashes } }, async (deferred, verified) => {
      if (!expected) return;
      input.targetObservation = { ...verified, deferred };
      await this.save(input);
    });
    ensure(graphIdentity(reachable) === graphIdentity(target), 'Android 目标 WebDAV 地址与候选不一致或不可达');
    const finalSource = await freshGraph(db, c.remote(c.connection(c.row(input.sourceId).connectionId)));
    ensure(graphIdentity(finalSource) === graphIdentity(source) && !blockers(db, finalSource).length, '验证期间旧库或本地状态变化，请重新检查');
    const identity = { source: graphIdentity(source), target: graphIdentity(target), marker: hash(Buffer.from(json(marker))).toString('hex'), archive: hash(Buffer.from(json(manifest))).toString('hex'), oldKey: old.key, targetKey: remote.key };
    if (expected) ensure(json(identity) === json(expected), '源、目标或归档变化，需要重新检查；禁止跨数据集合并');
    return { source, target: { ...target, bindingKey: remote.key }, identity, archive, candidate, targetUrl: connection.config.url };
  }
  async prepare(db, input, user) {
    const c = this.catalog;
    ensure(!c.migrations.some(m => m.phase !== 'cancelled' && m.phase !== 'retired'), '有其他未完成迁移');
    ensure(!c.protection(input.workspaceId), '当前工作区已受保护');
    ensure(!c.manifest.workspaces.some(w => w.connectionId === input.targetConnectionId), '目标连接已有工作区，请选择独立新连接');
    const check = await this.verify(db, { ...input, sourceId: input.workspaceId });
    const m = { id: randomUUID(), revision: 0, sourceId: input.workspaceId, targetId: randomUUID(), phase: 'prepared', user, rootId: input.rootId, candidateRelative: input.candidateRelative, targetConnectionId: input.targetConnectionId,
      sourceDatasetId: check.source.datasetId, targetDatasetId: check.target.datasetId, sourceTips: check.source.tips, sourceHashes: check.source.hashes, sourceWriters: [...new Set(Object.keys(check.source.raw).map(id => commitAt(check.source, id).writerId))].sort(), identity: check.identity, archive: check.archive, candidate: check.candidate, targetUrl: check.targetUrl,
      oldCount: Object.keys(check.source.raw).length, newCount: 1, reclaimEstimateBytes: Object.values(check.source.raw).reduce((n, b) => n + Buffer.from(b, 'base64').length, 0),
      recovery: db.snapshot(), devices: [], lastCheck: 'FPK 实际验证源、归档、候选与目标 WebDAV 全图；独立设备尚未验收', failure: null };
    await this.save(m); return this.summary(input.workspaceId);
  }
  async action(db, input, user) {
    const c = this.catalog;
    if (input.action === 'prepare') return this.prepare(db, input, user);
    const old = c.migrations.find(m => m.id === input.migrationId);
    ensure(old && [old.sourceId, old.targetId].includes(input.workspaceId) && old.revision === input.migrationVersion, '迁移版本或工作区不符');
    ensure(old.phase !== 'cancelled', '迁移已取消');
    const m = structuredClone(old); m.revision++;
    try {
      const sourceDb = await c.load(m.sourceId);
      if ((m.phase === 'retired' || m.phase === 'needs-recheck' && m.previousPhase === 'retired') && input.action === 'recheck') {
        const source = await freshGraph(sourceDb, c.remote(c.connection(c.row(m.sourceId).connectionId)));
        ensure(graphIdentity(source) === m.identity.source, '退役旧库出现新提交或历史变化，需要重新检查');
        const archive = await loadGraph(new LocalCommitStore(m.archive, c.grants), { datasetId: m.sourceDatasetId, hashes: m.sourceHashes });
        ensure(graphIdentity(archive) === m.identity.source, '退役归档变化');
        ensure(hash(Buffer.from(json(await c.grants.readPrivate(m.archive)))).toString('hex') === m.identity.archive, '退役归档完成标记变化');
        const target = await loadGraph(c.remote(c.connection(m.targetConnectionId)), { datasetId: m.targetDatasetId, hashes: m.fpkVerification.hashes });
        ensure(!target.deferred.length, '新库历史不完整');
        m.phase = 'retired'; delete m.previousPhase; m.failure = null;
        m.lastCheck = 'FPK 实际验证旧图与归档仍完整，新库合法后续编辑已验证；管理员仍须落实 NAS 服务端停写及独立归档保管';
        await this.save(m); return this.summary(input.workspaceId);
      }
      if (input.action === 'cancel') {
        ensure(m.phase !== 'retired' && input.confirm === m.sourceDatasetId && input.writersStopped === true, '先明确确认全部独立客户端已停写；新库开放后禁止反向覆盖');
        const target = await loadGraph(c.remote(c.connection(m.targetConnectionId)), m.targetObservation ?? {});
        ensure(graphIdentity(target) === m.identity.target, '新库有独立修改或历史异常；先保护新提交，禁止简单取消');
        await freshGraph(sourceDb, c.remote(c.connection(c.row(m.sourceId).connectionId)));
        m.phase = 'cancelled'; m.failure = null; await this.save(m); return this.summary(input.workspaceId);
      }
      const checked = await this.verify(sourceDb, m, m.identity);
      if (input.action === 'device') {
        ensure(['prepared', 'frozen', 'switched'].includes(m.phase), '当前阶段不可修改设备确认，请先重新检查');
        ensure(['X08E', 'Phone', '其他'].includes(input.type) && typeof input.name === 'string' && input.name.trim().length <= 120 && input.name.trim(), '设备登记无效');
        const device = { name: input.name.trim(), type: input.type, stopped: input.stopped === true, accepted: input.accepted === true, evidence: String(input.evidence ?? '').slice(0, 2000), origin: '管理员登记 / 确认，非自动设备发现或 Android 本机证明', lastCheck: { at: new Date().toISOString(), by: user, status: input.accepted === true ? '管理员已核对本机新库结果' : '尚未完成新库验收' } };
        if (device.accepted) ensure(device.stopped && device.evidence && m.phase === 'switched', '先切换 FPK，再逐台填写 Android 本机验收结果');
        m.devices = m.devices.filter(d => d.name !== device.name).concat(device); ensure(m.devices.length <= 64, '设备过多');
      } else if (input.action === 'freeze') {
        ensure(m.phase === 'prepared' && m.devices.length && m.devices.every(d => d.stopped) && input.confirm === m.sourceDatasetId, '请登记全部已知 Writer 并人工确认停写及旧 datasetId');
        m.phase = 'frozen';
      } else if (input.action === 'switch') {
        ensure(['frozen', 'switching'].includes(m.phase) && input.confirm === m.targetDatasetId, '请确认目标 datasetId 与停写状态');
        m.phase = 'switching'; await this.save(m); // 崩溃后重入；目标 ID 固定。
        let targetDb;
        if (c.manifest.workspaces.some(w => w.id === m.targetId)) targetDb = await c.load(m.targetId);
        else {
          targetDb = await Workspace.open(c.workspaceStore(m.targetId), checked.target);
          ensure(targetDb.frame.datasetId === m.targetDatasetId && equivalent(targetDb.frame.local, checked.target.state) && !targetDb.dirty(), '中断后的目标工作区不符');
          await targetDb.tx(f => { f.checkpoint = { datasetId: checked.target.datasetId, hashes: checked.target.hashes, bindingKey: checked.target.bindingKey, deferred: [] }; });
          const next = structuredClone(c.manifest);
          ensure(next.workspaces.length < 64, '工作区数量超限');
          next.workspaces.push({ id: m.targetId, name: '精简课表 ' + m.targetDatasetId.slice(0, 8), connectionId: m.targetConnectionId });
          next.activeId = m.targetId; next.revision++; await c.commit(next); c.attach(m.targetId, targetDb);
        }
        ensure(targetDb.frame.datasetId === m.targetDatasetId && equivalent(targetDb.frame.local, checked.target.state), '目标工作区状态变化');
        m.phase = 'switched'; m.fpkVerification = { origin: 'FPK 后端实际验证', writerId: c.store.identity.writerId, datasetId: m.targetDatasetId, hashes: checked.target.hashes };
      } else if (input.action === 'retire') {
        ensure(m.phase === 'switched' && m.devices.length && m.devices.every(d => d.stopped && d.accepted && d.evidence) && input.confirm === m.sourceDatasetId, '仍有未验收设备或确认不符');
        m.phase = 'retired'; // 仅释放新工作区；旧工作区永久保持只读。
        m.retirement = '完成换库 / 管理员确认设备迁移。旧目录服务端不可写及独立归档保管须 NAS 管理员另行落实；没有执行物理清理。';
      } else if (input.action === 'recheck') {
        if (m.phase === 'needs-recheck') { m.phase = m.previousPhase; delete m.previousPhase; }
      } else throw Error('未知迁移操作');
      m.failure = null; m.lastCheck = 'FPK 实际验证：旧图/完整归档/候选/目标 WebDAV 与准备时一致；全部独立客户端状态未知';
      await this.save(m);
      return this.summary(input.workspaceId);
    } catch (e) {
      m.failure = e.message;
      // 任一失败均保留保护，不能以客户端勾选跳过重新核验。
      if (!/版本|确认|登记|未知迁移|未验收|先切换/.test(e.message) && m.phase !== 'needs-recheck') { m.previousPhase = m.phase; m.phase = 'needs-recheck'; }
      await this.save(m); throw e;
    }
  }
}
