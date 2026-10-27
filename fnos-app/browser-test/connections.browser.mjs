import test, { before, after } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { mkdtemp, rm, readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { V3Maintenance } from '../src/v3-maintenance.mjs';
import { encode as encodeV3, decode as decodeV3 } from '../src/v3-protocol.mjs';
import { objectPath } from '../src/v3-store.mjs';
import { reference } from '../src/v3-history.mjs';
import { chromium } from 'playwright';
import { expect } from 'playwright/test';
import { createApp, PREFIX } from '../src/server.mjs';
import { encode, decode } from '../src/protocol.mjs';
import { loadGraph } from '../src/graph.mjs';
import { emptyState, same } from '../src/merge.mjs';
import { Workspace } from '../src/workspace.mjs';

let browser;
before(async () => { browser = await chromium.launch({ channel: process.env.BROWSER_CHANNEL || (process.platform === 'win32' ? 'msedge' : 'chromium') }); });
after(async () => { await browser?.close(); });

// PR61 review fixes use only the same isolated server and synthetic remote fixtures below.

// 只在独立临时实例中注入网关身份；生产服务仍拒绝 TCP 和伪造的客户端身份。
async function fixture(t, { authorized = false, proxyHost = null, remoteFactory, v3RemoteFactory } = {}) {
  const dir = await mkdtemp(join(tmpdir(), 'dp-connection-ui-'));
  const app = await createApp({ dataDir: dir, configPath: join(dir, 'config.json'), remoteFactory, v3RemoteFactory,
    authorizedRoots: authorized ? [{ id: 'approved', name: 'fnOS 已授权测试目录', path: dir }] : [] });
  const socketPath = process.platform === 'win32' ? '\\\\.\\pipe\\dp-ui-' + randomUUID() : join(dir, 'app.sock');
  await new Promise(r => app.server.listen(socketPath, r));
  const proxy = http.createServer((req, res) => {
    const upstream = http.request({ socketPath, path: req.url, method: req.method,
      headers: { ...req.headers, ...(proxyHost ? { host: proxyHost } : {}), 'x-trim-userid': '1000', 'x-trim-isadmin': 'true' } }, response => {
      res.writeHead(response.statusCode, response.headers); response.pipe(res);
    });
    upstream.on('error', () => { res.writeHead(502); res.end(); });
    req.pipe(upstream);
  });
  await new Promise(r => proxy.listen(0, '127.0.0.1', r));
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 } }), page = await context.newPage();
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.route('**/api/directories?*', route => route.fulfill({ json: { directories: ['测试子目录'] } }));
  t.after(async () => {
    await context.close();
    await new Promise(r => proxy.close(r)); await new Promise(r => app.server.close(r)); await app.catalog.close();
    await rm(dir, { recursive: true, force: true });
  });
  const open = async () => { await page.goto(`http://127.0.0.1:${proxy.address().port}${PREFIX}/`); await page.locator('#sourcesPage').click(); };
  const local = async () => { await open(); await page.locator('#addLocal').click(); };
  const save = page.locator('#editorForm [type=submit]');
  return { page, app, errors, open, local, save };
}

async function choosePrivate(f) { await f.page.getByRole('button', { name: '使用私有目录（自动创建专用文件夹）', exact: true }).click(); }

test('管理接口延迟时，本地按钮立即打开弹窗而非抛异常', async t => {
  const f = await fixture(t);
  let release;
  const gate = new Promise(r => { release = r; });
  await f.page.route('**/api/manage?*', async route => { await gate; await route.continue().catch(() => {}); });
  try {
    await f.open(); await f.page.locator('#addLocal').click();
    await f.page.locator('#editor[open]').waitFor({ timeout: 2000 });
    await expect(f.page.locator('#fields')).toContainText('正在加载授权目录');
    assert.equal(await f.save.isEnabled(), false);
    assert.deepEqual(f.errors, []);
  } finally { t.diagnostic('页面异常：' + JSON.stringify(f.errors)); release(); }
});

test('仅私有目录时可选择；取消、重新打开不会写配置或工作区', async t => {
  const f = await fixture(t), before = structuredClone(f.app.catalog.manifest);
  await f.local(); await choosePrivate(f); await expect(f.save).toBeEnabled();
  await expect(f.page.getByLabel('存储范围').locator('option')).toHaveText(['FPK 私有本地数据']);
  await f.page.getByRole('button', { name: '📁 测试子目录', exact: true }).click();
  await expect(f.page.locator('#fields')).toContainText('当前路径：FPK 私有本地数据 / 测试子目录');
  await f.page.locator('#cancel').click(); await expect(f.page.locator('#editor')).not.toBeVisible();
  await f.page.locator('#addLocal').click(); await choosePrivate(f); await expect(f.save).toBeEnabled();
  await expect(f.page.locator('#fields [role=status]')).toHaveText('当前路径：FPK 私有本地数据 / 根目录');
  await f.page.locator('#cancel').click();
  assert.deepEqual(f.app.catalog.manifest, before); assert.deepEqual(f.errors, []);
});

test('管理接口失败有明确错误，重试后能加载所选目录', async t => {
  const f = await fixture(t); let fail = true;
  await f.page.route('**/api/manage?*', route => fail
    ? route.fulfill({ status: 503, json: { error: '管理服务暂不可用' } }) : route.continue());
  await f.local(); await expect(f.page.locator('#formError')).toContainText('管理服务暂不可用');
  assert.equal(await f.save.isEnabled(), false);
  fail = false; await f.page.getByRole('button', { name: '重试加载配置' }).click();
  await choosePrivate(f); await expect(f.save).toBeEnabled(); assert.deepEqual(f.errors, []);
});

test('目录失败禁止保存和测试，重试保留名称；保存失败不改已有工作区', async t => {
  const f = await fixture(t), before = structuredClone(f.app.db.frame), manifest = structuredClone(f.app.catalog.manifest);
  let fail = true;
  await f.page.route('**/api/directories?*', route => route.fulfill(fail
    ? { status: 403, json: { error: '授权已撤销' } } : { json: { directories: [] } }));
  await f.local(); await expect(f.page.locator('#fields')).toContainText('目录加载失败：授权已撤销');
  await f.page.getByLabel('连接名称', { exact: true }).fill('保留名称');
  assert.equal(await f.save.isEnabled(), false);
  await f.page.getByRole('button', { name: '测试（不保存）' }).click();
  await expect(f.page.locator('#formError')).toContainText('请先成功读取所选目录');
  fail = false; await f.page.getByRole('button', { name: '重试读取目录' }).click();
  await choosePrivate(f); await expect(f.save).toBeEnabled(); await expect(f.page.getByLabel('连接名称', { exact: true })).toHaveValue('保留名称');
  await f.page.route('**/api/manage', route => route.fulfill({ status: 409, json: { error: '管理版本过期' } }));
  await f.save.click(); await expect(f.page.locator('#formError')).toHaveText('管理版本过期');
  await expect(f.page.locator('#editor')).toBeVisible();
  assert.deepEqual(f.app.db.frame, before); assert.deepEqual(f.app.catalog.manifest, manifest);
  await f.page.locator('#cancel').click(); assert.deepEqual(f.errors, []);
});

test('授权根切换时旧目录响应不能覆盖当前选择', async t => {
  const f = await fixture(t, { authorized: true }); let release, started;
  const gate = new Promise(r => { release = r; }), pending = new Promise(r => { started = r; });
  await f.page.route('**/api/directories?*', async route => {
    if (new URL(route.request().url()).searchParams.get('rootId') === 'private') { started(); await gate; }
    await route.fulfill({ json: { directories: ['当前目录'] } }).catch(() => {});
  });
  try {
    await f.local(); await pending;
    await f.page.getByLabel('存储范围').selectOption('approved');
    await f.page.getByRole('button', { name: '📁 当前目录', exact: true }).click();
    await expect(f.page.locator('#fields [role=status]')).toHaveText('当前路径：fnOS 已授权测试目录 / 当前目录');
    await f.page.getByRole('button', { name: '选择此文件夹', exact: true }).click();
    await expect(f.save).toBeEnabled(); release();
    await expect(f.page.getByLabel('存储范围')).toHaveValue('approved');
    await expect(f.page.locator('#fields [role=status]')).toHaveText('当前路径：fnOS 已授权测试目录 / 当前目录');
    assert.deepEqual(f.errors, []);
  } finally { release(); }
});

test('取消加载后迟到的响应不能污染重新打开的 WebDAV 表单', async t => {
  const f = await fixture(t); let release, started;
  const gate = new Promise(r => { release = r; }), pending = new Promise(r => { started = r; });
  await f.page.route('**/api/manage?*', async route => { started(); await gate; await route.continue().catch(() => {}); });
  try {
    await f.local(); await pending;
    await f.page.locator('#cancel').click(); await expect(f.page.locator('#editor')).not.toBeVisible();
    await f.page.locator('#addWebDav').click();
    await expect(f.page.getByLabel('WebDAV 服务器访问地址（可含起始路径）', { exact: true })).toBeVisible();
    release(); await expect(f.save).toBeDisabled();
    await expect(f.page.getByLabel('存储范围')).toHaveCount(0); assert.deepEqual(f.errors, []);
  } finally { release(); }
});

test('WebDAV 保存、新建与切换工作区及课程表单继续工作', async t => {
  const f = await fixture(t); await f.open();
  await expect(f.page.locator('#workspaceRows')).toContainText('默认工作区');
  await f.page.locator('#addWebDav').click();
  await f.page.getByLabel('WebDAV 服务器访问地址（可含起始路径）', { exact: true }).fill('https://example.test/schedule/');
  await f.page.getByLabel('用户名', { exact: true }).fill('test');
  await f.page.getByLabel('密码（仅同一访问身份可留空保留）', { exact: true }).fill('test-only');
  await f.page.getByText('高级：手动输入完整 URL（服务器不支持目录列举时使用）',{exact:true}).click();
  await f.page.getByLabel('共享父目录完整 URL',{exact:true}).fill('https://example.test/schedule/');
  await f.save.click(); await expect(f.page.locator('#connectionRows')).toContainText('WebDAV 课程');
  await f.page.getByRole('button', { name: '新建课程表', exact: true }).click();
  await expect(f.page.getByLabel('共享协议')).toHaveValue('v3');
  await f.page.getByLabel('共享协议').selectOption('v2'); await f.save.click();
  await expect(f.page.locator('#workspaceRows')).toContainText('WebDAV 课程');
  const id = f.app.catalog.manifest.workspaces.find(w => w.connectionId)?.id; assert(id);
  await f.page.locator('#workspaceSelect').selectOption(id);
  await expect(f.page.locator('#workspaceSelect')).toBeEnabled();
  await expect(f.page.locator('#workspaceSelect')).toHaveValue(id);
  await f.page.locator('#schedulePage').click(); await f.page.locator('#newCourse').click();
  await expect(f.page.locator('#editorTitle')).toHaveText('新增本地课程');
  await f.page.getByLabel('课程名', { exact: true }).fill('回归测试课程');
  await f.save.click(); await expect(f.page.locator('#week')).toContainText('回归测试课程');
  assert.deepEqual(f.errors, []);
});

test('公网同源代理改写 Host 时仍能初始化，并用完整公网 Origin 保存', async t => {
  const f = await fixture(t, { proxyHost: 'fnos-internal.test:5666' });
  await f.open();
  await expect(f.page.locator('#workspaceRows')).toContainText('默认工作区', { timeout: 2000 });
  await f.page.locator('#addWebDav').click();
  await f.page.getByLabel('WebDAV 服务器访问地址（可含起始路径）', { exact: true }).fill('https://example.test/schedule/');
  await f.page.getByLabel('用户名', { exact: true }).fill('test');
  await f.page.getByLabel('密码（仅同一访问身份可留空保留）', { exact: true }).fill('test-only');
  await f.page.getByText('高级：手动输入完整 URL（服务器不支持目录列举时使用）',{exact:true}).click();
  await f.page.getByLabel('共享父目录完整 URL',{exact:true}).fill('https://example.test/schedule/');
  await f.save.click(); await expect(f.page.locator('#connectionRows')).toContainText('WebDAV 课程');
  assert.deepEqual(f.errors, []);
});

