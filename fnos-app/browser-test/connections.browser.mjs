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

let browser;
before(async () => { browser = await chromium.launch({ channel: process.env.BROWSER_CHANNEL || (process.platform === 'win32' ? 'msedge' : 'chromium') }); });
after(async () => { await browser?.close(); });

// 只在独立临时实例中注入网关身份；生产服务仍拒绝 TCP 和伪造的客户端身份。
async function fixture(t, { authorized = false, proxyHost = null } = {}) {
  const dir = await mkdtemp(join(tmpdir(), 'dp-connection-ui-'));
  const app = await createApp({ dataDir: dir, configPath: join(dir, 'config.json'),
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
  await f.local(); await expect(f.save).toBeEnabled();
  await expect(f.page.getByLabel('授权根目录').locator('option')).toHaveText(['FPK 私有本地数据']);
  await f.page.getByLabel('选择子目录', { exact: true }).selectOption('测试子目录');
  await expect(f.page.locator('#fields')).toContainText('选定目录：测试子目录');
  await f.page.locator('#cancel').click(); await expect(f.page.locator('#editor')).not.toBeVisible();
  await f.page.locator('#addLocal').click(); await expect(f.save).toBeEnabled();
  await expect(f.page.locator('#fields [role=status]')).toHaveText('选定目录：/');
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
  await expect(f.save).toBeEnabled(); assert.deepEqual(f.errors, []);
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
  await expect(f.page.locator('#formError')).toHaveText('请先成功读取所选目录');
  fail = false; await f.page.getByRole('button', { name: '重试读取目录' }).click();
  await expect(f.save).toBeEnabled(); await expect(f.page.getByLabel('连接名称', { exact: true })).toHaveValue('保留名称');
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
    await f.page.getByLabel('授权根目录').selectOption('approved');
    await expect(f.page.getByLabel('选择子目录', { exact: true })).toBeEnabled();
    await f.page.getByLabel('选择子目录', { exact: true }).selectOption('当前目录');
    await expect(f.page.locator('#fields [role=status]')).toHaveText('选定目录：当前目录');
    await expect(f.save).toBeEnabled(); release();
    await expect(f.page.getByLabel('授权根目录')).toHaveValue('approved');
    await expect(f.page.locator('#fields [role=status]')).toHaveText('选定目录：当前目录');
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
    await expect(f.page.getByLabel('HTTPS 共享父目录（末尾 /）', { exact: true })).toBeVisible();
    release(); await expect(f.save).toBeEnabled();
    await expect(f.page.getByLabel('授权根目录')).toHaveCount(0); assert.deepEqual(f.errors, []);
  } finally { release(); }
});

test('WebDAV 保存、新建与切换工作区及课程表单继续工作', async t => {
  const f = await fixture(t); await f.open();
  await expect(f.page.locator('#workspaceRows')).toContainText('默认工作区');
  await f.page.locator('#addWebDav').click();
  await f.page.getByLabel('HTTPS 共享父目录（末尾 /）', { exact: true }).fill('https://example.test/schedule/');
  await f.page.getByLabel('用户名', { exact: true }).fill('test');
  await f.page.getByLabel('密码（留空保留已保存密码）', { exact: true }).fill('test-only');
  await f.save.click(); await expect(f.page.locator('#connectionRows')).toContainText('WebDAV 课程');
  await f.page.getByRole('button', { name: '新建工作区', exact: true }).click();
  await f.page.locator('#feedbackAccept').click();
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
  await f.page.getByLabel('HTTPS 共享父目录（末尾 /）', { exact: true }).fill('https://example.test/schedule/');
  await f.page.getByLabel('用户名', { exact: true }).fill('test');
  await f.page.getByLabel('密码（留空保留已保存密码）', { exact: true }).fill('test-only');
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
