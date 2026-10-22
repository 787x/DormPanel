import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,readFile,writeFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';

test('真实升级回调忽略陈旧安装向导，逐字节保留当前地址、凭据和证书',async t=>{
  const dir=await mkdtemp(join(tmpdir(),'pr38-upgrade-'));t.after(()=>rm(dir,{force:true,recursive:true}));
  const certificatePem=await readFile(new URL('./fixtures/test-certificate.pem',import.meta.url),'utf8');
  const path=join(dir,'webdav.json'),bytes=JSON.stringify({url:'https://192.168.3.153:5006/dormpanel/current/',username:'new-user',password:'rotated-password',certificatePem},null,2);
  await writeFile(path,bytes);
  const env={...process.env,TRIM_PKGETC:dir,wizard_webdav_url:'https://old.test/old/',wizard_webdav_username:'stale-user',wizard_webdav_password:'stale-password'};
  const script=fileURLToPath(new URL('../src/configure.mjs',import.meta.url));
  const upgrade=spawnSync(process.execPath,[script,'--upgrade'],{env});assert.equal(upgrade.status,0,upgrade.stderr.toString());assert.equal(await readFile(path,'utf8'),bytes);
  const legacy=spawnSync(process.execPath,[script],{env});assert.equal(legacy.status,0);assert.equal(await readFile(path,'utf8'),bytes,'无参数旧 callback 同样保留已有配置');
  const explicit=spawnSync(process.execPath,[script,'--configure'],{env});assert.equal(explicit.status,0);assert.equal(await readFile(path,'utf8'),bytes,'0.3 系统配置不消费旧向导，不更换绑定；目录授权可正常完成');
});

test('多工作区启用后目录授权的 config_callback 正常完成，忽略旧向导且不改写凭据', async t => {
  const dir=await mkdtemp(join(tmpdir(),'pr39-authorize-'));t.after(()=>rm(dir,{force:true,recursive:true}));
  const path=join(dir,'webdav.json'),bytes=JSON.stringify({url:'https://current.test/',username:'u',password:'old-secret'});
  await writeFile(path,bytes);await writeFile(join(dir,'catalog.json'),'{"schema":1}');
  const env={...process.env,TRIM_PKGETC:dir,TRIM_PKGVAR:dir,wizard_webdav_url:'https://stale.test/',wizard_webdav_username:'stale',wizard_webdav_password:'stale'};
  const r=spawnSync(process.execPath,[fileURLToPath(new URL('../src/configure.mjs',import.meta.url)),'--configure'],{env});
  assert.equal(r.status,0,r.stderr.toString());assert.equal(await readFile(path,'utf8'),bytes);
});

// 真实执行回调，再启动同一数据目录；不以“重建空配置”模拟升级。
import { readdir, mkdir } from 'node:fs/promises';
import { createApp } from '../src/server.mjs';
async function snapshot(dir) {
  const rows = {};
  async function visit(path, prefix = '') {
    for (const entry of await readdir(path, { withFileTypes: true })) {
      const key = prefix + entry.name;
      if (entry.isDirectory()) await visit(join(path, entry.name), key + '/');
      else rows[key] = (await readFile(join(path, entry.name))).toString('base64');
    }
  }
  await visit(dir); return rows;
}
const script = fileURLToPath(new URL('../src/configure.mjs', import.meta.url));
const stale = { wizard_webdav_url: 'https://stale.invalid/', wizard_webdav_username: 'stale', wizard_webdav_password: 'stale-secret' };
function callbacks(etc, data) {
  for (const flags of [[], ['--upgrade'], ['--configure']]) {
    const result = spawnSync(process.execPath, [script, ...flags], { env: { ...process.env, ...stale, TRIM_PKGETC: etc, TRIM_PKGVAR: data } });
    assert.equal(result.status, 0, result.stderr.toString());
  }
}

test('无向导全新安装忽略遗留环境，启动未绑定课表并可本地编辑', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'pr45-install-')); t.after(() => rm(dir, { recursive: true, force: true }));
  const etc = join(dir, 'etc'), dataDir = join(dir, 'var'); await mkdir(etc); await mkdir(dataDir);
  callbacks(etc, dataDir); assert.deepEqual(await snapshot(dir), {});
  const app = await createApp({ dataDir, configPath: join(etc, 'webdav.json') }); t.after(() => app.backups?.close());
  assert.equal(app.catalog.manifest.connections.length, 0); assert.equal(app.db.frame.datasetId, null);
  assert.equal(app.catalog.manifest.workspaces[0].connectionId, null);
  await app.db.edit({ kind: 'entry', action: 'upsert', value: { title: '安装后直接编辑', day: 1, startMinute: 480, endMinute: 530, location: '' } });
  assert.equal(app.db.frame.local.entries[0].title, '安装后直接编辑');
});

test('非空多工作区升级逐字节保留连接、身份、历史、冲突和待发布状态', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'pr45-upgrade-')); t.after(() => rm(dir, { recursive: true, force: true }));
  const etc = join(dir, 'etc'), dataDir = join(dir, 'var'); await mkdir(etc);
  const configPath = join(etc, 'webdav.json'); await writeFile(configPath, JSON.stringify({ url: 'https://legacy.test/', username: 'legacy', password: 'legacy-secret' }));
  const stores = new Map();
  const remoteFactory = c => {
    if (!stores.has(c.url)) stores.set(c.url, new Map()); const commits = stores.get(c.url);
    return { key: c.url + 'dormpanel-sync-v2/', list: async () => [...commits.keys()], read: async id => commits.get(id) ?? null, put: async (id, bytes) => { assert(!commits.has(id)); commits.set(id, bytes); } };
  };
  const options = { dataDir, configPath, remoteFactory };
  const app = await createApp(options), catalog = app.catalog;
  await catalog.manage({ action: 'connection.save', catalogVersion: catalog.manifest.revision, type: 'webdav', name: '应用内更新的连接', url: 'https://current.test/', username: 'current', password: 'rotated-secret' });
  const c = catalog.manifest.connections.at(-1);
  const result = await catalog.manage({ action: 'workspace.add', catalogVersion: catalog.manifest.revision, connectionId: c.id, name: '另一个课程表' });
  const db = await catalog.load(result.workspaceId);
  await catalog.network(result.workspaceId, e => e.synchronize({ create: true }));
  await catalog.network(catalog.manifest.activeId, e => e.synchronize({ create: true }));
  await db.edit({ kind: 'entry', action: 'upsert', value: { title: '未发布修改', day: 1, startMinute: 480, endMinute: 530, location: '' } });
  await db.tx(f => { db.stage(f, f.local, f.frontier); f.pending.attempted = true; f.error = '待回读'; });
  await app.db.tx(f => { f.conflict = { base: f.local, localBase: f.local, branches: [] }; f.extension = { history: '保持未识别的扩展字段' }; });
  const before = await snapshot(dir), identity = structuredClone(catalog.store.identity);
  callbacks(etc, dataDir); assert.deepEqual(await snapshot(dir), before);
  const restarted = await createApp(options);
  await restarted.catalog.load(result.workspaceId);
  assert.deepEqual(restarted.catalog.store.identity, identity);
  assert.deepEqual(restarted.catalog.manifest, JSON.parse(JSON.stringify(catalog.manifest)));
  assert.deepEqual(restarted.db.frame, app.db.frame);
  assert.deepEqual(restarted.catalog.db(result.workspaceId).frame, db.frame);
  assert.deepEqual(await snapshot(dir), before);
});
