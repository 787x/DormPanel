// 仅供已获应用用户/root 信任的隔离实机诊断，不是认证测试或浏览器认证替代品。
import http from "node:http";
import assert from "node:assert/strict";
const [run, phase] = process.argv.slice(2);
assert.match(run ?? "", /^[0-9a-f-]{36}$/);
const prefix = "/app/dormpanel-schedule-manager",
  socketPath =
    process.env.SOCKET_PATH ??
    "/var/apps/dormpanel-schedule-manager/target/app.sock";
async function call(method, path, body, token) {
  return new Promise((resolve, reject) => {
    const req = http.request(
      {
        socketPath,
        path: prefix + path,
        method,
        headers: {
          "X-Trim-Userid": "1000",
          "X-Trim-Isadmin": "true",
          "Content-Type": "application/json",
          ...(token ? { "X-DormPanel-CSRF": token } : {}),
        },
      },
      (res) => {
        let text = "";
        res.on("data", (b) => (text += b));
        res.on("end", () => {
          try {
            const v = JSON.parse(text);
            if (res.statusCode !== 200) reject(Error(v.error));
            else resolve(v);
          } catch (e) {
            reject(e);
          }
        });
      },
    );
    req.on("error", reject);
    req.end(body ? JSON.stringify(body) : undefined);
  });
}
const config = await call("GET", "/api/config");
assert(
  new URL(config.url).pathname.endsWith(`/pr38-e2e-${run}/`),
  "只允许明确的 PR38 隔离目录",
);
let view = await call("GET", "/api/schedule"),
  state = await call("GET", "/api/state");
assert.equal(view.datasetId, run);
const post = (path, data = {}) =>
  call("POST", path, { version: state.version, ...data }, view.csrf);
if (phase === "label")
  await post("/api/edit", {
    operation: {
      kind: "date",
      action: "upsert",
      value: { ...state.local.dayAdjustments[0], label: "FPK fnOS" },
    },
  });
else if (phase === "prepare-conflict")
  await post("/api/edit", {
    operation: {
      kind: "entry",
      action: "upsert",
      value: {
        ...state.local.entries.find((e) => e.id === "local"),
        location: "conflict-fpk",
      },
    },
  });
else if (phase === "sync") await post("/api/sync");
else if (phase === "resolve-local") {
  const c = await call("GET", "/api/conflicts");
  await post("/api/resolve", { token: c.token, choice: "local" });
} else assert.equal(phase, "status", "未知隔离操作");
view = await call("GET", "/api/schedule");
state = await call("GET", "/api/state");
console.log(
  JSON.stringify({
    phase,
    writerId: view.writerId,
    datasetId: view.datasetId,
    status: view.status,
    dirty: view.dirty,
    pending: view.pending,
    published: view.published,
    conflict: view.conflict,
    entries: state.local.entries.map((e) => ({
      id: e.id,
      title: e.title,
      location: e.location,
    })),
    events: state.local.events.map((e) => e.note),
    dates: state.local.dayAdjustments,
    tips: view.tips,
  }),
);
