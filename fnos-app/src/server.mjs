import http from 'node:http';
import { readFile, chmod, unlink } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { randomBytes, timingSafeEqual } from 'node:crypto';
import { Store, json } from './store.mjs';
import { WebDav, binding } from './webdav.mjs';
import { loadGraph } from './graph.mjs';
import { date, ensure, zone } from './protocol.mjs';
import { projectWeek, monday } from './projection.mjs';

export const PREFIX = '/app/dormpanel-schedule-manager';
export function authorize(req) {
  // Unix Socket 是唯一监听面；TCP 开发服务器不能复用此鉴权。
  ensure(req.socket.remoteAddress == null, '拒绝非网关传输');
  for (const key of ['x-trim-userid', 'x-trim-isadmin']) ensure(req.rawHeaders.filter((v, i) => i % 2 === 0 && v.toLowerCase() === key).length === 1, '身份 Header 缺失或重复');
  ensure(/^\d+$/.test(req.headers['x-trim-userid']) && req.headers['x-trim-isadmin'] === 'true', '仅允许 NAS 管理员');
  return req.headers['x-trim-userid'];
}
export function csrf(req, token) {
  ensure(req.headers['sec-fetch-site'] !== 'cross-site', '拒绝跨站请求');
  const supplied = req.headers['x-dormpanel-csrf'];
  ensure(typeof supplied === 'string' && supplied.length === token.length && timingSafeEqual(Buffer.from(supplied), Buffer.from(token)), 'CSRF 校验失败');
}
export async function createApp({ dataDir, configPath, webDir = join(dirname(fileURLToPath(import.meta.url)), '../web') }) {
  const store = await new Store(dataDir).init();
  let graph = await store.restore(), error = (await store.read('observation.json'))?.error ?? null, busy = false;
  const token = randomBytes(32).toString('hex');
  async function config() { try { const c = JSON.parse(await readFile(configPath, 'utf8')); binding(c); ensure(typeof c.username === 'string' && typeof c.password === 'string'); return c; } catch (e) { if (e.code === 'ENOENT') return null; throw new Error('后端绑定配置无效'); } }
  async function refresh() {
    ensure(!busy, '正在刷新'); busy = true;
    try {
      const c = await config(); if (!c) { error = null; return; }
      const remote = new WebDav(c), previous = await store.read('checkpoint.json') ?? {};
      ensure(!previous.bindingKey || previous.bindingKey === remote.key, '绑定地址已变更，需要显式重新绑定');
      const next = await loadGraph(remote, { ...graph, ...previous, hashes: { ...graph?.hashes, ...previous.hashes }, deferred: [...new Set([...(previous.deferred ?? []), ...(graph?.deferred ?? [])])] }, async (deferred, verified) => {
        await store.save('checkpoint.json', { ...previous, datasetId: verified?.datasetId ?? graph?.datasetId ?? previous.datasetId, hashes: verified?.hashes ?? { ...graph?.hashes, ...previous.hashes }, bindingKey: remote.key, deferred });
      });
      next.bindingKey = remote.key;
      await store.save('graph.json', next); graph = next;
      await store.save('checkpoint.json', { datasetId: next.datasetId, hashes: next.hashes, bindingKey: remote.key, deferred: next.deferred }); error = null;
    } catch (e) { error = e.message; } finally { await store.save('observation.json', { error }); busy = false; }
  }
  const server = http.createServer(async (req, res) => {
    const send = (status, value, type = 'application/json; charset=utf-8') => { res.writeHead(status, { 'Content-Type': type, 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'Content-Security-Policy': "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'none'", 'Referrer-Policy': 'no-referrer' }); res.end(type.startsWith('application/json') ? json(value) : value); };
    try {
      try { authorize(req); } catch { send(403, { error: '仅允许通过 fnOS 统一网关登录的管理员访问' }); return; }
      ensure(req.url.startsWith(PREFIX + '/') || req.url === PREFIX, '非法路由');
      const u = new URL(req.url, 'http://unix'); const path = u.pathname.slice(PREFIX.length);
      if (req.method === 'POST' && path === '/api/refresh') {
        try { csrf(req, token); } catch { send(403, { error: 'CSRF 校验失败' }); return; }
        await refresh(); send(200, { ok: error === null }); return;
      }
      if (req.method !== 'GET') { send(405, { error: '本版本不提供课程写入接口' }); return; }
      if (path === '/api/schedule') {
        const c = await config(), z = zone(u.searchParams.get('zone') ?? 'Asia/Shanghai');
        const first = monday(date(u.searchParams.get('date') ?? new Date().toISOString().slice(0, 10)).toString());
        const state = graph?.state;
        send(200, { writerId: store.identity.writerId, csrf: token, status: !c ? 'unbound' : error ? 'error' : graph?.status ?? 'unchecked', error, busy,
          datasetId: graph?.datasetId ?? null, checkedAt: graph?.checkedAt ?? null, cached: !!error && !!state, tips: graph?.tips ?? [], deferred: graph?.deferred ?? [], historyCount: Object.keys(graph?.hashes ?? {}).length,
          first, zone: z, profiles: state?.profiles ?? [], sources: (state?.sources ?? []).map(({ locator, ...s }) => s),
          courses: state ? projectWeek(state, first, z) : [], events: state?.events.filter(e => Number(e.start) < Number(date(first).add({ days: 7 }).toZonedDateTime(z).epochMilliseconds) && Number(e.end) > Number(date(first).toZonedDateTime(z).epochMilliseconds)) ?? [] }); return;
      }
      const files = { '': ['index.html', 'text/html; charset=utf-8'], '/': ['index.html', 'text/html; charset=utf-8'], '/app.js': ['app.js', 'text/javascript; charset=utf-8'], '/style.css': ['style.css', 'text/css; charset=utf-8'] };
      if (files[path]) { const [name, type] = files[path]; send(200, await readFile(join(webDir, name)), type); return; }
      send(404, { error: '不存在' });
    } catch { send(400, { error: '请求参数无效或本地状态不可用' }); }
  });
  server.requestTimeout = 65000; server.headersTimeout = 10000;
  return { server, refresh, store };
}
if (process.argv[1] === fileURLToPath(import.meta.url)) {
  process.umask(0o077);
  const socket = process.env.SOCKET_PATH;
  ensure(socket && process.env.TRIM_PKGVAR && process.env.TRIM_PKGETC, '需要 fnOS 运行环境');
  const app = await createApp({ dataDir: process.env.TRIM_PKGVAR, configPath: join(process.env.TRIM_PKGETC, 'webdav.json') });
  await new Promise((resolve, reject) => { app.server.once('error', reject); app.server.listen(socket, resolve); });
  // 网关以系统服务连接；所在 target 目录的写权限仅属于包用户。
  await chmod(socket, 0o600);
  process.on('SIGTERM', () => { app.server.close(async () => { await unlink(socket).catch(() => {}); process.exit(0); }); setTimeout(() => process.exit(1), 5000).unref(); });
}
