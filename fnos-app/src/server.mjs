import http from "node:http";
import { readFile, writeFile, chmod, unlink } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { randomBytes, timingSafeEqual, randomUUID, createHash } from "node:crypto";
import { Store, json } from "./store.mjs";
import { Catalog } from "./catalog.mjs";
import { date, ensure, zone } from "./protocol.mjs";
import { projectWeek, monday } from "./projection.mjs";
import { mergePlan } from "./merge.mjs";
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
  remoteFactory,
  authorizedRoots = [],
}) {
  const store = await new Store(dataDir).init();
  const catalog = await Catalog.open({ store, configPath, remoteFactory, authorizedRoots });
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
  const refresh = options => catalog.network(catalog.manifest.activeId, engine => engine.synchronize(options));
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
          "/api/manage",
          "/api/connections/test",
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
        const workspaceId = input.workspaceId;
        const db = catalog.db(workspaceId);
        if (
          !Number.isSafeInteger(input.version) ||
          input.version !== db.frame.revision
        ) {
          send(409, { error: "请求版本过期，请重新读取" });
          return;
        }
        ensure(!catalog.configuring && !catalog.manifest.transition, "正在保存配置或等待恢复");
        const network = action => catalog.network(workspaceId, action);
        if (path === "/api/edit") await db.edit(input.operation, input.version);
        else if (path === "/api/refresh" || path === "/api/sync")
          await network(engine => engine.synchronize({
            bind: input.bind === true,
            create: input.create === true,
          }));
        else if (path === "/api/resolve") {
          ensure(input.token === `${workspaceId}:${db.conflictToken()}` && db.conflictToken(), '冲突 token 已失效或工作区不匹配');
          await network((engine) =>
            engine.resolve(input.choice, db.conflictToken(), input.version),
          );
        }
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
            workspaceId,
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
              p.workspaceId === workspaceId &&
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
        } else if (path === "/api/connections/test") {
          send(200, await catalog.test(input)); return;
        } else if (path === "/api/manage") {
          const result = await catalog.manage(input);
          send(200, { ok: true, ...result, catalogVersion: catalog.manifest.revision }); return;
        }
        send(200, { ok: true, version: db.frame.revision });
        return;
      }
      if (req.method !== "GET") {
        send(405, { error: "仅支持明确的 GET/POST 路由" });
        return;
      }
      // schedule 无 ID 仅用于初次引导；其余数据读取必须明确指定工作区。
      if (path.startsWith('/api/') && !['/api/schedule', '/api/state', '/api/conflicts', '/api/manage', '/api/directories'].includes(path)) {
        send(404, { error: '不存在' }); return;
      }
      const workspaceId = u.searchParams.get("workspaceId") ??
        (path === "/api/schedule" ? catalog.manifest.activeId : null);
      const db = path.startsWith('/api/') ? catalog.db(workspaceId) : null;
      const row = db ? catalog.row(workspaceId) : null;
      const c = row?.connectionId ? catalog.connection(row.connectionId) : null;
      const busy = catalog.busy.has(workspaceId);
      if (path === "/api/manage") { send(200, await catalog.summary()); return; }
      if (path === "/api/directories") {
        send(200, { directories: await catalog.grants.browse(u.searchParams.get('rootId'), u.searchParams.get('relative') ?? '') }); return;
      }
      if (path === "/api/state") {
        send(200, {
          workspaceId,
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
            workspaceId,
            version: f.revision,
            token: db.conflictToken() ? `${workspaceId}:${db.conflictToken()}` : null,
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
        const z = zone(u.searchParams.get("zone") ?? "Asia/Shanghai"),
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
          (c && f.bindingKey && f.bindingKey !== catalog.remote(c).key
            ? "配置与本地绑定不一致；请显式验证同一数据集地址迁移或恢复原配置"
            : null);
        send(
          200,
          publicValue({
            workspaceId,
            workspaceName: row.name,
            catalogVersion: catalog.manifest.revision,
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
  return { server, refresh, store, catalog, get db() { return catalog.db(catalog.manifest.activeId); } };
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
    authorizedRoots: [
      ...(process.env.TRIM_DATA_SHARE_PATHS ?? '').split(':').filter(Boolean).map(path => ({ id: 'resource-' + createHash('sha256').update(path).digest('hex'), name: 'fnOS 应用共享目录', path, systemResource: true })),
      ...(process.env.TRIM_DATA_ACCESSIBLE_PATHS ?? '').split(':').filter(Boolean).map(path => ({ id: 'authorized-' + createHash('sha256').update(path).digest('hex'), name: 'fnOS 用户授权目录：' + path, path })),
    ],
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
