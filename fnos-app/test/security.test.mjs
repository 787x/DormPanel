import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { authorize, csrf, createApp, PREFIX, validatePageOrigin } from '../src/server.mjs';
import { binding, parseListing } from '../src/webdav.mjs';
import { Store } from '../src/store.mjs';
import http from 'node:http';
import { randomUUID } from 'node:crypto';
const request = (admin = 'true') => ({ socket: {}, headers: { 'x-trim-userid': '1000', 'x-trim-isadmin': admin }, rawHeaders: ['X-Trim-Userid', '1000', 'X-Trim-Isadmin', admin] });

test('公网代理来源引导仅兼容浏览器同源 fetch；拒绝跨源和伪造 Forwarded', () => {
  const origin = 'https://qweasdf.fnos.net', host = 'internal.test:5666';
  const headers = { host, 'sec-fetch-site': 'same-origin', 'sec-fetch-mode': 'cors' };
  validatePageOrigin({ headers }, origin);
  validatePageOrigin({ headers: { host: 'nas.test' } }, 'http://nas.test:5666');
  for (const changes of [
    { 'sec-fetch-site': 'cross-site' }, { 'sec-fetch-site': 'same-site' },
    { 'sec-fetch-site': 'none' }, { 'sec-fetch-site': undefined },
    { 'sec-fetch-mode': 'navigate' }, { 'sec-fetch-mode': 'no-cors' }, { 'sec-fetch-mode': undefined },
    { origin: 'https://evil.test' },
  ]) assert.throws(() => validatePageOrigin({ headers: { ...headers, ...changes } }, origin), /页面来源/);
  for (const value of ['null', 'file:///tmp/a', 'https://u:p@qweasdf.fnos.net', origin + '/path', origin + '?query'])
    assert.throws(() => validatePageOrigin({ headers }, value));
  assert.throws(() => validatePageOrigin({ headers: { host, forwarded: 'host=qweasdf.fnos.net;proto=https',
    'x-forwarded-host': 'qweasdf.fnos.net', 'x-forwarded-proto': 'https' } }, origin));
  const bound = { headers: { origin, 'x-dormpanel-csrf': 'token' } };
  csrf(bound, 'token', origin);
  for (const other of ['http://qweasdf.fnos.net', origin + ':444', 'https://evil.test'])
    assert.throws(() => csrf({ headers: { ...bound.headers, origin: other } }, 'token', origin), /Origin/);
  assert.throws(() => csrf(bound, 'different', origin), /CSRF/);
});
test('管理员身份、重复 Header、客户端 TCP 和 CSRF 边界', () => {
  assert.equal(authorize(request()), '1000'); assert.throws(() => authorize(request('false'))); assert.throws(() => authorize({ socket: {}, headers: {}, rawHeaders: [] }));
  const r = request(); r.socket.remoteAddress = '127.0.0.1'; assert.throws(() => authorize(r));
  const duplicate = request(); duplicate.rawHeaders.push('X-Trim-Isadmin', 'true'); assert.throws(() => authorize(duplicate));
  assert.throws(() => csrf({ headers: {} }, 'abc')); assert.throws(() => csrf({ headers: { 'x-dormpanel-csrf': 'abc', 'sec-fetch-site': 'cross-site' } }, 'abc'));
  csrf({ headers: { 'x-dormpanel-csrf': 'abc' } }, 'abc');
});
test('明确残余风险：可连接者能伪造 Header；CSRF 不认证对端', () => {
  const forged = request(); forged.headers['x-trim-userid'] = '424242'; forged.rawHeaders[1] = '424242';
  assert.equal(authorize(forged), '424242');
});
const base = new URL('https://example.com/share/dormpanel-sync-v2/');
const xml = hrefs => `<d:multistatus xmlns:d="DAV:">${hrefs.map(h => `<d:response><d:href>${h}</d:href><d:propstat><d:prop><d:resourcetype/></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>`).join('')}</d:multistatus>`;
test('严格 WebDAV 目录、重复/跨源/子目录/状态/实体', () => {
  const path = base.pathname + 'commit-11111111-1111-4111-8111-111111111111.dpc';
  assert.equal(parseListing(xml([path]), base).length, 1);
  for (const paths of [[path, path], ['https://evil.test' + path], [base.pathname + 'settings.json'], [base.pathname + 'x/' + path], [base.pathname + '%2f' + path]]) assert.throws(() => parseListing(xml(paths), base));
  assert.throws(() => parseListing(xml([path]).replace('200 OK', '404 Not Found'), base));
  assert.throws(() => parseListing('<!DOCTYPE x>' + xml([]), base)); assert.throws(() => parseListing('<invalid', base));
  for (const url of ['http://example.com/a/', 'https://a:b@example.com/', 'https://example.com/a/?x=1']) assert.throws(() => binding({ url }));
  assert.equal(binding({url:'http://192.168.3.153:5005/a/',allowInsecureHttp:true}).base.protocol,'http:');
  for(const url of ['ftp://example.com/a/','http://u:p@example.com/a/','http://example.com/a/?x=1']) assert.throws(()=>binding({url,allowInsecureHttp:true}));
  assert.throws(()=>binding({url:'http://example.com/a/',allowInsecureHttp:'true'}));
});
test('身份与配置进程重建保留；HTTP 数据端点全部授权、写操作拒绝', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'dormpanel-')); t.after(() => rm(dir, { recursive: true, force: true }));
  const store = await new Store(dir).init(), again = await new Store(dir).init(); assert.equal(store.identity.writerId, again.identity.writerId);
  const configPath = join(dir, 'webdav.json'); const app = await createApp({ dataDir: dir, configPath });
  // 测试用 TCP 服务器必须拒绝即便伪造了完整网关 Header 的请求。
  await new Promise(r => app.server.listen(0, '127.0.0.1', r)); t.after(() => new Promise(r => app.server.close(r)));
  const port = app.server.address().port;
  for (const method of ['GET', 'POST', 'PUT', 'DELETE']) {
    const status = await new Promise((resolve, reject) => { const req = http.request({ host: '127.0.0.1', port, path: PREFIX + '/api/schedule', method, headers: { 'X-Trim-Userid': '1000', 'X-Trim-Isadmin': 'true' } }, res => { res.resume(); res.on('end', () => resolve(res.statusCode)); }); req.on('error', reject); req.end(); }); assert.equal(status, 403);
  }
  await writeFile(configPath, JSON.stringify({ url: 'https://example.com/folder/', username: 'w', password: 'not-for-browser' }));
  assert.equal((await new Store(dir).init()).identity.writerId, store.identity.writerId);
});
test('Socket Header与路由校验，不将其误报为进程来源认证', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'dp-api-')); t.after(() => rm(dir, { recursive: true, force: true }));
  const configPath = join(dir, 'webdav.json'); await writeFile(configPath, JSON.stringify({ url: 'https://127.0.0.1:1/private/', username: 'secret-user', password: 'secret-password' }));
  const app = await createApp({ dataDir: dir, configPath }), socketPath = process.platform === 'win32' ? '\\\\.\\pipe\\dp-' + randomUUID() : join(dir, 'api.sock');
  await new Promise(r => app.server.listen(socketPath, r)); t.after(() => new Promise(r => app.server.close(r)));
  async function call(method, path, headers = {}, data) {
    return new Promise((resolve, reject) => { const req = http.request({ socketPath, path: PREFIX + path, method, headers }, res => { let body = ''; res.on('data', b => body += b); res.on('end', () => resolve({ status: res.statusCode, body })); }); req.on('error', reject); req.end(data===undefined?undefined:JSON.stringify(data)); });
  }
  assert.equal((await call('GET', '/api/schedule')).status, 403);
  const admin = { 'X-Trim-Userid': '1000', 'X-Trim-Isadmin': 'true' }, ordinary = { ...admin, 'X-Trim-Isadmin': 'false' };
  assert.equal((await call('GET', '/api/schedule', ordinary)).status, 403);
  const view = await call('GET', '/api/schedule', admin); assert.equal(view.status, 200); assert(!view.body.includes('secret-'));
  const spoof = { 'X-Trim-Userid': '424242', 'X-Trim-Isadmin': 'true' };
  const spoofed = await call('GET', '/api/schedule', spoof); assert.equal(spoofed.status, 200);
  assert(JSON.parse(spoofed.body).csrf, '有连接权限的非网关客户端同样能取得CSRF token');
  assert.equal((await call('POST', '/api/refresh', admin)).status, 403);
  assert.equal((await call('PUT', '/api/schedule', admin)).status, 405);
  for (const method of ['POST', 'PUT', 'PATCH', 'DELETE']) assert.equal((await call(method, '/api/publish', { ...spoof, 'X-DormPanel-CSRF': JSON.parse(spoofed.body).csrf })).status, 405);
  const refresh = await call('POST', '/api/refresh', { ...admin, 'Content-Type':'application/json', 'X-DormPanel-CSRF': JSON.parse(view.body).csrf },{version:0,workspaceId:JSON.parse(view.body).workspaceId}); assert.equal(refresh.status, 400);
  const failed = JSON.parse((await call('GET', '/api/schedule', admin)).body); assert.equal(failed.status, 'error'); assert(failed.error.includes('WebDAV'));
  const restarted = await createApp({ dataDir: dir, configPath }); assert.equal(restarted.store.identity.writerId, app.store.identity.writerId);
  assert(restarted.db.frame.error.includes('WebDAV'));
});
