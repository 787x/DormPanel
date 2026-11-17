import test, { before, after } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { chromium } from 'playwright';
import { expect } from 'playwright/test';
import { productivityFixture } from '../test-fixtures/productivity-fixture.mjs';

let browser;
before(async () => { browser = await chromium.launch({ channel:process.env.BROWSER_CHANNEL ?? (process.platform === 'win32' ? 'msedge' : 'chromium') }); });
after(async () => browser?.close());
async function pageFor(t, f) {
  const page = await browser.newPage({ viewport:{ width:1280, height:800 } });
  t.after(() => page.close());
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto(f.url);
  await expect(page.locator('#currentContext')).toContainText('隔离共享课表');
  return { page, errors };
}

test('Todo/备忘网页真实保存、切换、取消、删除与窄窗口', async t => {
  const f = await productivityFixture(t, { proxy:true }), { page, errors } = await pageFor(t, f);
  const click = name => page.getByRole('button', { name, exact:true }).click();
  const fill = (label, text) => page.getByLabel(label).fill(text);
  const save = () => click('保存本机');
  await click('Todo');
  await expect(page.getByText('还没有清单。')).toBeVisible();
  for (const title of ['开学准备', '宿舍轮值']) {
    await click('新建清单'); await fill(/清单名称/, title); await save();
    await expect(page.getByRole('heading', { name:title })).toBeVisible();
  }
  await click('开学准备'); await click('重命名清单');
  await fill(/清单名称/, '每周计划'); await save();
  await expect(page.getByRole('heading', { name:'每周计划' })).toBeVisible();
  for (const text of ['整理资料', '记录 WebDAV 回执']) {
    await click('新增任务'); await fill(/任务正文/, text); await save();
    await expect(page.getByText(text, { exact:true })).toBeVisible();
  }
  await page.getByRole('button', { name:'未完成 · 标为完成', exact:true }).first().click();
  await expect(page.getByRole('button', { name:'✓ 已完成 · 设为未完成' })).toBeVisible();
  await page.getByRole('button', { name:'编辑任务', exact:true }).first().click();
  await fill(/任务正文/, '整理资料 <b>安全文本</b>'); await save();
  await expect(page.getByText('整理资料 <b>安全文本</b>', { exact:true })).toBeVisible();
  if (process.env.PR89_SCREENSHOTS) await page.screenshot({ path:process.env.PR89_SCREENSHOTS+'/fnos-todo.png', fullPage:true });
  await click('删除清单');
  await expect(page.locator('#feedbackMessage')).toContainText('全部 2 项任务');
  await page.locator('#feedbackCancel').click();
  await click('宿舍轮值'); await expect(page.getByText('还没有任务。')).toBeVisible();
  await click('删除清单'); await page.locator('#feedbackAccept').click();
  await expect(page.getByRole('button', { name:'宿舍轮值' })).toHaveCount(0);

  await click('备忘');
  const body = '  周末安排\n\n' + '整理书桌和钥匙。\n'.repeat(24) + '末尾🙂  ';
  for (const [title, text] of [['宿舍检查', body], ['空白备忘', '']]) {
    await click('新建备忘'); await fill(/备忘标题/, title); await fill(/备忘正文/, text); await save();
    await expect(page.getByRole('heading', { name:title })).toBeVisible();
  }
  await click('宿舍检查'); await click('编辑备忘'); await fill(/备忘正文/, '取消测试');
  await expect(page.getByText('有未保存的草稿')).toBeVisible();
  await click('取消编辑'); assert.equal(await page.locator('#productivity pre').textContent(), body);
  if (process.env.PR89_SCREENSHOTS) await page.screenshot({ path:process.env.PR89_SCREENSHOTS+'/fnos-memo.png', fullPage:true });
  await page.setViewportSize({ width:360, height:720 });
  await click('编辑备忘'); await fill(/备忘正文/, body + '\n新内容');
  await save(); await expect(page.locator('#productivity pre')).toContainText('新内容');
  assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
  await click('空白备忘'); await click('删除备忘'); await page.locator('#feedbackAccept').click();
  await expect(page.getByRole('button', { name:'空白备忘' })).toHaveCount(0);
  assert.deepEqual(errors, []);
});

