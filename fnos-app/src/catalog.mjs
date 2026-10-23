import { mkdir, readFile, stat } from 'node:fs/promises';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { Store, json } from './store.mjs';
import { Workspace, workspaceSummary, workspaceStamp } from './workspace.mjs';
import { binding, WebDav } from './webdav.mjs';
import { LocalGrants, LocalCommitStore, segments } from './local.mjs';
import { ensure, uuid, decode } from './protocol.mjs';
import { loadGraph } from './graph.mjs';
import { SyncEngine, verifyEndpointMove, verifyRestoredHistory } from './sync.mjs';

const DEFAULT = '00000000-0000-4000-8000-000000000001';
const label = value => {
  ensure(typeof value === 'string' && value.trim().length > 0 && value.length <= 120, '名称应为 1–120 字符');
  return value.trim();
};
export class Catalog {
  static async open({ store, configPath, remoteFactory = c => new WebDav(c), authorizedRoots = [] }) {
    const privateRoot = join(store.dir, 'local-data');
    await mkdir(privateRoot, { recursive: true, mode: 0o700 });
    const self = new Catalog();
    Object.assign(self, { store, remoteFactory, grants: new LocalGrants([{ id: 'private', name: 'FPK 私有本地数据', path: privateRoot }, ...authorizedRoots]), guardQueue: Promise.resolve(), workspaces: new Map(), loading: new Map(), busy: new Set(), configuring: false });
    self.restoreGuards = (await store.read('restore-guards.json')) ?? {};
    self.migrations = (await store.read('dataset-migrations.json')) ?? [];
    self.manifest = await store.read('catalog.json');
    if (!self.manifest) {
      // 固定迁移 ID + 最后提交 manifest；中断时复用完整副本，原始文件从不删除或改写。
      const target = self.workspaceStore(DEFAULT);
      if (!(await target.read('workspace.json'))) {
        const legacy = await store.read('workspace.json');
        if (legacy) await target.save('workspace.json', legacy);
        else {
          for (const name of ['checkpoint.json', 'observation.json']) {
            const value = await store.read(name); if (value) await target.save(name, value);
          }
          await Workspace.open(target, await store.restore());
        }
      }
      const db = await Workspace.open(target);
      let config;
      try { config = JSON.parse(await readFile(configPath, 'utf8')); }
      catch (e) { if (e.code !== 'ENOENT') throw Error('原有配置无法安全迁移'); }
      if (config) self.validateWebDav(config);
      const connectionId = config ? DEFAULT : null;
      const manifest = { schema: 1, revision: 0, activeId: DEFAULT,
        connections: config ? [{ id: connectionId, name: '原有 WebDAV', type: 'webdav', config }] : [],
        workspaces: [{ id: DEFAULT, name: '默认工作区', connectionId }] };
      self.attach(DEFAULT, db);
      await self.commit(manifest);
    }
    ensure(self.manifest.schema === 1, '不支持管理目录版本');
    ensure(self.manifest.workspaces.length > 0 && self.manifest.workspaces.length <= 64, '工作区数量无效');
    ensure(new Set(self.manifest.workspaces.map(w => w.id)).size === self.manifest.workspaces.length, '工作区身份重复');
    for (const row of self.manifest.workspaces) uuid(row.id);
    await self.load(self.manifest.activeId);
    // 已验证端点更新的恢复日志：先持久化意图，再修改帧，最后提交连接配置。
    if (self.manifest.transition) await self.finishTransition();
    self.row(self.manifest.activeId);
    return self;
  }
  attach(id, db) {
    db.guard = () => ensure(!this.maintenance && !this.invalidated && this.workspaces.get(id) === db, '正在备份恢复或工作区引用已失效');
    db.assertWritable = () => ensure(!this.protection(id), '换库保护 / 退役工作区只读；请在历史与版本继续或取消迁移');
    db.mutationGuard = (before, after) => {
      if (this.protection(id)) ensure(['local', 'baseline', 'frontier', 'datasetId', 'pending', 'conflict', 'receipt', 'abandoned'].every(k => json(before[k]) === json(after[k])), '换库保护禁止课程编辑、发布与冲突决策');
    };
    this.workspaces.set(id, db); return db;
  }
  workspaceStore(id) {
    uuid(id); const child = new Store(join(this.store.dir, 'workspaces', id));
    child.identity = this.store.identity; return child;
  }
  protection(id) {
    return this.migrations.find(m => m.sourceId === id && m.phase !== 'cancelled' || m.targetId === id && m.phase !== 'retired');
  }
  row(id) { uuid(id); const row = this.manifest.workspaces.find(x => x.id === id); ensure(row, '工作区不存在'); return row; }
  db(id) { this.row(id); const db = this.workspaces.get(id); ensure(db, '工作区不可用'); return db; }
  async load(id) {
    ensure(!this.invalidated, '恢复后旧管理引用已失效');
    this.row(id);
    if (this.workspaces.has(id)) return this.workspaces.get(id);
    if (!this.loading.has(id)) this.loading.set(id, (async () => {
      const store = this.workspaceStore(id);
      // 已注册状态绝不进入 Workspace 的新建分支。
      const info = await stat(join(store.dir, 'workspace.json')).catch(() => null);
      ensure(info?.isFile(), '工作区文件缺失；拒绝重建覆盖，请从备份恢复');
      const db = await Workspace.open(store);
      this.attach(id, db);
      return db;
    })().finally(() => this.loading.delete(id)));
    return this.loading.get(id);
  }
  async status(id, repair = false) {
    if (this.workspaces.has(id)) return workspaceSummary(this.db(id));
    const store = this.workspaceStore(id);
    try {
      const stamp = await workspaceStamp(store), cached = await store.read('summary.json');
      if (cached?.schema === 1 && cached.stamp === stamp && cached.value?.unknown === false && Number.isSafeInteger(cached.value.version)
        && ['dirty', 'pending', 'conflict', 'recovery'].every(k => typeof cached.value[k] === 'boolean')) return cached.value;
    } catch { /* 缺失、损坏和过期摘要不能表示已同步 */ }
    if (repair) { const db = await this.load(id); await db.saveSummary(); return workspaceSummary(db); }
    return { unknown: true, version: null, datasetId: null, dirty: null, pending: null, conflict: null, recovery: null, error: '状态待检查；切换时读取并验证工作区' };
  }
  async identities() {
    const result = new Map();
    for (const w of this.manifest.workspaces) result.set(w.id, (await this.status(w.id, true)).datasetId);
    return result;
  }
  prune() {
    if (this.configuring || this.maintenance || this.loading.size) return;
    for (const [id, db] of this.workspaces) if (id !== this.manifest.activeId && !this.busy.has(id) && !db.writing) this.workspaces.delete(id);
  }
  connection(id) { uuid(id); const c = this.manifest.connections.find(x => x.id === id); ensure(c, '连接不存在'); return c; }
  async commit(next) { await this.store.save('catalog.json', next); this.manifest = next; }
  validateWebDav(c) {
    ensure(typeof c.url === 'string' && c.url.length <= 4096 && typeof c.username === 'string' && c.username.length <= 1024 && typeof c.password === 'string' && c.password.length <= 4096 && c.username && c.password, 'WebDAV 地址或凭据无效');
    binding(c);
  }
  async candidate(input, old = null) {
    const type = input.type ?? old?.type;
    ensure(['webdav', 'local'].includes(type) && (!old || type === old.type), '存储类型无效');
    const id = old?.id ?? randomUUID(), name = label(input.name ?? old?.name);
    let config;
    if (type === 'webdav') {
      config = { url: input.url ?? old?.config.url, username: input.username ?? old?.config.username,
        password: input.password || old?.config.password, certificatePem: input.certificatePem ?? old?.config.certificatePem };
      this.validateWebDav(config);
    } else {
      const rootId = input.rootId ?? old?.config.rootId;
      let relative = input.relative ?? old?.config.relative ?? '';
      segments(relative);
      if (rootId === 'private' && !old && input.createPrivate === true) relative = await this.grants.privateFolder(id);
      const dir = await this.grants.directory(rootId, relative);
      try { config = { rootId, relative, directoryIdentity: dir.stamp }; }
      finally { await dir.fd.close(); }
    }
    return { id, name, type, config };
  }
  remote(c) { return c.type === 'local' ? new LocalCommitStore(c.config, this.grants) : this.remoteFactory(c.config); }
  async summary() {
    const summaries = new Map();
    for (const row of this.manifest.workspaces) summaries.set(row.id, await this.status(row.id));
    return { revision: this.manifest.revision, activeId: this.manifest.activeId,
      roots: this.grants.roots.map(({ id, name }) => ({ id, name })),
      connections: this.manifest.connections.map(c => ({ id: c.id, name: c.name, type: c.type,
        ...(c.type === 'webdav' ? { url: c.config.url, username: c.config.username, hasCredentials: !!c.config.password, hasTrustedCertificate: !!c.config.certificatePem } : { rootId: c.config.rootId, relative: c.config.relative }) })),
      workspaces: this.manifest.workspaces.map(row => ({ ...row, ...summaries.get(row.id), restoredPendingValidation: !!this.restoreGuards[row.id], recovery: this.restoreGuards[row.id] ? true : summaries.get(row.id).recovery, busy: this.busy.has(row.id) })) };
  }