test('首页初始化失败也不会让本地按钮静默；显示工作区错误并允许取消', async t => {
  const f = await fixture(t), before = structuredClone(f.app.catalog.manifest);
  await f.page.route('**/api/schedule?*', route => route.fulfill({ status: 400, json: { error: '页面来源不匹配' } }));
  await f.local();
  await expect(f.page.locator('#managementStatus')).toContainText('工作区尚未就绪');
  await expect(f.page.locator('#formError')).toContainText('工作区尚未就绪');
  await expect(f.save).toBeDisabled();
  await f.page.locator('#cancel').click();
  assert.deepEqual(f.app.catalog.manifest, before); assert.deepEqual(f.errors, []);
});

const csvPath = fileURLToPath(new URL('../../schedule-core/src/test/resources/schedule/hubei_2026-2027-1.csv', import.meta.url));
test('XLS 网页预览确认与 CSV 来源替换保留身份', async t => {
  const f = await fixture(t); await configureTerm(f);
  await openImport(f);
  await f.page.locator('#fields input[type=file]').setInputFiles(csvPath);
  await expect(f.save).toBeEnabled(); await f.save.click(); await f.page.locator('#feedbackAccept').click();
  await expect(f.page.locator('#editor')).not.toBeVisible();
  const before = structuredClone(f.app.db.frame.local.imported), id = f.app.db.frame.local.sources[0].id;
  await openImport(f); await f.page.locator('#fields select').selectOption(id);
  await f.page.locator('#fields input[type=file]').setInputFiles(csvPath.replace(/\.csv$/, '.xls'));
  await expect(f.save).toBeEnabled(); await f.save.click();
  await expect(f.page.locator('#feedbackMessage')).toContainText('替换 131 个原实例 → 131 个新实例');
  await expect(f.page.locator('#feedbackMessage')).toContainText('可能不再匹配的课程：无');
  await f.page.locator('#feedbackCancel').click();
  assert.deepEqual(f.app.db.frame.local.imported, before);
  await f.save.click(); await f.page.locator('#feedbackAccept').click();
  await expect(f.page.locator('#editor')).not.toBeVisible();
  assert.equal(f.app.db.frame.local.sources[0].id, id);
  assert.deepEqual(f.app.db.frame.local.imported, before); assert.deepEqual(f.errors, []);
});
async function openImport(f) {
  await f.open(); await f.page.locator('#schedulePage').click();
  await f.page.locator('#manage').click(); await f.page.locator('#importFile').click();
}
async function configureTerm(f) {
  const time = n => `${String(Math.floor(n / 60)).padStart(2, '0')}:${String(n % 60).padStart(2, '0')}`;
  await f.app.db.edit({ kind: 'profile', action: 'upsert', value: {
    termKey: '2026-2027-1', timezone: 'Asia/Shanghai', week1Monday: '2026-08-31',
    phases: [{ effectiveFrom: '2026-08-31', periods: Array.from({ length: 11 }, (_, i) => ({ periodNumber: i + 1, start: time(480 + i * 60), end: time(525 + i * 60) })) }],
  } });
}
test('CSV 缺少学期时保留已选文件和真实原因，禁止保存且不改课表', async t => {
  const f = await fixture(t), before = structuredClone(f.app.db.frame);
  await openImport(f); await expect(f.save).toBeDisabled();
  await f.page.locator('#fields input[type=file]').setInputFiles(csvPath);
  await expect(f.page.locator('#formError')).toHaveText('预览失败：请先配置学期 2026-2027-1');
  await expect(f.page.locator('#fields pre')).toContainText('已选择 hubei_2026-2027-1.csv');
  await expect(f.page.locator('#fields pre')).toContainText('在管理中新增对应学期');
  await expect(f.save).toBeDisabled(); assert.deepEqual(f.app.db.frame, before); assert.deepEqual(f.errors, []);
});
test('CSV 预览请求失败可重试原文件，成功后才能确认并导入', async t => {
  const f = await fixture(t); await configureTerm(f); let fail = true;
  await f.page.route('**/api/import/preview', route => fail ? route.fulfill({ status: 503, json: { error: '服务暂不可用' } }) : route.continue());
  await openImport(f); await f.page.locator('#fields input[type=file]').setInputFiles(csvPath);
  await expect(f.page.locator('#formError')).toHaveText('预览失败：服务暂不可用'); await expect(f.save).toBeDisabled();
  fail = false; await f.page.getByRole('button', { name: '重试预览', exact: true }).click();
  await expect(f.save).toBeEnabled(); await expect(f.page.locator('#formError')).toBeEmpty();
  await f.save.click(); await expect(f.page.locator('#feedbackMessage')).toContainText('确认导入以上预览');
  await f.page.locator('#feedbackAccept').click(); await expect(f.page.locator('#editor')).not.toBeVisible();
  assert.equal(f.app.db.frame.local.sources.length, 1); assert(f.app.db.frame.local.imported.length > 50); assert.deepEqual(f.errors, []);
});
test('快速改选 CSV 时清空旧预览，迟到响应不能覆盖新文件', async t => {
  const f = await fixture(t); await configureTerm(f); let release, entered;
  const gate = new Promise(r => { release = r; }), pending = new Promise(r => { entered = r; });
  await f.page.route('**/api/import/preview', async route => {
    const response = await route.fetch();
    if (route.request().postDataJSON().filename === 'old.csv') { entered(); await gate; }
    await route.fulfill({ response }).catch(() => {});
  });
  try {
    await openImport(f); const buffer = await readFile(csvPath), input = f.page.locator('#fields input[type=file]');
    await input.setInputFiles({ name: 'old.csv', mimeType: 'text/csv', buffer }); await pending;
    await expect(f.save).toBeDisabled();
    await input.setInputFiles({ name: 'new.csv', mimeType: 'text/csv', buffer });
    await expect(f.page.locator('#fields pre')).toContainText('new.csv'); await expect(f.save).toBeEnabled();
    release(); await expect(f.page.locator('#fields pre')).not.toContainText('old.csv');
    await f.save.click(); await f.page.locator('#feedbackAccept').click();
    await expect(f.page.locator('#editor')).not.toBeVisible(); assert.equal(f.app.db.frame.local.sources[0].filename, 'new.csv');
    assert.deepEqual(f.errors, []);
  } finally { release(); }
});

test('首次本地编辑、常用管理与高级诊断入口清晰可达', async t => {
  const f = await fixture(t); await f.open(); await f.page.locator('#schedulePage').click();
  await expect(f.page.locator('#welcome')).toBeVisible();
  await f.page.locator('#manage').click();
  for (const id of ['managerCourse', 'importFile', 'newProfile', 'adjustDate', 'config']) await expect(f.page.locator('#' + id)).toBeVisible();
  await expect(f.page.locator('#bind')).not.toBeVisible();
  await expect(f.page.locator('#details')).not.toBeVisible();
  await f.page.locator('#manager details summary').click(); await expect(f.page.locator('#bind')).toBeVisible();
  await f.page.locator('#conflicts').click(); await expect(f.page.locator('#fields')).toContainText('暂无冲突');
  await f.page.keyboard.press('Escape'); await expect(f.page.locator('#editor')).not.toBeVisible();
  await f.page.locator('#adjustDate').click();
  await expect(f.page.getByLabel('调整方式')).toHaveValue('none');
  await expect(f.page.getByLabel('补用星期（1–7）', { exact: true })).toBeDisabled();
  await f.page.getByLabel('调整方式').selectOption('weekday');
  await expect(f.page.getByLabel('补用星期（1–7）', { exact: true })).toBeEnabled();
  await f.page.keyboard.press('Escape');
  await f.page.locator('#managerCourse').click(); await f.page.getByLabel('课程名', { exact: true }).fill('离线课程');
  await f.save.click(); await expect(f.page.locator('#week')).toContainText('离线课程');
  assert.equal(f.app.catalog.manifest.connections.length, 0); assert.equal(f.app.db.frame.datasetId, null);
  assert.deepEqual(f.errors, []);
});

test('文件夹浏览与选择分开，面包屑、上级与空目录均可用', async t => {
  const f = await fixture(t, { authorized: true }), before = structuredClone(f.app.catalog.manifest);
  await f.page.route('**/api/directories?*', route => route.fulfill({ json: { directories: new URL(route.request().url()).searchParams.get('relative') === '课程/学期' ? [] : [new URL(route.request().url()).searchParams.get('relative') ? '学期' : '课程'] } }));
  await f.local(); await expect(f.save).toBeDisabled();
  await f.page.getByLabel('存储范围').selectOption('approved');
  await f.page.getByRole('button', { name: '📁 课程', exact: true }).click();
  await f.page.getByRole('button', { name: '📁 学期', exact: true }).click();
  await expect(f.page.locator('.folder-list')).toContainText('此文件夹没有子目录');
  await expect(f.save).toBeDisabled();
  await f.page.getByRole('button', { name: '选择此文件夹', exact: true }).click();
  await expect(f.page.locator('#selectedFolder')).toHaveText('已选择：fnOS 已授权测试目录 / 课程/学期');
  await expect(f.save).toBeEnabled();
  await f.page.getByRole('navigation', { name: '文件夹路径' }).getByRole('button', { name: '课程', exact: true }).click();
  await expect(f.page.locator('#fields [role=status]')).toContainText('/ 课程'); await expect(f.save).toBeDisabled();
  await f.page.getByRole('button', { name: '上一级', exact: true }).click();
  await expect(f.page.locator('#fields [role=status]')).toContainText('根目录');
  await f.page.keyboard.press('Escape'); await expect(f.page.locator('#addLocal')).toBeFocused();
  assert.deepEqual(f.app.catalog.manifest, before); assert.deepEqual(f.errors, []);
});

test('同一根下快速导航与关闭重开会隔离旧目录响应', async t => {
  const f = await fixture(t); let release, started;
  const gate = new Promise(r => release = r), waiting = new Promise(r => started = r);
  await f.page.route('**/api/directories?*', async route => {
    if (new URL(route.request().url()).searchParams.get('relative') === '慢目录') { started(); await gate; await route.fulfill({ json: { directories: ['旧内容'] } }).catch(() => {}); }
    else await route.fulfill({ json: { directories: ['慢目录'] } });
  });
  try {
    await f.local(); await f.page.getByRole('button', { name: '📁 慢目录' }).click(); await waiting;
    await expect(f.save).toBeDisabled();
    await f.page.getByRole('navigation', { name: '文件夹路径' }).getByRole('button', { name: 'FPK 私有本地数据', exact: true }).click();
    await choosePrivate(f); await expect(f.save).toBeEnabled();
    await f.page.keyboard.press('Escape'); await f.page.locator('#addLocal').click(); await choosePrivate(f);
    release(); await expect(f.page.locator('.folder-list')).not.toContainText('旧内容');
    await expect(f.page.locator('#selectedFolder')).toContainText('根目录'); assert.deepEqual(f.errors, []);
  } finally { release(); }
});

test('编辑本地连接回显原目录；浏览后必须重新确认，取消不保存', async t => {
  const f = await fixture(t, { authorized: true });
  await f.page.route('**/api/manage?*', async route => {
    const response = await route.fetch(), data = await response.json();
    data.connections.push({ id: 'local-fixture', type: 'local', name: '已有本地目录', rootId: 'approved', relative: '课程/原目录' });
    await route.fulfill({ json: data });
  });
  await f.open(); const row = f.page.locator('#connectionRows article').filter({ hasText: '已有本地目录' });
  await row.locator('summary').click(); await row.getByRole('button', { name: '编辑连接' }).click();
  await expect(f.page.getByLabel('存储范围')).toHaveValue('approved');
  await expect(f.page.locator('#selectedFolder')).toHaveText('已选择：fnOS 已授权测试目录 / 课程/原目录');
  await expect(f.save).toBeEnabled();
  await f.page.getByRole('button', { name: '上一级', exact: true }).click(); await expect(f.save).toBeDisabled();
  await f.page.keyboard.press('Escape'); assert.equal(f.app.catalog.manifest.connections.length, 0); assert.deepEqual(f.errors, []);
});

