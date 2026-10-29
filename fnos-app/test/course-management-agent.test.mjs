import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, rm, readFile, chmod, symlink } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomUUID } from "node:crypto";
import http from "node:http";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import { Client } from "@modelcontextprotocol/client";
import { StdioClientTransport } from "@modelcontextprotocol/client/stdio";
import { createApp, PREFIX } from "../src/server.mjs";
import { callOperation } from "../src/agent-client.mjs";
import { AgentService } from "../src/agent-service.mjs";
import { operations, describeOperations } from "../src/operations.mjs";
const operationId = () => `${Date.now()}-${randomUUID()}`;
async function fixture(t, options = {}) {
  const dir = await mkdtemp(join(tmpdir(), "dp-agent-"));
  const exports = await mkdtemp(join(tmpdir(), "dp-agent-exports-"));
  const app = await createApp({
    dataDir: dir,
    configPath: join(dir, "webdav.json"),
    authorizedRoots: [{ id: "test", name: "独立验收目录", path: exports }],
    ...options,
  });
  const socket = (name) =>
    process.platform === "win32"
      ? "\\\\.\\pipe\\dp-" + randomUUID()
      : join(dir, name);
  const socketPath = socket("agent.sock"),
    web = socket("app.sock");
  await new Promise((r) => app.agentServer.listen(socketPath, r));
  await new Promise((r) => app.server.listen(web, r));
  if (process.platform !== "win32") await chmod(socketPath, 0o600);
  t.after(async () => {
    app.agentServer.closeAllConnections();
    app.server.closeAllConnections();
    await Promise.all([
      new Promise((r) => app.agentServer.close(r)),
      new Promise((r) => app.server.close(r)),
    ]);
    await app.backups.close();
    await rm(dir, { recursive: true, force: true });
    await rm(exports, { recursive: true, force: true });
  });
  const call = (name, input = {}, options = {}) =>
    callOperation(name, input, { socketPath, ...options });
  const context = () => ({
    workspaceId: app.catalog.manifest.activeId,
    datasetId: app.db.frame.datasetId,
    version: app.db.frame.revision,
    catalogVersion: app.catalog.manifest.revision,
  });
  const webCall = (method, path, data, headers = {}) =>
    new Promise((resolve, reject) => {
      const req = http.request(
        {
          socketPath: web,
          path: PREFIX + path,
          method,
          headers: {
            "x-trim-userid": "1000",
            "x-trim-isadmin": "true",
            "Content-Type": "application/json",
            ...headers,
          },
        },
        (res) => {
          const chunks = [];
          res.on("data", (c) => chunks.push(c));
          res.on("end", () =>
            resolve({
              status: res.statusCode,
              data: JSON.parse(Buffer.concat(chunks)),
            }),
          );
        },
      );
      req.on("error", reject);
      req.end(data ? JSON.stringify(data) : undefined);
    });
  const csrf = (await webCall("GET", "/api/schedule")).data.csrf;
  const admin = (data) =>
    webCall("POST", "/api/agent", data, { "X-DormPanel-CSRF": csrf });
  const grant = async (permissions = ["edit", "admin", "maintenance"]) => {
    assert.equal(
      (await admin({ action: "permissions", permissions })).status,
      200,
    );
  };
  const write = (name, input = {}, options = {}) =>
    call(
      name,
      { ...context(), ...input },
      { operationId: operationId(), ...options },
    );
  return {
    app,
    dir,
    socketPath,
    call,
    context,
    webCall,
    csrf,
    admin,
    grant,
    write,
  };
}

test("课程 Agent 默认只读、权限、同一预览哈希、回执与过期拒绝", async (t) => {
  const f = await fixture(t);
  await f.app.db.edit({
    kind: "entry",
    action: "upsert",
    value: {
      id: "x",
      title: "数学",
      day: 1,
      startMinute: 480,
      endMinute: 540,
      location: "",
    },
  });
  f.app.db.frame.local.sharedRecords = [];
  f.app.db.frame.v3 = { binding: { format: 4 } };
  const edit = {
    target: { sourceId: null, series: ["x"] },
    common: { teacher: "老师" },
    colors: [{ seriesId: "x", rgb: 0x336699 }],
  };
  const context = f.context();
  const preview = await f.call("courses.preview", { ...context, edit });
  assert(preview.ok, JSON.stringify(preview));
  assert.equal(preview.data.localSaved, undefined);
  const id = operationId(),
    request = { ...context, edit, previewHash: preview.data.businessHash };
  assert.equal(
    (await f.call("courses.save", request, { operationId: id })).error.code,
    "PERMISSION",
  );
  assert.equal(
    (await f.webCall("POST", "/api/courses/save", request)).status,
    403,
  );
  await f.grant(["read", "edit"]);
  const saved = await f.call("courses.save", request, { operationId: id });
  assert(saved.ok, JSON.stringify(saved));
  assert.equal(saved.data.businessHash, preview.data.businessHash);
  assert.deepEqual(
    await f.call("courses.save", request, { operationId: id }),
    saved,
  );
  assert.deepEqual(
    await f.call("operations.receipt", { operationId: id }),
    saved,
  );
  assert.equal(
    (await f.call("courses.save", request, { operationId: operationId() }))
      .error.code,
    "STALE",
  );
  const rows = await f.call("courses.list", {
    workspaceId: context.workspaceId,
  });
  assert(rows.ok);
  assert.equal(rows.data.rows[0].color.rgb, 0x336699);
  assert.equal(
    (
      await f.call("courses.preview", {
        ...f.context(),
        edit: { ...edit, state: {} },
      })
    ).ok,
    false,
  );
});