test('网页迟到 GET、已发出写入期间取消和切页、缺失回执核对', async t => {
  const f = await productivityFixture(t, { proxy:true }), { page, errors } = await pageFor(t, f);
  const click = name => page.getByRole('button', { name, exact:true }).click();
  const gates = () => {
    let release, arrived;
    return { pause:new Promise(r => { release = r; }), arrived:new Promise(r => { arrived = r; }),
      release:() => release(), signal:() => arrived() };
  };
  const read = gates();
  await page.route(url => url.searchParams.get('view') === 'todo.lists', async route => {
    const response = await route.fetch();
    read.signal(); await read.pause;
    await route.fulfill({ response });
  }, { times:1 });
  await click('Todo'); await read.arrived; await click('备忘');
  read.release();
  await expect(page.getByRole('heading', { name:'备忘列表' })).toBeVisible();
  await click('新建备忘'); await page.getByLabel(/备忘标题/).fill('已发出的写入');
  const sent = gates();
  await page.route('**/api/productivity/edit', async route => {
    const response = await route.fetch();
    assert.equal(response.status(), 200);
    sent.signal(); await sent.pause;
    await route.fulfill({ status:502, json:{ error:'回执模拟丢失' } });
  }, { times:1 });
  await click('保存本机'); await sent.arrived;
  await click('取消编辑'); await click('Todo');
  sent.release();
  await expect(page.locator('#writeStatus')).toContainText('结果不确定');
  await expect(page.locator('#writeStatus')).toContainText('目标记录存在');
  await expect(page.getByRole('heading', { name:'Todo 清单' })).toBeVisible();
  await expect(page.getByRole('button', { name:'新建清单' })).toBeDisabled();
  await page.reload();
  await expect(page.locator('#writeStatus')).toContainText('上次共享效率请求的结果需要先只读核对');
  await click('重新读取操作状态');
  await expect(page.locator('#writeStatus')).toContainText('目标记录存在');
  await click('已核对结果，解除操作保护（不重发）');
  await click('备忘');
  await expect(page.getByRole('button', { name:'已发出的写入' })).toHaveCount(1);

  await click('编辑备忘'); await page.getByLabel(/备忘正文/).fill('过期草稿');
  await f.edit('memo.create', { id:randomUUID(), title:'另一个管理员', body:'' });
  await click('保存本机');
  await expect(page.getByText('编辑版本已失效。可复制草稿，取消后重新读取。')).toBeVisible();
  await expect(page.getByLabel(/备忘正文/)).toHaveValue('过期草稿');
  await expect(page.getByRole('button', { name:'保存本机' })).toBeDisabled();
  assert.deepEqual(errors, []);
});

test('网页显示备忘三方内容、保护和实际决定范围', async t => {
  const f = await productivityFixture(t, { proxy:true });
  const peer = await productivityFixture(t, { format:0, transport:f.transport });
  const id = randomUUID();
  const edit = async (target, action, input) => {
    const result = await target.edit(action, { id, ...input });
    assert.equal(result.status, 200, JSON.stringify(result.data));
  };
  await edit(f, 'memo.create', { title:'共享备忘', body:'原始正文\r\n' });
  await f.settle();
  assert.equal((await peer.joinDataset()).status, 200);
  await peer.settle();
  await edit(f, 'memo.edit', { body:'本机正文' });
  await edit(peer, 'memo.edit', { body:'远端正文' });
  await peer.settle();
  assert((await f.settle()).conflict);
  const { page, errors } = await pageFor(t, f);
  const click = name => page.getByRole('button', { name, exact:true }).click();
  await click('备忘');
  await expect(page.getByRole('heading', { name:'共享效率冲突' })).toBeVisible();
  for (const value of ['原始正文', '本机正文', '远端正文']) {
    await expect(page.locator('.productivity-conflict pre').filter({ hasText:value })).toHaveCount(1);
  }
  await expect(page.getByRole('button', { name:'编辑备忘' })).toBeDisabled();
  await expect(page.getByText(/全部冲突组，包括其他业务记录/)).toBeVisible();
  if (process.env.PR89_SCREENSHOTS) await page.screenshot({ path:process.env.PR89_SCREENSHOTS+'/fnos-conflict.png', fullPage:true });
  await click('采用远端冲突方案');
  await f.sync(); // 另一次检查使网页之前的 revision 过期
  await page.locator('#feedbackAccept').click();
  await expect(page.locator('#writeStatus')).toContainText('版本过期');
  await expect(page.getByRole('heading', { name:'共享效率冲突' })).toBeVisible();
  await click('采用远端冲突方案'); await page.locator('#feedbackAccept').click();
  await expect(page.getByRole('heading', { name:'共享效率冲突' })).toHaveCount(0);
  assert.equal((await f.read('memo.get', { id })).data.memo.body, '远端正文');
  assert.deepEqual(errors, []);
});

test('切换工作区后丢弃旧 Todo 读取结果', async t => {
  const f = await productivityFixture(t, { proxy:true });
  assert.equal((await f.edit('todo.createList', { id:randomUUID(), name:'旧工作区清单' })).status, 200);
  const original = f.ctx().workspaceId;
  const next = await f.createWorkspace('第二个独立工作区');
  await f.app.catalog.manage({ ...f.ctx(), action:'workspace.switch', targetId:original });
  const { page, errors } = await pageFor(t, f);
  let arrived, release;
  const dispatched = new Promise(resolve => { arrived = resolve; });
  const hold = new Promise(resolve => { release = resolve; });
  await page.route(url => url.searchParams.get('view') === 'todo.lists' &&
    url.searchParams.get('workspaceId') === original, async route => {
    const response = await route.fetch();
    arrived();
    await hold;
    await route.fulfill({ response });
  }, { times:1 });
  await page.locator('#todoPage').click();
  await dispatched;
  await page.locator('#workspaceSelect').selectOption(next);
  await expect(page.locator('#currentContext')).toContainText('第二个独立工作区');
  release();
  await page.locator('#todoPage').click();
  await expect(page.getByText(/尚未完成共享数据集初始化或加入验证/)).toBeVisible();
  await expect(page.getByText('旧工作区清单')).toHaveCount(0);
  assert.deepEqual(errors, []);
});
