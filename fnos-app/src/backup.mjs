import { constants as C, createReadStream } from 'node:fs';
import { open, mkdir, readdir, lstat, rename, unlink, link, rm, statfs, realpath } from 'node:fs/promises';
import { join, dirname, resolve } from 'node:path';
import { randomUUID, randomBytes, scrypt, createCipheriv, createDecipheriv, createHash } from 'node:crypto';
import { promisify } from 'node:util';
import { pipeline } from 'node:stream/promises';
import { Store, json } from './store.mjs';
import { Workspace, workspaceStamp } from './workspace.mjs';
import { ensure, uuid, decode } from './protocol.mjs';
import { loadGraph, graphPlan } from './graph.mjs';
import { verifyRestoredHistory } from './sync.mjs';
import { binding, tlsOptions } from './webdav.mjs';
import { segments } from './local.mjs';

// 有界、无压缩、无通用解包器。固定头 + AES-256-GCM(长度前缀清单 + 顺序文件)。
const MAGIC = Buffer.from('DPBK0001'), HEADER = 52;
export const LIMITS = { files: 4096, file: 64 * 1024 * 1024, total: 256 * 1024 * 1024, manifest: 1024 * 1024 };
const digest = bytes => createHash('sha256').update(bytes).digest('hex');
const fdPath = (fd, name) => `/proc/self/fd/${fd.fd}/${name}`;
const fileName = name => typeof name === 'string' && /^backup-[0-9a-f-]{36}\.dpbk$/.test(name);
async function writeAt(file, bytes, position) {
  let n = 0;
  while (n < bytes.length) { const r = await file.write(bytes, n, bytes.length - n, position + n); ensure(r.bytesWritten > 0, '归档写入未完成'); n += r.bytesWritten; }
}
async function readAt(file, bytes, position) {
  let n = 0;
  while (n < bytes.length) { const r = await file.read(bytes, n, bytes.length - n, position + n); ensure(r.bytesRead > 0, '归档读取截断'); n += r.bytesRead; }
  return bytes;
}
const privateFile = name => {
  if (!/^local-data\/(?:[^/]+\/)*dormpanel-sync-v2\/commit-[0-9a-f-]{36}\.dpc$/.test(name)) return false;
  try { segments(name.split('/').slice(1, -2).join('/')); return true; } catch { return false; }
};
const allowed = name => /^(identity|catalog|workspace|checkpoint|observation|graph|restore-guards)\.json$/.test(name)
  || /^archive-[0-9a-f-]{36}\.json$/.test(name)
  || /^workspaces\/[0-9a-f-]{36}\/(workspace|checkpoint|observation|graph)\.json$/.test(name)
  || privateFile(name)
  || name === 'config/webdav.json';
