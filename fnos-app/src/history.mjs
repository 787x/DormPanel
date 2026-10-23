import { randomUUID } from 'node:crypto';
import { decode, encode, ensure, hash, schemas, stateBytes, termKey } from './protocol.mjs';
import { loadGraph, graphPlan } from './graph.mjs';
import { same, emptyState } from './merge.mjs';
import { json } from './store.mjs';
import { LocalCommitStore } from './local.mjs';
import { SyncEngine } from './sync.mjs';

const digest = value => hash(Buffer.from(json(value))).toString('hex');
const nextSteps = [
  '当前仅准备独立候选，任何已绑定 FPK / Android 均未切换，旧库全部保留。',
  '管理员先登记所有客户端和 Writer，协调停止旧库写入；本协议无法证明全体 Writer 已退役。',
  '在历史与版本的“迁移到精简数据集”中选择 compact 目录与 Android 实际使用的独立 WebDAV 连接，按向导创建新工作区；不要把新根复制到旧目录。',
  '已绑定 Android 使用“更换共享课表数据集 / 恢复换库”逐台验证、暂停、切换和验收；不得清空数据库或改写确认哈希来绕过身份校验。',
  '逐台验收课程、事件、来源、原始实例、覆盖和学期配置，并再次检查旧库无新分支；全部验收完成前不得退役旧库。',
  '旧库退役、归档保管与磁盘回收由管理员另行安排；此工具不提供旧 DPC 自动删除。',
];
export const graphIdentity = g => digest({ datasetId: g.datasetId,
  hashes: Object.fromEntries(Object.entries(g.hashes).sort(([a], [b]) => a.localeCompare(b))), tips: [...g.tips].sort(), deferred: [...g.deferred].sort() });
