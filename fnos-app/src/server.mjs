import http from "node:http";
import { Readable } from "node:stream";
import { AgentService } from "./agent-service.mjs";
import { HistoryManager, cachedGraph, historySummary, commitAt, versionDifferences } from './history.mjs';
import { DatasetMigration } from './migration.mjs';
import { BackupManager, recoverRestore } from "./backup.mjs";
import { readFile, writeFile, chmod, unlink } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { randomBytes, timingSafeEqual, randomUUID, createHash } from "node:crypto";
import { Store, json } from "./store.mjs";
import { Catalog } from "./catalog.mjs";
import { date, ensure, zone } from "./protocol.mjs";
import { projectWeek, monday, addDays } from "./projection.mjs";
import { mergePlan } from "./merge.mjs";
import { previewImport, commitImport } from "./import.mjs";

export const PREFIX = "/app/dormpanel-schedule-manager";
export function validatePageOrigin(req, value) {
  const origin = new URL(value), host = new URL('http://' + req.headers.host);
  // fnOS 公网入口可能改写 Host。仅浏览器同源 fetch 的不可脚本设置元数据
  // 允许此代理场景；不信任 Forwarded/X-Forwarded-*，不接受跨源引导。
  const sameOriginFetch = req.headers['sec-fetch-site'] === 'same-origin' &&
    ['cors', 'same-origin'].includes(req.headers['sec-fetch-mode']);
  ensure(!['cross-site', 'same-site'].includes(req.headers['sec-fetch-site']) &&
    origin.origin === value && ['http:', 'https:'].includes(origin.protocol) &&
    !origin.username && !origin.password &&
    (!req.headers.origin || req.headers.origin === value) &&
    (origin.hostname === host.hostname || sameOriginFetch), '页面来源不匹配');
}
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
  // 网关 TLS 终止，不信任 Forwarded；引导 token 绑定完整 Origin，旧调用回退到 Host 检查。
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
  await recoverRestore(dataDir, configPath);
  const store = await new Store(dataDir).init();
  let catalog = await Catalog.open({ store, configPath, remoteFactory, authorizedRoots });
  const backups = new BackupManager({ catalog, configPath, reopen: async () => {
    catalog = await Catalog.open({ store, configPath, remoteFactory, authorizedRoots }); backups.catalog = catalog;
    previews.clear(); tokens.clear(); history.tokens.clear();
  } });
  await backups.cleanupStages();
  let requests = 0, writes = 0, historyLock = false;
  const history = new HistoryManager(catalog);
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
  let agents;
  const handle = async (req, res, internal = false) => {
    requests++; let writing = false;
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
        user = internal ? 'agent-package-os' : authorize(req);
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
          '/api/agent', '/api/import/cancel',
          '/api/migration', '/api/migration/verify',
          '/api/history/check', '/api/history/preview', '/api/history/rollback', '/api/history/archive', '/api/history/compact',
          "/api/backups/create", "/api/backups/list", "/api/backups/verify", "/api/backups/restore", "/api/backups/cancel",
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
          if (!internal) csrf(
            req,
            userToken(user, req.headers.origin ?? null),
            req.headers.origin ?? null,
          );
        } catch {
          send(403, { error: "CSRF 或同源校验失败" });
          return;
        }
        const input = await body(req);
        if (path === '/api/agent') { ensure(!internal, 'Agent 不能批准自身'); send(200, await agents.admin(input, user)); return; }
        writing = true; writes++;
        ensure(!catalog.maintenance, '正在备份恢复；请稍后重试');
        ensure(!historyLock, '历史操作进行中，请稍后重试');
        if (path.startsWith('/api/backups/')) {
          if (path.endsWith('/restore')) ensure(!catalog.migrations.some(m => m.phase !== 'cancelled'), '换库保护期间及退役后禁止恢复旧应用备份覆盖迁移证据');
          ensure(writes === 1 && !catalog.configuring, '存在其他写入操作，请稍后重试');
          ensure(input.catalogVersion === catalog.manifest.revision, '管理版本过期');
          const action = path.split('/').pop();
          const result = ['verify', 'restore', 'cancel'].includes(action) ? await backups[action](input, user) : await backups[action](input);
          send(200, result); return;
        }
        const workspaceId = input.workspaceId;
        const db = await catalog.load(workspaceId);
        if (
          !Number.isSafeInteger(input.version) ||
          input.version !== db.frame.revision
        ) {
          send(409, { error: "请求版本过期，请重新读取" });
          return;
        }
        ensure(!catalog.configuring && !catalog.manifest.transition, "正在保存配置或等待恢复");
        const network = action => catalog.network(workspaceId, action);
        if (path === '/api/migration' || path === '/api/migration/verify') {
          ensure(writes === 1 && !catalog.busy.size && input.catalogVersion === catalog.manifest.revision && workspaceId === catalog.manifest.activeId, '迁移版本过期或有并发操作');
          historyLock = true;
          try {
            const migration = new DatasetMigration(catalog);
            if (path.endsWith('/verify')) {
              const result = await migration.verify(db, { ...input, sourceId:workspaceId });
              send(200, { verified:true, sourceDatasetId:result.source.datasetId, targetDatasetId:result.target.datasetId, sourceTips:result.source.tips, targetTips:result.target.tips, archive:result.archive.relative, candidate:result.candidate.relative, targetUrl:result.targetUrl });
            } else send(200, publicValue(await migration.action(db, input, user)));
            return;
          }
          finally { historyLock = false; }
        }
        if (path.startsWith('/api/history/')) {
          ensure(writes === 1 && !catalog.busy.size && input.catalogVersion === catalog.manifest.revision && workspaceId === catalog.manifest.activeId, '历史操作版本过期或有并发操作');
          ensure(!catalog.restoreGuards[workspaceId], '恢复异常需先检查并同步');
          history.catalog = catalog;
          historyLock = true;
          try {
            let result;
            // 未绑定工作区也直接进入持久观察，避免预探测列举后再列举时遗漏新路径。
            // 逐提交的 validateDataset 仍由 observe 调用，身份去重不放宽。
            await catalog.network(workspaceId, async engine => {
              if (path.endsWith('/check')) { await engine.observe(); return; }
              if (path.endsWith('/preview')) result = await history.preview(db, engine, input, user);
              else if (path.endsWith('/rollback')) result = await history.rollback(db, engine, input, user);
              else result = await history.archive(db, engine, input, path.endsWith('/compact'));
            }, { probeIdentity: false });
            // 网络包装器结束后绑定入口 revision；确认内部的观察增量单独验证。
            if (result?.token) { const t = history.tokens.get(result.token); t.fingerprint = history.fingerprint(db, t.graph); t.version = db.frame.revision; }
            send(200, publicValue({ ...result, version: db.frame.revision })); return;
          } finally { historyLock = false; }
        }
        else if (path === "/api/edit") await db.edit(input.operation, input.version);
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
            replacements: db.frame.local.sources.map(source => {
              const old = db.frame.local.imported.filter(row => row.sourceId === source.id);
              const identities = new Set(preview.occurrences.map(row => row.seriesId + '\0' + row.originalStart));
              return { sourceId: source.id, count: old.length, unmatchedTitles: [...new Set(old.filter(row => !identities.has(row.seriesId + '\0' + row.originalStart)).map(row => row.title))] };
            }),
          });
          return;
        } else if (path === "/api/import/cancel") {
          const p = previews.get(input.id);
          ensure(p && p.user === user && p.workspaceId === workspaceId, '导入预览已失效');
          previews.delete(input.id);
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
      if (path.startsWith('/api/') && !['/api/maintenance', '/api/agent', '/api/import/rows', '/api/schedule/range', '/api/migration', '/api/history', '/api/history/snapshot', '/api/history/diff', '/api/history/preview-diff', '/api/schedule', '/api/state', '/api/conflicts', '/api/manage', '/api/directories'].includes(path)) {
        send(404, { error: '不存在' }); return;
      }
      ensure(!catalog.maintenance, '正在备份恢复；请稍后重试');
      const workspaceId = u.searchParams.get("workspaceId") ??
        (path === "/api/schedule" ? catalog.manifest.activeId : null);
      const db = path.startsWith('/api/') && !['/api/agent', '/api/manage', '/api/directories'].includes(path) ? await catalog.load(workspaceId) : null;
      if (path === '/api/agent') { ensure(!internal, 'Agent 不能读取管理员审批通道'); send(200, agents.adminSummary()); return; }
      if (path === '/api/import/rows') {
        const p = previews.get(u.searchParams.get('id')), offset = Number(u.searchParams.get('offset') ?? 0), limit = Number(u.searchParams.get('limit') ?? 40);
        ensure(p && p.user === user && p.workspaceId === workspaceId && p.expires > Date.now() && p.version === db.frame.revision, '导入预览已失效');
        ensure(Number.isSafeInteger(offset) && offset >= 0 && Number.isSafeInteger(limit) && limit >= 1 && limit <= 100, '预览分页无效');
        send(200, { version:db.frame.revision, total:p.preview.occurrences.length, rows:p.preview.occurrences.slice(offset, offset + limit), next:offset + limit < p.preview.occurrences.length ? offset + limit : null }); return;
      }
      if (path === '/api/maintenance') {
        const protection = catalog.protection(workspaceId), restored = await store.read('restore-result.json');
        send(200, { workspaceId, version:db.frame.revision, catalogVersion:catalog.manifest.revision, datasetId:db.frame.datasetId, restoreGuard:!!catalog.restoreGuards[workspaceId], restorePoisoned:backups.poisoned, restorePreviews:backups.previews.size, lastRestore:restored ? { ok:restored.ok, restoredAt:restored.restoredAt, backupCreatedAt:restored.backupCreatedAt } : null, protection:protection ? { id:protection.id, phase:protection.phase, sourceId:protection.sourceId, targetId:protection.targetId } : null, historyCount:Object.keys(db.frame.checkpoint.hashes ?? {}).length, historyCapacity:256, automaticDelete:false, reclaimedBytes:0 }); return;
      }
      const row = db ? catalog.row(workspaceId) : null;
      const c = row?.connectionId ? catalog.connection(row.connectionId) : null;
      const busy = catalog.busy.has(workspaceId);
      if (path === '/api/migration') { ensure(workspaceId === catalog.manifest.activeId, '迁移只可访问当前工作区'); send(200, publicValue(new DatasetMigration(catalog).summary(workspaceId))); return; }
      if (path.startsWith('/api/history')) {
        ensure(workspaceId === catalog.manifest.activeId, '历史只可访问当前工作区');
        history.catalog = catalog;
        const g = await cachedGraph(db.frame);
        let result;
        if (path === '/api/history') {
          result = historySummary(db, g);
          if (catalog.protection(workspaceId)) { result.blockers.push('迁移保护 / 退役工作区只读；从换库向导继续'); result.rollbackEnabled = false; }
          if (catalog.restoreGuards[workspaceId]) { result.blockers.push('恢复后尚未验证远端历史，请先检查并同步'); result.rollbackEnabled = false; }
        }
        else if (path.endsWith('/preview-diff')) result = history.previewDiff(db, { workspaceId, token:u.searchParams.get('token'), offset:Number(u.searchParams.get('offset') ?? 0) }, user);
        else if (path.endsWith('/diff')) result = versionDifferences(g, u.searchParams.get('from'), u.searchParams.get('to'), Number(u.searchParams.get('offset') ?? 0));
        else {
          const state = commitAt(g, u.searchParams.get('id')).state, table = u.searchParams.get('table') ?? 'entries', offset = Number(u.searchParams.get('offset') ?? 0);
          ensure(Object.hasOwn(state, table) && Number.isSafeInteger(offset) && offset >= 0, '快照分页无效');
          result = { table, total: state[table].length, rows: state[table].slice(offset, offset + 40), next: offset + 40 < state[table].length ? offset + 40 : null };
        }
        send(200, publicValue({ ...result, version: db.frame.revision })); return;
      }
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
      if (path === '/api/schedule/range') {
        const start = date(u.searchParams.get('start')).toString(), end = date(u.searchParams.get('end')).toString(), z = zone(u.searchParams.get('zone') ?? 'Asia/Shanghai');
        const days = date(start).until(date(end)).days;
        ensure(days >= 0 && days <= 30, '日期范围须为 1–31 天');
        const rows = [];
        for (let first = monday(start); first <= end; first = addDays(first, 7)) rows.push(...projectWeek(db.frame.local, first, z).filter(r => r.date >= start && r.date <= end).map(r => ({ ...r, recordType:'course' })));
        // 与周投影使用同一时区，事件保留原毫秒时间与稳定 ID。
        const from = date(start).toZonedDateTime(z).epochMilliseconds, to = date(addDays(end, 1)).toZonedDateTime(z).epochMilliseconds;
        rows.push(...db.frame.local.events.filter(r => Number(r.end) > from && Number(r.start) < to).map(r => ({ ...r, recordType:'event' })));
        rows.sort((a,b) => Number(a.start) - Number(b.start) || a.id.localeCompare(b.id));
        const offset = Number(u.searchParams.get('offset') ?? 0), limit = Number(u.searchParams.get('limit') ?? 40);
        ensure(Number.isSafeInteger(offset) && offset >= 0 && Number.isSafeInteger(limit) && limit >= 1 && limit <= 100, '分页无效');
        // IPC 在统一服务分页；网页调用也必须有界。
        send(200, { workspaceId, version:db.frame.revision, ...(internal ? { rows } : { total:rows.length, rows:rows.slice(offset, offset+limit), next:offset+limit < rows.length ? offset+limit : null }) }); return;
      }
      if (path === "/api/schedule") {
        const pageOrigin = u.searchParams.get("origin");
        if (pageOrigin) validatePageOrigin(req, pageOrigin);
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
          (catalog.restoreGuards[workspaceId] ? '恢复后等待只读验证远端身份和历史；验证前禁止发布' : null) ?? f.error ??
          (c && f.bindingKey && f.bindingKey !== catalog.remote(c).key
            ? "配置与本地绑定不一致；请显式验证同一数据集地址迁移或恢复原配置"
            : null);
        send(
          200,
          publicValue({
            workspaceId,
            workspaceName: row.name,
            writeProtection: catalog.protection(workspaceId) ? '换库保护 / 退役工作区只读；请在历史与版本继续或取消迁移' : null,
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
        "/feedback.js": ["feedback.js", "text/javascript; charset=utf-8"],
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
    } finally {
      if (writing) writes--; requests--;
      if (requests === 0) catalog.prune();
    }
  };
  const dispatch = async (method, path, input) => {
    const query = method === 'GET' ? '?' + new URLSearchParams(Object.entries(input).filter(([,v]) => v != null).map(([k,v]) => [k, String(v)])) : '';
    const req = Readable.from(method === 'POST' ? [Buffer.from(json(input))] : []);
    Object.assign(req, { method, url:PREFIX + path + query, headers:{ 'content-type':'application/json' }, socket:{}, rawHeaders:[] });
    let status, data;
    await handle(req, { writeHead:s => { status = s; }, end:b => { data = JSON.parse(b); } }, true);
    return { status, data };
  };
  agents = await AgentService.open({ store, catalog:() => catalog, dispatch, approvalSummary:async (operation, input) => {
    if (operation === 'backups.restore') {
      const p = backups.previews.get(input.token);
      ensure(p && p.user === 'agent-package-os' && p.expires > Date.now(), '恢复预览已失效');
      return { createdAt:p.manifest.createdAt, appVersion:p.manifest.appVersion, workspaceCount:p.manifest.workspaceCount, fileCount:p.manifest.files.length, dependencies:p.manifest.dependencies, scope:'完整应用状态覆盖；恢复后重新验证外部历史' };
    }
    if (operation === 'history.rollback') {
      const p = history.tokens.get(input.token);
      ensure(p && p.user === 'agent-package-os' && p.target === input.target && p.workspaceId === input.workspaceId && p.expires > Date.now(), '回退预览已失效');
      return { target:p.target, graph:p.graph, version:p.version, scope:'发布新的完整回退快照，保留旧历史' };
    }
    return { scope:operation.startsWith('migration.') ? '按现有迁移保护验证执行；设备声明仅为管理员登记，非自动发现证明' : '输出至已授权目录，不删除旧库' };
  } });
  const agentServer = agents.createServer();
  const server = http.createServer((req, res) => handle(req, res));
  server.once('close', () => { void backups.close(); });
  server.requestTimeout = 65000;
  server.headersTimeout = 10000;
  return { server, agentServer, agents, refresh, store, backups, get catalog() { return catalog; }, get db() { return catalog.db(catalog.manifest.activeId); } };
}
if (process.argv[1] === fileURLToPath(import.meta.url)) {
  process.umask(0o077);
  const socket = process.env.SOCKET_PATH;
  ensure(
    socket && process.env.TRIM_PKGVAR && process.env.TRIM_PKGETC && process.env.DORMPANEL_WRITER_LOCK === '1',
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
  const agentSocket = join(dirname(socket), 'agent.sock');
  // 同一进程、同一启动锁；新 Socket 不出现在 fnOS gateway 配置。
  await unlink(agentSocket).catch(e => { if (e.code !== 'ENOENT') throw e; });
  await new Promise((resolve, reject) => { app.agentServer.once('error', reject); app.agentServer.listen(agentSocket, resolve); });
  await chmod(agentSocket, 0o600);
  const ready = join(process.env.TRIM_PKGVAR, "socket.ready");
  await writeFile(ready, String(process.pid), { mode: 0o600 });
  process.on("SIGTERM", () => {
    app.agentServer.close();
    app.agentServer.closeAllConnections();
    app.server.close(async () => {
      await app.backups.close();
      await unlink(agentSocket).catch(() => {});
      await unlink(socket).catch(() => {});
      await unlink(ready).catch(() => {});
      process.exit(0);
    });
    setTimeout(() => process.exit(1), 5000).unref();
  });
}