test("真实 Catalog DPV3/4 绑定后的 HTTP/Agent 课程事务", async (t) => {
  const { openV3Workspace } = await import("../src/v3-workspace.mjs"),
    { V3Engine } = await import("../src/v3-engine.mjs"),
    { emptyState } = await import("../src/merge.mjs");
  const objects = new Map(),
    remote = {
      read: async (p) => objects.get(p) ?? null,
      putOnce: async (p, b) => {
        assert(!objects.has(p));
        objects.set(p, Buffer.from(b));
      },
      list: async (scope) =>
        [
          ...new Set(
            [...objects.keys()]
              .filter((p) => p.startsWith(scope))
              .map((p) => {
                const tail = p.slice(scope.length),
                  i = tail.indexOf("/");
                return scope + (i < 0 ? tail : tail.slice(0, i + 1));
              }),
          ),
        ].sort(),
    };
  const root = await mkdtemp(join(tmpdir(), "pr71-seed-")),
    seed = await openV3Workspace(root, () => {});
  t.after(async () => {
    await seed.journal.close();
    await rm(root, { recursive: true, force: true });
  });
  const binding = await new V3Engine(seed.journal, seed.writerId).initialize(
    remote,
    {
      ...emptyState(),
      entries: [
        {
          id: "x",
          title: "原课程",
          day: 1,
          startMinute: 480,
          endMinute: 540,
          location: "A",
        },
      ],
      sharedRecords: [],
    },
  );
  const f = await fixture(t, { v3RemoteFactory: () => remote });
  await f.grant();
  const connection = await f.write("connections.save", {
    name: "隔离网络",
    type: "webdav",
    url: "https://isolated.test/",
    username: "test",
    password: "test",
  });
  assert(connection.ok);
  const added = await f.write("workspaces.add", {
    name: "DPV4",
    protocol: "v3",
    connectionId: connection.data.connectionId,
  });
  assert(added.ok);
  assert(
    (
      await f.write("workspaces.switch", {
        targetId: added.data.resultWorkspaceId,
      })
    ).ok,
  );
  await f.write("sync.bind", {
    descriptorPath: binding.descriptorPath,
    descriptorHash: binding.descriptorHash,
  });
  for (let n = 0; n < 5; n++) await f.write("sync.run");
  assert.equal(f.app.db.frame.v3.binding.format, 4);
  const edit = {
    target: { sourceId: null, series: ["x"] },
    common: { teacher: "真实事务" },
    colors: [{ seriesId: "x", rgb: 0x123456 }],
  };
  const before = f.context(),
    preview = await f.call("courses.preview", { ...before, edit });
  assert(preview.ok, JSON.stringify(preview));
  const saved = await f.write("courses.save", {
    edit,
    previewHash: preview.data.businessHash,
  });
  assert(saved.ok, JSON.stringify(saved));
  assert.equal(saved.data.businessHash, preview.data.businessHash);
  assert.equal(f.context().version, before.version + 1);
  const http = await f.webCall(
    "GET",
    "/api/courses?workspaceId=" + before.workspaceId,
  );
  assert.equal(http.status, 200);
  assert.equal(http.data.businessHash, saved.data.businessHash);
  assert.equal(http.data.rows[0].color.rgb, 0x123456);
  const beforeFault = f.context(),
    hashBefore = http.data.businessHash;
  const failing = {
    target: { sourceId: null, series: ["x"] },
    common: { note: "不得部分保存" },
    colors: [{ seriesId: "x", rgb: null }],
  };
  const candidate = await f.call("courses.preview", {
    ...beforeFault,
    edit: failing,
  });
  assert(candidate.ok);
  f.app.db.activeV3.journal.meta.fault = (point) => {
    if (point === "before-commit") throw Error("injected commit rollback");
  };
  const failure = await f.write("courses.save", {
    edit: failing,
    previewHash: candidate.data.businessHash,
  });
  assert.equal(failure.ok, false);
  f.app.db.activeV3.journal.meta.fault = null;
  assert.equal(f.context().version, beforeFault.version);
  const unchanged = await f.call("courses.list", {
    workspaceId: beforeFault.workspaceId,
  });
  assert.equal(unchanged.data.businessHash, hashBefore);
  assert.equal(unchanged.data.rows[0].color.rgb, 0x123456);
});
