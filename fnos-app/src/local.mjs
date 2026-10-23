import { constants as C } from 'node:fs';
import { open, mkdir, opendir, realpath, link, unlink, statfs, access } from 'node:fs/promises';
import { resolve, parse } from 'node:path';
import { randomUUID } from 'node:crypto';
import { ensure, uuid, decode, MAX } from './protocol.mjs';

const fdPath = (f, name = '') => `/proc/self/fd/${f.fd}${name ? '/' + name : ''}`;
const dirs = C.O_RDONLY | C.O_DIRECTORY | C.O_NOFOLLOW;
// Node 未暴露 Linux O_PATH；中间祖先只需遍历权限，不能要求能列出整个卷/用户目录。
const ancestors = 0x200000 | C.O_DIRECTORY | C.O_NOFOLLOW;
const identity = s => `${s.dev}:${s.ino}`;
async function entries(fd, max) {
  const result = [], dir = await opendir(fdPath(fd));
  for await (const entry of dir) {
    ensure(result.length < max, '目录条目超限'); result.push(entry);
  }
  return result;
}
export function segments(relative = '') {
  ensure(typeof relative === 'string' && relative.length <= 1024, '目录路径无效');
  if (!relative) return [];
  const parts = relative.split('/');
  ensure(parts.length <= 16 && parts.every(p => /^[^\\/\x00-\x1f:]+$/.test(p) && p !== '.' && p !== '..' && !p.startsWith('.')), '目录路径越界');
  return parts;
}
// 从文件系统根逐级 O_NOFOLLOW 打开；仅可信系统资源入口允许预先 realpath。
async function absoluteDirectory(path) {
  ensure(process.platform === 'linux', '安全本地文件发布仅支持已验证的 Linux 文件系统');
  const full = resolve(path), root = parse(full).root;
  const parts = full.slice(root.length).split('/').filter(Boolean);
  let fd = await open(root, parts.length ? ancestors : dirs);
  try {
    for (const [i, p] of parts.entries()) {
      const next = await open(fdPath(fd, p), i === parts.length - 1 ? dirs : ancestors);
      await fd.close(); fd = next;
    }
    return fd;
  } catch (e) { await fd.close(); throw e; }
}
export class LocalGrants {
  constructor(roots) { this.roots = roots; }
  async readPrivate(config, name = 'verified.json') {
    ensure(name === 'verified.json', '无效清单');
    const dir = await this.directory(config.rootId, config.relative, config.directoryIdentity); let file;
    try {
      file = await open(fdPath(dir.fd, name), C.O_RDONLY | C.O_NOFOLLOW | C.O_NONBLOCK);
      const s = await file.stat(); ensure(s.isFile() && s.size <= 128 * 1024, '清单类型或大小无效');
      const b = Buffer.alloc(s.size + 1); const r = await file.read(b, 0, b.length, 0);
      ensure(r.bytesRead === s.size, '清单读取不完整');
      return JSON.parse(b.subarray(0, s.size).toString('utf8'));
    } finally { await file?.close(); await dir.fd.close(); }
  }
  async protectedFolder(rootId, relative, name) {
    ensure(rootId !== 'private', '请使用 fnOS 授权的导出目录');
    ensure(!segments(relative).includes('dormpanel-sync-v2'), '不能写入活跃协议目录');
    ensure(/^(history|compact)-[0-9a-f-]{36}$/.test(name), '导出名称无效');
    const parent = await this.directory(rootId, relative);
    try {
      ensure(!parent.path.split(/[\\/]/).includes('dormpanel-sync-v2'), '授权目标不能位于活跃协议目录中');
      const fs = await statfs(fdPath(parent.fd));
      ensure(fs.bavail * fs.bsize >= 80 * 1024 * 1024, '导出目录可用容量不足 80 MiB');
      await mkdir(fdPath(parent.fd, name), { mode: 0o700 }); await parent.fd.sync();
    } finally { await parent.fd.close(); }
    const path = [relative, name].filter(Boolean).join('/'), child = await this.directory(rootId, path);
    try { ensure(((await child.fd.stat()).mode & 0o777) === 0o700, '导出目录权限不受保护'); return { rootId, relative: path, directoryIdentity: child.stamp, protected: true }; }
    finally { await child.fd.close(); }
  }
  async writePrivate(config, name, bytes) {
    ensure(name === 'verified.json' && bytes.length <= 128 * 1024, '归档清单超限');
    const dir = await this.directory(config.rootId, config.relative, config.directoryIdentity); let file;
    const stage = '.manifest-' + randomUUID();
    try {
      ensure(((await dir.fd.stat()).mode & 0o777) === 0o700, '导出权限变化');
      file = await open(fdPath(dir.fd, stage), C.O_RDWR | C.O_CREAT | C.O_EXCL | C.O_NOFOLLOW, 0o600);
      await file.writeFile(bytes); await file.sync();
      const read = Buffer.alloc(bytes.length); await file.read(read, 0, read.length, 0);
      ensure(read.equals(bytes), '归档清单回读失败');
      await link(fdPath(dir.fd, stage), fdPath(dir.fd, name)); await dir.fd.sync();
    } finally { await file?.close(); await unlink(fdPath(dir.fd, stage)).catch(() => {}); await dir.fd.close(); }
  }
  async root(id) {
    const grant = this.roots.find(r => r.id === id);
    ensure(grant, '目录未授权或授权已撤销');
    const path = grant.systemResource ? await realpath(grant.path) : resolve(grant.path);
    return { grant, path, fd: await absoluteDirectory(path) };
  }
  async directory(id, relative = '', expected = null) {
    const root = await this.root(id);
    let fd = root.fd;
    try {
      for (const p of segments(relative)) {
        const next = await open(fdPath(fd, p), dirs);
        await fd.close(); fd = next;
      }
      const stamp = identity(await fd.stat());
      ensure(!expected || expected === stamp, '授权目录身份变化，请重新验证');
      return { fd, stamp, path: root.path + (relative ? '/' + relative : '') };
    } catch (e) { await fd.close(); throw e; }
  }
  async browse(id, relative = '') {
    const { fd } = await this.directory(id, relative);
    try {
      const rows = await entries(fd, 2048);
      return rows.filter(x => x.isDirectory() && !x.name.startsWith('.') && x.name !== 'dormpanel-sync-v2').map(x => x.name).sort();
    } finally { await fd.close(); }
  }
  async privateFolder(id) {
    uuid(id);
    const { fd } = await this.directory('private');
    try { await mkdir(fdPath(fd, id), { mode: 0o700 }); await fd.sync(); }
    finally { await fd.close(); }
    return id;
  }
}
export class LocalCommitStore {
  constructor(config, grants) {
    this.config = config; this.grants = grants;
    this.key = `local:${config.rootId}:${config.relative}:${config.directoryIdentity}`;
    this.requests = 0; this.bytes = 0; this.started = Date.now();
  }
  budget(bytes = 0, requests = 1) {
    this.bytes += bytes;
    this.requests += requests;
    ensure(this.requests <= 512 && this.bytes <= 64 * 1024 * 1024 && Date.now() - this.started < 90000, '本地同步资源预算超限');
  }
  async parent() {
    this.budget();
    try {
      const d = await this.grants.directory(this.config.rootId, this.config.relative, this.config.directoryIdentity);
      if (this.config.protected && ((await d.fd.stat()).mode & 0o777) !== 0o700) { await d.fd.close(); throw Error('导出权限变化'); }
      return d;
    }
    catch { throw Error('授权目录不可访问、已撤销或身份变化'); }
  }
  async directory(create = false) {
    const parent = await this.parent();
    try {
      if (create) {
        try { await mkdir(fdPath(parent.fd, 'dormpanel-sync-v2'), { mode: 0o700 }); await parent.fd.sync(); }
        catch (e) { if (e.code !== 'EEXIST') throw e; }
      }
      return await open(fdPath(parent.fd, 'dormpanel-sync-v2'), dirs);
    } finally { await parent.fd.close(); }
  }
  async list() {
    let fd;
    try { fd = await this.directory(); }
    catch (e) { if (e.code === 'ENOENT') { this.missing = true; return []; } throw e; }
    try {
      this.missing = false;
      const rows = await entries(fd, 256);
      return rows.map(r => {
        const m = /^commit-([0-9a-f-]{36})\.dpc$/.exec(r.name);
        ensure(m && r.isFile(), 'v2 目录含非协议文件或符号链接'); uuid(m[1]); return m[1];
      }).sort();
    } finally { await fd.close(); }
  }
  async read(id) {
    uuid(id); let dir, file;
    try {
      dir = await this.directory();
      file = await open(fdPath(dir, `commit-${id}.dpc`), C.O_RDONLY | C.O_NOFOLLOW | C.O_NONBLOCK);
      const before = await file.stat();
      ensure(before.isFile() && before.size <= MAX + 8192, '本地提交类型或大小无效');
      // 有界读取，即使外部进程并发增长文件也不能无限分配内存。
      const data = Buffer.alloc(before.size + 1);
      let length = 0;
      while (length < data.length) {
        const r = await file.read(data, length, data.length - length, length);
        if (!r.bytesRead) break; length += r.bytesRead;
      }
      const after = await file.stat(), current = await open(fdPath(dir, `commit-${id}.dpc`), C.O_RDONLY | C.O_NOFOLLOW | C.O_NONBLOCK);
      try { ensure(identity(await current.stat()) === identity(before), '提交路径发生变化'); }
      finally { await current.close(); }
      ensure(length === before.size && before.size === after.size && before.mtimeNs === after.mtimeNs && before.mtimeMs === after.mtimeMs && before.ctimeMs === after.ctimeMs, '提交文件发生变化');
      this.budget(length, 0); return data.subarray(0, length);
    } catch (e) { if (e.code === 'ENOENT') return null; throw e; }
    finally { await file?.close(); await dir?.close(); }
  }
  async ensureDirectory() { const fd = await this.directory(true); await fd.close(); }
  async put(id, bytes) {
    uuid(id); ensure(bytes.length <= MAX + 8192, '上传超限'); decode(bytes, id);
    await this.publish(bytes, `commit-${id}.dpc`);
  }
  async publish(bytes, name = null) {
    const parent = await this.parent(); let stage, file, protocol;
    const stageName = '.dormpanel-stage-' + randomUUID();
    try {
      // 不对未验证的网络/FUSE 文件系统假定 link/fsync 语义。
      const fs = await statfs(fdPath(parent.fd));
      ensure([0xef53, 0x9123683e, 0x58465342, 0x01021994, 0x794c7630].includes(fs.type), '文件系统不具备已验证的安全发布能力');
      await mkdir(fdPath(parent.fd, stageName), { mode: 0o700 });
      stage = await open(fdPath(parent.fd, stageName), dirs);
      file = await open(fdPath(stage, 'content'), C.O_WRONLY | C.O_CREAT | C.O_EXCL | C.O_NOFOLLOW, 0o600);
      await file.writeFile(bytes);
      // 提交只读；父目录 ACL 决定谁能遍历读取，同目录的 WebDAV 读者可互操作。
      // 暂存目录仍为 0700，凭据和工作区状态从不写入共享位置。
      await file.chmod(0o444); await file.sync(); await file.close(); file = null; await stage.sync();
      if (name) {
        protocol = await this.directory();
        // hard link 是同文件系统的排他原子新建；EEXIST 绝不覆盖。
        await link(fdPath(stage, 'content'), fdPath(protocol, name));
        await protocol.sync();
      } else {
        await link(fdPath(stage, 'content'), fdPath(stage, 'probe'));
        await stage.sync(); await unlink(fdPath(stage, 'probe'));
      }
      await parent.fd.sync();
    } finally {
      await file?.close(); await protocol?.close();
      if (stage) {
        await unlink(fdPath(stage, 'content')).catch(() => {});
        await stage.close();
        // 仅清理本次探测/发布的私有暂存目录，崩溃残留不进入协议目录。
        const { rmdir } = await import('node:fs/promises');
        await rmdir(fdPath(parent.fd, stageName)).catch(() => {});
        await parent.fd.sync();
      }
      await parent.fd.close();
    }
  }
  async test() {
    await this.list();
    if (!this.missing) {
      const dir = await this.directory();
      try { await access(fdPath(dir), C.W_OK | C.X_OK); await dir.sync(); }
      finally { await dir.close(); }
    }
    await this.publish(Buffer.from('permission probe'));
  }
}
