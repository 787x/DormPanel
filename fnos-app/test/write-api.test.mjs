import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, rm, writeFile, readFile, mkdir } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { randomUUID } from "node:crypto";
import http from "node:http";
import { createApp, PREFIX } from "../src/server.mjs";
import { safeAddress, tlsOptions } from "../src/webdav.mjs";
import { X509Certificate } from "node:crypto";

async function fixture(t) {
  const dir = await mkdtemp(join(tmpdir(), "pr38-api-"));
  t.after(() => rm(dir, { force: true, recursive: true }));
  const configPath = join(dir, "webdav.json"),
    remote = {
      key: "https://example.com/folder/dormpanel-sync-v2/",
      list: async () => [],
      read: async () => null,
      put: async () => {
        throw Error("本测试禁止远端 PUT");
      },
    };
  const app = await createApp({
      dataDir: dir,
      configPath,
      remoteFactory: () => remote,
    }),
    socketPath =
      process.platform === "win32"
        ? "\\\\.\\pipe\\dp-" + randomUUID()
        : join(dir, "api.sock");
  await new Promise((r) => app.server.listen(socketPath, r));
  t.after(() => new Promise((r) => app.server.close(r)));
  const admin = { "X-Trim-Userid": "1000", "X-Trim-Isadmin": "true" };
  const call = (method, path, data, headers = {}) =>
    new Promise((resolve, reject) => {
      const req = http.request(
        {
          socketPath,
          path: PREFIX + path,
          method,
          headers: { ...admin, ...headers },
        },
        (res) => {
          let text = "";
          res.on("data", (b) => (text += b));
          res.on("end", () =>
            resolve({ status: res.statusCode, data: JSON.parse(text), text }),
          );
        },
      );
      req.on("error", reject);
      req.end(data === undefined ? undefined : JSON.stringify(data));
    });
  const view = (
    await call(
      "GET",
      "/api/schedule?origin=http%3A%2F%2Fnas.test%3A5666",
      undefined,
      { Host: "nas.test" },
    )
  ).data;
  const post = (path, data = {}, headers = {}) =>
    call(
      "POST",
      path,
      { workspaceId: app.catalog.manifest.activeId, catalogVersion: app.catalog.manifest.revision, version: app.db.frame.revision, ...data },
      {
        "Content-Type": "application/json",
        "X-DormPanel-CSRF": view.csrf,
        Host: "nas.test",
        Origin: "http://nas.test:5666",
        ...headers,
      },
    );
  return { app, dir, configPath, call, post, view };
}
test("每条修改路由均检查管理员、CSRF、同源、请求版本；拒绝过期重放", async (t) => {
  const f = await fixture(t);
  for (const path of [
    "/api/edit",
    "/api/sync",
    "/api/refresh",
    "/api/resolve",
    "/api/import/preview",
    "/api/import/confirm",
    "/api/manage",
    "/api/connections/test",
  ]) {
    assert.equal(
      (await f.post(path, {}, { "X-Trim-Isadmin": "false" })).status,
      403,
    );
    assert.equal(
      (await f.post(path, {}, { "X-DormPanel-CSRF": "invalid" })).status,
      403,
    );
    assert.equal(
      (await f.post(path, {}, { Origin: "https://evil.test" })).status,
      403,
    );
    assert.equal(
      (await f.post(path, {}, { "Sec-Fetch-Site": "same-site" })).status,
      403,
    );
    assert.equal((await f.post(path, { version: -1 })).status, 409);
    assert.equal(
      (await f.post(path, {}, { "Content-Type": "text/plain" })).status,
      400,
    );
  }
  const operation = {
    kind: "entry",
    action: "upsert",
    value: {
      title: "<img src=x onerror=alert(1)>",
      day: 1,
      startMinute: 480,
      endMinute: 530,
      location: "",
    },
  };
  assert.equal(
    (
      await f.post(
        "/api/edit",
        { operation },
        { Origin: "http://nas.test:5667" },
      )
    ).status,
    403,
  );
  assert.equal(
    (
      await f.call(
        "GET",
        "/api/schedule?origin=https%3A%2F%2Fevil.test",
        undefined,
        { Host: "nas.test" },
      )
    ).status,
    400,
  );
  assert.equal((await f.post("/api/edit", { operation })).status, 200);
  assert.equal(
    (await f.post("/api/edit", { operation, version: 0 })).status,
    409,
  );
  assert.equal(f.app.db.frame.local.entries.length, 1);
  assert.equal(
    (await f.post("/api/edit", { operation }, { "X-Trim-Userid": "1001" }))
      .status,
    403,
    "CSRF 绑定真实管理员用户",
  );
  assert.equal((await f.call("GET", "/api/edit")).status, 404);
  assert.equal((await f.call("DELETE", "/api/edit")).status, 405);
  assert.equal(
    (
      await f.post("/api/edit", {
        operation: {
          kind: "entry",
          action: "upsert",
          value: { ...operation.value, title: "x".repeat(2 * 1024 * 1024) },
        },
      })
    ).status,
    400,
  );
  assert.equal(f.app.db.frame.local.entries.length, 1);
});
test('仅私有目录可完成连接、工作区、编辑和合法发布；保留已有工作区', { skip: process.platform !== 'linux' }, async t => {
  const f = await fixture(t), original = structuredClone(f.app.db.frame);
  const roots = (await f.call('GET', '/api/manage')).data.roots;
  assert.deepEqual(roots.map(r => r.id), ['private']);
  const result = await f.post('/api/manage', { action: 'connection.save', type: 'local', name: '私有测试', rootId: 'private', relative: '', createPrivate: true });
  assert.equal(result.status, 200, result.text);
  const connectionId = result.data.connectionId, connection = f.app.catalog.connection(connectionId);
  assert.equal(connection.config.relative, connectionId); assert(connection.config.directoryIdentity);
  assert.equal((await f.post('/api/manage', { action: 'workspace.add', name: '独立测试', connectionId })).status, 200);
  const targetId = f.app.catalog.manifest.workspaces.find(w => w.connectionId === connectionId).id;
  assert.equal((await f.post('/api/manage', { action: 'workspace.switch', targetId })).status, 200);
  assert.equal((await f.post('/api/edit', { operation: { kind: 'entry', action: 'upsert', value: {
    title: '仅私有目录课程', day: 1, startMinute: 480, endMinute: 530, location: '',
  } } })).status, 200);
  const published = await f.post('/api/sync', { create: true }); assert.equal(published.status, 200, published.text);
  assert(f.app.db.frame.datasetId); assert(f.app.db.receipt || f.app.db.frame.receipt);
  assert.deepEqual((await f.app.catalog.load(f.view.workspaceId)).frame, original);
});