export function commitAt(g, id) {
  ensure(g?.raw?.[id], '版本未经过完整验证');
  const b = Buffer.from(g.raw[id], 'base64');
  ensure(hash(b).toString('hex') === g.hashes[id], '历史身份变化');
  return decode(b, id);
}
export async function cachedGraph(f) {
  ensure(f.graph, '尚无已验证历史，请检查远端');
  for (const [id, value] of Object.entries(f.graph.hashes)) ensure(!f.checkpoint.hashes?.[id] || f.checkpoint.hashes[id] === value, '缓存已知哈希变化');
  // 失败观察可能已记录缓存以外的新路径。仅验证旧闭包供只读查看，异常仍明确阻止写入。
  return loadGraph({ list: async () => Object.keys(f.graph.raw), read: async id => f.graph.raw[id] ? Buffer.from(f.graph.raw[id], 'base64') : null }, f.graph);
}
// 所有源数据集读取复用同步观察：失败也持久化已观察路径、哈希与待回查集合。
export const freshGraph = (db, remote, validateDataset) => new SyncEngine(db, remote, validateDataset).observe({ expectedRevision: db.frame.revision });
export function blockers(db, g) {
  const f = db.frame;
  return [!f.datasetId || g.datasetId !== f.datasetId ? '数据集身份未确认' : null,
    !g.tips.length || g.deferred.length ? '历史不完整' : null,
    f.checkpoint.deferred?.length || f.checkpoint.observed?.some(id => !g.hashes[id]) ? '存在暂不可验证的观察路径' : null,
    g.status === 'branches' ? '多个前沿状态有分歧，先处理分支' : null,
    f.pending ? '有待确认上传' : null, db.dirty() ? '有未发布本地修改' : null,
    f.conflict ? '有未解决冲突' : null, f.error ? '有同步异常，请重新检查' : null,
    f.restoreGuard || Object.keys(f.abandoned).length ? '有待恢复状态' : null,
    !g.state || !same(g.state, f.local) ? '当前课程尚未与远端前沿一致' : null].filter(Boolean);
}
const names = { entries: '本地课程', events: '日历事件', sources: '导入来源', imported: '原始课程实例', classOverrides: '全周 / 指定周覆盖', dayAdjustments: '停课 / 补课及日期调整', profiles: '学期、阶段作息及节次', customProfileKeys: '自定义学期身份' };
// 稳定业务身份比较；来源、实例与覆盖通过 group 关联，绝不按标题匹配。
export function differences(a, b, offset = 0, limit = 40, restored = new Map()) {
  ensure(Number.isSafeInteger(offset) && offset >= 0 && Number.isSafeInteger(limit) && limit > 0 && limit <= 80, '分页无效');
  const counts = {}, rows = [], groups = new Map(); let total = 0;
  for (const [table, , key] of schemas) {
    const left = new Map(a[table].map(r => [key ? r[key] : r, r])), right = new Map(b[table].map(r => [key ? r[key] : r, r]));
    const count = { added: 0, restored: 0, deleted: 0, modified: 0 };
    for (const id of [...new Set([...left.keys(), ...right.keys()])].sort()) {
      const before = left.get(id), after = right.get(id);
      if (json(before ?? null) === json(after ?? null)) continue;
      const action = before === undefined ? restored.get(table)?.has(id) ? 'restored' : 'added' : after === undefined ? 'deleted' : 'modified'; count[action]++;
      const r = after ?? before;
      const group = table === 'sources' ? `source:${id}` : r.sourceId ? `source:${r.sourceId}` : table === 'classOverrides' ? `entry:${r.seriesId}` : `${table}:${id}`;
      if (!groups.has(group)) {
        const sourceId = group.startsWith('source:') ? group.slice(7) : null;
        const source = sourceId ? b.sources.find(s => s.id === sourceId) ?? a.sources.find(s => s.id === sourceId) : null;
        groups.set(group, { id: group, title: source?.displayName ?? r.title ?? r.termKey ?? id, termKey: source ? termKey(source) : null, added: 0, restored: 0, deleted: 0, modified: 0 });
      }
      groups.get(group)[action]++;
      if (total >= offset && rows.length < limit) rows.push({ table, label: names[table], id, action,
        group,
        title: r.title ?? r.displayName ?? r.termKey ?? id, before, after });
      total++;
    }
    counts[table] = { label: names[table], ...count };
  }
  return { counts, total, offset, next: offset + rows.length < total ? offset + rows.length : null, rows,
    groups: [...groups.values()].slice(offset, offset + limit), groupTotal: groups.size };
}
const identities = state => new Map(schemas.map(([table, , key]) => [table, new Set(state[table].map(r => key ? r[key] : r))]));
export function versionDifferences(g, from, to, offset = 0) {
  const before = commitAt(g, from), target = commitAt(g, to), restored = new Map(), visited = new Set();
  const visit = id => {
    if (visited.has(id)) return; visited.add(id); const c = commitAt(g, id);
    for (const [table, ids] of identities(c.state)) { if (!restored.has(table)) restored.set(table, new Set()); for (const key of ids) restored.get(table).add(key); }
    c.parents.forEach(visit);
  };
  before.parents.forEach(visit);
  return differences(before.state, target.state, offset, 40, restored);
}
export function historySummary(db, g) {
  const levels = new Map(), rows = [];
  const commits = new Map(Object.keys(g.raw).map(id => [id, commitAt(g, id)]));
  function visit(id) { if (levels.has(id)) return levels.get(id); const c = commits.get(id); const level = c.parents.length ? Math.max(...c.parents.map(visit)) + 1 : 0; levels.set(id, level); return level; }
  for (const id of Object.keys(g.raw)) {
    const c = commits.get(id);
    const comparisons = c.parents.map(parentId => {
      const d = differences(commits.get(parentId).state, c.state, 0, 1);
      return { parentId, changes: d.counts, total: d.total };
    });
    const d = c.parents.length > 1 ? null : c.parents.length ? comparisons[0] : differences(emptyState(), c.state, 0, 1);
    rows.push({ id, writer: c.writerId, parents: c.parents, sha256: g.hashes[id], level: visit(id), tip: g.tips.includes(id), matchesCurrent: same(c.state, db.frame.local), changes: d?.counts ?? d?.changes ?? null, total: d?.total ?? null,
      ...(c.parents.length > 1 ? { comparisons } : {}) });
  }
  rows.sort((a, b) => b.level - a.level || a.id.localeCompare(b.id));
  return { datasetId: g.datasetId, count: rows.length, capacity: 256, bytes: Object.values(g.raw).reduce((n, r) => n + Buffer.from(r, 'base64').length, 0), tips: g.tips, status: g.status,
    warning: rows.length >= 230 ? '历史容量接近或达到上限；不能删除祖先，请准备独立新数据集' : null,
    blockers: blockers(db, g), rollbackEnabled: !blockers(db, g).length && rows.length < 256,
    cached: true, error: db.frame.error, deferred: g.deferred, pending: !!db.frame.pending, dirty: db.dirty(), rows };
}