test('窄窗口目录按钮可触达，管理页面截图可核对', async t => {
  const f = await fixture(t, { authorized: true }); await f.open();
  if (process.env.UX_SCREENSHOTS) await f.page.screenshot({ path: process.env.UX_SCREENSHOTS + '/sources-desktop.png', fullPage: true });
  await f.page.locator('#schedulePage').click(); await f.page.locator('#manage').click();
  if (process.env.UX_SCREENSHOTS) await f.page.screenshot({ path: process.env.UX_SCREENSHOTS + '/manage-desktop.png', fullPage: true });
  await f.page.setViewportSize({ width: 390, height: 640 }); await f.page.locator('#config').click(); await f.page.locator('#addLocal').click();
  await choosePrivate(f); await expect(f.save).toBeInViewport();
  assert.equal(await f.page.locator('#editor').evaluate(e => e.scrollWidth <= e.clientWidth), true);
  await f.page.getByRole('button', { name: '📁 测试子目录', exact: true }).click();
  await f.page.getByRole('button', { name: '选择此文件夹', exact: true }).click();
  await expect(f.save).toBeEnabled();
  if (process.env.UX_SCREENSHOTS) await f.page.screenshot({ path: process.env.UX_SCREENSHOTS + '/folders-narrow.png' });
  await f.page.keyboard.press('Escape'); assert.deepEqual(f.errors, []);
});

test('文件夹确认提交所选授权范围和相对路径，保存失败不改配置', async t => {
  const f = await fixture(t, { authorized: true }), before = structuredClone(f.app.catalog.manifest);
  let input;
  await f.page.route('**/api/manage', route => {
    input = route.request().postDataJSON(); return route.fulfill({ status: 409, json: { error: '测试期间不保存配置' } });
  });
  await f.local(); await f.page.getByLabel('存储范围').selectOption('approved');
  await f.page.getByRole('button', { name: '📁 测试子目录', exact: true }).click();
  await f.page.getByRole('button', { name: '选择此文件夹', exact: true }).click();
  await f.save.click(); await expect(f.page.locator('#formError')).toHaveText('测试期间不保存配置');
  assert.equal(input.action, 'connection.save'); assert.equal(input.rootId, 'approved'); assert.equal(input.relative, '测试子目录'); assert.equal(input.createPrivate, false);
  assert(input.workspaceId); assert.equal(typeof input.catalogVersion, 'number'); assert.equal(typeof input.version, 'number');
  assert.deepEqual(f.app.catalog.manifest, before); assert.deepEqual(f.errors, []);
});

test('数据源高级操作保持可达，重命名生效，创建和绑定仍须确认', async t => {
  const f = await fixture(t); await f.open();
  await f.page.locator('#addWebDav').click();
  await f.page.getByLabel('WebDAV 服务器访问地址（可含起始路径）', { exact: true }).fill('https://example.test/schedule/');
  await f.page.getByLabel('用户名', { exact: true }).fill('test');
  await f.page.getByLabel('密码（仅同一访问身份可留空保留）', { exact: true }).fill('test-only');
  await f.page.getByText('高级：手动输入完整 URL（服务器不支持目录列举时使用）',{exact:true}).click();
  await f.page.getByLabel('共享父目录完整 URL',{exact:true}).fill('https://example.test/schedule/');
  await f.save.click(); await expect(f.page.locator('#connectionRows')).toContainText('WebDAV 课程');
  await f.page.getByRole('button', { name: '新建课程表', exact: true }).click(); await expect(f.page.getByLabel('共享协议')).toHaveValue('v3');
  await f.page.getByLabel('共享协议').selectOption('v2'); await f.save.click();
  await expect(f.page.locator('#workspaceRows')).toContainText('WebDAV 课程');
  const id = f.app.catalog.manifest.workspaces.find(w => w.connectionId).id;
  await f.page.locator('#workspaceSelect').selectOption(id); await expect(f.page.locator('#workspaceSelect')).toBeEnabled();
  let card = f.page.locator('#workspaceRows article').filter({ hasText: 'WebDAV 课程' });
  await card.getByText('高级操作：端点、迁移与移除', { exact: true }).click();
  await card.getByRole('button', { name: '修改名称', exact: true }).click();
  await f.page.locator('#feedbackInput').fill('重命名后的课程表'); await f.page.locator('#feedbackAccept').click();
  card = f.page.locator('#workspaceRows article').filter({ hasText: '重命名后的课程表' }); await expect(card).toBeVisible();
  await card.getByText('高级操作：端点、迁移与移除', { exact: true }).click();
  await card.getByRole('button', { name: '选择访问端点', exact: true }).click(); await expect(f.page.locator('#editorTitle')).toHaveText('工作区访问端点');
  await f.page.keyboard.press('Escape');
  for (const [label, text] of [['加入已有共享课表…', '确认加入此目录已有共享课表'], ['创建新的共享课表…', '将当前课程表内容发布为新的独立共享课表']]) {
    await card.getByRole('button', { name: label, exact: true }).click(); await expect(f.page.locator('#feedbackMessage')).toContainText(text);
    await f.page.locator('#feedbackCancel').click();
  }
  await card.getByText('高级诊断', { exact: true }).click(); await expect(card.locator('pre')).toBeVisible();
  await card.getByRole('button', { name: '移除配置', exact: true }).click(); await expect(f.page.locator('#feedbackMessage')).toContainText('只移除配置');
  await f.page.locator('#feedbackCancel').click(); assert.equal(f.app.catalog.manifest.workspaces.length, 2); assert.deepEqual(f.errors, []);
});

test('历史中心完整快照分页、稳定身份比较、回退预览确认及旧历史保留', async t => {
  const rows = new Map(), datasetId = randomUUID(), writerId = randomUUID(), rootId = randomUUID(), tipId = randomUUID();
  const entry = (id,title) => ({ id,title,day:1,startMinute:480,endMinute:540,location:'' });
  const root = { datasetId, writerId, commitId:rootId, parents:[], state:{ ...emptyState(), entries:[entry('stable','原课程')] } };
  const tip = { ...root, commitId:tipId, parents:[rootId], state:{ ...emptyState(), entries:[entry('stable','修改后课程'), ...Array.from({length:45},(_,i)=>entry('extra-'+i,'新增'+i))] } };
  rows.set(rootId,encode(root)); rows.set(tipId,encode(tip));
  const remote = { key:'test',list:async()=>[...rows.keys()],read:async id=>rows.get(id)??null,put:async(id,b)=>rows.set(id,b) };
  const f = await fixture(t,{remoteFactory:()=>remote});
  const graph = await loadGraph(remote), id = randomUUID(), manifest = structuredClone(f.app.catalog.manifest);
  manifest.connections.push({id,name:'测试连接',type:'webdav',config:{url:'https://example.com/test/',username:'test',password:'test'}});manifest.workspaces[0].connectionId=id;
  await f.app.catalog.commit(manifest);
  await f.app.db.tx(frame=>{ frame.graph=graph;frame.local=graph.state;frame.baseline=graph.state;frame.frontier=graph.tips;frame.datasetId=datasetId;frame.bindingKey='test';frame.checkpoint={hashes:graph.hashes,datasetId}; });
  await f.open(); await f.page.locator('#historyPage').click(); await f.page.locator('#advancedHistory').evaluate(e=>e.open=true);
  await expect(f.page.locator('#historyStatus')).toContainText('2 / 256');
  await f.page.locator('#historyRows button').first().click();
  await f.page.getByRole('button',{name:'展开此集合',exact:true}).first().click();
  await f.page.getByRole('button',{name:'继续查看',exact:true}).click();
  await expect(f.page.locator('#historyOutput')).toContainText('修改后课程');
  await f.page.locator('#historyFrom').selectOption(rootId);await f.page.locator('#historyTo').selectOption(tipId);
  await f.page.locator('#historyDiff').click(); await expect(f.page.locator('#historyOutput')).toContainText('共 46 条记录变化');
  await f.page.getByRole('button',{name:'继续查看下 40 条',exact:true}).click();
  await expect(f.page.locator('#historyOutput')).toContainText('修改');
  await f.page.locator('#historyTo').selectOption(rootId); await f.page.locator('#historyPreview').click();
  await expect(f.page.locator('#historyConfirm')).toBeEnabled();
  await expect(f.page.locator('#historyOutput')).toContainText('删除 45');
  await f.page.locator('#historyConfirm').click(); await f.page.locator('#feedbackAccept').click();
  await expect(f.page.locator('#historyStatus')).toContainText('3 / 256');
  assert.equal(rows.size,3); assert(rows.get(rootId).equals(encode(root))); assert(rows.get(tipId).equals(encode(tip)));
  const published = decode(rows.get(f.app.db.frame.receipt.commitId)); assert.deepEqual(published.parents,[tipId]);assert.equal(published.state.entries.length,1);
  const left={...published,commitId:randomUUID(),parents:[published.commitId],state:{...emptyState(),entries:[...published.state.entries,entry('left','左分支新增')]}};
  const right={...published,commitId:randomUUID(),parents:[published.commitId],state:{...emptyState(),entries:[...published.state.entries,entry('right','右分支新增')]}};
  const merged={...published,commitId:randomUUID(),parents:[left.commitId,right.commitId].sort(),state:{...emptyState(),entries:[...left.state.entries,entry('right','右分支新增')]}};
  for(const c of [left,right,merged]) rows.set(c.commitId,encode(c));
  await f.page.locator('#historyCheck').click();
  const mergeRow=f.page.locator('#historyRows article.course').first();
  await expect(mergeRow).toContainText('合并版本');
  await expect(mergeRow.locator('p').filter({hasText:/相对父版本.*1 条变化/})).toHaveCount(2);
  await expect(mergeRow).not.toContainText('空快照');
  if(process.env.UX_SCREENSHOTS)await f.page.screenshot({path:process.env.UX_SCREENSHOTS+'/history-1280x800.png',fullPage:true});
  assert.deepEqual(f.errors,[]);
});

