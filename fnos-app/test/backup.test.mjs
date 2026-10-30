import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, rm, readFile, writeFile, readdir, symlink, chmod, stat } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID, createCipheriv, createDecipheriv, scryptSync } from 'node:crypto';
import { Store, json } from '../src/store.mjs';
import { Catalog } from '../src/catalog.mjs';
import { BackupManager, recoverRestore, LIMITS } from '../src/backup.mjs';
import { decode, encode, hash } from '../src/protocol.mjs';
import { createApp, PREFIX } from '../src/server.mjs';
import http from 'node:http';
import { spawnSync } from 'node:child_process';

const linux = process.platform === 'linux' ? test : test.skip;
const password = 'synthetic backup passphrase';
const operation = title => ({ kind: 'entry', action: 'upsert', value: { id: 'course', title, day: 1, startMinute: 480, endMinute: 530, location: '' } });
async function fixture(t) {
  const dir = await mkdtemp(join(tmpdir(), 'pr42-')), dataDir = join(dir, 'var'), exports = join(dir, 'exports'), etc = join(dir, 'etc');
  let manager;
  await mkdir(exports); await mkdir(etc); t.after(async () => { await manager?.close(); await rm(dir, { recursive: true, force: true }); });
  const store = await new Store(dataDir).init(), rows = new Map();
  const remoteFactory = c => {
    if (!rows.has(c.url)) rows.set(c.url, new Map()); const data = rows.get(c.url);
    return { key: c.url + 'dormpanel-sync-v2/', list: async () => [...data.keys()], read: async id => data.get(id) ?? null, put: async (id, b) => { assert(!data.has(id)); data.set(id, b); } };
  };
  const configPath = join(etc, 'webdav.json'); await writeFile(configPath, json({ url: 'https://default.test/', username: 'legacy-u', password: 'legacy-secret' }));
  const options = { store, configPath, remoteFactory, authorizedRoots: [{ id: 'exports', name: '测试授权目录', path: exports }] };
  let catalog = await Catalog.open(options);
  manager = new BackupManager({ catalog, configPath, reopen: async () => { catalog = await Catalog.open(options); manager.catalog = catalog; } });
  const manage = input => catalog.manage({ catalogVersion: catalog.manifest.revision, ...input });
  async function add(name, type = 'webdav') {
    const c = await manage({ action: 'connection.save', type, name, ...(type === 'webdav' ? { url: `https://${name}.test/`, username: 'sensitive-user', password: 'sensitive-password', certificatePem: await readFile(new URL('./fixtures/test-certificate.pem', import.meta.url), 'utf8') } : { rootId: 'private', createPrivate: true }) });
    const w = await manage({ action: 'workspace.add', name, connectionId: c.connectionId });
    return { id: w.workspaceId, db: await catalog.load(w.workspaceId), connection: catalog.connection(c.connectionId) };
  }
  const a = await add('pending'), b = await add('conflict'), local = await add('local', 'local');
  for (const w of [a, b, local]) { await w.db.edit(operation(w.id)); await catalog.network(w.id, e => e.synchronize({ create: true })); }
  await a.db.edit(operation('候选 capture'));
  await a.db.tx(f => { a.db.stage(f, f.local, f.frontier); f.pending.attempted = true; f.abandoned[randomUUID()] = 'a'.repeat(64); f.error = '未确认恢复历史'; f.extension = { attempt: '不得丢弃' }; });
  await a.db.edit(operation('capture 之后的修改'));
  const remote = rows.get(b.connection.config.url), root = decode([...remote.values()][0]), branch = { ...root, commitId: randomUUID(), writerId: randomUUID(), parents: [root.commitId], state: structuredClone(root.state) };
  branch.state.entries[0].title = '远端冲突'; remote.set(branch.commitId, encode(branch));
  await b.db.edit(operation('本地冲突')); await catalog.network(b.id, e => e.synchronize()); assert(b.db.frame.conflict);
  const location = { rootId: 'exports', relative: '', password };
  return { dir, dataDir, exports, store, options, configPath, manager, get catalog() { return catalog; }, a, b, local, location, manage, add, rows };
}
async function original(f) {
  const files = new Map();
  for (const row of f.catalog.manifest.workspaces) files.set(row.id, await readFile(join(f.dataDir, 'workspaces', row.id, 'workspace.json'), 'utf8'));
  return { catalog: await readFile(join(f.dataDir, 'catalog.json'), 'utf8'), identity: await readFile(join(f.dataDir, 'identity.json'), 'utf8'), config: await readFile(f.configPath, 'utf8'), files };
}
async function equalOriginal(f, before) {
  assert.equal(await readFile(join(f.dataDir, 'catalog.json'), 'utf8'), before.catalog);
  assert.equal(await readFile(join(f.dataDir, 'identity.json'), 'utf8'), before.identity);
  assert.equal(await readFile(f.configPath, 'utf8'), before.config);
  for (const [id, bytes] of before.files) assert.equal(await readFile(join(f.dataDir, 'workspaces', id, 'workspace.json'), 'utf8'), bytes);
}
linux('多工作区、候选/capture/attempt/abandoned、真实冲突、凭据证书和私有历史精确恢复', async t => {
  const f = await fixture(t), before = await original(f), created = await f.manager.create(f.location);
  assert(created.verified); assert.equal(created.workspaceCount, 4); assert.equal(created.appVersion, '0.8.2');
  assert(created.dependencies.some(d => d.included)); assert(created.dependencies.some(d => !d.included && d.type === 'webdav'));
  const newerLocal = await f.catalog.load(f.local.id);
  await newerLocal.edit(operation('备份后新增私有提交'));
  await f.catalog.network(f.local.id, e => e.synchronize());
  const retainedId = newerLocal.frame.receipt.commitId;
  const retainedPath = join(f.dataDir, 'local-data', f.local.connection.config.relative, 'dormpanel-sync-v2', `commit-${retainedId}.dpc`);
  const retainedBytes = await readFile(retainedPath);
  const bytes = await readFile(join(f.exports, created.name));
  for (const secret of ['sensitive-password', 'legacy-secret', 'BEGIN CERTIFICATE', 'capture 之后']) assert(!bytes.includes(Buffer.from(secret)));
  const remoteBefore = [...f.rows].filter(([, rows]) => rows.size).map(([k, rows]) => [k, [...rows].map(([id, b]) => [id, hash(b).toString('hex')])]);
  await (await f.catalog.load(f.a.id)).edit(operation('备份后的编辑'));
  const verified = await f.manager.verify({ ...f.location, name: created.name }, 'admin'); assert(verified.verified);
  await f.manager.restore({ token: verified.token, confirm: '恢复应用数据' }, 'admin');
  await equalOriginal(f, before);
  assert.deepEqual(await readFile(retainedPath), retainedBytes);
  assert.deepEqual([...f.rows].filter(([, rows]) => rows.size).map(([k, rows]) => [k, [...rows].map(([id, b]) => [id, hash(b).toString('hex')])]), remoteBefore);
  assert(f.catalog.restoreGuards[f.a.id]);
  const local = await f.catalog.load(f.local.id);
  await f.catalog.network(f.local.id, e => e.synchronize()); assert(!f.catalog.restoreGuards[f.local.id]); assert(local.frame.receipt);
  const a = await f.catalog.load(f.a.id); let puts = 0;
  const factory = f.catalog.remoteFactory; f.catalog.remoteFactory = c => ({ ...factory(c), list: async () => { throw Error('离线'); }, put: async () => puts++ });
  await assert.rejects(f.catalog.network(f.a.id, e => e.synchronize()), /离线/); assert.equal(puts, 0); assert(a.frame.pending);
});
for (const corruption of ['password', 'truncated', 'ciphertext', 'identity']) linux(`拒绝 ${corruption}，有效数据完全不变`, async t => {
  const f = await fixture(t), created = await f.manager.create(f.location), before = await original(f), path = join(f.exports, created.name);
  const input = { ...f.location, name: created.name };
  if (corruption === 'password') input.password = 'wrong backup password';
  if (corruption === 'truncated') await writeFile(path, (await readFile(path)).subarray(0, 80));
  if (corruption === 'ciphertext') { const b = await readFile(path); b[b.length - 1] ^= 1; await writeFile(path, b); }
  if (corruption === 'identity') f.manager.catalog.store.identity = { ...f.store.identity, installationId: randomUUID() };
  await assert.rejects(f.manager.verify(input, 'admin')); await equalOriginal(f, before);
});
linux('私有提交 UUID 已有不同字节时恢复在决策前拒绝，不覆盖不可变历史', async t => {
  const f = await fixture(t), backup = await f.manager.create(f.location), before = await original(f);
  const id = f.local.db.frame.receipt.commitId, path = join(f.dataDir, 'local-data', f.local.connection.config.relative, 'dormpanel-sync-v2', `commit-${id}.dpc`);
  await chmod(path, 0o600); await writeFile(path, 'damaged existing path');
  const p = await f.manager.verify({ ...f.location, name: backup.name }, 'admin');
  await assert.rejects(f.manager.restore({ token: p.token, confirm: '恢复应用数据' }, 'admin'), /私有提交路径/);
  await equalOriginal(f, before); assert.equal(await readFile(path, 'utf8'), 'damaged existing path');
  assert(!f.manager.poisoned);
  await assert.rejects(readFile(join(f.dataDir, '.restore-journal.json')), { code: 'ENOENT' });
});
linux('回执已保存而 observe 尚未完成的合法帧也能完整备份和恢复', async t => {
  const f = await fixture(t), w = await f.add('receipt');
  await w.db.edit(operation('root')); await f.catalog.network(w.id, e => e.synchronize({ create: true }));
  await w.db.edit(operation('published but not observed'));
  const factory = f.catalog.remoteFactory; let published = false;
  f.catalog.remoteFactory = config => {
    const remote = factory(config);
    return { ...remote, put: async (id, b) => { await remote.put(id, b); published = true; }, list: async () => { if (published) throw Error('observe interrupted'); return remote.list(); } };
  };
  await assert.rejects(f.catalog.network(w.id, e => e.synchronize()), /interrupted/);
  assert(w.db.frame.receipt); assert(!w.db.frame.graph.raw[w.db.frame.receipt.commitId]);
  const before = await original(f), backup = await f.manager.create(f.location);
  const p = await f.manager.verify({ ...f.location, name: backup.name }, 'admin');
  await f.manager.restore({ token: p.token, confirm: '恢复应用数据' }, 'admin'); await equalOriginal(f, before);
  f.catalog.remoteFactory = factory;
  await f.catalog.network(w.id, e => e.synchronize()); assert.equal((await f.catalog.load(w.id)).frame.local.entries[0].title, 'published but not observed');
});
linux('授权 NAS 历史仅记录为外部依赖，撤销授权后恢复本地视图但拒绝发布', async t => {
  const f = await fixture(t); await mkdir(join(f.exports, 'external-data'));
  const c = await f.manage({ action: 'connection.save', type: 'local', name: 'external NAS', rootId: 'exports', relative: 'external-data' });
  const w = await f.manage({ action: 'workspace.add', name: 'external NAS', connectionId: c.connectionId }), db = await f.catalog.load(w.workspaceId);
  await db.edit(operation('NAS')); await f.catalog.network(w.workspaceId, e => e.synchronize({ create: true }));
  const remoteFile = join(f.exports, 'external-data', 'dormpanel-sync-v2', `commit-${db.frame.receipt.commitId}.dpc`), bytes = await readFile(remoteFile);
  const backup = await f.manager.create(f.location); assert.equal(backup.dependencies.find(d => d.workspaceId === w.workspaceId).included, false);
  const p = await f.manager.verify({ ...f.location, name: backup.name }, 'admin'); assert(p.external.find(d => d.workspaceId === w.workspaceId).verified);
  f.catalog.grants.roots = f.catalog.grants.roots.filter(r => r.id !== 'exports'); f.options.authorizedRoots = [];
  await assert.rejects(f.manager.list(f.location), /未授权|撤销/);
  await f.manager.restore({ token: p.token, confirm: '恢复应用数据' }, 'admin');
  assert.equal((await f.catalog.load(w.workspaceId)).frame.local.entries[0].title, 'NAS');
  await assert.rejects(f.catalog.network(w.workspaceId, e => e.synchronize()), /授权目录/);
  assert(f.catalog.restoreGuards[w.workspaceId]); assert.deepEqual(await readFile(remoteFile), bytes);
});
for (const point of ['restore-precommit', 'restore-committed', 'restore-file', 'restore-complete']) linux(`恢复在 ${point} 注入故障，重启确定选择完整旧状态或新状态`, async t => {
  const f = await fixture(t), saved = await original(f), backup = await f.manager.create(f.location);
  await (await f.catalog.load(f.a.id)).edit(operation('恢复前保留状态')); const old = await original(f);
  const p = await f.manager.verify({ ...f.location, name: backup.name }, 'admin');
  let failed = false; f.manager.fault = async name => { if (!failed && name === point) { failed = true; throw Error('注入崩溃'); } };
  await assert.rejects(f.manager.restore({ token: p.token, confirm: '恢复应用数据' }, 'admin'), /注入崩溃/);
  if (point !== 'restore-precommit') assert(f.manager.poisoned && f.catalog.maintenance);
  const child = spawnSync(process.execPath, ['--max-old-space-size=192', '--input-type=module', '-e',
    `import { recoverRestore } from ${JSON.stringify(new URL('../src/backup.mjs', import.meta.url).href)}; await recoverRestore(process.argv[1], process.argv[2]);`, f.dataDir, f.configPath], { encoding: 'utf8', timeout: 60000 });
  assert.equal(child.status, 0, child.stderr); await recoverRestore(f.dataDir, f.configPath);
  const reopened = await Catalog.open(f.options); assert.equal(reopened.workspaces.size, 1);
  await equalOriginal(f, point === 'restore-precommit' ? old : saved);
});
linux('备份失败没有有效半成品；只使用授权目录，拒绝遍历/链接/私有目录', async t => {
  const f = await fixture(t), before = await original(f);
  f.manager.fault = async () => { throw Error('写入中断'); };
  await assert.rejects(f.manager.create(f.location), /中断/); assert.deepEqual(await readdir(f.exports), []);
  for (const input of [{ rootId: 'private' }, { rootId: 'unknown' }, { ...f.location, relative: '../var' }, { ...f.location, relative: '/tmp' }]) await assert.rejects(f.manager.list(input));
  await symlink(f.dataDir, join(f.exports, 'escape')); await assert.rejects(f.manager.list({ ...f.location, relative: 'escape' }));
  for (const name of ['../identity.json', '/etc/passwd', 'backup-../../a.dpbk']) await assert.rejects(f.manager.verify({ ...f.location, name }, 'admin'));
  const id = `backup-${randomUUID()}.dpbk`; await symlink(join(f.dataDir, 'identity.json'), join(f.exports, id)); await assert.rejects(f.manager.verify({ ...f.location, name: id }, 'admin'));
  await equalOriginal(f, before);
});
linux('恢复确认绑定管理员和预览，预览后编辑/切换拒绝；并发操作相互隔离', async t => {
  const f = await fixture(t), b = await f.manager.create(f.location), p = await f.manager.verify({ ...f.location, name: b.name }, 'admin');
  await assert.rejects(f.manager.restore({ token: p.token, confirm: '恢复应用数据' }, 'other'));
  await assert.rejects(f.manager.restore({ token: p.token, confirm: 'yes' }, 'admin'));
  await (await f.catalog.load(f.a.id)).edit(operation('预览后的编辑'));
  await assert.rejects(f.manager.restore({ token: p.token, confirm: '恢复应用数据' }, 'admin'), /变化/);
  let release, entered;
  const gate = new Promise(r => release = r), waiting = new Promise(r => entered = r);
  f.manager.fault = async name => { if (name === 'backup-written') { entered(); await gate; } };
  const backup = f.manager.create(f.location); await waiting;
  await assert.rejects((await f.catalog.load(f.a.id)).edit(operation('隔离期间的编辑')), /备份/);
  await assert.rejects(f.catalog.manage({ catalogVersion: f.catalog.manifest.revision, action: 'workspace.switch', targetId: f.a.id }), /备份/);
  await assert.rejects(f.catalog.network(f.a.id, e => e.synchronize()));
  await assert.rejects(f.manager.create(f.location)); release(); await backup;
  f.catalog.busy.add(f.a.id); await assert.rejects(f.manager.create(f.location)); f.catalog.busy.clear();
});
linux('实际 Socket 备份 API：管理员、CSRF、版本、口令、敏感响应与确认恢复', async t => {
  const f = await fixture(t), before = await original(f), app = await createApp({ dataDir: f.dataDir, configPath: f.configPath, remoteFactory: f.options.remoteFactory, authorizedRoots: f.options.authorizedRoots });
  const socketPath = join(f.dir, 'test.sock'); await new Promise(r => app.server.listen(socketPath, r));
  t.after(() => new Promise(r => app.server.close(r)));
  async function call(method, path, data, headers = {}) {
    return new Promise((resolve, reject) => {
      const req = http.request({ socketPath, method, path: PREFIX + path, headers: { 'X-Trim-Userid': '1000', 'X-Trim-Isadmin': 'true', 'Content-Type': 'application/json', ...headers } }, res => {
        let text = ''; res.on('data', b => text += b); res.on('end', () => resolve({ status: res.statusCode, text, data: JSON.parse(text) }));
      }); req.on('error', reject); req.end(data ? json(data) : undefined);
    });
  }
  const view = (await call('GET', '/api/schedule')).data, input = { ...f.location, catalogVersion: app.catalog.manifest.revision };
  for (const action of ['create', 'list', 'verify', 'restore', 'cancel']) {
    assert.equal((await call('POST', '/api/backups/' + action, input, { 'X-Trim-Isadmin': 'false' })).status, 403);
    assert.equal((await call('POST', '/api/backups/' + action, input)).status, 403);
    assert.equal((await call('POST', '/api/backups/' + action, { ...input, catalogVersion: -1 }, { 'X-DormPanel-CSRF': view.csrf })).status, 409);
  }
  const post = (action, data) => call('POST', '/api/backups/' + action, { ...input, ...data }, { 'X-DormPanel-CSRF': view.csrf });
  const created = await post('create'); assert.equal(created.status, 200, created.text);
  let verified = await post('verify', { name: created.data.name }); assert.equal(verified.status, 200, verified.text);
  for (const secret of ['sensitive-user', 'sensitive-password', 'legacy-secret', 'BEGIN CERTIFICATE', 'synthetic backup passphrase']) assert(!verified.text.includes(secret));
  assert.equal((await post('cancel', { token: verified.data.token })).status, 200); await app.backups.runCleanup();
  assert(!app.backups.previews.has(verified.data.token));
  assert.equal((await post('restore', { token: verified.data.token, confirm: '恢复应用数据' })).status, 409);
  verified = await post('verify', { name: created.data.name }); assert.equal(verified.status, 200, verified.text);
  assert.equal((await post('restore', { token: verified.data.token, confirm: 'yes' })).status, 409);
  const restored = await post('restore', { token: verified.data.token, confirm: '恢复应用数据' }); assert.equal(restored.status, 200, restored.text);
  await equalOriginal(f, before);
  assert.equal((await post('list')).status, 403, '恢复后旧页面 CSRF 失效，防止回退的 revision 被旧请求重放');
  const fresh = await call('GET', '/api/schedule'); assert.equal(fresh.status, 200); view.csrf = fresh.data.csrf;
  const listed = await post('list'); assert(listed.data.lastRestore.ok);
});
// 用合法认证外壳承载恶意清单，证明拒绝原因来自解包验证而非碰巧口令错误。
async function forged(f, m, payload = Buffer.alloc(0)) {
  const header = Buffer.alloc(52); header.write('DPBK0001'); const salt = Buffer.alloc(16, 1), iv = Buffer.alloc(12, 2); salt.copy(header, 8); iv.copy(header, 24);
  const secret = scryptSync(password, salt, 32, { N: 32768, r: 8, p: 1, maxmem: 64 * 1024 * 1024 }), cipher = createCipheriv('aes-256-gcm', secret, iv); cipher.setAAD(header.subarray(0, 36));
  const manifest = Buffer.from(json(m)), len = Buffer.alloc(4); len.writeUInt32BE(manifest.length);
  const encrypted = Buffer.concat([cipher.update(Buffer.concat([len, manifest, payload])), cipher.final()]); cipher.getAuthTag().copy(header, 36);
  const name = `backup-${randomUUID()}.dpbk`; await writeFile(join(f.exports, name), Buffer.concat([header, encrypted])); return name;
}
for (const oldVersion of ['0.4.1', '0.4.2', '0.4.3', '0.4.4']) linux(`0.5.0 兼容 ${oldVersion} 版本元数据并精确恢复，备份格式保持不变`, async t => {
  const f = await fixture(t), before = await original(f), created = await f.manager.create(f.location);
  const bytes = await readFile(join(f.exports, created.name));
  const secret = scryptSync(password, bytes.subarray(8, 24), 32, { N: 32768, r: 8, p: 1, maxmem: 64 * 1024 * 1024 });
  const decipher = createDecipheriv('aes-256-gcm', secret, bytes.subarray(24, 36));
  decipher.setAAD(bytes.subarray(0, 36)); decipher.setAuthTag(bytes.subarray(36, 52));
  const plain = Buffer.concat([decipher.update(bytes.subarray(52)), decipher.final()]);
  const length = plain.readUInt32BE(0), manifest = JSON.parse(plain.subarray(4, 4 + length));
  manifest.appVersion = oldVersion;
  const name = await forged(f, manifest, plain.subarray(4 + length));
  const preview = await f.manager.verify({ ...f.location, name }, 'admin');
  assert.equal(preview.appVersion, oldVersion); assert.equal(preview.formatVersion, 1);
  await f.manager.restore({ token: preview.token, confirm: '恢复应用数据' }, 'admin');
  await equalOriginal(f, before);
});

