// 显式独立目录的真实后端验收；不由 npm test 自动启动，不删除任何历史。
import { readFile } from 'node:fs/promises';
import { basename, join } from 'node:path';
import https from 'node:https';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { Catalog } from '../src/catalog.mjs';
import { Store, json } from '../src/store.mjs';
import { tlsOptions } from '../src/webdav.mjs';
import { decode, encode, ensure } from '../src/protocol.mjs';

const root = process.env.PR39_TEST_DIR;
ensure(root && /^pr39-e2e-[0-9a-f-]{36}$/.test(basename(root)), '需要独立 PR39_TEST_DIR');
const config = JSON.parse(await readFile(join(root, 'config.json'), 'utf8'));
const base = new URL(config.url);
ensure(base.protocol === 'https:' && base.pathname.endsWith('/' + basename(root) + '/'), 'WebDAV 必须位于同名隔离测试目录');
async function mkcol(url) {
  ensure(url.origin === base.origin && url.pathname.startsWith(base.pathname), '测试目录越界');
  return new Promise((resolve, reject) => {
    const req = https.request(url, { method: 'MKCOL', ...tlsOptions(config), headers: { Authorization: 'Basic ' + Buffer.from(config.username + ':' + config.password).toString('base64') } }, res => {
      res.resume(); res.on('end', () => [201, 405].includes(res.statusCode) ? resolve() : reject(Error('MKCOL ' + res.statusCode)));
    }); req.on('error', () => reject(Error('测试目录连接失败'))); req.setTimeout(15000, () => req.destroy()); req.end();
  });
}
await mkcol(base);
for (const name of ['a', 'b']) await mkcol(new URL(name + '/', base));
const store = await new Store(join(root, 'state')).init(), options = { store, configPath: join(root, 'no-legacy-config.json') };
const catalog = await Catalog.open(options);
const manage = input => catalog.manage({ catalogVersion: catalog.manifest.revision, ...input });
const edit = (db, title) => db.edit({ kind: 'entry', action: 'upsert', value: { id: 'test-course', title, day: 1, startMinute: 480, endMinute: 530, location: '合成测试' } }, db.frame.revision);
const rows = [];
for (const [name, type] of [['本地 A', 'local'], ['本地 B', 'local'], ['WebDAV A', 'webdav'], ['WebDAV B', 'webdav']]) {
  const connection = await manage({ action: 'connection.save', type, name,
    ...(type === 'local' ? { rootId: 'private', createPrivate: true } : { ...config, url: new URL(name.endsWith('A') ? 'a/' : 'b/', base).href }) });
  const { workspaceId: id } = await manage({ action: 'workspace.add', connectionId: connection.connectionId, name });
  const db = catalog.db(id); await edit(db, name);
  await catalog.network(id, e => e.synchronize({ create: true }));
  assert(db.frame.receipt); assert(!db.frame.pending);
  const c = catalog.connection(connection.connectionId), result = await catalog.test({ connectionId: c.id, name });
  assert(result.ok); rows.push({ id, c, name, db }); console.log(name + ' 创建/发布/完整验证 PASS');
}
assert.equal(new Set(rows.map(r => r.db.frame.datasetId)).size, 4);
const a = rows[0], b = rows[3];
await edit(a.db, 'A 待发布候选'); await a.db.tx(f => a.db.stage(f, f.local, f.frontier));
await edit(a.db, 'capture 之后的编辑');
const remote = catalog.remote(b.c), rootCommit = decode(await remote.read(b.db.frame.receipt.commitId));
const branch = { ...rootCommit, commitId: randomUUID(), writerId: randomUUID(), parents: [rootCommit.commitId], state: structuredClone(rootCommit.state) };
branch.state.entries[0].title = '远端 B 冲突'; await remote.put(branch.commitId, encode(branch));
await edit(b.db, '本地 B 冲突'); await catalog.network(b.id, e => e.synchronize()); assert(b.db.frame.conflict);
const frames = rows.map(r => json(r.db.frame));
for (const r of [...rows, a]) await manage({ action: 'workspace.switch', targetId: r.id });
const restarted = await Catalog.open(options);
rows.forEach((r, i) => assert.equal(json(restarted.db(r.id).frame), frames[i]));
console.log('候选/capture/冲突/四工作区切换/重启逐字节保留 PASS');
const extra = await manage({ action: 'connection.save', type: 'webdav', name: '重复地址', ...config, url: b.c.config.url });
const duplicate = await manage({ action: 'workspace.add', connectionId: extra.connectionId, name: '重复数据集保护' });
await assert.rejects(catalog.network(duplicate.workspaceId, e => e.synchronize({ bind: true })), /已绑定/);
console.log('重复数据集拒绝绑定 PASS');
const proof = { run: basename(root), writerId: store.identity.writerId, workspaces: rows.map(r => ({ id: r.id, name: r.name, datasetId: r.db.frame.datasetId, pending: !!r.db.frame.pending, conflict: !!r.db.frame.conflict, receipt: r.db.frame.receipt })), verifiedAt: new Date().toISOString() };
await new Store(root).save('verification.json', proof);
console.log(json(proof));