test('换库向导显示真实来源、人工设备清单与零物理回收；失败保留可恢复入口', async t => {
  const root = { datasetId: randomUUID(), commitId: randomUUID(), writerId: randomUUID(), parents: [], state: emptyState() };
  const rows = new Map([[root.commitId, encode(root)]]), remote = { key: 'migration-ui', list: async () => [...rows.keys()], read: async id => rows.get(id) ?? null };
  const f = await fixture(t, { authorized: true, remoteFactory: () => remote });
  const graph = await loadGraph(remote), manifest = structuredClone(f.app.catalog.manifest), connectionId = randomUUID();
  manifest.connections.push({ id: connectionId, name: '目标地址', type: 'webdav', config: { url: 'https://example.test/compact/', username: 'synthetic', password: 'synthetic' } });
  manifest.workspaces[0].connectionId = connectionId; await f.app.catalog.commit(manifest);
  await f.app.db.tx(frame => { frame.graph = graph; frame.datasetId = root.datasetId; frame.local = root.state; frame.baseline = root.state; frame.frontier = graph.tips; frame.checkpoint = { datasetId: root.datasetId, hashes: graph.hashes }; });
  await f.open(); await f.page.locator('#historyPage').click(); await f.page.locator('#advancedHistory').evaluate(e=>e.open=true);
  await expect(f.page.locator('#datasetMigration')).toContainText('迁移到精简数据集');
  await f.page.getByRole('button',{name:'选择精简候选目录',exact:true}).click();
  await f.page.getByRole('button',{name:'📁 测试子目录',exact:true}).click();
  await f.page.getByRole('button',{name:'选择此文件夹',exact:true}).click();
  await f.page.getByRole('button', { name: '检查归档与候选，准备换库', exact: true }).click();
  await expect(f.page.locator('#historyStatus')).toContainText('目标连接已有工作区');
  assert.equal(f.app.catalog.migrations.length, 0); assert.equal(rows.size, 1);
  const m = { id: randomUUID(), revision: 0, sourceId: manifest.activeId, targetId: randomUUID(), phase: 'frozen', sourceDatasetId: root.datasetId, targetDatasetId: randomUUID(), oldCount: 2, newCount: 1, reclaimEstimateBytes: 1024, archive: { relative: 'history-synthetic' }, sourceWriters: [root.writerId], lastCheck: 'FPK 实际验证（浏览器合成展示夹具）', failure: '离线，已保留保护', devices: [{ name: '床头', type: 'X08E', stopped: true, accepted: false, evidence: '尚未执行本机迁移', origin: '管理员登记 / 确认' }] };
  f.app.catalog.migrations = [m]; await f.app.catalog.store.save('dataset-migrations.json', [m]);
  await f.page.locator('#historyPage').click(); await f.page.locator('#advancedHistory').evaluate(e=>e.open=true);
  await expect(f.page.locator('#datasetMigration')).toContainText('实际已回收 0 字节');
  await expect(f.page.locator('#datasetMigration')).toContainText('管理员登记 / 确认');
  await expect(f.page.locator('#datasetMigration')).toContainText('尚未执行本机迁移');
  await expect(f.page.getByRole('button', { name: '5. 切换 FPK（仍保持新旧停写）', exact: true })).toBeVisible();
  if(process.env.UX_SCREENSHOTS)await f.page.screenshot({path:process.env.UX_SCREENSHOTS+'/migration-1280x800.png',fullPage:true});
  // 仅模拟后端已经成功切换；验证页面不再携带旧工作区身份请求历史。
  await f.page.route('**/api/migration', async route => {
    const data = route.request().postDataJSON();
    if (data.action !== 'switch') return route.continue();
    const next = structuredClone(f.app.catalog.manifest);
    const db = await Workspace.open(f.app.catalog.workspaceStore(m.targetId), graph);
    next.workspaces.push({ id: m.targetId, name: '已切换的新工作区', connectionId });
    next.activeId = m.targetId; next.revision++;
    await f.app.catalog.commit(next); f.app.catalog.attach(m.targetId, db);
    m.phase = 'switched';
    await route.fulfill({ json: { records: [m] } });
  });
  await f.page.getByRole('button', { name: '5. 切换 FPK（仍保持新旧停写）', exact: true }).click();
  await f.page.locator('#feedbackInput').fill(m.targetDatasetId);
  await f.page.locator('#feedbackAccept').click();
  await expect(f.page.locator('#workspaceSelect')).toHaveValue(m.targetId);
  await expect(f.page.locator('#datasetMigration')).toContainText('阶段：switched');
  await expect(f.page.locator('#historyStatus')).toContainText('1 / 256');
  await expect(f.page.locator('#historyStatus')).not.toContainText('迁移只可访问当前工作区');
  assert.deepEqual(f.errors, []);
});

test('PR58 v3 端点确认后刷新 Catalog revision，随后备份读取不要求手工刷新', async t => {
  const f = await fixture(t, { authorized: true }), datasetId = randomUUID(), connectionId = randomUUID(), token = randomUUID();
  const revision = f.app.catalog.manifest.revision;
  let committed = false, backupRevision;
  await f.page.route('**/api/schedule?*', async route => {
    const response = await route.fetch(), data = await response.json();
    await route.fulfill({ response, json: { ...data, protocol: 'v3', datasetId, catalogVersion: revision + Number(committed) } });
  });
  await f.page.route('**/api/manage?*', async route => {
    const response = await route.fetch(), data = await response.json();
    data.revision = revision + Number(committed);
    data.connections.push({ id: connectionId, name: '合成候选', type: 'webdav', url: 'https://candidate.test/', username: 'synthetic', hasCredentials: true });
    data.workspaces[0] = { ...data.workspaces[0], protocol: 'v3', connectionId, datasetId };
    await route.fulfill({ response, json: data });
  });
  await f.page.route('**/api/endpoint', async route => {
    const input = route.request().postDataJSON();
    if (input.action === 'status') return route.fulfill({ json: { status: 'NOT_STARTED' } });
    if (input.action === 'confirm') {
      assert.equal(input.catalogVersion, revision); committed = true;
      // 与真实确认回执一致：不携带新 Catalog revision。
      return route.fulfill({ json: { status: 'COMMITTED', token, datasetId } });
    }
    await route.fulfill({ json: { status: 'READY', token, datasetId, version: 0, catalogVersion: revision } });
  });
  await f.page.route('**/api/backups/list', async route => {
    backupRevision = route.request().postDataJSON().catalogVersion;
    if (backupRevision !== revision + 1) return route.fulfill({ status: 409, json: { error: '管理版本过期' } });
    await route.fulfill({ json: { backups: [], directoryIdentity: 'synthetic-directory' } });
  });
  await f.open();
  const card = f.page.locator('#workspaceRows article').first();
  await card.getByText('高级操作：端点、迁移与移除', { exact: true }).click();
  await card.getByRole('button', { name: '选择访问端点', exact: true }).click();
  await f.page.getByRole('button', { name: '只读预检所选连接', exact: true }).click();
  await expect(f.page.getByRole('button', { name: '确认更换此工作区端点', exact: true })).toBeEnabled();
  await f.page.getByRole('button', { name: '确认更换此工作区端点', exact: true }).click();
  await f.page.locator('#feedbackAccept').click();
  await expect(f.page.locator('#fields')).toContainText('本机访问端点已切换');
  await f.page.locator('#cancel').click();
  await f.page.locator('#backupsPage').click();
  await expect(f.page.locator('#backupStatus')).toContainText('0 个加密备份');
  assert.equal(backupRevision, revision + 1); assert.deepEqual(f.errors, []);
});


test('PR59 WebDAV 草稿只读浏览、编码路径、明确选择与高级退路',async t=>{
  const f=await fixture(t), before=structuredClone(f.app.catalog.manifest), calls=[];
  await f.page.route('**/api/connections/browse',async route=>{
    const data=route.request().postDataJSON();calls.push(data);
    const url=data.browseUrl, child=new URL('%E8%AF%BE%E8%A1%A8%20%F0%9F%93%9A/',data.url).href;
    await route.fulfill({json:{kind:'webdav',root:data.url,url,directories:url===data.url?[{name:'课表 📚',url:child}]:[]}});
  });
  await f.open();await f.page.locator('#addWebDav').click();
  await f.page.getByLabel('WebDAV 服务器访问地址（可含起始路径）',{exact:true}).fill('https://nas.test/dav/');
  await f.page.getByLabel('用户名',{exact:true}).fill('synthetic');
  await f.page.getByLabel('密码（仅同一访问身份可留空保留）',{exact:true}).fill('test-only');
  await f.page.getByRole('button',{name:'浏览服务器文件夹',exact:true}).click();
  await f.page.getByRole('button',{name:'📁 课表 📚',exact:true}).click();
  await expect(f.page.locator('#fields')).toContainText('此文件夹没有子目录');await expect(f.save).toBeDisabled();
  await f.page.getByRole('button',{name:'选择此文件夹',exact:true}).click();await expect(f.save).toBeEnabled();
  await expect(f.page.locator('#fields')).toContainText('已选择：https://nas.test/dav/课表 📚/');
  if(process.env.UX_SCREENSHOTS)await f.page.screenshot({path:process.env.UX_SCREENSHOTS+'/webdav-desktop.png'});
  await f.page.setViewportSize({width:420,height:800});
  if(process.env.UX_SCREENSHOTS)await f.page.screenshot({path:process.env.UX_SCREENSHOTS+'/webdav-narrow.png'});
  await f.page.getByLabel('用户名',{exact:true}).fill('changed');await expect(f.save).toBeDisabled();
  await expect(f.page.locator('#fields .folder-list')).toHaveCount(0);
  await f.page.locator('#cancel').click();assert.deepEqual(f.app.catalog.manifest,before);
  assert(calls.every(c=>c.url==='https://nas.test/dav/'));assert.equal(calls.length,2);assert.deepEqual(f.errors,[]);
});

test('PR59 WebDAV 失败可重试；改凭据和关闭后迟到响应不改其他草稿',async t=>{
  const f=await fixture(t);let fail=true,release,entered;
  const gate=new Promise(r=>{release=r;}),pending=new Promise(r=>{entered=r;});
  await f.page.route('**/api/connections/browse',async route=>{
    if(fail)return route.fulfill({status:403,json:{error:'目录无权访问；可使用高级地址'}});
    entered();await gate;await route.fulfill({json:{directories:[{name:'迟到文件夹',url:'https://nas.test/dav/late/'}]}}).catch(()=>{});
  });
  await f.open();await f.page.locator('#addWebDav').click();
  await f.page.getByLabel('WebDAV 服务器访问地址（可含起始路径）',{exact:true}).fill('https://nas.test/dav/');
  await f.page.getByRole('button',{name:'浏览服务器文件夹',exact:true}).click();
  await expect(f.page.locator('#fields')).toContainText('目录加载失败：目录无权访问');await expect(f.save).toBeDisabled();
  fail=false;await f.page.getByRole('button',{name:'重试读取目录',exact:true}).click();await pending;
  await f.page.locator('#cancel').click();await f.page.locator('#addWebDav').click();
  release();await expect(f.page.locator('#fields')).not.toContainText('迟到文件夹');await expect(f.save).toBeDisabled();
  await f.page.getByText('高级：手动输入完整 URL（服务器不支持目录列举时使用）',{exact:true}).click();
  await f.page.getByLabel('共享父目录完整 URL',{exact:true}).fill('https://manual.test/中文/');await expect(f.save).toBeEnabled();
  await f.page.locator('#cancel').click();assert.deepEqual(f.errors,[]);
});

test('PR59 备份共用选择器保留目录身份与文件名；切换位置撤销恢复预览',async t=>{
  const f=await fixture(t,{authorized:true});let verified,cancelled;
  await f.page.route('**/api/backups/list',route=>route.fulfill({json:{directoryIdentity:'identity-'+route.request().postDataJSON().relative,backups:[{name:'synthetic.dpbk',size:42,modifiedAt:0,status:'未验证'}]}}));
  await f.page.route('**/api/backups/verify',route=>{verified=route.request().postDataJSON();return route.fulfill({json:{token:'preview-only',createdAt:0,appVersion:'test',formatVersion:'DPBK0002',workspaceCount:0,fileCount:0,bytes:0,coverage:'合成测试',dependencies:[]}});});
  await f.page.route('**/api/backups/cancel',route=>{cancelled=route.request().postDataJSON();return route.fulfill({json:{ok:true}});});
  await f.open();await f.page.locator('#backupsPage').click();await f.page.getByRole('button',{name:'选择备份文件夹',exact:true}).click();
  await f.page.getByRole('button',{name:'📁 测试子目录',exact:true}).click();await f.page.getByRole('button',{name:'选择此文件夹',exact:true}).click();
  await expect(f.page.locator('#backupRelative')).toHaveValue('测试子目录');await f.page.getByRole('button',{name:'验证并预览恢复',exact:true}).click();
  await expect(f.page.locator('#backupRestore')).toBeEnabled();assert.equal(verified.relative,'测试子目录');assert.equal(verified.directoryIdentity,'identity-测试子目录');assert.equal(verified.name,'synthetic.dpbk');
  await f.page.locator('#backupChoose').click();await f.page.locator('dialog[open]').getByRole('button',{name:'取消',exact:true}).click();
  await expect(f.page.locator('#backupRestore')).toBeEnabled();
  await f.page.locator('#backupChoose').click();await f.page.locator('dialog[open]').getByRole('button',{name:'上一级',exact:true}).click();await f.page.getByRole('button',{name:'选择此文件夹',exact:true}).click();
  await expect(f.page.locator('#backupRestore')).toBeDisabled();await expect(f.page.locator('#backupPreview')).toBeEmpty();
  assert.equal(cancelled.token,'preview-only');assert.deepEqual(f.errors,[]);
});