  async exclusive(version, action) {
    ensure(!this.configuring && !this.maintenance && !this.invalidated, '正在保存配置或备份恢复'); this.configuring = true;
    try {
      ensure(version === this.manifest.revision, '管理版本过期');
      ensure(!this.manifest.transition, '连接更新需要重启恢复');
      return await action();
    } finally { this.configuring = false; }
  }
  async finishTransition() {
    const { connection, frames } = this.manifest.transition;
    for (const update of frames) {
      const db = await this.load(update.id);
      if (db.frame.bindingKey !== update.key) await db.tx(f => {
        f.bindingKey = update.key;
        f.checkpoint = { ...f.checkpoint, bindingKey: update.key };
        f.error = null;
      });
    }
    const next = structuredClone(this.manifest);
    next.connections = next.connections.map(c => c.id === connection.id ? connection : c);
    delete next.transition; next.revision++;
    await this.commit(next);
  }
  async manage(input) {
    return this.exclusive(input.catalogVersion, async () => {
      if (input.workspaceId) {
        const db = await this.load(input.workspaceId); await db.queue;
        ensure(db.frame.revision === input.version, '请求版本过期');
      }
      if (['workspace.endpoint', 'workspace.remove', 'workspace.rename'].includes(input.action)) {
        await (await this.load(input.targetId)).queue;
        ensure(this.db(input.targetId).frame.revision === input.targetVersion, '目标工作区版本过期');
      }
      const next = structuredClone(this.manifest);
      if (input.action !== 'workspace.switch' && input.action !== 'workspace.rename') {
        ensure(!this.migrations.some(m => m.phase !== 'cancelled' && m.targetConnectionId === input.connectionId), '迁移目标连接受保护');
        const affected = input.connectionId ? this.manifest.workspaces.filter(w => w.connectionId === input.connectionId) : [];
        ensure(![input.workspaceId, input.targetId, ...affected.map(w => w.id)].filter(Boolean).some(id => this.protection(id)), '迁移关联配置受保护；先完成或取消迁移');
      }
      if (input.action === 'connection.save') {
        ensure(next.connections.length < 64 || input.connectionId, '连接数量超限');
        const old = input.connectionId ? this.connection(input.connectionId) : null;
        const affected = old ? next.workspaces.filter(w => w.connectionId === old.id) : [];
        ensure(affected.every(w => !this.busy.has(w.id)), '连接的工作区正在同步');
        const c = await this.candidate(input, old), frames = [];
        if (old && JSON.stringify(c.config) === JSON.stringify(old.config)) {
          next.connections = next.connections.map(x => x.id === c.id ? c : x);
          next.revision++; await this.commit(next); return { connectionId: c.id };
        }
        for (const w of affected) {
          const db = await this.load(w.id); await db.queue;
          if (db.frame.datasetId) await verifyEndpointMove(db, this.remote(c));
          else ensure(!db.frame.checkpoint.datasetId && !db.frame.graph?.datasetId && !Object.keys(db.frame.checkpoint.hashes ?? {}).length && !db.frame.checkpoint.observed?.length && !db.frame.checkpoint.deferred?.length && !db.frame.pending, '已观察数据集或未完成恢复，不能更改连接');
          frames.push({ id: w.id, key: this.remote(c).key });
        }
        if (old) {
          next.transition = { connection: c, frames };
          await this.commit(next); await this.finishTransition(); return { connectionId: c.id };
        }
        next.connections.push(c);
        next.revision++; await this.commit(next); return { connectionId: c.id };
      } else if (input.action === 'workspace.add') {
        ensure(next.workspaces.length < 64, '工作区数量超限');
        const c = this.connection(input.connectionId);
        ensure(!next.workspaces.some(w => w.connectionId === c.id), '连接已有工作区，请切换或验证端点迁移');
        const id = randomUUID(), db = await Workspace.open(this.workspaceStore(id));
        this.attach(id, db);
        next.workspaces.push({ id, name: label(input.name), connectionId: c.id });
        next.revision++; await this.commit(next); return { workspaceId: id };
      } else if (input.action === 'workspace.switch') {
        await this.load(input.targetId); next.activeId = input.targetId;
      } else if (input.action === 'workspace.rename') {
        next.workspaces.find(w => w.id === this.row(input.targetId).id).name = label(input.name);
      } else if (input.action === 'workspace.endpoint') {
        const row = this.row(input.targetId), db = await this.load(row.id), c = this.connection(input.connectionId);
        ensure(!this.busy.has(row.id), '工作区正在同步');
        ensure(!next.workspaces.some(w => w.id !== row.id && w.connectionId === c.id), '连接已被其他工作区使用');
        await db.queue;
        if (db.frame.datasetId) await verifyEndpointMove(db, this.remote(c));
        else ensure(!db.frame.bindingKey && !db.frame.pending && !db.frame.checkpoint.observed?.length, '已有观察历史，不能替换连接');
        // 帧保存后 manifest 失败时 key 不匹配会安全停止，重新验证可恢复；课程状态从不重置。
        await db.tx(f => { f.bindingKey = this.remote(c).key; f.checkpoint.bindingKey = f.bindingKey; });
        next.workspaces.find(w => w.id === row.id).connectionId = c.id;
      } else if (input.action === 'workspace.remove') {
        const row = this.row(input.targetId), db = await this.load(row.id); await db.queue;
        ensure(next.workspaces.length > 1 && row.id !== next.activeId && !this.busy.has(row.id), '不能移除当前、唯一或正在同步的工作区');
        const f = db.frame;
        const neverObserved = !f.datasetId && !f.baseline && !f.graph && !f.bindingKey && !Object.keys(f.checkpoint).length;
        ensure(!db.dirty() && !f.pending && !f.conflict && (!f.error || neverObserved) && !f.checkpoint.deferred?.length && !f.checkpoint.observed?.some(id => !f.checkpoint.hashes?.[id]) && !Object.keys(f.abandoned).length, '存在修改、冲突或恢复信息，拒绝移除');
        ensure(input.confirm === row.name, '请输入工作区名称确认移除配置');
        next.workspaces = next.workspaces.filter(w => w.id !== row.id);
        // 保留完整状态文件，移除配置永不删除课程/历史。
      } else if (input.action === 'connection.remove') {
        const c = this.connection(input.connectionId);
        ensure(!next.workspaces.some(w => w.connectionId === c.id), '连接仍被工作区使用');
        next.connections = next.connections.filter(x => x.id !== c.id);
      } else throw Error('未知管理操作');
      next.revision++; await this.commit(next); return {};
    });
  }
  async test(input) {
    ensure(!this.maintenance && !this.invalidated, '正在备份恢复');
    ensure(!input.createPrivate, '测试不会创建私有目录，请先保存连接');
    const c = await this.candidate(input, input.connectionId ? this.connection(input.connectionId) : null);
    const remote = this.remote(c);
    if (remote.test) await remote.test();
    const graph = await loadGraph(remote);
    return { ok: true, observed: Object.keys(graph.hashes).length, datasetId: graph.datasetId, deferred: graph.deferred.length, permission: c.type === 'local' ? '已验证读写与排他原子发布' : '已验证只读连接；发布权限将在创建/同步时验证' };
  }
  async network(id, action, { probeIdentity = true } = {}) {
    ensure(!this.configuring && !this.maintenance && !this.manifest.transition, '正在保存配置或等待恢复');
    const db = await this.load(id), row = this.row(id);
    ensure(!this.configuring && !this.maintenance, '正在保存配置或备份恢复');
    ensure(!this.busy.has(id), '工作区正在执行网络操作');
    // 首次绑定串行检查身份，避免两个地址并发绑定同一 dataset。
    const binding = !db.frame.datasetId;
    ensure(!binding || !this.bindingBusy, '正在验证其他工作区的数据集身份');
    if (binding) this.bindingBusy = true;
    this.busy.add(id);
    try {
      ensure(row.connectionId, '请先添加连接并创建工作区');
      const remote = this.remote(this.connection(row.connectionId));
      const identities = await this.identities();
      if (this.restoreGuards[id]) {
        ensure(!this.restoreGuards[id].bindingKey || this.restoreGuards[id].bindingKey === remote.key, '恢复后的端点身份不匹配');
        await verifyRestoredHistory(db, remote);
        const cleared = this.guardQueue.then(async () => {
          const nextGuards = { ...this.restoreGuards }; delete nextGuards[id];
          await this.store.save('restore-guards.json', nextGuards); this.restoreGuards = nextGuards;
        });
        this.guardQueue = cleared.catch(() => {}); await cleared;
      }
      if (db.frame.datasetId) ensure(!this.manifest.workspaces.some(w => w.id !== id && identities.get(w.id) === db.frame.datasetId), '数据集重复绑定，请保留原工作区');
      if (!db.frame.datasetId && probeIdentity) {
        // 只做一个有界身份探测；完整闭包仍由核心验证，避免把最大图完整下载两次。
        const ids = await remote.list();
        if (ids.length) {
          const bytes = await remote.read(ids[0]); let probe = null;
          try { if (bytes) probe = decode(bytes, ids[0]); } catch { /* 核心保留损坏文件的既有恢复语义 */ }
          if (probe) ensure(!this.manifest.workspaces.some(w => w.id !== id && identities.get(w.id) === probe.datasetId), '数据集已绑定其他工作区；请使用该工作区或验证其端点迁移');
        }
      }
      // observe 每次验证后、应用图前再次检查身份，防止预检后远端变更。
      const engine = new SyncEngine(db, remote, datasetId => {
        ensure(!this.manifest.workspaces.some(w => w.id !== id && identities.get(w.id) === datasetId), '数据集已绑定其他工作区');
      });
      await action(engine); await db.tx(f => { f.error = null; });
    } catch (e) {
      if (!this.protection(id)) await db.tx(f => { f.error = e.message; }); throw e;
    } finally { this.busy.delete(id); if (binding) this.bindingBusy = false; }
  }
}