for (const attack of ['traversal', 'duplicate', 'size', 'count', 'missing', 'checksum', 'version', 'appVersion']) linux(`认证归档仍拒绝恶意 ${attack}`, async t => {
  const f = await fixture(t), before = await original(f), m = { format: 'DormPanel FPK backup', version: 1, appVersion: '0.4.0', identity: f.store.identity, files: [], dependencies: [] };
  const file = { name: 'identity.json', size: 0, sha256: '0'.repeat(64) };
  m.files = [file, { ...file, name: 'catalog.json' }];
  if (attack === 'traversal') file.name = '../identity.json';
  if (attack === 'duplicate') m.files.push(file);
  if (attack === 'size') file.size = LIMITS.file + 1;
  if (attack === 'count') m.files = Array(LIMITS.files + 1).fill(file);
  if (attack === 'missing') m.files = [file];
  if (attack === 'version') m.version = 99;
  if (attack === 'appVersion') m.appVersion = '0.4.5';
  const name = await forged(f, m);
  await assert.rejects(f.manager.verify({ ...f.location, name }, 'admin')); await equalOriginal(f, before);
});

const delay = ms => new Promise(r => setTimeout(r, ms));
linux('0.4.0 旧源码生成的真实加密归档在 0.5.0 验证并精确恢复，安装身份边界不变', async t => {
  const f = await fixture(t), base = new URL('./fixtures/backup-0.4.0/', import.meta.url);
  const saved = JSON.parse(await readFile(new URL('expected.json', base), 'utf8'));
  // 夹具全部是合成数据；只在独立临时安装中使用旧安装身份。
  await writeFile(join(f.dataDir, 'identity.json'), saved.identity);
  f.store.identity = JSON.parse(saved.identity);
  const name = `backup-${randomUUID()}.dpbk`;
  await writeFile(join(f.exports, name), await readFile(new URL('backup.dpbk', base)));
  const input = { ...f.location, name }, currentIdentity = f.store.identity;
  for (const field of ['writerId', 'installationId']) {
    f.store.identity = { ...currentIdentity, [field]: randomUUID() };
    await assert.rejects(f.manager.verify(input, 'admin'));
  }
  f.store.identity = currentIdentity;
  const p = await f.manager.verify(input, 'admin');
  assert.equal(p.appVersion, '0.4.0'); assert.equal(p.formatVersion, 1); assert.equal(p.workspaceCount, 4);
  await f.manager.restore({ token: p.token, confirm: '恢复应用数据' }, 'admin');
  await equalOriginal(f, { ...saved, files: new Map(saved.files) });
  assert.equal(f.catalog.store.identity.writerId, currentIdentity.writerId);
  assert.equal(f.catalog.store.identity.installationId, currentIdentity.installationId);
  assert(Object.keys(f.catalog.restoreGuards).length > 0);
});
async function eventually(check) {
  const deadline = Date.now() + 5000;
  while (!(await check())) { assert(Date.now() < deadline, '生命周期清理未在期限内完成'); await delay(10); }
}
const exists = path => stat(path).then(() => true, e => { if (e.code !== 'ENOENT') throw e; return false; });
linux('预览无后续 API 或重启也按到期定时器释放，且空闲时无清理定时器', async t => {
  const f = await fixture(t), backup = await f.manager.create(f.location); await f.manager.runCleanup();
  f.manager.previewTtlMs = 250;
  const p = await f.manager.verify({ ...f.location, name: backup.name }, 'admin'), stage = f.manager.previews.get(p.token).stage;
  assert.equal((await stat(stage)).mode & 0o777, 0o700); assert.equal(f.manager.cleanupTimer.hasRef(), false);
  await eventually(async () => !f.manager.previews.has(p.token) && !(await exists(stage)) && !f.manager.pendingCleanup.size && !f.manager.cleanupTask && !f.manager.cleanupTimer);
  assert.equal(f.manager.pendingCleanup.size, 0); assert.equal(f.manager.cleanupTimer, null);
});
linux('取消只释放当前管理员选定的预览，不影响其他仍有效预览', async t => {
  const f = await fixture(t), backup = await f.manager.create(f.location);
  const a = await f.manager.verify({ ...f.location, name: backup.name }, 'admin'), b = await f.manager.verify({ ...f.location, name: backup.name }, 'admin');
  const sa = f.manager.previews.get(a.token).stage, sb = f.manager.previews.get(b.token).stage;
  await assert.rejects(f.manager.cancel({ token: a.token }, 'other'), /当前管理员/); assert(await exists(sa));
  await f.manager.cancel({ token: a.token }, 'admin'); await f.manager.runCleanup();
  assert(!(await exists(sa))); assert(await exists(sb)); assert(f.manager.previews.has(b.token));
  await f.manager.cleanupStages(); assert(await exists(sb));
});
linux('认证后注册预览失败及认证失败均交回明文目录，不留无主暂存', async t => {
  const f = await fixture(t), backup = await f.manager.create(f.location); await f.manager.runCleanup();
  f.manager.stamps = async () => { throw Error('验证后注入失败'); };
  await assert.rejects(f.manager.verify({ ...f.location, name: backup.name }, 'admin'), /验证后注入失败/);
  await f.manager.runCleanup(); assert.equal(f.manager.previews.size, 0);
  assert(!(await readdir(f.dataDir)).some(n => n.startsWith('.backup-')));
  await assert.rejects(f.manager.verify({ ...f.location, name: backup.name, password: 'wrong backup password' }, 'admin'), /认证失败/);
  await f.manager.runCleanup(); assert.equal(f.manager.pendingCleanup.size, 0);
  assert(!(await readdir(f.dataDir)).some(n => n.startsWith('.backup-')));
});
linux('清理失败撤销令牌但保留待清理所有权，自动重试成功后停止定时器', async t => {
  const f = await fixture(t), backup = await f.manager.create(f.location); await f.manager.runCleanup();
  const p = await f.manager.verify({ ...f.location, name: backup.name }, 'admin'), value = f.manager.previews.get(p.token), discard = f.manager.discard.bind(f.manager);
  let failures = 0; f.manager.cleanupRetryMs = 100;
  f.manager.discard = async path => { if (path === value.stage && failures++ === 0) throw Error('模拟 EACCES'); return discard(path); };
  value.expires = Date.now(); f.manager.scheduleCleanup();
  await eventually(() => failures === 1 && f.manager.pendingCleanup.has(value.stage));
  assert(!f.manager.previews.has(p.token)); assert(await exists(value.stage));
  await assert.rejects(f.manager.restore({ token: p.token, confirm: '恢复应用数据' }, 'admin'), /预览失效/);
  await eventually(async () => !(await exists(value.stage)) && !f.manager.pendingCleanup.size && !f.manager.cleanupTask);
  assert.equal(f.manager.pendingCleanup.size, 0); assert.equal(f.manager.cleanupTimer, null);
});
linux('保护引用读取失败时不猜测删除，后续定时重试安全释放', async t => {
  const f = await fixture(t), backup = await f.manager.create(f.location); await f.manager.runCleanup();
  const p = await f.manager.verify({ ...f.location, name: backup.name }, 'admin'), value = f.manager.previews.get(p.token), read = f.store.read.bind(f.store);
  let failed = false; f.manager.cleanupRetryMs = 100;
  f.store.read = async name => { if (name === '.restore-journal.json' && !failed) { failed = true; throw Error('模拟引用读取失败'); } return read(name); };
  value.expires = Date.now(); f.manager.scheduleCleanup();
  await eventually(() => failed && f.manager.pendingCleanup.size > 0); assert(await exists(value.stage));
  await eventually(async () => !(await exists(value.stage)) && !f.manager.cleanupTask && !f.manager.cleanupTimer); assert.equal(f.manager.cleanupTimer, null);
});
linux('清理持续失败后关服，启动重试仅清理无主预览并保留恢复前快照', async t => {
  const f = await fixture(t), backup = await f.manager.create(f.location); await f.manager.runCleanup();
  const p = await f.manager.verify({ ...f.location, name: backup.name }, 'admin'), stage = f.manager.previews.get(p.token).stage;
  const old = await f.manager.stage(); await f.store.save('restore-result.json', { previousState: old.split('/').pop() });
  f.manager.discard = async () => { throw Error('持续 EACCES'); };
  await f.manager.cancel({ token: p.token }, 'admin'); await f.manager.runCleanup(); await f.manager.close();
  assert(await exists(stage)); assert.equal(f.manager.cleanupTimer, null);
  const startup = new BackupManager({ catalog: await Catalog.open(f.options), configPath: f.configPath });
  try { await startup.cleanupStages(); assert(!(await exists(stage))); assert(await exists(old)); assert.equal(startup.cleanupTimer, null); }
  finally { await startup.close(); }
});
linux('恢复使用中越过预览到期点也不清理输入；成功后释放预览并保留旧快照', async t => {
  const f = await fixture(t), backup = await f.manager.create(f.location);
  const p = await f.manager.verify({ ...f.location, name: backup.name }, 'admin'), value = f.manager.previews.get(p.token);
  let release, entered; const gate = new Promise(r => release = r), waiting = new Promise(r => entered = r);
  f.manager.fault = async point => { if (point === 'restore-precommit') { entered(); await gate; } };
  const restoring = f.manager.restore({ token: p.token, confirm: '恢复应用数据' }, 'admin'); await waiting;
  value.expires = Date.now() - 1; f.manager.scheduleCleanup(); await delay(20);
  assert(await exists(value.stage)); await f.manager.runCleanup(); assert(await exists(value.stage));
  await assert.rejects(f.manager.cancel({ token: p.token }, 'admin'), /操作/);
  release(); await restoring; await f.manager.runCleanup();
  assert(!(await exists(value.stage))); assert.equal(f.manager.previews.size, 0);
  const old = (await f.store.read('restore-result.json')).previousState; assert(await exists(join(f.dataDir, old)));
  await f.manager.cleanupStages(); assert(await exists(join(f.dataDir, old)));
});
linux('已提交恢复中断后到期和关服不删除日志输入，启动清理也保留新旧状态', async t => {
  const f = await fixture(t), backup = await f.manager.create(f.location), p = await f.manager.verify({ ...f.location, name: backup.name }, 'admin');
  const value = f.manager.previews.get(p.token);
  f.manager.fault = async point => { if (point === 'restore-committed') throw Error('模拟恢复中断'); };
  await assert.rejects(f.manager.restore({ token: p.token, confirm: '恢复应用数据' }, 'admin'), /中断/);
  value.expires = Date.now() - 1; f.manager.scheduleCleanup(); await delay(20); await f.manager.close();
  const journal = await f.store.read('.restore-journal.json'); assert(await exists(value.stage)); assert(await exists(join(f.dataDir, journal.old)));
  const startup = new BackupManager({ catalog: await Catalog.open(f.options), configPath: f.configPath });
  try {
    await startup.cleanupStages(); assert(await exists(value.stage)); assert(await exists(join(f.dataDir, journal.old)));
    await recoverRestore(f.dataDir, f.configPath); assert(!(await exists(value.stage))); assert(await exists(join(f.dataDir, journal.old)));
  } finally { await startup.close(); }
});