test('授权 NAS 目录可选择保存；未授权根、绝对路径、穿越和过期版本不能改配置', { skip: process.platform !== 'linux' }, async t => {
  const f = await fixture(t), approved = join(f.dir, 'approved'); await mkdir(approved);
  f.app.catalog.grants.roots.push({ id: 'approved', name: '已授权 NAS 测试目录', path: approved });
  assert.equal((await f.call('GET', '/api/directories?rootId=approved')).status, 200);
  const input = { action: 'connection.save', type: 'local', name: 'NAS 测试', rootId: 'approved', relative: '' };
  assert.equal((await f.post('/api/manage', input)).status, 200);
  const before = structuredClone(f.app.catalog.manifest);
  for (const change of [{ rootId: 'unapproved' }, { relative: '/etc' }, { relative: '../approved' }, { catalogVersion: -1 }]) {
    assert.notEqual((await f.post('/api/manage', { ...input, ...change })).status, 200);
    assert.deepEqual(f.app.catalog.manifest, before);
  }
  assert.equal((await f.call('GET', '/api/directories?rootId=unapproved')).status, 400);
});

test("多连接配置凭据不返回浏览器，测试不改变当前工作区，拒绝覆盖绑定", async t => {
  const f = await fixture(t), secret = 'private-password-39';
  const result = await f.post('/api/manage', { action: 'connection.save', type: 'webdav', name: '连接 A', url: 'https://example.com/folder/', username: 'w', password: secret });
  assert.equal(result.status, 200, result.text);
  const connectionId = result.data.connectionId;
  const before = JSON.stringify(f.app.db.frame), manifest = JSON.stringify(f.app.catalog.manifest);
  assert.equal((await f.post('/api/connections/test', { name: '预览', type: 'webdav', url: 'https://other.test/preview/', username: 'w', password: 'temporary' })).status, 200);
  assert.equal(JSON.stringify(f.app.db.frame), before);
  assert.equal(JSON.stringify(f.app.catalog.manifest), manifest);
  for (const path of ['/api/manage', '/api/state', '/api/schedule', '/api/conflicts']) {
    const result = await f.call('GET', path + '?workspaceId=' + f.view.workspaceId);
    assert.equal(result.status, 200); assert(!result.text.includes(secret));
  }
  assert.equal((await f.post('/api/manage', { action: 'workspace.endpoint', targetId: f.view.workspaceId, targetVersion: f.app.db.frame.revision, connectionId })).status, 200);
  await f.app.db.tx(frame => { frame.datasetId = randomUUID(); });
  const rotated = await f.post('/api/manage', { action: 'connection.save', connectionId, password: 'rotated' });
  assert.equal(rotated.status, 400, '不能绕过已绑定历史验证');
  assert.equal(f.app.catalog.connection(connectionId).config.password, secret);
});

