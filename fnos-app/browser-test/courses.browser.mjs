import test, { before, after } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import {mkdir} from 'node:fs/promises';
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


test('课程管理列表、只读预览、取消和真实原子保存',async t=>{
 const f=await fixture(t);
 await f.app.db.edit({kind:'entry',action:'upsert',value:{id:'course-a',title:'离散数学',day:1,startMinute:480,endMinute:540,location:'教室 A'}});
 // 独立临时 Workspace：只注入显式能力，不接触日用绑定或远端。
 f.app.db.frame.local.sharedRecords=[];f.app.db.frame.v3={binding:{format:4}};
 await f.open();await f.page.locator('#coursesPage').click();await mkdir(new URL('../../docs/screenshots/pr71/',import.meta.url),{recursive:true});await f.page.screenshot({path:new URL('../../docs/screenshots/pr71/list.png',import.meta.url).pathname.replace(/^\/(\w:)/,'$1')});await f.page.getByRole('button',{name:'编辑整门课程',exact:true}).click();
 await expect(f.page.locator('.course-manager')).toContainText('公共信息');
 await f.page.screenshot({path:fileURLToPath(new URL('../../docs/screenshots/pr71/editor.png',import.meta.url))});const before=f.app.db.frame.revision;
 await f.page.locator('.course-manager label').filter({hasText:'教师'}).first().locator('input[type=checkbox]').check();
 await f.page.locator('.course-manager label').filter({hasText:'教师'}).first().locator('input:not([type=checkbox])').fill('新教师');
 await f.page.getByRole('button',{name:'预览并保存',exact:true}).click();
 await expect(f.page.getByRole('button',{name:'确认一次原子保存',exact:true})).toBeVisible();assert.equal(f.app.db.frame.revision,before);await f.page.screenshot({path:fileURLToPath(new URL('../../docs/screenshots/pr71/preview.png',import.meta.url))});
 await f.page.getByRole('button',{name:'确认一次原子保存',exact:true}).click();
 await expect(f.page.getByRole('button',{name:'编辑整门课程',exact:true})).toBeVisible();
 assert.equal(f.app.db.frame.revision,before+1);assert.equal(f.app.db.frame.local.classOverrides[0].teacher,'新教师');assert.deepEqual(f.errors,[]);
});