async function syncDir(path) {
  if (process.platform === 'win32') return;
  const f = await open(path, 'r'); try { await f.sync(); } finally { await f.close(); }
}
async function safePath(root, name, create = false) {
  ensure(allowed(name), '归档文件路径不允许');
  const base = await lstat(root); ensure(base.isDirectory() && !base.isSymbolicLink(), '拒绝链接或异常根目录');
  let current = resolve(root);
  for (const part of name.split('/').slice(0, -1)) {
    current = join(current, part);
    if (create) { try { await mkdir(current, { mode: 0o700 }); await syncDir(dirname(current)); } catch (e) { if (e.code !== 'EEXIST') throw e; } }
    const s = await lstat(current); ensure(s.isDirectory() && !s.isSymbolicLink(), '拒绝链接或异常目录');
  }
  const path = join(root, name), s = await lstat(path).catch(e => { if (e.code !== 'ENOENT') throw e; return null; });
  ensure(!s || (s.isFile() && !s.isSymbolicLink()), '拒绝链接或异常文件');
  return path;
}
async function readBounded(path, max = LIMITS.file) {
  const f = await open(path, C.O_RDONLY | C.O_NOFOLLOW | C.O_NONBLOCK);
  try {
    const before = await f.stat(); ensure(before.isFile() && before.size <= max, '备份文件类型或大小超限');
    const b = Buffer.alloc(before.size + 1); let n = 0;
    while (n < b.length) { const r = await f.read(b, n, b.length - n, n); if (!r.bytesRead) break; n += r.bytesRead; }
    const after = await f.stat(); ensure(n === before.size && before.size === after.size && before.mtimeMs === after.mtimeMs && before.ctimeMs === after.ctimeMs, '备份文件发生变化');
    return b.subarray(0, n);
  } finally { await f.close(); }
}
async function* chunks(file, start, end) {
  let offset = start;
  while (offset < end) {
    const b = Buffer.alloc(Math.min(65536, end - offset));
    const { bytesRead } = await file.read(b, 0, b.length, offset);
    ensure(bytesRead > 0, '归档截断'); offset += bytesRead; yield b.subarray(0, bytesRead);
  }
}
async function atomic(path, bytes) {
  const tmp = path + '.' + randomUUID() + '.tmp', f = await open(tmp, 'wx', 0o600);
  try { await f.writeFile(bytes); await f.sync(); } finally { await f.close(); }
  try { await rename(tmp, path); await syncDir(dirname(path)); } finally { await unlink(tmp).catch(() => {}); }
}
async function key(password, salt) {
  ensure(typeof password === 'string' && password.length >= 12 && Buffer.byteLength(password) <= 1024, '备份口令须为 12–1024 字节');
  return promisify(scrypt)(password, salt, 32, { N: 32768, r: 8, p: 1, maxmem: 64 * 1024 * 1024 });
}
async function inventory(root, configPath) {
  const result = []; let directories = 0;
  async function scan(dir, prefix = '') {
    ensure(++directories <= LIMITS.files, '私有目录数量超限');
    for (const e of await readdir(dir, { withFileTypes: true })) {
      const name = prefix + e.name;
      if (e.name.startsWith('.') || /\.(tmp|log|pid|sock)$/.test(e.name) || ['socket.ready', 'summary.json'].includes(e.name)) continue;
      if (!prefix && !['local-data', 'workspaces'].includes(e.name) && !allowed(name)) continue;
      if (e.isDirectory()) {
        if (name.startsWith('local-data/')) segments(name.slice('local-data/'.length));
        if (!prefix || /^workspaces\/[0-9a-f-]{36}$/.test(name) || name.startsWith('local-data/')) await scan(join(dir, e.name), name + '/');
        else throw Error('私有持久化目录不能识别；拒绝遗漏备份');
      } else { ensure(e.isFile() && allowed(name), '私有数据类型不能识别；拒绝遗漏备份'); result.push(name); }
      ensure(result.length <= LIMITS.files, '备份文件数量超限');
    }
  }
  await scan(root);
  if (configPath && await lstat(configPath).catch(e => { if (e.code !== 'ENOENT') throw e; return null; })) result.push('config/webdav.json');
  return [...new Set(result)].sort();
}
const sourcePath = (root, config, name) => name === 'config/webdav.json' ? config : safePath(root, name);
async function snapshot(root, config, target) {
  const files = []; let total = 0;
  for (const name of await inventory(root, config)) {
    const bytes = await readBounded(await sourcePath(root, config, name));
    total += bytes.length; ensure(total <= LIMITS.total, '备份总大小超限');
    await atomic(await safePath(target, name, true), bytes);
    files.push({ name, size: bytes.length, sha256: digest(bytes) });
  }
  return files;
}
function validateManifest(m) {
  ensure(m?.format === 'DormPanel FPK backup' && m.version === 1 && ['0.4.0', '0.4.1', '0.4.2', '0.4.3'].includes(m.appVersion), '不支持备份格式或版本');
  uuid(m.identity?.writerId); uuid(m.identity?.installationId);
  ensure(Array.isArray(m.files) && m.files.length > 0 && m.files.length <= LIMITS.files, '备份文件数量超限');
  const names = new Set(); let total = 0;
  for (const f of m.files) {
    ensure(allowed(f.name) && !names.has(f.name), '备份路径非法或文件重复'); names.add(f.name);
    ensure(Number.isSafeInteger(f.size) && f.size >= 0 && f.size <= LIMITS.file && /^[0-9a-f]{64}$/.test(f.sha256), '文件大小或校验信息无效');
    total += f.size; ensure(total <= LIMITS.total, '备份总大小超限');
  }
  ensure(names.has('identity.json') && names.has('catalog.json') && Array.isArray(m.dependencies), '缺少恢复依赖');
  return total;
}
function dependency(row, connection, f) {
  return { workspaceId: row.id, workspaceName: row.name, revision: f.revision, pending: !!f.pending, conflict: !!f.conflict,
    courseCount: f.local.entries.length + f.local.imported.length, connectionId: row.connectionId, datasetId: f.datasetId,
    endpointIdentity: f.bindingKey, included: connection?.type === 'local' && connection.config.rootId === 'private',
    type: connection?.type ?? 'unbound', endpoint: connection?.type === 'webdav' ? connection.config.url : connection ? { rootId: connection.config.rootId, relative: connection.config.relative, directoryIdentity: connection.config.directoryIdentity } : null,
    hashes: { ...f.graph?.hashes, ...f.checkpoint.hashes }, frontier: f.frontier, deferred: f.checkpoint.deferred ?? [], abandoned: f.abandoned };
}
async function verifyFiles(root, m, identity, configPath = null) {
  validateManifest(m);
  ensure(json(m.identity) === json(identity), '仅支持同一安装身份恢复；不支持迁移或 writer 接管');
  for (const f of m.files) {
    const b = await readBounded(configPath && f.name === 'config/webdav.json' ? configPath : await safePath(root, f.name));
    ensure(b.length === f.size && digest(b) === f.sha256, '备份文件长度或 SHA-256 不匹配');
    if (f.name.endsWith('.dpc')) decode(b, /commit-([0-9a-f-]+)\.dpc$/.exec(f.name)[1]);
  }
  const store = new Store(root); store.identity = identity;
  ensure(json(await store.read('identity.json')) === json(identity), '清单与文件身份不匹配');
  const catalog = await store.read('catalog.json');
  ensure(catalog.schema === 1 && !catalog.transition && Array.isArray(catalog.workspaces) && catalog.workspaces.length > 0 && catalog.workspaces.length <= 64 && Array.isArray(catalog.connections) && catalog.connections.length <= 64, '管理目录或迁移状态无效');
  const ids = new Set(), connections = new Set();
  for (const c of catalog.connections) {
    uuid(c.id); ensure(!connections.has(c.id) && ['local', 'webdav'].includes(c.type), '连接身份无效'); connections.add(c.id);
    if (c.type === 'webdav') { binding(c.config); tlsOptions(c.config); ensure(typeof c.config.username === 'string' && typeof c.config.password === 'string' && c.config.username && c.config.password, '凭据配置无效'); }
    else { segments(c.config.relative); ensure(typeof c.config.rootId === 'string' && typeof c.config.directoryIdentity === 'string', '本地连接身份无效'); }
  }
  const datasets = new Set(), dependencies = [];
  for (const row of catalog.workspaces) {
    uuid(row.id); ensure(!ids.has(row.id) && (!row.connectionId || connections.has(row.connectionId)), '工作区或连接身份无效'); ids.add(row.id);
    ensure(m.files.some(f => f.name === `workspaces/${row.id}/workspace.json`), '备份缺少注册工作区');
    const child = new Store(join(root, 'workspaces', row.id)); child.identity = identity;
    const db = await Workspace.open(child), f = db.frame;
    const connection = catalog.connections.find(c => c.id === row.connectionId);
    dependencies.push(dependency(row, connection, f));
    if (f.datasetId) { uuid(f.datasetId); ensure(!datasets.has(f.datasetId), '备份数据集重复绑定'); datasets.add(f.datasetId); }
    if (f.graph) {
      const g = await loadGraph({ list: async () => Object.keys(f.graph.raw ?? {}), read: async id => f.graph.raw[id] ? Buffer.from(f.graph.raw[id], 'base64') : null },
        { datasetId: f.graph.datasetId, hashes: f.graph.hashes, deferred: f.graph.deferred });
      ensure(json(g.hashes) === json(f.graph.hashes), '缓存历史校验不匹配');
      if (f.baseline && f.frontier.every(id => g.raw[id])) graphPlan(g, f.baseline, f.frontier);
      else if (f.baseline) {
        // 已保存发布回执、尚未来得及重新 observe：旧 graph 可以落后于 frontier。
        // 不伪造缓存闭包，缺少的原字节仍属于清单记录的外部恢复依赖。
        for (const id of f.frontier) { uuid(id); ensure(f.checkpoint.hashes?.[id], '基线前沿缺少持久化历史校验'); }
      }
    }
    if (connection?.type === 'local' && connection.config.rootId === 'private') {
      const prefix = 'local-data/' + (connection.config.relative ? connection.config.relative + '/' : '') + 'dormpanel-sync-v2/';
      const files = m.files.filter(x => x.name.startsWith(prefix));
      await loadGraph({ list: async () => files.map(x => /commit-([0-9a-f-]{36})\.dpc$/.exec(x.name)[1]),
        read: async id => { const name = prefix + `commit-${id}.dpc`; return m.files.some(x => x.name === name) ? readBounded(await safePath(root, name)) : null; } },
      { ...f.checkpoint, datasetId: f.datasetId ?? f.checkpoint.datasetId, hashes: { ...f.graph?.hashes, ...f.checkpoint.hashes } });
    }
  }
  ensure(ids.has(catalog.activeId), '活动工作区身份无效');
  ensure(m.workspaceCount === catalog.workspaces.length && json(dependencies) === json(m.dependencies), '清单覆盖范围或外部依赖与工作区不一致');
  return catalog;
}
function preview(m) {
  return { formatVersion: m.version, appVersion: m.appVersion, createdAt: m.createdAt, workspaceCount: m.workspaceCount,
    fileCount: m.files.length, bytes: m.files.reduce((n, f) => n + f.size, 0), dependencies: m.dependencies,
    coverage: '完整应用私有状态、连接凭据和私有本地提交；外部 WebDAV / 授权目录历史不在归档内，不能保证离线独立恢复整个远端数据集' };
}

