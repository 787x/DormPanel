import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, rm, writeFile, readFile } from "node:fs/promises";
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
      { version: app.db.frame.revision, ...data },
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
    "/api/config",
    "/api/config/test",
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
test("配置凭据安全查看、测试不保存、未发布本地修改阻止换库", async (t) => {
  const f = await fixture(t);
  const secret = "private-password-38";
  assert.equal(
    (
      await f.post("/api/config", {
        url: "https://example.com/folder/",
        username: "w",
        password: secret,
      })
    ).status,
    200,
  );
  const before = await readFile(f.configPath, "utf8");
  assert.equal(
    (
      await f.post("/api/config/test", {
        url: "https://other.test/preview/",
        username: "w",
        password: "test",
      })
    ).status,
    200,
  );
  assert.equal(await readFile(f.configPath, "utf8"), before);
  for (const path of [
    "/api/config",
    "/api/state",
    "/api/schedule",
    "/api/conflicts",
  ])
    assert(!(await f.call("GET", path)).text.includes(secret));
  assert.equal(
    (
      await f.post("/api/edit", {
        operation: {
          kind: "entry",
          action: "upsert",
          value: {
            title: "离线",
            day: 1,
            startMinute: 480,
            endMinute: 530,
            location: "",
          },
        },
      })
    ).status,
    200,
  );
  assert.equal(
    (
      await f.post("/api/config", {
        url: "https://other.test/folder/",
        username: "w",
        password: "new",
        confirm: f.app.db.frame.bindingKey,
      })
    ).status,
    400,
  );
  assert.equal(await readFile(f.configPath, "utf8"), before);
  assert.equal(f.app.db.frame.local.entries[0].title, "离线");
  assert.equal(
    (
      await f.post("/api/config", {
        url: "https://example.com/folder/",
        username: "w",
        password: "rotated",
      })
    ).status,
    200,
  );
  assert.equal(
    JSON.parse(await readFile(f.configPath, "utf8")).password,
    "rotated",
  );
  // 模拟配置已 rename、数据库切换尚未提交的崩溃窗口，不能靠重发同 URL 绕过保护。
  await writeFile(
    f.configPath,
    JSON.stringify({
      url: "https://other.test/folder/",
      username: "w",
      password: "other",
    }),
  );
  assert.equal((await f.call("GET", "/api/schedule")).data.status, "error");
  assert.equal(
    (
      await f.post("/api/config", {
        url: "https://other.test/folder/",
        username: "w",
        password: "other",
        confirm: f.app.db.frame.bindingKey,
      })
    ).status,
    400,
  );
  assert.equal(f.app.db.frame.local.entries[0].title, "离线");
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