test('PR59 历史导出及迁移候选通过同一授权浏览器选择，取消不改表单',async t=>{
  const f=await fixture(t,{authorized:true});await f.open();await f.page.locator('#historyPage').click(); await f.page.locator('#advancedHistory').evaluate(e=>e.open=true);
  await f.page.locator('#historyChoose').click();await f.page.getByRole('button',{name:'📁 测试子目录',exact:true}).click();await f.page.getByRole('button',{name:'选择此文件夹',exact:true}).click();
  await expect(f.page.locator('#historyRelative')).toHaveValue('测试子目录');
  await f.page.getByRole('button',{name:'选择精简候选目录',exact:true}).click();await f.page.getByRole('button',{name:'📁 测试子目录',exact:true}).click();
  await f.page.locator('dialog[open]').getByRole('button',{name:'取消',exact:true}).click();await expect(f.page.locator('#migrationCandidate')).toHaveValue('');
  await f.page.getByRole('button',{name:'选择精简候选目录',exact:true}).click();await f.page.getByRole('button',{name:'选择此文件夹',exact:true}).click();
  await expect(f.page.locator('#migrationCandidate')).toHaveValue('测试子目录');assert.deepEqual(f.errors,[]);
});


test('PR59 重开历史页可发现现有 compact 目录；名称不是验证结论',async t=>{
  const f=await fixture(t,{authorized:true});
  await f.page.route('**/api/directories?*',route=>route.fulfill({json:{directories:['compact-existing','history-existing']}}));
  await f.open();await f.page.locator('#historyPage').click(); await f.page.locator('#advancedHistory').evaluate(e=>e.open=true);await f.page.getByRole('button',{name:'选择精简候选目录',exact:true}).click();
  await f.page.getByRole('button',{name:'compact-existing（待后端验证）',exact:true}).click();
  await expect(f.page.locator('#migrationCandidate')).toHaveValue('compact-existing');assert.deepEqual(f.errors,[]);
});


test('PR59 独立归档从授权目录选择，仍由恢复 API 验证清单',async t=>{
  const f=await fixture(t,{authorized:true});let input=null;
  await f.page.route('**/api/schedule?*',async route=>{const response=await route.fetch(),data=await response.json();await route.fulfill({response,json:{...data,protocol:'v3'}});});
  await f.page.route('**/api/history?*',route=>route.fulfill({json:{version:0,count:0,frontier:[],blockers:[],rows:[],next:null,rollbackEnabled:false}}));
  await f.page.route('**/api/history/archive-restore',route=>{input=route.request().postDataJSON();return route.fulfill({status:400,json:{error:'归档清单验证失败；原始缓存保留'}});});
  await f.open();await f.page.locator('#historyPage').click(); await f.page.locator('#advancedHistory').evaluate(e=>e.open=true);await f.page.getByRole('button',{name:'从独立归档恢复缺失缓存',exact:true}).click();
  await f.page.getByRole('button',{name:'📁 测试子目录',exact:true}).click();await f.page.locator('dialog[open]').getByRole('button',{name:'取消',exact:true}).click();assert.equal(input,null);
  await f.page.getByRole('button',{name:'从独立归档恢复缺失缓存',exact:true}).click();await f.page.getByRole('button',{name:'📁 测试子目录',exact:true}).click();await f.page.getByRole('button',{name:'选择此文件夹',exact:true}).click();
  await expect(f.page.locator('#historyStatus')).toContainText('归档清单验证失败');await expect(f.page.getByRole('button',{name:'从独立归档恢复缺失缓存',exact:true})).toBeEnabled();assert.equal(input.rootId,'approved');assert.equal(input.relative,'测试子目录');assert(input.workspaceId);assert.deepEqual(f.errors,[]);
});


test('审查修复：编辑 WebDAV 从原路径浏览，取消和失败不扩大范围或改连接',async t=>{
  const f=await fixture(t),id=randomUUID();
  const manifest=structuredClone(f.app.catalog.manifest);
  manifest.connections.push({id,name:'原路径连接',type:'webdav',config:{url:'https://nas.test/dav/中文%20a/',username:'u',password:'synthetic-secret'}});
  await f.app.catalog.commit(manifest);const before=structuredClone(f.app.catalog.manifest);let request;
  await f.page.route('**/api/connections/browse',route=>{request=route.request().postDataJSON();return route.fulfill({status:403,json:{error:'无权读取此目录'}});});
  await f.open();const card=f.page.locator('#connectionRows article').filter({hasText:'原路径连接'});
  await card.getByText('连接操作',{exact:true}).click();await card.getByRole('button',{name:'编辑连接',exact:true}).click();
  await expect(f.page.getByLabel('WebDAV 服务器访问地址（可含起始路径）',{exact:true})).toHaveValue('https://nas.test/dav/中文%20a/');
  await f.page.getByRole('button',{name:'浏览服务器文件夹',exact:true}).click();
  await expect(f.page.locator('#fields')).toContainText('目录加载失败');
  assert.equal(new URL(request.url).pathname,'/dav/%E4%B8%AD%E6%96%87%20a/');assert.equal(request.url,request.browseUrl);assert.equal(request.password,'');assert.equal(request.connectionId,id);
  await f.page.getByText('高级：手动输入完整 URL（服务器不支持目录列举时使用）',{exact:true}).click();
  await expect(f.page.getByLabel('共享父目录完整 URL',{exact:true})).toHaveValue('https://nas.test/dav/中文%20a/');
  await f.page.locator('#cancel').click();assert.deepEqual(f.app.catalog.manifest,before);assert.deepEqual(f.errors,[]);
});


test('PR60 首页按结构化状态显示共享事实，保留异常与写保护', async t => {
  const f = await fixture(t);
  let sample = { status: 'OBSERVED', protocol: 'v3', datasetId: 'fixture-only', dirty: false, pending: false };
  await f.page.route('**/api/schedule?*', async route => {
    const response = await route.fetch();
    const value = await response.json();
    await route.fulfill({ json: { ...value, ...sample } });
  });
  await f.open();
  for (const [status, expected] of [['OBSERVED', '服务器检查成功'], ['PUBLISHED', '回读确认'], ['CONFLICT', '修改冲突'], ['BUDGET', '尚未完成'], ['DISCOVERING', '尚未完成'], ['RECOVERY_REQUIRED', '恢复未完成'], ['error', '检查失败']]) {
    sample = { ...sample, status };
    await f.page.reload();
    await expect(f.page.locator('#status')).toContainText(expected);
    assert.doesNotMatch(await f.page.locator('#status').textContent(), /Epoch|Writer|DAG|Descriptor|所有设备同步完成/);
  }
  sample = { ...sample, status: 'OBSERVED', dirty: true };
  await f.page.reload(); await expect(f.page.locator('#status')).toContainText('待发布');
  sample = { ...sample, pending: true, writeProtection: '管理员已禁止写入' };
  await f.page.reload(); await expect(f.page.locator('#status')).toContainText('待服务器回读确认');
  await expect(f.page.locator('#status')).toContainText('管理员已禁止写入');
  assert.deepEqual(f.errors, []);
});

// PR61: real protocol history in isolated local servers; credentials and all writes are synthetic.
function memoryV3() {
  const objects=new Map();
  return {objects,read:async p=>objects.get(p)??null,putOnce:async(p,b)=>{assert(!objects.has(p));objects.set(p,Buffer.from(b));},
    list:async prefix=>[...new Set([...objects.keys()].filter(p=>p.startsWith(prefix)).map(p=>{const tail=p.slice(prefix.length),i=tail.indexOf('/');return prefix+(i<0?tail:tail.slice(0,i+1));}))].sort()};
}
async function seedV3(f,remote) {
  const catalog=f.app.catalog,manage=input=>catalog.manage({catalogVersion:catalog.manifest.revision,...input});
  const {connectionId}=await manage({action:'connection.save',name:'隔离共享连接',type:'webdav',url:'https://isolated.test/',username:'synthetic',password:'synthetic'});
  const {workspaceId:id}=await manage({action:'workspace.add',connectionId,name:'隔离 v3 课程表',protocol:'v3'});
  await manage({action:'workspace.switch',targetId:id});
  const sync=options=>catalog.network(id,e=>e.synchronize(options));
  await sync({create:true});for(let i=0;i<4;i++)await sync();
  const db=catalog.db(id), maintenance=new V3Maintenance(catalog,id), root=maintenance.list().rows.find(r=>r.type===2);
  await db.edit({kind:'entry',action:'upsert',value:{id:'stable',title:'历史验证课程',day:1,startMinute:480,endMinute:540,location:'测试'}});
  await sync();for(let i=0;i<4;i++)await sync();
  return {id,db,root,sync,maintenance};
}

test('PR61 管理服务单独失败不覆盖成功读取的课表，四页均可直接返回',async t=>{
  const f=await fixture(t);await f.app.db.edit({kind:'entry',action:'upsert',value:{id:'local',title:'保留本地课程',day:1,startMinute:480,endMinute:540,location:''}});
  await f.page.route('**/api/manage?*',route=>route.fulfill({status:503,json:{error:'管理接口独立故障'}}));
  await f.open();await expect(f.page.locator('#managementStatus')).toContainText('管理接口独立故障');
  await expect(f.page.locator('#status')).not.toContainText('管理接口独立故障');
  await f.page.locator('#schedulePage').click();await expect(f.page.locator('#week')).toContainText('保留本地课程');
  for(const id of ['historyPage','backupsPage','sourcesPage']){
    await f.page.locator('#'+id).click();await f.page.locator('#schedulePage').click();await expect(f.page.locator('#week')).toBeVisible();
    await expect(f.page.locator('#schedulePage')).toHaveAttribute('aria-current','page');
  }
  assert.deepEqual(f.errors,[]);
});

test('PR61 默认 v3 创建引导保留本地课程，取消新建不改变绑定，空白配置不写远端',async t=>{
  const remote=memoryV3(),f=await fixture(t,{v3RemoteFactory:()=>remote});
  await f.app.db.edit({kind:'entry',action:'upsert',value:{id:'local',title:'原本地课程',day:1,startMinute:480,endMinute:540,location:''}});
  await f.open();await f.page.locator('#addWebDav').click();
  await f.page.getByLabel('WebDAV 服务器访问地址（可含起始路径）',{exact:true}).fill('https://isolated.test/');
  await f.page.getByLabel('用户名',{exact:true}).fill('synthetic');await f.page.getByLabel('密码（仅同一访问身份可留空保留）',{exact:true}).fill('synthetic');
  await f.page.getByText('高级：手动输入完整 URL（服务器不支持目录列举时使用）',{exact:true}).click();
  await f.page.getByLabel('共享父目录完整 URL',{exact:true}).fill('https://isolated.test/');await f.save.click();
  await expect(f.page.locator('#connectionNext')).toContainText('没有创建或加入共享数据');
  const before=structuredClone(f.app.catalog.manifest);
  await f.page.getByRole('button',{name:'继续：新建空白课程表',exact:true}).click();
  await expect(f.page.getByLabel('共享协议')).toHaveValue('v3');await expect(f.page.locator('#fields')).toContainText('不包含当前本地课程');
  await f.page.locator('#cancel').click();assert.deepEqual(f.app.catalog.manifest,before);assert.equal(remote.objects.size,0);
  await f.page.getByRole('button',{name:'新建课程表',exact:true}).click();await f.save.click();
  await expect(f.page.locator('#workspaceSelect')).not.toHaveValue(before.activeId);
  assert.equal(f.app.catalog.row(f.app.catalog.manifest.activeId).protocol,'v3');assert.equal(remote.objects.size,0);
  assert.equal((await f.app.catalog.load(before.activeId)).frame.local.entries[0].title,'原本地课程');await f.app.catalog.load(f.app.catalog.manifest.activeId);
  const card=f.page.locator('#workspaceRows article').filter({hasText:'当前使用'});
  await card.getByRole('button',{name:'加入已有共享课表…',exact:true}).click();
  await expect(f.page.locator('#feedbackMessage')).toContainText('没有可加入');await f.page.locator('#feedbackAccept').click();assert.equal(remote.objects.size,0);
  await card.getByRole('button',{name:'创建新的共享课表…',exact:true}).click();
  await expect(f.page.locator('#feedbackAccept')).toHaveText('创建并发布共享课表');await f.page.locator('#feedbackCancel').click();assert.equal(remote.objects.size,0);
  assert.deepEqual(f.errors,[]);
});