export class BackupManager {
  constructor({ catalog, configPath, reopen, fault = async () => {}, previewTtlMs = 15 * 60000, cleanupRetryMs = 60000 }) {
    Object.assign(this, { catalog, configPath, reopen, fault, previewTtlMs, cleanupRetryMs });
    this.previews = new Map(); this.pendingCleanup = new Map();
  }
  async protectedStages() {
    const journal = await this.catalog.store.read('.restore-journal.json'), last = await this.catalog.store.read('restore-result.json');
    const paths = new Set([...this.previews.values()].map(p => resolve(p.stage)));
    for (const name of [journal?.stage, journal?.old, last?.previousState]) if (name) {
      ensure(/^\.backup-[0-9a-f-]{36}$/.test(name), '恢复暂存引用无效；停止自动清理');
      paths.add(resolve(this.catalog.store.dir, name));
    }
    return paths;
  }
  retireStage(path) {
    this.pendingCleanup.set(resolve(path), Date.now()); this.scheduleCleanup();
  }
  expirePreviews() {
    for (const [id, p] of this.previews) if (p.expires <= Date.now()) {
      this.previews.delete(id); this.retireStage(p.stage);
    }
  }
  scheduleCleanup() {
    clearTimeout(this.cleanupTimer); this.cleanupTimer = null;
    if (this.closed || this.cleanupTask || this.catalog.maintenance || this.poisoned) return;
    const due = Math.min(...[...this.previews.values()].map(p => p.expires), ...this.pendingCleanup.values());
    if (!Number.isFinite(due)) return;
    // 一个到期定时器；仅真实清理失败时延后重试，不扫描空闲目录。
    this.cleanupTimer = setTimeout(() => { this.cleanupTimer = null; void this.runCleanup(); }, Math.max(0, due - Date.now()));
    this.cleanupTimer.unref();
  }
  runCleanup() {
    if (this.cleanupTask) return this.cleanupTask;
    if (this.catalog.maintenance || this.poisoned) return Promise.resolve();
    this.cleanupTask = Promise.resolve().then(async () => {
      this.expirePreviews();
      if (!this.pendingCleanup.size) return;
      try {
        const protectedPaths = await this.protectedStages();
        for (const [path, due] of this.pendingCleanup) {
          if (protectedPaths.has(path)) { this.pendingCleanup.delete(path); continue; }
          if (due > Date.now()) continue;
          try { await this.discard(path); this.pendingCleanup.delete(path); }
          catch { this.pendingCleanup.set(path, Date.now() + this.cleanupRetryMs); }
        }
      } catch {
        // 引用无法读取时不能猜测哪些目录可删；保留队列，后续定时或启动重试。
        for (const path of this.pendingCleanup.keys()) this.pendingCleanup.set(path, Date.now() + this.cleanupRetryMs);
      }
    }).finally(() => { this.cleanupTask = null; this.scheduleCleanup(); });
    return this.cleanupTask;
  }
  async close() {
    this.closed = true; clearTimeout(this.cleanupTimer); this.cleanupTimer = null;
    await this.cleanupTask;
    // 中断的恢复由磁盘日志接管；关服不清理其输入及旧状态。
    if (this.catalog.maintenance || this.poisoned) return;
    for (const p of this.previews.values()) this.retireStage(p.stage);
    this.previews.clear(); await this.runCleanup();
  }
  async cleanupStages() {
    // 预览令牌只在内存中有效。重启后仅保留最近一次恢复前快照；不触及提交历史。
    if (this.catalog.maintenance || this.poisoned) return;
    const protectedPaths = await this.protectedStages(), names = await readdir(this.catalog.store.dir);
    if (this.catalog.maintenance || this.poisoned) return;
    for (const name of names) if (/^\.backup-[0-9a-f-]{36}$/.test(name) && !protectedPaths.has(resolve(this.catalog.store.dir, name))) this.retireStage(join(this.catalog.store.dir, name));
    await this.runCleanup();
  }
  async isolated(action) {
    const c = this.catalog;
    ensure(!this.closed && !c.maintenance && !c.configuring && !c.busy.size && !c.loading.size && !c.manifest.transition, '已关闭或存在同步、配置或备份恢复操作，请稍后重试');
    c.maintenance = true;
    try { await this.cleanupTask; await Promise.all([...c.workspaces.values()].map(db => db.queue)); return await action(); }
    finally { if (!this.poisoned) { c.maintenance = false; c.prune(); } this.scheduleCleanup(); }
  }
  async stage() {
    const fs = await statfs(this.catalog.store.dir);
    ensure(fs.bavail * fs.bsize >= 3 * LIMITS.total, '私有磁盘可用空间不足（至少需要 768 MiB 暂存预算）');
    const id = randomUUID(), path = join(this.catalog.store.dir, '.backup-' + id);
    await mkdir(path, { mode: 0o700 });
    try { await syncDir(this.catalog.store.dir); return path; }
    catch (e) { this.retireStage(path); throw e; }
  }
  async discard(path) {
    const root = resolve(this.catalog.store.dir), target = resolve(path);
    ensure(dirname(target) === root && /^\.backup-[0-9a-f-]{36}$/.test(target.slice(root.length + 1)), '暂存清理范围无效');
    await rm(target, { recursive: true, force: true }); await syncDir(root);
  }
  async stamps() {
    const result = [];
    for (const row of this.catalog.manifest.workspaces) result.push([row.id, await workspaceStamp(this.catalog.workspaceStore(row.id))]);
    return json(result);
  }
  async directory(input) {
    ensure(input.rootId !== 'private', '备份须保存在应用私有 var 以外的授权目录');
    const d = await this.catalog.grants.directory(input.rootId, input.relative ?? '', input.directoryIdentity ?? null);
    const privatePath = resolve(this.catalog.store.dir);
    if (resolve(d.path) === privatePath || resolve(d.path).startsWith(privatePath + '/')) { await d.fd.close(); throw Error('备份位置不能在应用私有 var 内'); }
    return d;
  }
  async create(input) {
    return this.isolated(async () => {
      // 口令先检查，不在无效请求中产生私有明文副本。
      ensure(typeof input.password === 'string' && input.password.length >= 12 && Buffer.byteLength(input.password) <= 1024, '备份口令须为 12–1024 字节');
      const c = this.catalog, stage = await this.stage();
      try {
      const files = await snapshot(c.store.dir, this.configPath, stage);
      const dependencies = [];
      for (const row of c.manifest.workspaces) {
        // 从静止快照逐个读取，备份不把所有非活动帧放进 Catalog 缓存。
        const child = new Store(join(stage, 'workspaces', row.id)); child.identity = c.store.identity;
        const db = await Workspace.open(child), f = db.frame, connection = row.connectionId ? c.connection(row.connectionId) : null;
        dependencies.push(dependency(row, connection, f));
      }
      const m = { format: 'DormPanel FPK backup', version: 1, appVersion: '0.4.3', createdAt: new Date().toISOString(), identity: c.store.identity, files, workspaceCount: c.manifest.workspaces.length, dependencies };
      await verifyFiles(stage, m, c.store.identity);
      const manifest = Buffer.from(json(m)); ensure(manifest.length <= LIMITS.manifest, '备份清单超限');
      const header = Buffer.alloc(HEADER); MAGIC.copy(header); randomBytes(16).copy(header, 8); randomBytes(12).copy(header, 24);
      const secret = await key(input.password, header.subarray(8, 24)), cipher = createCipheriv('aes-256-gcm', secret, header.subarray(24, 36)); cipher.setAAD(header.subarray(0, 36));
      const d = await this.directory(input), name = `backup-${randomUUID()}.dpbk`, tmp = '.stage-' + randomUUID(); let output;
      try {
        output = await open(fdPath(d.fd, tmp), 'wx', 0o600); await writeAt(output, header, 0);
        async function* parts() {
          const len = Buffer.alloc(4); len.writeUInt32BE(manifest.length); yield len; yield manifest;
          for (const f of files) for await (const b of createReadStream(await safePath(stage, f.name))) yield b;
        }
        let offset = HEADER;
        await pipeline(parts(), cipher, async input => {
          for await (const b of input) { await writeAt(output, b, offset); offset += b.length; }
        });
        await writeAt(output, cipher.getAuthTag(), 36); await output.sync(); await output.close(); output = null;
        await this.fault('backup-written');
        // 公开文件名前，重新读取实际密文并做认证解密及全部文件/领域校验。
        const check = await this.decrypt(fdPath(d.fd, tmp), input.password); this.retireStage(check.stage);
        await link(fdPath(d.fd, tmp), fdPath(d.fd, name)); await d.fd.sync();
        return { name, directoryIdentity: d.stamp, verified: true, ...preview(m) };
      } finally { secret.fill(0); await output?.close(); await unlink(fdPath(d.fd, tmp)).catch(() => {}); await d.fd.close(); }
      } finally { this.retireStage(stage); }
    });
  }
  async decrypt(path, password) {
    const stage = await this.stage(); let source, plain, success = false;
    try {
      source = await open(path, C.O_RDONLY | C.O_NOFOLLOW | C.O_NONBLOCK);
      const s = await source.stat(); ensure(s.isFile() && s.size >= HEADER + 4 && s.size <= HEADER + 4 + LIMITS.manifest + LIMITS.total, '归档类型、长度或资源预算无效');
      const header = await readAt(source, Buffer.alloc(HEADER), 0); ensure(header.subarray(0, 8).equals(MAGIC), '归档格式无效');
      const secret = await key(password, header.subarray(8, 24)), decipher = createDecipheriv('aes-256-gcm', secret, header.subarray(24, 36));
      decipher.setAAD(header.subarray(0, 36)); decipher.setAuthTag(header.subarray(36));
      plain = await open(join(stage, '.plain'), 'wx+', 0o600);
      let count = 0;
      try {
        await pipeline(chunks(source, HEADER, s.size), decipher, async input => {
          for await (const b of input) { count += b.length; ensure(count <= LIMITS.total + LIMITS.manifest + 4, '解密资源预算超限'); await plain.writeFile(b); }
        });
      } catch { throw Error('口令错误或备份认证失败'); } finally { secret.fill(0); }
      const len = await readAt(plain, Buffer.alloc(4), 0), n = len.readUInt32BE(); ensure(n > 0 && n <= LIMITS.manifest && n + 4 <= count, '清单长度无效');
      const mb = await readAt(plain, Buffer.alloc(n), 4);
      const m = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(mb)), total = validateManifest(m);
      ensure(count === 4 + n + total, '归档缺失文件、截断或多余字节');
      let offset = 4 + n;
      for (const f of m.files) {
        const bytes = await readAt(plain, Buffer.alloc(f.size), offset); offset += f.size;
        ensure(digest(bytes) === f.sha256, '备份 SHA-256 不匹配'); await atomic(await safePath(stage, f.name, true), bytes);
      }
      await verifyFiles(stage, m, this.catalog.store.identity);
      await new Store(stage).save('.manifest.json', m);
      success = true;
      return { stage, manifest: m };
    } finally {
      // 即使关闭句柄失败也必须把明文暂存交给清理队列，不能留下无主目录。
      try { await Promise.allSettled([plain?.close(), source?.close()]); await unlink(join(stage, '.plain')).catch(() => {}); }
      finally { if (!success) this.retireStage(stage); }
    }
  }
  async list(input) {
    const d = await this.directory(input);
    try {
      const rows = await readdir(`/proc/self/fd/${d.fd.fd}`, { withFileTypes: true }); ensure(rows.length <= 4096, '备份目录条目超限');
      const result = [];
      for (const r of rows) if (r.isFile() && fileName(r.name)) {
        const f = await open(fdPath(d.fd, r.name), C.O_RDONLY | C.O_NOFOLLOW | C.O_NONBLOCK);
        try { const s = await f.stat(); result.push({ name: r.name, size: s.size, modifiedAt: s.mtime.toISOString(), verified: false, status: '加密文件；输入口令后验证版本、时间与覆盖范围' }); } finally { await f.close(); }
      }
      return { directoryIdentity: d.stamp, backups: result, lastRestore: await this.catalog.store.read('restore-result.json') };
    } finally { await d.fd.close(); }
  }
  async verify(input, user) {
    return this.isolated(async () => {
      ensure(fileName(input.name), '备份文件名无效');
      this.expirePreviews();
      ensure(this.previews.size < 8, '恢复预览过多');
      const d = await this.directory(input); let result, registered = false;
      try {
        result = await this.decrypt(fdPath(d.fd, input.name), input.password);
        const external = [], catalog = await new Store(result.stage).read('catalog.json'), started = Date.now();
        for (const row of catalog.workspaces) {
          const c = catalog.connections.find(c => c.id === row.connectionId);
          if (!c || (c.type === 'local' && c.config.rootId === 'private')) continue;
          if (Date.now() - started > 60000) { external.push({ workspaceId: row.id, verified: false, reason: '本轮外部检查时间预算耗尽；恢复后保持发布隔离' }); continue; }
          const store = new Store(join(result.stage, 'workspaces', row.id)); store.identity = this.catalog.store.identity;
          const db = await Workspace.open(store);
          try { await verifyRestoredHistory(db, this.catalog.remote(c)); external.push({ workspaceId: row.id, verified: true, status: '本轮只读身份和已知历史验证通过；恢复后仍须重新验证' }); }
          catch (e) { external.push({ workspaceId: row.id, verified: false, reason: e.message }); }
        }
        const token = randomUUID();
        this.previews.set(token, { ...result, user, expires: Date.now() + this.previewTtlMs, catalogVersion: this.catalog.manifest.revision, stamps: await this.stamps() }); registered = true;
        return { token, verified: true, ...preview(result.manifest), external, externalStatus: '外部依赖不在归档内；恢复后先只读验证身份与已知历史，再允许发布' };
      } finally {
        if (result && !registered) this.retireStage(result.stage);
        await d.fd.close();
      }
    });
  }
  async cancel(input, user) {
    return this.isolated(async () => {
      const p = this.previews.get(input.token);
      if (p) { ensure(p.user === user, '恢复预览不属于当前管理员'); this.previews.delete(input.token); this.retireStage(p.stage); }
      return { ok: true };
    });
  }
  async restore(input, user) {
    return this.isolated(async () => {
      const p = this.previews.get(input.token), c = this.catalog;
      ensure(p && p.user === user && p.expires > Date.now() && input.confirm === '恢复应用数据', '恢复预览失效或缺少明确确认');
      ensure(p.catalogVersion === c.manifest.revision, '管理目录变化，请重新验证备份');
      ensure(p.stamps === await this.stamps(), '工作区变化，请重新验证备份');
      await verifyFiles(p.stage, p.manifest, c.store.identity);
      // 私有提交同样不可变。保留备份后新增的历史，UUID 碰撞绝不以恢复覆盖。
      for (const f of p.manifest.files.filter(f => privateFile(f.name))) {
        const path = await safePath(c.store.dir, f.name).catch(e => { if (e.code !== 'ENOENT') throw e; return null; });
        if (path && await lstat(path).catch(() => null)) ensure(digest(await readBounded(path)) === f.sha256, '私有提交路径内容与备份冲突，拒绝覆盖');
      }
      const old = await this.stage(), oldFiles = await snapshot(c.store.dir, this.configPath, old);
      await new Store(old).save('.manifest.json', { files: oldFiles });
      const journal = { schema: 1, stage: p.stage.split(/[\\/]/).pop(), old: old.split(/[\\/]/).pop(), manifestHash: digest(Buffer.from(json(p.manifest))), oldFiles,
        identity: c.store.identity, status: 'committed' };
      await this.fault('restore-precommit');
      await c.store.save('.restore-journal.json', journal);
      // 决策落盘后不回到旧内存；失败时所有 API 隔离，重启只完成已验证的新状态。
      this.poisoned = true; c.invalidated = true;
      await this.fault('restore-committed');
      await recoverRestore(c.store.dir, this.configPath, this.fault);
      await this.reopen(); this.poisoned = false;
      for (const value of this.previews.values()) this.retireStage(value.stage);
      this.previews.clear();
      return { ok: true, result: '恢复完成；外部绑定和历史重新验证前禁止发布。恢复前状态已私有保留。' };
    });
  }
}

