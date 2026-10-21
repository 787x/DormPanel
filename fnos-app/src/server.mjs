import http from "node:http";
import { readFile, writeFile, chmod, unlink } from "node:fs/promises";
import { dirname, join, basename } from "node:path";
import { fileURLToPath } from "node:url";
import { randomBytes, timingSafeEqual, randomUUID } from "node:crypto";
import { Store, json } from "./store.mjs";
import { WebDav, binding } from "./webdav.mjs";
import { date, ensure, zone } from "./protocol.mjs";
import { projectWeek, monday } from "./projection.mjs";
import { Workspace } from "./workspace.mjs";
import { emptyState, mergePlan } from "./merge.mjs";
import { SyncEngine, verifyEndpointMove } from "./sync.mjs";
import { previewImport, commitImport } from "./import.mjs";

export const PREFIX = "/app/dormpanel-schedule-manager";
export function authorize(req) {
  // Header 仅在 owner-only Socket 边界之内可信；root/包用户已有完整代码与数据权限。
  ensure(req.socket.remoteAddress == null, "拒绝 TCP");
  for (const key of ["x-trim-userid", "x-trim-isadmin"])
    ensure(
      req.rawHeaders.filter((v, i) => i % 2 === 0 && v.toLowerCase() === key)
        .length === 1,
      "身份 Header 缺失或重复",
    );
  ensure(
    /^\d+$/.test(req.headers["x-trim-userid"]) &&
      req.headers["x-trim-isadmin"] === "true",
    "仅允许 NAS 管理员",
  );
  return req.headers["x-trim-userid"];
}
export function csrf(req, token, boundOrigin = null) {
  ensure(
    !["cross-site", "same-site"].includes(req.headers["sec-fetch-site"]),
    "拒绝跨源请求",
  );
  const origin = req.headers.origin;
  // 网关 TLS 终止，不信任客户端 Forwarded；比较 Host 且只接受 HTTP(S) Origin。
  if (boundOrigin) ensure(origin === boundOrigin, "Origin 不匹配");
  else if (origin) {
    const u = new URL(origin);
    ensure(
      ["http:", "https:"].includes(u.protocol) &&
        u.host === req.headers.host &&
        !u.username &&
        !u.password,
      "Origin 不匹配",
    );
  }
  const supplied = req.headers["x-dormpanel-csrf"];
  ensure(
    typeof supplied === "string" &&
      Buffer.byteLength(supplied) === Buffer.byteLength(token) &&
      timingSafeEqual(Buffer.from(supplied), Buffer.from(token)),
    "CSRF 校验失败",
  );
}
async function body(req) {
  ensure(req.headers["content-type"] === "application/json", "需要 JSON 请求");
  ensure(!req.headers["content-encoding"], "不接受压缩请求");
  ensure(
    !req.headers["content-length"] ||
      Number(req.headers["content-length"]) <= 2 * 1024 * 1024,
    "请求过大",
  );
  let size = 0;
  const chunks = [];
  for await (const b of req) {
    size += b.length;
    ensure(size <= 2 * 1024 * 1024, "请求过大");
    chunks.push(b);
  }
  const parsed = JSON.parse(
    new TextDecoder("utf-8", { fatal: true }).decode(Buffer.concat(chunks)),
  );
  ensure(
    parsed && typeof parsed === "object" && !Array.isArray(parsed),
    "请求无效",
  );
  return parsed;
}
// locator 从不发给浏览器，冲突与导出同样应用。
const publicValue = (value) =>
  JSON.parse(json(value), (key, v) => (key === "locator" ? undefined : v));
