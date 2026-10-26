import test, { before, after } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { mkdtemp, rm, readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { chromium } from 'playwright';
import { expect } from 'playwright/test';
import { createApp, PREFIX } from '../src/server.mjs';
import { encode, decode } from '../src/protocol.mjs';
import { loadGraph } from '../src/graph.mjs';
import { emptyState } from '../src/merge.mjs';
import { Workspace } from '../src/workspace.mjs';

let browser;
before(async () => { browser = await chromium.launch({ channel: process.env.BROWSER_CHANNEL || (process.platform === 'win32' ? 'msedge' : 'chromium') }); });
after(async () => { await browser?.close(); });

// 只在独立临时实例中注入网关身份；生产服务仍拒绝 TCP 和伪造的客户端身份。
async function fixture(t, { authorized = false, proxyHost = null, remoteFactory } = {}) {
  const dir = await mkdtemp(join(tmpdir(), 'dp-connection-ui-'));
  const app = await createApp({ dataDir: dir, configPath: join(dir, 'config.json'), remoteFactory,
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
    await new Promise(r => proxy.close(r)); await new Promise(r => app.server.close(r));
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
  await f.page.getByLabel('密码（留空保留已保存密码）', { exact: true }).fill('test-only');
  await f.page.getByText('高级：手动输入完整 URL（服务器不支持目录列举时使用）',{exact:true}).click();
  await f.page.getByLabel('共享父目录完整 URL',{exact:true}).fill('https://example.test/schedule/');
  await f.save.click(); await expect(f.page.locator('#connectionRows')).toContainText('WebDAV 课程');
  await f.page.getByRole('button', { name: '新建工作区', exact: true }).click();
  await f.page.locator('#feedbackAccept').click();
  await f.page.getByRole('button',{name:'Sync v2（兼容现有工作区）',exact:true}).click();
  await expect(f.page.locator('#workspaceRows')).toContainText('WebDAV 课程');
  const id = f.app.catalog.manifest.workspaces.find(w => w.connectionId)?.id; assert(id);
  await f.page.locator('#workspaceSelect').selectOption(id);
  await expect(f.page.locator('#workspaceSelect')).toBeEnabled();
  await expect(f.page.locator('#workspaceSelect')).toHaveValue(id);
  await f.page.locator('#sourcesPage').click(); await f.page.locator('#newCourse').click();
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
  await f.page.getByLabel('密码（留空保留已保存密码）', { exact: true }).fill('test-only');
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
  await f.open(); await f.page.locator('#sourcesPage').click();
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
  const f = await fixture(t); await f.open(); await f.page.locator('#sourcesPage').click();
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
  await f.page.locator('#sourcesPage').click(); await f.page.locator('#manage').click();
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
  await f.page.getByLabel('密码（留空保留已保存密码）', { exact: true }).fill('test-only');
  await f.page.getByText('高级：手动输入完整 URL（服务器不支持目录列举时使用）',{exact:true}).click();
  await f.page.getByLabel('共享父目录完整 URL',{exact:true}).fill('https://example.test/schedule/');
  await f.save.click(); await expect(f.page.locator('#connectionRows')).toContainText('WebDAV 课程');
  await f.page.getByRole('button', { name: '新建工作区', exact: true }).click(); await f.page.locator('#feedbackAccept').click();
  await f.page.getByRole('button',{name:'Sync v2（兼容现有工作区）',exact:true}).click();
  await expect(f.page.locator('#workspaceRows')).toContainText('WebDAV 课程');
  const id = f.app.catalog.manifest.workspaces.find(w => w.connectionId).id;
  await f.page.locator('#workspaceSelect').selectOption(id); await expect(f.page.locator('#workspaceSelect')).toBeEnabled();
  let card = f.page.locator('#workspaceRows article').filter({ hasText: 'WebDAV 课程' });
  await card.getByText('更多操作', { exact: true }).click();
  await card.getByRole('button', { name: '修改名称', exact: true }).click();
  await f.page.locator('#feedbackInput').fill('重命名后的课程表'); await f.page.locator('#feedbackAccept').click();
  card = f.page.locator('#workspaceRows article').filter({ hasText: '重命名后的课程表' }); await expect(card).toBeVisible();
  await card.getByText('更多操作', { exact: true }).click();
  await card.getByRole('button', { name: '选择访问端点', exact: true }).click(); await expect(f.page.locator('#editorTitle')).toHaveText('工作区访问端点');
  await f.page.keyboard.press('Escape');
  for (const [label, text] of [['接收已有课表…', '确认接收此目录已有数据集'], ['在空目录创建共享课表…', '确认在已配置的空目录创建独立数据集']]) {
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
  await f.open(); await f.page.locator('#historyPage').click();
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
  await f.open(); await f.page.locator('#historyPage').click();
  await expect(f.page.locator('#datasetMigration')).toContainText('迁移到精简数据集');
  await f.page.getByRole('button',{name:'选择精简候选目录',exact:true}).click();
  await f.page.getByRole('button',{name:'📁 测试子目录',exact:true}).click();
  await f.page.getByRole('button',{name:'选择此文件夹',exact:true}).click();
  await f.page.getByRole('button', { name: '检查归档与候选，准备换库', exact: true }).click();
  await expect(f.page.locator('#historyStatus')).toContainText('目标连接已有工作区');
  assert.equal(f.app.catalog.migrations.length, 0); assert.equal(rows.size, 1);
  const m = { id: randomUUID(), revision: 0, sourceId: manifest.activeId, targetId: randomUUID(), phase: 'frozen', sourceDatasetId: root.datasetId, targetDatasetId: randomUUID(), oldCount: 2, newCount: 1, reclaimEstimateBytes: 1024, archive: { relative: 'history-synthetic' }, sourceWriters: [root.writerId], lastCheck: 'FPK 实际验证（浏览器合成展示夹具）', failure: '离线，已保留保护', devices: [{ name: '床头', type: 'X08E', stopped: true, accepted: false, evidence: '尚未执行本机迁移', origin: '管理员登记 / 确认' }] };
  f.app.catalog.migrations = [m]; await f.app.catalog.store.save('dataset-migrations.json', [m]);
  await f.page.locator('#historyPage').click();
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
  await card.getByText('更多操作', { exact: true }).click();
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
  await f.page.getByLabel('密码（留空保留已保存密码）',{exact:true}).fill('test-only');
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
  const f=await fixture(t,{authorized:true});await f.open();await f.page.locator('#historyPage').click();
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
  await f.open();await f.page.locator('#historyPage').click();await f.page.getByRole('button',{name:'选择精简候选目录',exact:true}).click();
  await f.page.getByRole('button',{name:'compact-existing（待后端验证）',exact:true}).click();
  await expect(f.page.locator('#migrationCandidate')).toHaveValue('compact-existing');assert.deepEqual(f.errors,[]);
});


test('PR59 独立归档从授权目录选择，仍由恢复 API 验证清单',async t=>{
  const f=await fixture(t,{authorized:true});let input=null;
  await f.page.route('**/api/schedule?*',async route=>{const response=await route.fetch(),data=await response.json();await route.fulfill({response,json:{...data,protocol:'v3'}});});
  await f.page.route('**/api/history?*',route=>route.fulfill({json:{version:0,count:0,frontier:[],blockers:[],rows:[],next:null,rollbackEnabled:false}}));
  await f.page.route('**/api/history/archive-restore',route=>{input=route.request().postDataJSON();return route.fulfill({status:400,json:{error:'归档清单验证失败；原始缓存保留'}});});
  await f.open();await f.page.locator('#historyPage').click();await f.page.getByRole('button',{name:'从独立归档恢复缺失缓存',exact:true}).click();
  await f.page.getByRole('button',{name:'📁 测试子目录',exact:true}).click();await f.page.locator('dialog[open]').getByRole('button',{name:'取消',exact:true}).click();assert.equal(input,null);
  await f.page.getByRole('button',{name:'从独立归档恢复缺失缓存',exact:true}).click();await f.page.getByRole('button',{name:'📁 测试子目录',exact:true}).click();await f.page.getByRole('button',{name:'选择此文件夹',exact:true}).click();
  await expect(f.page.locator('#historyStatus')).toContainText('归档清单验证失败');await expect(f.page.getByRole('button',{name:'从独立归档恢复缺失缓存',exact:true})).toBeEnabled();assert.equal(input.rootId,'approved');assert.equal(input.relative,'测试子目录');assert(input.workspaceId);assert.deepEqual(f.errors,[]);
});