test("阻止回环、元数据地址、IPv4映射回环和多播 SSRF，保留可明确配置的 LAN NAS", () => {
  for (const [ip, family] of [
    ["127.0.0.1", 4],
    ["169.254.169.254", 4],
    ["0.0.0.0", 4],
    ["::1", 6],
    ["::ffff:127.0.0.1", 6],
    ["fe80::1", 6],
    ["224.0.0.1", 4],
  ])
    assert.equal(safeAddress(ip, family), false, ip);
  assert.equal(safeAddress("192.168.3.153", 4), true);
});

test('所有管理写路由拒绝缺失/伪造工作区与过期管理版本；导入预览不能跨工作区使用', async t => {
  const f = await fixture(t);
  const c = await f.post('/api/manage', { action: 'connection.save', type: 'webdav', name: 'B 连接', url: 'https://b.test/', username: 'u', password: 'p' });
  const w = await f.post('/api/manage', { action: 'workspace.add', connectionId: c.data.connectionId, name: 'B' });
  const b = (await f.app.catalog.load(w.data.workspaceId)), a = f.app.db;
  for (const path of ['/api/edit', '/api/sync', '/api/resolve', '/api/import/preview', '/api/import/confirm', '/api/manage', '/api/connections/test']) {
    assert.equal((await f.post(path, { workspaceId: undefined })).status, 400);
    assert.equal((await f.post(path, { workspaceId: randomUUID() })).status, 400);
  }
  assert.equal((await f.post('/api/manage', { action: 'workspace.switch', targetId: b.id, catalogVersion: -1 })).status, 409);
  const bytes = await readFile(new URL('../../schedule-core/src/test/resources/schedule/wakeup.ics', import.meta.url));
  const preview = await f.post('/api/import/preview', { filename: 'wakeup.ics', base64: bytes.toString('base64') });
  assert.equal(preview.status, 200, preview.text);
  await f.post('/api/manage', { action: 'workspace.switch', targetId: w.data.workspaceId });
  const before = JSON.stringify(b.frame);
  const replay = await f.post('/api/import/confirm', { id: preview.data.id, displayName: '错误目标' });
  assert.equal(replay.status, 409, replay.text); assert.equal(JSON.stringify(b.frame), before);
  const confirmed = await f.post('/api/import/confirm', { workspaceId: f.view.workspaceId, version: a.frame.revision, id: preview.data.id, displayName: '正确目标' });
  assert.equal(confirmed.status, 200, confirmed.text); assert((await f.app.catalog.load(f.view.workspaceId)).frame.local.sources.length > 0);
  assert.equal(b.frame.local.sources.length, 0);
  assert.equal((await f.call('GET', '/api/state')).status, 400);
  const denied = await f.call('GET', '/api/directories?workspaceId=' + w.data.workspaceId + '&rootId=/etc&relative=');
  assert.equal(denied.status, 400);
});

test('冲突 token 同时绑定工作区，即使两个恢复帧碰巧完全相同也不能重放', async t => {
  const f = await fixture(t), a = f.app.db;
  const c = await f.post('/api/manage', { action: 'connection.save', type: 'webdav', name: 'B', url: 'https://b.test/', username: 'u', password: 'p' });
  const w = await f.post('/api/manage', { action: 'workspace.add', name: 'B', connectionId: c.data.connectionId });
  const b = (await f.app.catalog.load(w.data.workspaceId)), base = structuredClone(a.frame.local);
  const plan = { base, localBase: base, branches: [] };
  await a.tx(frame => { frame.conflict = plan; }); await b.tx(frame => { frame.conflict = plan; });
  assert.equal(a.conflictToken(), b.conflictToken());
  const response = await f.post('/api/resolve', { workspaceId: w.data.workspaceId, version: b.frame.revision, token: f.view.workspaceId + ':' + a.conflictToken(), choice: 'local' });
  assert.equal(response.status, 409); assert(!f.app.catalog.busy.size); assert(b.frame.conflict);
});
test("显式信任的独立证书仍启用 TLS 校验，精确拒绝另一个证书", async () => {
  const pem = await readFile(
      new URL("./fixtures/test-certificate.pem", import.meta.url),
      "utf8",
    ),
    cert = new X509Certificate(pem),
    options = tlsOptions({ certificatePem: pem });
  assert.equal(options.rejectUnauthorized, true);
  assert.equal(
    options.checkServerIdentity("192.168.3.153", { raw: cert.raw }),
    undefined,
  );
  const changed = Buffer.from(cert.raw);
  changed[changed.length - 1] ^= 1;
  assert(
    options.checkServerIdentity("192.168.3.153", { raw: changed }) instanceof
      Error,
  );
  assert.throws(() => tlsOptions({ certificatePem: "invalid" }));
  assert.throws(() => tlsOptions({ certificatePem: "x".repeat(16385) }));
  assert.deepEqual(tlsOptions({}), {});
});
