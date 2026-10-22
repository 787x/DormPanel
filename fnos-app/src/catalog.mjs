import { mkdir, readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { Store } from './store.mjs';
import { Workspace } from './workspace.mjs';
import { binding, WebDav } from './webdav.mjs';
import { LocalGrants, LocalCommitStore, segments } from './local.mjs';
import { ensure, uuid, decode } from './protocol.mjs';
import { loadGraph } from './graph.mjs';
import { SyncEngine, verifyEndpointMove } from './sync.mjs';

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
    Object.assign(self, { store, remoteFactory, grants: new LocalGrants([{ id: 'private', name: 'FPK 私有本地数据', path: privateRoot }, ...authorizedRoots]), workspaces: new Map(), busy: new Set(), configuring: false });
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
      self.workspaces.set(DEFAULT, db);
      await self.commit(manifest);
    }
    ensure(self.manifest.schema === 1, '不支持管理目录版本');
    for (const row of self.manifest.workspaces) {
      uuid(row.id);
      ensure(await self.workspaceStore(row.id).read('workspace.json'), '工作区文件缺失；拒绝重建覆盖，请从备份恢复');
      if (!self.workspaces.has(row.id)) self.workspaces.set(row.id, await Workspace.open(self.workspaceStore(row.id)));
    }
    // 已验证端点更新的恢复日志：先持久化意图，再修改帧，最后提交连接配置。
    if (self.manifest.transition) await self.finishTransition();
    self.row(self.manifest.activeId);
    return self;
  }
  workspaceStore(id) {
    uuid(id); const child = new Store(join(this.store.dir, 'workspaces', id));
    child.identity = this.store.identity; return child;
  }
  row(id) { uuid(id); const row = this.manifest.workspaces.find(x => x.id === id); ensure(row, '工作区不存在'); return row; }
  db(id) { this.row(id); const db = this.workspaces.get(id); ensure(db, '工作区不可用'); return db; }
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
    return { revision: this.manifest.revision, activeId: this.manifest.activeId,
      roots: this.grants.roots.map(({ id, name }) => ({ id, name })),
      connections: this.manifest.connections.map(c => ({ id: c.id, name: c.name, type: c.type,
        ...(c.type === 'webdav' ? { url: c.config.url, username: c.config.username, hasCredentials: !!c.config.password, hasTrustedCertificate: !!c.config.certificatePem } : { rootId: c.config.rootId, relative: c.config.relative }) })),
      workspaces: this.manifest.workspaces.map(row => { const db = this.db(row.id), f = db.frame;
        return { ...row, version: f.revision, datasetId: f.datasetId, dirty: db.dirty(), pending: !!f.pending, conflict: !!f.conflict, error: f.error, checkedAt: f.graph?.checkedAt ?? null, busy: this.busy.has(row.id), recovery: !!f.checkpoint.deferred?.length || Object.keys(f.abandoned).length > 0 }; }) };
  }
  async exclusive(version, action) {
    ensure(!this.configuring, '正在保存配置'); this.configuring = true;
    try {
      ensure(version === this.manifest.revision, '管理版本过期');
      ensure(!this.manifest.transition, '连接更新需要重启恢复');
      return await action();
    } finally { this.configuring = false; }
  }
  async finishTransition() {
    const { connection, frames } = this.manifest.transition;
    for (const update of frames) {
      const db = this.db(update.id);
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
        const db = this.db(input.workspaceId); await db.queue;
        ensure(db.frame.revision === input.version, '请求版本过期');
      }
      if (['workspace.endpoint', 'workspace.remove', 'workspace.rename'].includes(input.action)) {
        await this.db(input.targetId).queue;
        ensure(this.db(input.targetId).frame.revision === input.targetVersion, '目标工作区版本过期');
      }
      const next = structuredClone(this.manifest);
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
          const db = this.db(w.id); await db.queue;
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
        this.workspaces.set(id, db);
        next.workspaces.push({ id, name: label(input.name), connectionId: c.id });
        next.revision++; await this.commit(next); return { workspaceId: id };
      } else if (input.action === 'workspace.switch') {
        this.row(input.targetId); next.activeId = input.targetId;
      } else if (input.action === 'workspace.rename') {
        next.workspaces.find(w => w.id === this.row(input.targetId).id).name = label(input.name);
      } else if (input.action === 'workspace.endpoint') {
        const row = this.row(input.targetId), db = this.db(row.id), c = this.connection(input.connectionId);
        ensure(!this.busy.has(row.id), '工作区正在同步');
        ensure(!next.workspaces.some(w => w.id !== row.id && w.connectionId === c.id), '连接已被其他工作区使用');
        await db.queue;
        if (db.frame.datasetId) await verifyEndpointMove(db, this.remote(c));
        else ensure(!db.frame.bindingKey && !db.frame.pending && !db.frame.checkpoint.observed?.length, '已有观察历史，不能替换连接');
        // 帧保存后 manifest 失败时 key 不匹配会安全停止，重新验证可恢复；课程状态从不重置。
        await db.tx(f => { f.bindingKey = this.remote(c).key; f.checkpoint.bindingKey = f.bindingKey; });
        next.workspaces.find(w => w.id === row.id).connectionId = c.id;
      } else if (input.action === 'workspace.remove') {
        const row = this.row(input.targetId), db = this.db(row.id); await db.queue;
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
    ensure(!input.createPrivate, '测试不会创建私有目录，请先保存连接');
    const c = await this.candidate(input, input.connectionId ? this.connection(input.connectionId) : null);
    const remote = this.remote(c);
    if (remote.test) await remote.test();
    const graph = await loadGraph(remote);
    return { ok: true, observed: Object.keys(graph.hashes).length, datasetId: graph.datasetId, deferred: graph.deferred.length, permission: c.type === 'local' ? '已验证读写与排他原子发布' : '已验证只读连接；发布权限将在创建/同步时验证' };
  }
  async network(id, action) {
    ensure(!this.configuring && !this.manifest.transition, '正在保存配置或等待恢复');
    const db = this.db(id), row = this.row(id);
    ensure(!this.busy.has(id), '工作区正在执行网络操作');
    // 首次绑定串行检查身份，避免两个地址并发绑定同一 dataset。
    const binding = !db.frame.datasetId;
    ensure(!binding || !this.bindingBusy, '正在验证其他工作区的数据集身份');
    if (binding) this.bindingBusy = true;
    this.busy.add(id);
    try {
      ensure(row.connectionId, '请先添加连接并创建工作区');
      const remote = this.remote(this.connection(row.connectionId));
      if (db.frame.datasetId) ensure(!this.manifest.workspaces.some(w => w.id !== id && this.db(w.id).frame.datasetId === db.frame.datasetId), '数据集重复绑定，请保留原工作区');
      if (!db.frame.datasetId) {
        // 只做一个有界身份探测；完整闭包仍由核心验证，避免把最大图完整下载两次。
        const ids = await remote.list();
        if (ids.length) {
          const bytes = await remote.read(ids[0]); let probe = null;
          try { if (bytes) probe = decode(bytes, ids[0]); } catch { /* 核心保留损坏文件的既有恢复语义 */ }
          if (probe) ensure(!this.manifest.workspaces.some(w => w.id !== id && this.db(w.id).frame.datasetId === probe.datasetId), '数据集已绑定其他工作区；请使用该工作区或验证其端点迁移');
        }
      }
      // observe 每次验证后、应用图前再次检查身份，防止预检后远端变更。
      const engine = new SyncEngine(db, remote, datasetId => {
        ensure(!this.manifest.workspaces.some(w => w.id !== id && this.db(w.id).frame.datasetId === datasetId), '数据集已绑定其他工作区');
      });
      await action(engine); await db.tx(f => { f.error = null; });
    } catch (e) {
      await db.tx(f => { f.error = e.message; }); throw e;
    } finally { this.busy.delete(id); if (binding) this.bindingBusy = false; }
  }
}