test('PR61 真实 v3 业务版本、RAW、比较方向、回退取消和确认保留历史',async t=>{
  const remote=memoryV3(),f=await fixture(t,{authorized:true,v3RemoteFactory:()=>remote}),v=await seedV3(f,remote);
  await f.open();await f.page.locator('#historyPage').click();
  await expect(f.page.locator('#historyRows article')).toHaveCount(2);
  assert.doesNotMatch(await f.page.locator('#historyStatus').innerText(),/Epoch|Writer|Descriptor|停写|审计未完成/);
  await f.page.locator('#historyView').selectOption('evidence');await expect(f.page.locator('#historyFilters')).toBeVisible();
  await expect(f.page.locator('#historyRows')).toContainText('Descriptor');
  await f.page.locator('#historyView').selectOption('business');
  const row=f.page.locator('#historyRows article').filter({hasText:'版本 '+v.root.id.slice(0,8)}).first();
  await row.getByText('查看完整诊断证据（RAW 身份与引用）',{exact:true}).click();await expect(row.locator('pre')).toContainText(v.root.hash);
  await row.getByRole('button',{name:'查看版本内容',exact:true}).click();await expect(f.page.locator('#historyOutput h4')).toHaveText(['本地课程','日历事件','课程来源','导入原始实例','全周与指定周课程覆盖','停课与补课','学期与作息','自定义身份信息']);
  await row.getByRole('button',{name:'与当前课程表比较',exact:true}).click();await expect(f.page.locator('#historyOutput')).toContainText('新增 1');
  await expect(f.page.locator('#historyOutput')).toContainText('→ 当前课程表');
  await row.getByRole('button',{name:'预览恢复到此版本',exact:true}).click();await expect(f.page.locator('#historyOutput')).toContainText('删除 1');
  const original=new Map(remote.objects);await f.page.locator('#historyCancel').click();assert.deepEqual(remote.objects,original);assert.equal(v.db.frame.local.entries.length,1);
  await row.getByRole('button',{name:'预览恢复到此版本',exact:true}).click();await f.page.locator('#historyConfirm').click();
  await expect(f.page.locator('#feedbackAccept')).toHaveText('确认回退');await f.page.locator('#feedbackAccept').click();
  await expect(f.page.locator('#historyRows article')).toHaveCount(3);assert.equal(v.db.frame.local.entries.length,0);
  for(const [path,bytes] of original)assert(remote.objects.get(path).equals(bytes));
  assert.deepEqual(f.errors,[]);
});