export async function recoverRestore(dataDir, configPath, fault = async () => {}) {
  dataDir = await realpath(dataDir).catch(e => { if (e.code !== 'ENOENT') throw e; return null; });
  if (!dataDir) return;
  const store = new Store(dataDir), journal = await store.read('.restore-journal.json'); if (!journal) return;
  ensure(journal.schema === 1 && /^\.backup-[0-9a-f-]{36}$/.test(journal.stage) && /^\.backup-[0-9a-f-]{36}$/.test(journal.old), '恢复日志无效；停止启动');
  const stage = join(dataDir, journal.stage), m = await new Store(stage).read('.manifest.json');
  ensure(digest(Buffer.from(json(m))) === journal.manifestHash, '恢复暂存清单变化');
  const currentIdentity = await store.read('identity.json');
  ensure(json(currentIdentity) === json(journal.identity), '恢复安装身份变化');
  const catalog = await verifyFiles(stage, m, currentIdentity);
  for (const f of m.files) {
    const path = f.name === 'config/webdav.json' ? configPath : await safePath(dataDir, f.name, true);
    if (privateFile(f.name) && await lstat(path).catch(() => null)) {
      ensure(digest(await readBounded(path)) === f.sha256, '私有提交内容冲突，停止恢复'); continue;
    }
    ensure(path, '缺少旧配置恢复位置'); await atomic(path, await readBounded(await safePath(stage, f.name))); await fault('restore-file');
  }
  for (const f of journal.oldFiles) if (!m.files.some(n => n.name === f.name)) {
    if (privateFile(f.name)) continue;
    const path = await sourcePath(dataDir, configPath, f.name); await unlink(path).catch(e => { if (e.code !== 'ENOENT') throw e; }); await syncDir(dirname(path));
  }
  const guards = {};
  for (const row of catalog.workspaces) {
    const f = await new Store(join(dataDir, 'workspaces', row.id)).read('workspace.json');
    if (row.connectionId) guards[row.id] = { bindingKey: f.bindingKey, restoredAt: m.createdAt };
  }
  await store.save('restore-guards.json', guards);
  // 再次读取所有有效文件；guards 是独立恢复隔离元数据，不改写备份帧。
  const check = { ...m, files: m.files.filter(f => f.name !== 'restore-guards.json') };
  await verifyFiles(dataDir, check, currentIdentity, configPath);
  await store.save('restore-result.json', { ok: true, restoredAt: new Date().toISOString(), backupCreatedAt: m.createdAt, previousState: journal.old });
  await fault('restore-complete');
  await unlink(join(dataDir, '.restore-journal.json')); await syncDir(dataDir);
  // 已完成切换后可丢弃新状态暂存；恢复前快照继续由 result 引用保留。
  ensure(dirname(resolve(stage)) === resolve(dataDir), '恢复暂存清理范围无效');
  await rm(stage, { recursive: true, force: true });
}