export class HistoryManager {
  constructor(catalog) { this.catalog = catalog; this.tokens = new Map(); }
  fingerprint(db, g) {
    // 观察会推进 revision 并刷新缓存 / checkpoint；业务输入及远端图身份独立核对。
    // 确认入口另行校验预览结束时的精确 revision，防止此前的操作被忽略。
    const { revision, graph, checkpoint, ...inputs } = db.snapshot(), c = this.catalog;
    return digest({ inputs, graph: typeof g === 'string' ? g : graphIdentity(g), catalogVersion: c.manifest.revision, activeId: c.manifest.activeId });
  }
  async preview(db, engine, input, user) {
    const g = await freshGraph(db, engine.remote, engine.validateDataset); ensure(!blockers(db, g).length, blockers(db, g).join('；'));
    ensure(Object.keys(g.raw).length < 256, '历史容量已满，只能查看和归档');
    const target = commitAt(g, input.target), diff = differences(db.frame.local, target.state, 0, 40, identities(target.state));
    for (const [id, t] of this.tokens) if (t.expires < Date.now()) this.tokens.delete(id);
    ensure(this.tokens.size < 8, '预览过多'); const token = randomUUID();
    this.tokens.set(token, { user, workspaceId: input.workspaceId, version: db.frame.revision, target: input.target, raw: g.raw[input.target], fingerprint: this.fingerprint(db, g), graph: graphIdentity(g), expires: Date.now() + 10 * 60000 });
    return { token, target: input.target, diff, unchanged: !diff.total, version: db.frame.revision };
  }
  async rollback(db, engine, input, user) {
    const t = this.tokens.get(input.token);
    ensure(t && t.user === user && t.workspaceId === input.workspaceId && t.target === input.target && t.expires > Date.now() && input.confirm === input.target, '回退预览已失效或确认输入变化');
    ensure(input.version === db.frame.revision && t.version === db.frame.revision, '回退预览版本过期，请刷新');
    const g = await freshGraph(db, engine.remote, engine.validateDataset);
    ensure(this.fingerprint(db, g) === t.fingerprint && g.raw[t.target] === t.raw, '回退输入变化，请刷新预览');
    ensure(!blockers(db, g).length && Object.keys(g.raw).length < 256, '当前状态不能回退');
    graphPlan(g, db.frame.baseline, db.frame.frontier);
    const target = commitAt(g, t.target); this.tokens.delete(input.token);
    if (same(db.frame.local, target.state)) return { unchanged: true, version: db.frame.revision };
    await db.tx(f => { f.local = target.state; db.stage(f, f.local, g.tips); }, db.frame.revision);
    ensure(await engine.publish(), '回退尚未获得发布回执；保留候选，请检查并同步恢复');
    // 新提交回读已经确认；再用独立 I/O 预算观察远端，保留并发分支的真实状态。
    engine.remote = this.catalog.remote(this.catalog.connection(this.catalog.row(input.workspaceId).connectionId));
    await engine.observe();
    return { ok: true, version: db.frame.revision, receipt: db.frame.receipt };
  }
  previewDiff(db, input, user) {
    const t = this.tokens.get(input.token);
    ensure(t && t.user === user && t.workspaceId === input.workspaceId && t.version === db.frame.revision && t.expires > Date.now() && t.fingerprint === this.fingerprint(db, t.graph), '回退预览已失效，请刷新');
    const target = decode(Buffer.from(t.raw, 'base64'), t.target).state;
    return differences(db.frame.local, target, input.offset, 40, identities(target));
  }
  async archive(db, engine, input, compact = false) {
    const c = this.catalog, g = await freshGraph(db, engine.remote, engine.validateDataset);
    ensure(g.datasetId && g.tips.length && !g.deferred.length, '完整历史尚不可验证');
    if (compact) ensure(!blockers(db, g).length, blockers(db, g).join('；'));
    const fingerprint = this.fingerprint(db, g), sourceRevision = db.frame.revision;
    const location = await c.grants.protectedFolder(input.rootId, input.relative ?? '', 'history-' + randomUUID());
    const archive = new LocalCommitStore(location, c.grants); await archive.ensureDirectory();
    for (const id of Object.keys(g.raw)) await new LocalCommitStore(location, c.grants).put(id, Buffer.from(g.raw[id], 'base64'));
    // 使用独立适配器重置单次 I/O 预算，实际从文件系统逐个读回并重建闭包。
    const restored = await loadGraph(new LocalCommitStore(location, c.grants), { datasetId: g.datasetId, hashes: g.hashes });
    ensure(graphIdentity(restored) === graphIdentity(g), '归档回读不等价');
    const manifest = { format: 'DormPanel-history-1', datasetId: g.datasetId, tips: g.tips, coverage: '本轮完整验证的远端 DPC2 图；不包含应用凭据、本地未发布状态或其他数据集', verification: { fileReadback: true, sha256: true, ancestorClosure: true, identity: true }, bytes: Object.values(g.raw).reduce((n, b) => n + Buffer.from(b, 'base64').length, 0), commits: Object.keys(g.raw).map(id => ({ id, sha256: g.hashes[id], parents: commitAt(g, id).parents })), complete: true };
    let candidate = null;
    if (compact) {
      candidate = await c.grants.protectedFolder(input.rootId, input.relative ?? '', 'compact-' + randomUUID());
      const next = { datasetId: randomUUID(), commitId: randomUUID(), writerId: c.store.identity.writerId, parents: [], state: g.state }, bytes = encode(next);
      const remote = new LocalCommitStore(candidate, c.grants); await remote.ensureDirectory(); await remote.put(next.commitId, bytes);
      const check = await loadGraph(new LocalCommitStore(candidate, c.grants));
      ensure(check.tips.length === 1 && Object.keys(check.raw).length === 1 && check.datasetId !== g.datasetId && stateBytes(check.state).equals(stateBytes(g.state)), '精简副本快照不等价');
      candidate = { ...candidate, datasetId: next.datasetId, commitId: next.commitId };
    }
    ensure(db.frame.revision === sourceRevision, '源状态变化；请刷新后重新准备');
    const current = await freshGraph(db, c.remote(c.connection(c.row(input.workspaceId).connectionId)), engine.validateDataset);
    ensure(fingerprint === this.fingerprint(db, current), '源状态变化；输出仅为未完成候选，不能宣布迁移完成');
    await c.grants.writePrivate(location, 'verified.json', Buffer.from(json(manifest)));
    if (candidate) await c.grants.writePrivate(candidate, 'verified.json', Buffer.from(json({ format: 'DormPanel-compact-1', sourceDatasetId: g.datasetId, archive: location.relative, datasetId: candidate.datasetId, migrated: false, nextSteps })));
    return { archive: location.relative, candidate: candidate?.relative ?? null, datasetId: candidate?.datasetId ?? g.datasetId, count: Object.keys(g.raw).length, nextSteps: candidate ? nextSteps : undefined, message: '已回读验证。未切换任何客户端，旧库全部保留。所有设备须由管理员停写、切换并验收后另行退役旧库；本工具不删除旧历史。' };
  }
}
