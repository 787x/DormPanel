import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm, readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { randomUUID } from 'node:crypto';
import { Store } from '../src/store.mjs';
import { Catalog } from '../src/catalog.mjs';

async function fixture(t) {
  const dir = await mkdtemp(join(tmpdir(), 'pr42-lazy-')); t.after(() => rm(dir, { recursive: true, force: true }));
  const store = await new Store(dir).init(), options = { store, configPath: join(dir, 'config.json') };
  const catalog = await Catalog.open(options), manage = input => catalog.manage({ catalogVersion: catalog.manifest.revision, ...input });
  const ids = [catalog.manifest.activeId];
  for (let i = 0; i < 7; i++) {
    const c = await manage({ action: 'connection.save', type: 'webdav', name: 'w' + i, url: `https://w${i}.test/`, username: 'u', password: 'p' });
    const w = await manage({ action: 'workspace.add', connectionId: c.connectionId, name: 'w' + i }); ids.push(w.workspaceId);
  }
  return { dir, store, options, catalog, ids };
}
test('八工作区启动只读取活动帧；管理列表不解码非活动工作区', async t => {
  const f = await fixture(t), reads = [], read = Store.prototype.read;
  Store.prototype.read = async function(name) { if (name === 'workspace.json') reads.push(this.dir); return read.call(this, name); };
  t.after(() => { Store.prototype.read = read; });
  const c = await Catalog.open(f.options); assert.equal(c.workspaces.size, 1); assert.equal(reads.length, 1);
  const summary = await c.summary(); assert.equal(summary.workspaces.length, 8); assert.equal(reads.length, 1);
  assert(summary.workspaces.every(w => !w.unknown));
});
test('缺失和过期摘要显示未知；切换修复摘要；损坏或缺失帧绝不重建', async t => {
  const f = await fixture(t), id = f.ids[1], store = f.catalog.workspaceStore(id);
  await rm(join(store.dir, 'summary.json'));
  let c = await Catalog.open(f.options); assert((await c.summary()).workspaces.find(w => w.id === id).unknown);
  await c.manage({ catalogVersion: c.manifest.revision, action: 'workspace.switch', targetId: id });
  assert(!(await c.summary()).workspaces.find(w => w.id === id).unknown);
  await c.manage({ catalogVersion: c.manifest.revision, action: 'workspace.switch', targetId: f.ids[0] }); c.prune();
  const frame = await store.read('workspace.json'); frame.revision++; frame.error = '新异常'; await store.save('workspace.json', frame);
  assert((await c.summary()).workspaces.find(w => w.id === id).unknown);
  await writeFile(join(store.dir, 'workspace.json'), '{corrupt');
  await assert.rejects(c.manage({ catalogVersion: c.manifest.revision, action: 'workspace.switch', targetId: id }));
  assert.equal(c.manifest.activeId, f.ids[0]); assert.equal(await readFile(join(store.dir, 'workspace.json'), 'utf8'), '{corrupt');
  await rm(join(store.dir, 'workspace.json')); await assert.rejects(c.load(id), /缺失/);
  await assert.rejects(readFile(join(store.dir, 'workspace.json')), { code: 'ENOENT' });
});
test('并发按需读取共享同一队列；释放缓存后拒绝旧引用写入，重载保留候选恢复扩展', async t => {
  const f = await fixture(t), c = await Catalog.open(f.options), id = f.ids[1];
  const [a, b] = await Promise.all([c.load(id), c.load(id)]); assert.equal(a, b);
  await Promise.all([a.tx(frame => { frame.error = 'first'; }), b.tx(frame => { frame.error = 'second'; frame.abandoned[randomUUID()] = 'a'.repeat(64); frame.recoveryExtra = { receipt: '保留' }; })]);
  assert.equal(a.frame.revision, 2); assert.equal(a.frame.error, 'second');
  const bytes = await readFile(join(a.store.dir, 'workspace.json'), 'utf8');
  c.busy.add(id); c.prune(); assert(c.workspaces.has(id)); c.busy.delete(id); c.prune(); assert(!c.workspaces.has(id));
  await assert.rejects(a.tx(frame => { frame.error = 'stale'; }), /失效/);
  const fresh = await c.load(id); assert.notEqual(fresh, a); assert.equal(JSON.stringify(fresh.frame), bytes);
  assert((await c.summary()).workspaces.find(w => w.id === id).recovery);
});