test('PR61 真实 v3 多父关系分别比较，分页不解码全部快照，宽窄窗口无横向溢出',async t=>{
  const remote=memoryV3(),f=await fixture(t,{v3RemoteFactory:()=>remote}),v=await seedV3(f,remote);
  const tip=v.maintenance.list().rows.find(r=>r.type===3),parent=decodeV3(remote.objects.get(tip.path));
  const child=title=>({...parent,objectId:randomUUID(),writerId:randomUUID(),refs:[reference(parent,tip.hash,1)],state:{...parent.state,events:[{id:title,title,start:1,end:2,location:'',note:''}]}});
  const left=child('左分支'),right=child('右分支');for(const o of [left,right])remote.objects.set(objectPath(o),encodeV3(o));
  const {sha}=await import('../src/v3-protocol.mjs');
  const merged={...parent,objectId:randomUUID(),refs:[reference(left,sha(encodeV3(left)),1),reference(right,sha(encodeV3(right)),1)],state:{...parent.state,events:[...left.state.events,...right.state.events]}};
  remote.objects.set(objectPath(merged),encodeV3(merged));for(let i=0;i<5;i++)await v.sync();
  await f.open();await f.page.locator('#historyPage').click();
  const row=f.page.locator('#historyRows article').filter({hasText:'版本 '+merged.objectId.slice(0,8)}).first();
  await expect(row).toContainText('多父合并版本');await expect(row.getByRole('button',{name:/比较父版本/})).toHaveCount(2);
  await row.getByRole('button',{name:/比较父版本/}).first().click();await expect(f.page.locator('#historyOutput')).toContainText('新增 1');
  for(const width of [1280,390]){
    await f.page.setViewportSize({width,height:800});assert(await f.page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
    if(process.env.UX_SCREENSHOTS){await f.page.screenshot({path:process.env.UX_SCREENSHOTS+`/pr61-v3-${width}.png`,fullPage:true});await f.page.screenshot({path:process.env.UX_SCREENSHOTS+`/pr61-v3-${width}-viewport.png`});}
    await f.page.locator('#schedulePage').click();await expect(f.page.locator('#week')).toBeVisible();await f.page.locator('#historyPage').click();
  }
  // Same verified metadata API, with a small page limit injected to exercise sparse cursor pages.
  let snapshots=0;await f.page.route('**/api/history/snapshot?*',route=>{snapshots++;return route.continue();});
  await f.page.route('**/api/history?*',route=>{const u=new URL(route.request().url());u.searchParams.set('limit','1');return route.continue({url:u.href});});
  await f.page.locator('#historyPage').click();let pages=0;
  while(await f.page.getByRole('button',{name:'继续下一页',exact:true}).count()){
    assert(++pages<15);const old=await f.page.locator('#historyRows').getAttribute('data-after');await f.page.getByRole('button',{name:'继续下一页',exact:true}).click();await expect(f.page.locator('#historyRows')).not.toHaveAttribute('data-after',old);
    assert(await f.page.locator('#historyRows article').count()<=1);
  }
  assert(pages>1);assert.equal(snapshots,0);assert.deepEqual(f.errors,[]);
});

test('PR61 历史列表失败可重试；页面切换后迟到版本和回退预览不能生效',async t=>{
  const remote=memoryV3(),f=await fixture(t,{v3RemoteFactory:()=>remote}),v=await seedV3(f,remote);await f.open();
  let fail=true;await f.page.route('**/api/history?*',route=>fail?route.fulfill({status:503,json:{error:'列表暂不可读'}}):route.continue());
  await f.page.locator('#historyPage').click();await expect(f.page.locator('#historyStatus')).toContainText('列表暂不可读');fail=false;
  await f.page.getByRole('button',{name:'重试版本列表',exact:true}).click();await expect(f.page.locator('#historyRows article')).toHaveCount(2);
  let release,started;const gate=new Promise(r=>release=r),pending=new Promise(r=>started=r);
  await f.page.route('**/api/history/preview',async route=>{const response=await route.fetch();started();await gate;await route.fulfill({response}).catch(()=>{});});
  try{
    await f.page.locator('#historyTo').selectOption(v.root.id);await f.page.locator('#historyPreview').click();await pending;
    await f.page.locator('#schedulePage').click();release();await expect(f.page.locator('#historyConfirm')).toBeDisabled();
    assert.equal(v.db.frame.local.entries.length,1);await expect(f.page.locator('#week')).toContainText('历史验证课程');
  }finally{release();}assert.deepEqual(f.errors,[]);
});


test('PR61 过期回退被原后端拒绝，重复点击不重复提交，刷新后显示阻塞下一步',async t=>{
  const remote=memoryV3(),f=await fixture(t,{v3RemoteFactory:()=>remote}),v=await seedV3(f,remote);
  await f.open();await f.page.locator('#historyPage').click();await f.page.locator('#historyTo').selectOption(v.root.id);await f.page.locator('#historyPreview').click();
  await expect(f.page.locator('#historyConfirm')).toBeEnabled();
  const original=new Map(remote.objects);await v.db.edit({kind:'entry',action:'upsert',value:{id:'new-edit',title:'预览后的新编辑',day:1,startMinute:500,endMinute:550,location:''}});
  let attempts=0;f.page.on('request',r=>{if(r.url().endsWith('/api/history/rollback'))attempts++;});
  await f.page.locator('#historyConfirm').click();await f.page.locator('#feedbackAccept').click();
  await expect(f.page.locator('#historyStatus')).toContainText('过期');await expect(f.page.locator('#historyConfirm')).toBeDisabled();
  assert.equal(attempts,1);assert.deepEqual(remote.objects,original);assert.equal(v.db.frame.local.entries.length,2);
  await f.page.getByRole('button',{name:'刷新当前课表与历史',exact:true}).click();await expect(f.page.locator('#historyRows article')).toHaveCount(2);assert.deepEqual(f.errors,[]);
});

test('PR61 归档继续入口、本机整理预览取消与确认，以及真实测量文案',async t=>{
  const remote=memoryV3(),f=await fixture(t,{authorized:true,v3RemoteFactory:()=>remote});await seedV3(f,remote);
  let cleanup=0,archive=0;await f.page.route('**/api/history/cleanup-preview',route=>route.fulfill({json:{token:'cleanup-token',candidates:[{key:'synthetic'}],estimatedReusableBytes:2048}}));
  await f.page.route('**/api/history/cleanup',route=>{cleanup++;assert.equal(route.request().postDataJSON().confirm,'整理本地冗余快照');return route.fulfill({json:{status:'COMPLETED',reclaimedFileBytes:0,estimatedReusableBytes:2048}});});
  await f.page.route('**/api/history/archive',route=>{archive++;return route.fulfill({json:{id:'archive-only',status:'VERIFIED',location:{rootId:'approved',relative:'history-isolated'},message:'冻结范围已实际回读验证'}});});
  await f.open();await f.page.locator('#historyPage').click();await expect(f.page.locator('#advancedHistory')).not.toHaveAttribute('open','');
  await f.page.locator('#advancedHistory > summary').click();await f.page.getByRole('button',{name:'预览本机冗余快照整理',exact:true}).click();
  await expect(f.page.locator('#feedbackAccept')).toHaveText('整理本机冗余快照');await expect(f.page.locator('#feedbackMessage')).toContainText('2 KiB');await f.page.locator('#feedbackCancel').click();assert.equal(cleanup,0);
  await f.page.getByRole('button',{name:'预览本机冗余快照整理',exact:true}).click();await f.page.locator('#feedbackAccept').click();
  await expect(f.page.locator('#historyOutput')).toContainText('实测文件空间释放：0 B');assert.equal(cleanup,1);
  await f.page.locator('#historyArchive').click();await expect(f.page.locator('#feedbackAccept')).toHaveText('确认导出');await f.page.locator('#feedbackCancel').click();assert.equal(archive,0);
  await f.page.locator('#historyArchive').click();await f.page.locator('#feedbackAccept').click();await expect(f.page.locator('#historyOutput')).toContainText('history-isolated');assert.equal(archive,1);
  assert.deepEqual(f.errors,[]);
});

test('PR61 备份口令失败撤销旧恢复能力，外部依赖和替换范围可见，离页后失效',async t=>{
  const f=await fixture(t,{authorized:true});let fail=false,verified=0,cancelled=0;
  await f.page.route('**/api/backups/list',route=>route.fulfill({json:{directoryIdentity:'isolated-directory',backups:[{name:'isolated.dpbk',size:4096,modifiedAt:0,status:'未验证'}]}}));
  await f.page.route('**/api/backups/verify',route=>{verified++;return route.fulfill(fail?{status:400,json:{error:'口令认证失败'}}:{json:{token:'backup-'+verified,createdAt:0,appVersion:'test',formatVersion:'DPBK0002',workspaceCount:1,fileCount:3,bytes:4096,coverage:'应用状态、连接和凭据；外部目录不包含',dependencies:[{workspaceId:'other',workspaceName:'共享课程',courseCount:3,type:'webdav',included:false,endpoint:'https://isolated.test/'}]}});});
  await f.page.route('**/api/backups/cancel',route=>{cancelled++;return route.fulfill({json:{ok:true}});});
  await f.open();await f.page.locator('#backupsPage').click();await f.page.locator('#backupPassword').fill('synthetic-long-passphrase');await f.page.getByRole('button',{name:'验证并预览恢复',exact:true}).click();
  await expect(f.page.locator('#backupRestore')).toBeEnabled();await expect(f.page.locator('#backupPreview')).toContainText('需外部访问：https://isolated.test/');await expect(f.page.locator('#backups')).toContainText('全部课程表、连接和凭据');
  fail=true;await f.page.getByRole('button',{name:'验证并预览恢复',exact:true}).click();await expect(f.page.locator('#backupStatus')).toContainText('口令认证失败');await expect(f.page.locator('#backupRestore')).toBeDisabled();await expect(f.page.locator('#backupPreview')).toBeEmpty();assert(cancelled>0);
  fail=false;await f.page.getByRole('button',{name:'验证并预览恢复',exact:true}).click();await expect(f.page.locator('#backupRestore')).toBeEnabled();
  await f.page.locator('#backupRestore').click();await f.page.locator('#feedbackCancel').click();await expect(f.page.locator('#backupRestore')).toBeDisabled();
  await f.page.getByRole('button',{name:'验证并预览恢复',exact:true}).click();await expect(f.page.locator('#backupRestore')).toBeEnabled();await f.page.locator('#schedulePage').click();await expect(f.page.locator('#backupRestore')).toBeDisabled();assert.deepEqual(f.errors,[]);
});


test('PR61 从另一安装加入真实已验证 v3 共享课表，不新建第二份共享数据',async t=>{
  const remote=memoryV3(),publisher=await fixture(t,{v3RemoteFactory:()=>remote});await seedV3(publisher,remote);
  const f=await fixture(t,{v3RemoteFactory:()=>remote}),catalog=f.app.catalog,manage=input=>catalog.manage({catalogVersion:catalog.manifest.revision,...input});
  const {connectionId}=await manage({action:'connection.save',name:'加入测试连接',type:'webdav',url:'https://isolated.test/',username:'synthetic',password:'synthetic'});
  const {workspaceId:id}=await manage({action:'workspace.add',name:'加入现有课程',protocol:'v3',connectionId});await manage({action:'workspace.switch',targetId:id});
  const original=new Map(remote.objects);await f.open();const card=f.page.locator('#workspaceRows article').filter({hasText:'当前使用'});
  await card.getByRole('button',{name:'加入已有共享课表…',exact:true}).click();await f.page.getByRole('button',{name:/^共享课表 /}).click();
  await expect(f.page.locator('#feedbackAccept')).toHaveText('加入此共享课表');await f.page.locator('#feedbackAccept').click();
  await expect(f.page.locator('#bind')).toBeEnabled();await f.page.locator('#schedulePage').click();
  for(let i=0;i<5 && !f.app.catalog.db(id).frame.local.entries.length;i++){await f.page.locator('#refresh').click();await expect(f.page.locator('#refresh')).toBeEnabled();}
  await expect(f.page.locator('#week')).toContainText('历史验证课程');
  assert.deepEqual(remote.objects,original);assert.deepEqual(f.errors,[]);
});

test('PR61 快速切换工作区后迟到的旧 state 不覆盖新课表或预览资格',async t=>{
  const remote=memoryV3(),f=await fixture(t,{v3RemoteFactory:()=>remote}),v=await seedV3(f,remote),other=f.app.catalog.manifest.workspaces.find(w=>w.id!==v.id);
  await f.open();let release,started;const gate=new Promise(r=>release=r),pending=new Promise(r=>started=r);
  await f.page.route('**/api/state?*',async route=>{
    if(new URL(route.request().url()).searchParams.get('workspaceId')===v.id){const response=await route.fetch();started();await gate;return route.fulfill({response}).catch(()=>{});}return route.continue();
  });
  try{
    await f.page.locator('#refreshSources').click();await pending;await f.page.locator('#workspaceSelect').selectOption(other.id);
    await expect(f.page.locator('#workspaceSelect')).toHaveValue(other.id);await expect(f.page.locator('#workspaceSelect')).toBeEnabled();release();
    await expect(f.page.locator('#currentContext')).toContainText(other.name);await expect(f.page.locator('#week')).not.toContainText('历史验证课程');await expect(f.page.locator('#historyConfirm')).toBeDisabled();
  }finally{release();}assert.deepEqual(f.errors,[]);
});


test('PR61 中断的历史归档能从持久任务继续，使用原归档目录身份',async t=>{
  const remote=memoryV3(),f=await fixture(t,{authorized:true,v3RemoteFactory:()=>remote});await seedV3(f,remote);
  await f.page.route('**/api/history?*',async route=>{const response=await route.fetch(),data=await response.json();data.archiveJobs=[{id:'saved-job',status:'COPYING',location:{rootId:'approved',relative:'history-existing'},after:7,ceiling:12}];return route.fulfill({response,json:data});});
  let input;await f.page.route('**/api/history/archive',route=>{input=route.request().postDataJSON();return route.fulfill({json:{status:'VERIFIED',pageCount:3,buffer:[],location:{rootId:'approved',relative:'history-existing'},message:'冻结范围已实际回读验证'}});});
  await f.open();await f.page.locator('#historyPage').click();await f.page.locator('#advancedHistory > summary').click();
  await f.page.getByRole('button',{name:'继续归档 saved-jo',exact:true}).click();await expect(f.page.locator('#historyOutput')).toContainText('history-existing');await expect(f.page.locator('#historyOutput')).toContainText('3 页归档清单');
  assert.equal(input.jobId,'saved-job');assert.equal(input.rootId,'approved');assert.deepEqual(f.errors,[]);
});


test('PR61 管理卡片按正常、未发布、待确认、冲突、断线与恢复状态突出动作',async t=>{
  const f=await fixture(t),connectionId=randomUUID();let sample={};
  await f.page.route('**/api/manage?*',async route=>{const response=await route.fetch(),data=await response.json();data.connections=[{id:connectionId,name:'合成连接',type:'webdav',url:'https://isolated.test/'}];data.workspaces[0]={...data.workspaces[0],protocol:'v3',datasetId:'synthetic',connectionId,...sample};return route.fulfill({response,json:data});});
  for(const [flags,action,description] of [[{},'检查并同步','本地已保存'],[{dirty:true},'检查并同步','有本地修改待发布'],[{pending:true},'继续检查远端回执','待回读确认'],[{conflict:true},'查看和处理冲突','存在冲突'],[{error:'offline'},'重试连接并同步','连接或同步异常'],[{recovery:true},'继续原操作 / 查看恢复信息','有恢复信息']]){
    sample=flags;await f.open();const card=f.page.locator('#workspaceRows article').first();await expect(card).toContainText(description);await expect(card.getByRole('button',{name:action,exact:true})).toBeVisible();
    if(!flags.recovery)await expect(card.getByRole('button',{name:'继续原操作 / 查看恢复信息',exact:true})).toHaveCount(0);
  }assert.deepEqual(f.errors,[]);
});

test('PR61 首次读取 state 期间打开管理页仍完成课程表和管理数据加载',async t=>{
  const f=await fixture(t);let release,started;const gate=new Promise(r=>release=r),pending=new Promise(r=>started=r);
  await f.page.route('**/api/state?*',async route=>{const response=await route.fetch();started();await gate;return route.fulfill({response}).catch(()=>{});});
  try{await f.open();await pending;release();await expect(f.page.locator('#workspaceRows')).toContainText('默认工作区');await expect(f.page.locator('#workspaceSelect option')).toHaveCount(1);await expect(f.page.locator('#managementStatus')).toBeEmpty();}finally{release();}assert.deepEqual(f.errors,[]);
});


test('PR61 离开正在读取的备份页再返回，旧请求不锁死新页面按钮',async t=>{
  const f=await fixture(t,{authorized:true});let release,started,first=true;const gate=new Promise(r=>release=r),pending=new Promise(r=>started=r);
  await f.page.route('**/api/backups/list',async route=>{if(first){first=false;started();await gate;}return route.fulfill({json:{directoryIdentity:'isolated',backups:[]}}).catch(()=>{});});
  try{await f.open();await f.page.locator('#backupsPage').click();await pending;await f.page.locator('#schedulePage').click();await f.page.locator('#backupsPage').click();await expect(f.page.locator('#backupStatus')).toContainText('0 个加密备份');release();await expect(f.page.locator('#backupBrowse')).toBeEnabled();await expect(f.page.locator('#backupCreate')).toBeEnabled();await expect(f.page.locator('#backupRestore')).toBeDisabled();}finally{release();}assert.deepEqual(f.errors,[]);
});


const reviewEntry=(id,title)=>({id,title,day:1,startMinute:480,endMinute:540,location:''});
async function addUnboundWorkspace(f,name,entry) {
  const id=randomUUID(),db=await Workspace.open(f.app.catalog.workspaceStore(id));
  await db.edit({kind:'entry',action:'upsert',value:entry});
  const manifest=structuredClone(f.app.catalog.manifest);manifest.workspaces.push({id,name});manifest.revision++;
  await f.app.catalog.commit(manifest);f.app.catalog.attach(id,db);return {id,db};
}

test('审查修复61 A/B 切换后 state 失败隔离旧记录、来源和学期闭包，重试只恢复 B',async t=>{
  const f=await fixture(t),a=f.app.catalog.manifest.activeId;
  await f.app.db.edit({kind:'entry',action:'upsert',value:reviewEntry('a-entry','A独有课程')});await configureTerm(f);
  await f.app.db.tx(frame=>{
    frame.local.sources=[{id:'a-source',displayName:'A来源',filename:'a.ics',kind:'ics',locator:null,calendarName:null,sha256:'0'.repeat(64),importedAt:0,timezones:'UTC',occurrenceCount:1,firstStart:1,lastEnd:2,termKey:null}];
    frame.local.imported=[{id:'a-instance',sourceId:'a-source',seriesId:'a-series',uid:null,originalStart:1,title:'A原始课程',start:1,end:2,timezone:'UTC',location:'',description:'',periodLabel:null}];
  });
  const aBefore=structuredClone(f.app.db.frame.local),b=await addUnboundWorkspace(f,'B课程表',reviewEntry('b-entry','B独有课程')),before=structuredClone(b.db.frame.local);
  let fail=true,edits=0;f.page.on('request',r=>{if(r.method()==='POST'&&r.url().endsWith('/api/edit'))edits++;});
  await f.page.route('**/api/state?*',route=>new URL(route.request().url()).searchParams.get('workspaceId')===b.id&&fail?route.fulfill({status:503,json:{error:'B编辑数据暂不可读'}}):route.continue());
  await f.open();await expect(f.page.locator('#records')).toContainText('A来源');await f.page.locator('#schedulePage').click();await f.page.locator('#manage').click();
  // Keep references to detached nodes to exercise the closures themselves, beyond DOM removal.
  await f.page.evaluate(()=>{window.reviewOld=[...document.querySelectorAll('#records button')];window.reviewCourse=document.querySelector('#week [role=button]');});
  await f.page.locator('#workspaceSelect').selectOption(b.id);await expect(f.page.locator('#editingStatus')).toContainText('B编辑数据暂不可读');
  await expect(f.page.locator('#records button')).toHaveCount(0);await expect(f.page.locator('#week')).toContainText('B独有课程');await expect(f.page.locator('#status')).not.toContainText('初始化失败');
  for(const id of ['newCourse','newEvent','newProfile','importFile','adjustDate'])await expect(f.page.locator('#'+id)).toBeDisabled();
  await f.page.evaluate(()=>window.reviewCourse?.click());await expect(f.page.locator('#editor')).not.toBeVisible();
  for(const i of [0,1,2,4]){
    await f.page.evaluate(i=>window.reviewOld[i]?.click(),i);await expect(f.page.locator('#feedback[open]')).toBeVisible();await f.page.locator('#feedbackAccept').click();
  }
  assert.equal(edits,0);assert.deepEqual((await f.app.catalog.load(b.id)).frame.local,before);assert(same((await f.app.catalog.load(a)).frame.local,aBefore));await f.app.catalog.load(b.id);
  fail=false;await f.page.locator('#editingRetry').click();await expect(f.page.locator('#newCourse')).toBeEnabled();await expect(f.page.locator('#records')).toContainText('B独有课程');await expect(f.page.locator('#records')).not.toContainText('A独有课程');assert.deepEqual(f.errors,[]);
});

test('审查修复61 同工作区 state 失败和迟到响应保持只读，版本不匹配不能恢复编辑',async t=>{
  const f=await fixture(t);await f.app.db.edit({kind:'entry',action:'upsert',value:reviewEntry('stable','当前课程')});await f.open();await expect(f.page.locator('#newCourse')).toBeEnabled();
  let mode='fail',release,started;const gate=new Promise(r=>release=r),pending=new Promise(r=>started=r);
  await f.page.route('**/api/state?*',async route=>{
    if(mode==='fail')return route.fulfill({status:503,json:{error:'本地读取故障'}});
    const response=await route.fetch(),value=await response.json();
    if(mode==='late'){mode='valid';started();await gate;}
    else if(mode==='wrong')value.version++;
    else if(mode==='wrongScope')value.workspaceId=randomUUID();
    return route.fulfill({response,json:value}).catch(()=>{});
  });
  await f.page.locator('#schedulePage').click();await f.page.locator('#today').click();await expect(f.page.locator('#editingStatus')).toContainText('本地读取故障');await expect(f.page.locator('#records button')).toHaveCount(0);await expect(f.page.locator('#week')).toContainText('当前课程');
  await f.page.locator('#week [role=button]').first().evaluate(e=>e.click());await expect(f.page.locator('#editor')).not.toBeVisible();
  for(const invalid of ['wrong','wrongScope']){mode=invalid;await f.page.locator('#editingRetry').click();await expect(f.page.locator('#editingStatus')).toContainText('身份或版本');await expect(f.page.locator('#newCourse')).toBeDisabled();}
  try{
    mode='late';await f.page.locator('#editingRetry').click();await pending;
    await f.app.db.edit({kind:'entry',action:'upsert',value:reviewEntry('stable','新版本课程')});
    await f.page.locator('#editingRetry').click();await expect(f.page.locator('#records')).toContainText('新版本课程');release();
    await expect(f.page.locator('#records')).not.toContainText('当前课程');await expect(f.page.locator('#newCourse')).toBeEnabled();
  }finally{release();}assert.deepEqual(f.errors,[]);
});

for(const kind of ['rollback','restore'])for(const outcome of ['success','failure','uncertain'])test(`审查修复61 ${kind} POST 切页后保留 ${outcome} 结果并读取权威状态`,async t=>{
  const remote=memoryV3(),f=await fixture(t,{authorized:true,v3RemoteFactory:()=>remote}),v=await seedV3(f,remote);
  let release,started,requests=0,cancellations=0,authorityFail=outcome==='uncertain';const gate=new Promise(r=>release=r),pending=new Promise(r=>started=r);
  const path=kind==='rollback'?'/api/history/rollback':'/api/backups/restore';
  await f.page.route('**/api/maintenance?*',route=>authorityFail?route.fulfill({status:503,json:{error:'结果核对暂不可读'}}):route.continue());
  await f.page.route('**'+path,async route=>{
    requests++;let response;
    if(kind==='rollback'&&outcome!=='failure')response=await route.fetch();
    if(kind==='restore'&&outcome!=='failure')await v.db.edit({kind:'entry',action:'upsert',value:reviewEntry('restored','恢复后的权威内容')});
    started();await gate;
    if(outcome==='uncertain')return route.abort('failed').catch(()=>{});
    if(outcome==='failure')return route.fulfill({status:500,json:{error:'服务端执行失败，进度已保留'}}).catch(()=>{});
    return route.fulfill(response?{response}:{json:{result:'隔离夹具模拟恢复成功'}}).catch(()=>{});
  });
  await f.page.route('**/api/backups/cancel',route=>{cancellations++;return route.fulfill({json:{ok:true}});});
  if(kind==='restore'){
    await f.page.route('**/api/backups/list',route=>route.fulfill({json:{directoryIdentity:'review-isolated',backups:[{name:'isolated.dpbk',size:4,modifiedAt:0,status:'未验证'}]}}));
    await f.page.route('**/api/backups/verify',route=>route.fulfill({json:{token:'review-restore',createdAt:0,appVersion:'test',formatVersion:'DPBK0002',workspaceCount:1,fileCount:1,bytes:4,coverage:'仅用于在途响应交互验证',dependencies:[]}}));
  }
  try {
    await f.open();
    if(kind==='rollback'){
      await f.page.locator('#historyPage').click();await f.page.locator('#historyTo').selectOption(v.root.id);await f.page.locator('#historyPreview').click();await f.page.locator('#historyConfirm').click();await f.page.locator('#feedbackAccept').click();
    }else{
      await f.page.locator('#backupsPage').click();await f.page.getByRole('button',{name:'验证并预览恢复',exact:true}).click();await f.page.locator('#backupRestore').click();await f.page.locator('#feedbackInput').fill('恢复应用数据');await f.page.locator('#feedbackAccept').click();
    }
    await pending;await expect(f.page.locator('#writeStatus')).toContainText('请求已发出');
    if(kind==='rollback')await f.page.evaluate(()=>document.querySelector('#historyConfirm').onclick());
    await f.page.locator('#schedulePage').click();await expect(f.page.locator('#writeStatus')).toContainText('切换页面不会取消');
    await expect(f.page.locator('#historyConfirm')).toBeDisabled();await expect(f.page.locator('#backupRestore')).toBeDisabled();
    release();
    await expect(f.page.locator('#writeStatus')).toContainText(outcome==='success'?'服务器已返回成功响应':outcome==='failure'?'服务器返回失败':'结果不确定');
    await expect(f.page.locator('#writeStatus')).toContainText(outcome==='uncertain'?'结果核对暂不可读':'已重新读取服务器状态');await expect(f.page.locator('#pageTitle')).toHaveText('课程表');
    assert.equal(requests,1);assert.equal(cancellations,0);assert.doesNotMatch(await f.page.locator('#writeStatus').textContent(),/已取消恢复|已取消预览/);
    if(kind==='rollback')assert.equal(v.db.frame.local.entries.length,outcome==='failure'?1:0);
    else if(outcome!=='failure')await expect(f.page.locator('#week')).toContainText('恢复后的权威内容');
    if(outcome==='uncertain'){
      await expect(f.page.getByRole('button',{name:'已核对结果，解除操作保护（不重发）',exact:true})).toHaveCount(0);authorityFail=false;
      await f.page.getByRole('button',{name:'重新读取操作状态',exact:true}).click();await expect(f.page.locator('#writeStatus')).toContainText('结果不确定');
      await f.page.getByRole('button',{name:'已核对结果，解除操作保护（不重发）',exact:true}).click();await expect(f.page.locator('#writeStatus')).toContainText('没有重发原请求');await expect(f.page.getByRole('button',{name:'已核对结果，解除操作保护（不重发）',exact:true})).toHaveCount(0);assert.equal(requests,1);
    }
  } finally{release();}assert.deepEqual(f.errors,[]);
});

test('审查修复61 v2 迁移接口失败或延迟不阻止历史、比较和回退资格',async t=>{
  const root={datasetId:randomUUID(),writerId:randomUUID(),commitId:randomUUID(),parents:[],state:{...emptyState(),entries:[reviewEntry('stable','v2历史课程')]}};
  const rows=new Map([[root.commitId,encode(root)]]),remote={key:'review-v2',list:async()=>[...rows.keys()],read:async id=>rows.get(id)??null,put:async(id,b)=>rows.set(id,b)};
  const f=await fixture(t,{remoteFactory:()=>remote}),graph=await loadGraph(remote),manifest=structuredClone(f.app.catalog.manifest),connectionId=randomUUID();
  manifest.connections.push({id:connectionId,name:'隔离连接',type:'webdav',config:{url:'https://review.test/',username:'synthetic',password:'synthetic'}});manifest.workspaces[0].connectionId=connectionId;await f.app.catalog.commit(manifest);
  await f.app.db.tx(frame=>{frame.graph=graph;frame.local=graph.state;frame.baseline=graph.state;frame.frontier=graph.tips;frame.datasetId=root.datasetId;frame.bindingKey=remote.key;frame.checkpoint={hashes:graph.hashes,datasetId:root.datasetId};});
  let release,started;const gate=new Promise(r=>release=r),pending=new Promise(r=>started=r);
  await f.page.route('**/api/migration?*',async route=>{started();await gate;return route.fulfill({status:503,json:{error:'高级迁移接口不可用'}}).catch(()=>{});});
  try{
    await f.open();await f.page.locator('#historyPage').click();await pending;await expect(f.page.locator('#historyRows')).toContainText(root.commitId.slice(0,8));
    await f.page.locator('#historyTo').selectOption(root.commitId);await expect(f.page.locator('#historyPreview')).toBeEnabled();
    await f.page.locator('#historyRows').getByRole('button',{name:'查看版本内容',exact:true}).click();await f.page.getByRole('button',{name:'展开此集合',exact:true}).first().click();await expect(f.page.locator('#historyOutput')).toContainText('v2历史课程');
    release();await expect(f.page.locator('#datasetMigration')).toContainText('高级迁移接口不可用');await expect(f.page.locator('#historyStatus')).not.toContainText('高级迁移接口不可用');
    await f.page.locator('#historyFrom').selectOption('current');await f.page.locator('#historyDiff').click();await expect(f.page.locator('#historyOutput')).toContainText('共 0 条记录变化');
  }finally{release();}assert.deepEqual(f.errors,[]);
});


test('审查修复61 编辑 POST 发出后关闭表单切页保留结果，并阻止竞争写入和工作区切换',async t=>{
  const f=await fixture(t),a=f.app.catalog.manifest.activeId;await f.app.db.edit({kind:'entry',action:'upsert',value:reviewEntry('a-entry','待编辑课程')});
  const b=await addUnboundWorkspace(f,'另一课程表',reviewEntry('b-entry','B课程'));
  let release,started,edits=0,syncs=0,switches=0;const gate=new Promise(r=>release=r),pending=new Promise(r=>started=r);
  await f.page.route('**/api/edit',async route=>{edits++;const response=await route.fetch();started();await gate;return route.fulfill({response}).catch(()=>{});});
  f.page.on('request',r=>{if(r.url().endsWith('/api/sync'))syncs++;if(r.url().endsWith('/api/manage')&&r.method()==='POST'&&r.postDataJSON().action==='workspace.switch')switches++;});
  try{
    await f.open();await f.page.locator('#schedulePage').click();await f.page.locator('#manage').click();await f.page.locator('#records button').first().click();await f.page.getByLabel('课程名',{exact:true}).fill('已保存的编辑');await f.save.click();await pending;
    await f.page.locator('#cancel').click();await f.page.locator('#sourcesPage').click();await f.page.locator('#workspaceSelect').selectOption(b.id);await expect(f.page.locator('#workspaceSelect')).toHaveValue(a);assert.equal(switches,0);
    await f.page.locator('#schedulePage').click();await f.page.locator('#refresh').click();await expect(f.page.locator('#feedbackMessage')).toContainText('已有写操作');await f.page.locator('#feedbackAccept').click();assert.equal(syncs,0);
    await f.page.locator('#sourcesPage').click();release();await expect(f.page.locator('#writeStatus')).toContainText('服务器已返回成功响应');await expect(f.page.locator('#writeStatus')).toContainText('已重新读取服务器状态');await expect(f.page.locator('#pageTitle')).toHaveText('课程表与共享');
    await expect(f.page.locator('#week')).toContainText('已保存的编辑');assert.equal(edits,1);assert.deepEqual((await f.app.catalog.load(b.id)).frame.local.entries,[reviewEntry('b-entry','B课程')]);
  }finally{release();}assert.deepEqual(f.errors,[]);
});


test('审查修复61 关闭的课程详情重置闭包不能向后来打开的编辑表单提交或写入提示',async t=>{
  const f=await fixture(t);await f.app.db.edit({kind:'entry',action:'upsert',value:reviewEntry('stable','原课程')});let edits=0;f.page.on('request',r=>{if(r.url().endsWith('/api/edit'))edits++;});
  await f.open();await f.page.locator('#schedulePage').click();await f.page.locator('#week [role=button]').first().click();await expect(f.page.locator('#editorTitle')).toContainText('课程详情');
  await f.page.getByRole('button',{name:'重置所选范围',exact:true}).evaluate(e=>window.oldReset=e);await f.page.locator('#cancel').click();await f.page.locator('#newCourse').click();
  await f.page.evaluate(()=>window.oldReset.click());await expect(f.page.locator('#feedback')).not.toBeVisible();await expect(f.page.locator('#editorTitle')).toHaveText('新增本地课程');await expect(f.page.locator('#formError')).toBeEmpty();assert.equal(edits,0);assert.deepEqual(f.errors,[]);
});