export async function createApp({
  dataDir,
  configPath,
  webDir = join(dirname(fileURLToPath(import.meta.url)), "../web"),
  remoteFactory = (c) => new WebDav(c),
}) {
  const store = await new Store(dataDir).init(),
    db = await Workspace.open(
      store,
      (await store.read("workspace.json")) ? null : await store.restore(),
    ),
    configStore = new Store(dirname(configPath));
  let busy = false,
    configuring = false;
  const tokens = new Map(),
    previews = new Map();
  const userToken = (user, origin = null) => {
    const key = user + "\n" + (origin ?? "");
    if (!tokens.has(key)) {
      ensure(tokens.size < 128, "会话数量超限");
      tokens.set(key, randomBytes(32).toString("hex"));
    }
    return tokens.get(key);
  };
  async function config() {
    try {
      const c = JSON.parse(await readFile(configPath, "utf8"));
      binding(c);
      ensure(typeof c.username === "string" && typeof c.password === "string");
      return c;
    } catch (e) {
      if (e.code === "ENOENT") return null;
      throw Error("后端绑定配置无效");
    }
  }
  async function network(action) {
    ensure(!busy, "正在执行网络操作");
    busy = true;
    try {
      const c = await config();
      ensure(c, "请先配置 WebDAV");
      const engine = new SyncEngine(db, remoteFactory(c));
      await action(engine);
      await db.tx((f) => {
        f.error = null;
      });
    } catch (e) {
      await db.tx((f) => {
        f.error = e.message;
      });
      throw e;
    } finally {
      busy = false;
    }
  }
  const refresh = (options) => network((engine) => engine.synchronize(options));
  const server = http.createServer(async (req, res) => {
    const send = (status, value, type = "application/json; charset=utf-8") => {
      res.writeHead(status, {
        "Content-Type": type,
        "Cache-Control": "no-store",
        "X-Content-Type-Options": "nosniff",
        "Content-Security-Policy":
          "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'none'",
        "Referrer-Policy": "no-referrer",
      });
      res.end(type.startsWith("application/json") ? json(value) : value);
    };
    try {
      let user;
      try {
        user = authorize(req);
      } catch {
        send(403, { error: "需要管理员身份及私有 Socket 访问路径" });
        return;
      }
      ensure(
        req.url === PREFIX || req.url.startsWith(PREFIX + "/"),
        "非法路由",
      );
      const u = new URL(req.url, "http://unix"),
        path = u.pathname.slice(PREFIX.length);
      if (req.method === "POST") {
        const routes = [
          "/api/edit",
          "/api/refresh",
          "/api/sync",
          "/api/resolve",
          "/api/import/preview",
          "/api/import/confirm",
          "/api/config",
          "/api/config/test",
        ];
        if (!routes.includes(path)) {
          send(405, { error: "不支持的写入路由" });
          return;
        }
        try {
          csrf(
            req,
            userToken(user, req.headers.origin ?? null),
            req.headers.origin ?? null,
          );
        } catch {
          send(403, { error: "CSRF 或同源校验失败" });
          return;
        }
        const input = await body(req);
        if (
          !Number.isSafeInteger(input.version) ||
          input.version !== db.frame.revision
        ) {
          send(409, { error: "请求版本过期，请重新读取" });
          return;
        }
        ensure(!configuring, "正在保存配置");
        if (path === "/api/edit") await db.edit(input.operation, input.version);
        else if (path === "/api/refresh" || path === "/api/sync")
          await refresh({
            bind: input.bind === true,
            create: input.create === true,
          });
        else if (path === "/api/resolve")
          await network((engine) =>
            engine.resolve(input.choice, input.token, input.version),
          );
        else if (path === "/api/import/preview") {
          const bytes = Buffer.from(input.base64 ?? "", "base64"),
            preview = previewImport(bytes, input.filename, db.frame.local),
            id = randomUUID();
          for (const [id, p] of previews)
            if (p.expires < Date.now()) previews.delete(id);
          ensure(previews.size < 8, "预览过多，请等待过期后再试");
          previews.set(id, {
            preview,
            user,
            version: db.frame.revision,
            expires: Date.now() + 15 * 60000,
          });
          send(200, {
            id,
            version: db.frame.revision,
            filename: preview.filename,
            calendarName: preview.calendarName,
            count: preview.occurrences.length,
            warnings: preview.warnings,
            termKey: preview.termKey,
            samples: preview.occurrences.slice(0, 20),
          });
          return;
        } else if (path === "/api/import/confirm") {
          const p = previews.get(input.id);
          ensure(
            p &&
              p.user === user &&
              p.version === input.version &&
              p.expires > Date.now(),
            "导入预览已失效",
          );
          await db.tx(
            (f) =>
              commitImport(
                f.local,
                p.preview,
                input.displayName,
                input.targetId ?? null,
              ),
            input.version,
          );
          previews.delete(input.id);
        } else if (path === "/api/config/test") {
          ensure(!busy, "正在同步");
          const old = await config(),
            c = {
              url: input.url ?? old?.url,
              username: input.username ?? old?.username,
              password: input.password || old?.password,
              certificatePem: input.certificatePem ?? old?.certificatePem,
            };
          binding(c);
          ensure(c.username && c.password, "缺少凭据");
          const remote = remoteFactory(c);
          const ids = await remote.list();
          send(200, { ok: true, observed: ids.length });
          return;
        } else if (path === "/api/config") {
          ensure(!busy, "正在同步");
          configuring = true;
          busy = true;
          try {
            const old = await config(),
              c = {
                url: input.url,
                username: input.username,
                password: input.password || old?.password,
                certificatePem: input.certificatePem ?? old?.certificatePem,
              };
            binding(c);
            ensure(c.username && c.password, "缺少凭据");
            const activeKey =
              db.frame.bindingKey ?? (old ? binding(old).key : null);
            const changed = !!activeKey && activeKey !== binding(c).key;
            ensure(
              !changed || input.confirm === activeKey,
              "切换目录必须确认旧绑定地址",
            );
            const relocate = changed && input.relocate === true;
            ensure(
              !changed ||
                relocate ||
                (!db.dirty() &&
                  !db.frame.pending &&
                  !db.frame.conflict &&
                  !db.frame.checkpoint.deferred?.length),
              "存在本地修改、待确认发布、冲突或异常，拒绝切换；请先处理",
            );
            // 配置写入和状态切换期间禁止其他网络/配置操作。崩溃于两者之间会因 key 不符安全停止。
            // 排空此前已排队事务，在改凭据前复核版本，后续写路由受 configuring 阻挡。
            await db.tx(() => {}, input.version);
            // 同一数据集迁移须完整读取新端点，证明旧身份、已知哈希与前沿闭包；不合并、不清空本地。
            const moved = relocate
              ? await verifyEndpointMove(db, remoteFactory(c))
              : null;
            if (changed)
              await store.save("archive-" + randomUUID() + ".json", db.frame);
            await configStore.save(basename(configPath), c);
            await db.tx((f) => {
              if (changed && !relocate) {
                f.local = emptyState();
                f.baseline = null;
                f.datasetId = null;
                f.frontier = [];
                f.pending = null;
                f.conflict = null;
                f.graph = null;
                f.checkpoint = {};
                f.abandoned = {};
                f.receipt = null;
              }
              if (moved)
                f.checkpoint = {
                  ...f.checkpoint,
                  hashes: moved.hashes,
                  deferred: moved.deferred,
                  bindingKey: binding(c).key,
                };
              f.bindingKey = binding(c).key;
              f.error = null;
            });
          } finally {
            busy = false;
            configuring = false;
          }
        }
        send(200, { ok: true, version: db.frame.revision });
        return;
      }
      if (req.method !== "GET") {
        send(405, { error: "仅支持明确的 GET/POST 路由" });
        return;
      }
      if (path === "/api/config") {
        const c = await config();
        send(200, {
          bound: !!c,
          url: c?.url ?? null,
          hasCredentials: !!c?.username && !!c?.password,
          hasTrustedCertificate: !!c?.certificatePem,
          bindingKey: db.frame.bindingKey,
          version: db.frame.revision,
        });
        return;
      }
      if (path === "/api/state") {
        send(200, {
          version: db.frame.revision,
          local: publicValue(db.frame.local),
        });
        return;
      }
      if (path === "/api/conflicts") {
        const f = db.frame,
          result = f.conflict ? mergePlan(f.conflict, f.local) : null;
        send(
          200,
          publicValue({
            version: f.revision,
            token: db.conflictToken(),
            conflicts: result?.conflicts ?? [],
            details: result?.details ?? [],
            branches: f.conflict?.branches ?? [],
            remote: result?.remote ?? null,
          }),
        );
        return;
      }
      if (path === "/api/schedule") {
        const pageOrigin = u.searchParams.get("origin");
        if (pageOrigin) {
          const o = new URL(pageOrigin),
            h = new URL("http://" + req.headers.host);
          ensure(
            o.origin === pageOrigin &&
              ["http:", "https:"].includes(o.protocol) &&
              o.hostname === h.hostname &&
              !o.username &&
              !o.password,
            "页面来源不匹配",
          );
        }
        const c = await config(),
          z = zone(u.searchParams.get("zone") ?? "Asia/Shanghai"),
          first = monday(
            date(
              u.searchParams.get("date") ??
                new Date().toISOString().slice(0, 10),
            ).toString(),
          ),
          f = db.frame,
          state = f.local;
        const viewError =
          f.error ??
          (c && f.bindingKey && f.bindingKey !== binding(c).key
            ? "配置与本地绑定不一致；请显式验证同一数据集地址迁移或恢复原配置"
            : null);
        send(
          200,
          publicValue({
            writerId: store.identity.writerId,
            csrf: userToken(user, pageOrigin),
            version: f.revision,
            status: !c
              ? "unbound"
              : viewError
                ? "error"
                : f.conflict
                  ? "conflict"
                  : (f.graph?.status ?? "unchecked"),
            error: viewError,
            busy,
            datasetId: f.datasetId,
            checkedAt: f.graph?.checkedAt ?? null,
            cached: !!viewError,
            tips: f.graph?.tips ?? [],
            deferred: f.checkpoint.deferred ?? [],
            historyCount: Object.keys(f.checkpoint.hashes ?? {}).length,
            localSaved: true,
            dirty: db.dirty(),
            pending: !!f.pending,
            published: f.receipt,
            conflict: !!f.conflict,
            first,
            zone: z,
            profiles: state.profiles,
            sources: state.sources,
            courses: projectWeek(state, first, z),
            events: state.events,
          }),
        );
        return;
      }
      const files = {
        "": ["index.html", "text/html; charset=utf-8"],
        "/": ["index.html", "text/html; charset=utf-8"],
        "/app.js": ["app.js", "text/javascript; charset=utf-8"],
        "/style.css": ["style.css", "text/css; charset=utf-8"],
      };
      if (files[path]) {
        const [name, type] = files[path];
        send(200, await readFile(join(webDir, name)), type);
        return;
      }
      send(404, { error: "不存在" });
    } catch (e) {
      send(/过期|变化|失效/.test(e.message) ? 409 : 400, { error: e.message });
    }
  });
  server.requestTimeout = 65000;
  server.headersTimeout = 10000;
  return { server, refresh, store, db };
}
if (process.argv[1] === fileURLToPath(import.meta.url)) {
  process.umask(0o077);
  const socket = process.env.SOCKET_PATH;
  ensure(
    socket && process.env.TRIM_PKGVAR && process.env.TRIM_PKGETC,
    "需要 fnOS 运行环境",
  );
  const app = await createApp({
    dataDir: process.env.TRIM_PKGVAR,
    configPath: join(process.env.TRIM_PKGETC, "webdav.json"),
  });
  await new Promise((resolve, reject) => {
    app.server.once("error", reject);
    app.server.listen(socket, resolve);
  });
  await chmod(socket, 0o600);
  const ready = join(process.env.TRIM_PKGVAR, "socket.ready");
  await writeFile(ready, String(process.pid), { mode: 0o600 });
  process.on("SIGTERM", () => {
    app.server.close(async () => {
      await unlink(socket).catch(() => {});
      await unlink(ready).catch(() => {});
      process.exit(0);
    });
    setTimeout(() => process.exit(1), 5000).unref();
  });
}
