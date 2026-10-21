import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, rm, readFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomUUID } from "node:crypto";
import { Store } from "../src/store.mjs";
import { Workspace, canonical, applyEdit } from "../src/workspace.mjs";
import { SyncEngine, verifyEndpointMove } from "../src/sync.mjs";
import { decode, encode, hash, overrideKey } from "../src/protocol.mjs";
import { same, merge, mergePlan, emptyState } from "../src/merge.mjs";
import { previewImport, commitImport } from "../src/import.mjs";
import { loadGraph, graphPlan } from "../src/graph.mjs";
const fixture = async (name) =>
  decode(
    await readFile(
      new URL(
        `../../schedule-core/src/test/resources/sync-v2/${name}.dpc`,
        import.meta.url,
      ),
    ),
  );
const entry = (id, title = id) => ({
  id,
  title,
  day: 1,
  startMinute: 480,
  endMinute: 530,
  location: "",
});
async function database(t) {
  const dir = await mkdtemp(join(tmpdir(), "pr38-"));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const store = await new Store(dir).init();
  return Workspace.open(store, null);
}
function remote() {
  const rows = new Map();
  return {
    key: "test",
    rows,
    puts: [],
    list: async () => [...rows.keys()],
    read: async (id) => rows.get(id) ?? null,
    put: async function (id, b) {
      assert(!rows.has(id), "不可覆盖");
      this.puts.push(id);
      rows.set(id, b);
    },
  };
}
async function sync(db, r, options) {
  await new SyncEngine(db, r).synchronize(options);
}
async function edit(db, id, title) {
  await db.edit(
    { kind: "entry", action: "upsert", value: entry(id, title) },
    db.frame.revision,
  );
}

test("离线编辑重启保留；版本检查与部分文件不被采纳", async (t) => {
  const db = await database(t);
  await edit(db, "a", "离线课程");
  const restarted = await Workspace.open(
    await new Store(db.store.dir).init(),
    null,
  );
  assert.equal(restarted.frame.local.entries[0].title, "离线课程");
  assert.equal(restarted.store.identity.writerId, db.store.identity.writerId);
  await assert.rejects(
    db.edit({ kind: "entry", action: "delete", id: "a" }, 0),
    /过期/,
  );
  assert.equal(db.frame.local.entries.length, 1);
  const save = db.store.save.bind(db.store);
  db.store.save = async () => {
    throw Error("模拟磁盘失败");
  };
  await assert.rejects(edit(db, "b", "失败"));
  db.store.save = save;
  assert.equal(db.frame.local.entries.length, 1);
});
test("三个 Writer 独立记录离线编辑收敛；等价 tips 不产生无限纯合并", async (t) => {
  const a = await database(t),
    b = await database(t),
    c = await database(t),
    r = remote();
  await edit(a, "a");
  await edit(a, "b");
  await edit(a, "c");
  await sync(a, r, { create: true });
  await sync(b, r, { bind: true });
  await sync(c, r, { bind: true });
  await edit(a, "a", "A");
  await edit(b, "b", "B");
  await edit(c, "c", "C");
  await sync(a, r);
  await sync(b, r);
  await sync(c, r);
  await sync(a, r);
  await sync(b, r);
  await sync(c, r);
  for (const db of [a, b, c]) {
    assert.deepEqual(db.frame.local.entries.map((e) => e.title).sort(), [
      "A",
      "B",
      "C",
    ]);
    assert(!db.frame.pending);
  }
  const size = r.rows.size;
  await sync(a, r);
  await sync(b, r);
  assert.equal(r.rows.size, size);
});
test("真正并发分支逐对冲突，保留本地不采纳任意远端；决策 token 拒绝新分支", async (t) => {
  const db = await database(t),
    r = remote();
  await edit(db, "a", "初始");
  await sync(db, r, { create: true });
  const root = decode([...r.rows.values()][0]);
  for (const title of ["一", "二", "三"]) {
    const c = {
      ...root,
      writerId: randomUUID(),
      commitId: randomUUID(),
      parents: [root.commitId],
      state: { ...root.state, entries: [entry("a", title)] },
    };
    r.rows.set(c.commitId, encode(c));
  }
  await sync(db, r);
  assert(db.frame.conflict);
  assert.equal(db.frame.local.entries[0].title, "初始");
  const token = db.conflictToken(),
    revision = db.frame.revision;
  const engine = new SyncEngine(db, r),
    branch = {
      ...root,
      commitId: randomUUID(),
      parents: [root.commitId],
      state: {
        ...root.state,
        events: [{ id: "event", title: "新", start: 1, end: 2, note: "" }],
      },
    };
  r.rows.set(branch.commitId, encode(branch));
  await assert.rejects(engine.resolve("local", token, revision), /变化/);
  assert(db.frame.conflict);
  await sync(db, r);
  await engine.resolve("local", db.conflictToken(), db.frame.revision);
  assert.equal(db.frame.local.entries[0].title, "初始");
  assert.equal(db.frame.local.events.length, 1);
  assert(db.frame.pending);
  await sync(db, r);
  assert(!db.frame.pending);
});
test("上传中编辑继续保留；回执丢失后同路径只 GET 恢复", async (t) => {
  const db = await database(t),
    r = remote();
  await edit(db, "a", "上传前");
  const original = r.put.bind(r);
  let lost = true;
  r.put = async (id, b) => {
    await original(id, b);
    if (lost) {
      lost = false;
      await edit(db, "a", "上传期间");
      throw Error("回执丢失");
    }
  };
  await assert.rejects(sync(db, r, { create: true }), /丢失/);
  assert(db.frame.pending.attempted);
  assert.equal(db.frame.local.entries[0].title, "上传期间");
  const restarted = await Workspace.open(
    await new Store(db.store.dir).init(),
    null,
  );
  await sync(restarted, r);
  assert.equal(restarted.frame.local.entries[0].title, "上传期间");
  assert.equal(new Set(r.puts).size, r.puts.length);
  assert(!restarted.frame.pending);
  assert.equal(
    decode(r.rows.get(restarted.frame.receipt.commitId)).state.entries[0].title,
    "上传期间",
  );
});
test("已尝试但404改用新 UUID；中断路径持久回查且恢复为不同有效内容停止", async (t) => {
  const db = await database(t),
    r = remote();
  await edit(db, "a");
  const original = r.put.bind(r);
  let fail = true;
  r.put = async (id, b) => {
    if (fail) {
      fail = false;
      r.puts.push(id);
      throw Error("中断");
    }
    return original(id, b);
  };
  await assert.rejects(sync(db, r, { create: true }));
  const abandoned = decode(Buffer.from(db.frame.pending.raw, "base64"));
  await sync(db, r);
  assert(db.frame.abandoned[abandoned.commitId]);
  assert(db.frame.checkpoint.deferred.includes(abandoned.commitId));
  assert.equal(r.puts.length, 2);
  assert.notEqual(r.puts[0], r.puts[1]);
  r.rows.set(abandoned.commitId, encode({ ...abandoned, state: emptyState() }));
  await assert.rejects(sync(db, r), /不同内容/);
});
test("缺失/篡改已确认历史和目录漏列不能成功；未知损坏孤立文件持续标记", async (t) => {
  const db = await database(t),
    r = remote();
  await edit(db, "a");
  await sync(db, r, { create: true });
  const [id, bytes] = [...r.rows][0];
  r.list = async () => [];
  await sync(db, r);
  assert.equal(db.frame.graph.tips[0], id); // 漏列仍按已知 ID 回查
  r.rows.delete(id);
  await assert.rejects(sync(db, r), /缺失/);
  r.rows.set(id, encode({ ...decode(bytes), writerId: randomUUID() }));
  await assert.rejects(sync(db, r), /改变/);
});
test("跨语言固定左右分支合并与 multi-parent 完全相同", async () => {
  const b = await fixture("complex-root"),
    l = await fixture("left"),
    r = await fixture("right"),
    m = await fixture("multi-parent");
  const result = merge(b.state, l.state, r.state);
  assert.equal(result.conflicts.length, 0);
  assert(same(result.state, m.state));
});
test("来源删除、课程删除、学期、日期组合与重置遵守关联冲突", async () => {
  const base = (await fixture("complex-root")).state;
  const s = structuredClone(base);
  s.sources = [];
  s.imported = [];
  s.classOverrides = s.classOverrides.filter((e) => e.sourceId === null);
  const e = structuredClone(base);
  e.classOverrides.find((e) => e.sourceId !== null).note = "新编辑";
  assert.equal(merge(base, s, e).conflicts.length, 1);
  canonical(merge(base, s, e, "remote").state);
  canonical(merge(base, s, e, "local").state);
  const p = structuredClone(base);
  p.profiles.find((p) => p.termKey === base.sources[0].termKey).timezone =
    "Asia/Tokyo";
  assert(merge(base, p, e).conflicts.length);
  const anchor = "2026-10-19";
  applyEdit(base, {
    kind: "override",
    action: "upsert",
    sourceId: base.sources[0].id,
    seriesId: base.imported[0].seriesId,
    anchorDate: anchor,
    patch: { note: "原始" },
  });
  const d = structuredClone(base),
    reset = structuredClone(base);
  d.dayAdjustments = d.dayAdjustments
    .filter((d) => d.date !== anchor)
    .concat({ date: anchor, mode: "none", weekday: null, label: "" });
  reset.classOverrides = reset.classOverrides.filter(
    (e) => e.anchorDate !== anchor,
  );
  assert(merge(base, d, reset).conflicts.length);
  const l = structuredClone(base);
  l.sources[0].importedAt = 123n;
  assert(same(l, base));
});
test("全周和指定周覆盖 null/清空/重置及课程/来源级删除", async () => {
  const s = emptyState();
  s.entries = [entry("a")];
  applyEdit(s, {
    kind: "override",
    action: "upsert",
    sourceId: null,
    seriesId: "a",
    anchorDate: null,
    patch: { location: "", note: null },
  });
  const all = s.classOverrides[0];
  assert.equal(all.location, "");
  assert.equal(all.note, null);
  applyEdit(s, {
    kind: "override",
    action: "upsert",
    sourceId: null,
    seriesId: "a",
    anchorDate: "2026-10-19",
    patch: { date: "2026-10-20" },
  });
  assert.equal(s.classOverrides.length, 2);
  canonical(s);
  applyEdit(s, {
    kind: "override",
    action: "reset",
    sourceId: null,
    seriesId: "a",
    anchorDate: "2026-10-19",
  });
  assert.equal(s.classOverrides.length, 1);
  applyEdit(s, { kind: "entry", action: "delete", id: "a" });
  assert.equal(s.classOverrides.length, 0);
});
test("ICS 既有 WakeUp 文件和 CSV 预览、确认替换保留 sourceId 及旧覆盖", async () => {
  const state = (await fixture("complex-root")).state;
  const bytes = await readFile(
    new URL(
      "../../schedule-core/src/test/resources/schedule/wakeup.ics",
      import.meta.url,
    ),
  );
  const p = previewImport(bytes, "wakeup.ics", state);
  assert(p.occurrences.length > 100);
  const first = p.occurrences[0];
  assert.equal(first.seriesId, `${first.uid}\0${first.originalStart}`);
  assert.equal(
    first.id,
    hash(Buffer.from(first.seriesId + "\0" + first.originalStart)).toString(
      "hex",
    ),
  );
  commitImport(state, p, "导入", null, 1);
  const source = state.sources.at(-1);
  const ids = state.imported
    .filter((r) => r.sourceId === source.id)
    .map((r) => r.id);
  commitImport(state, p, "不变", source.id, 2);
  assert.deepEqual(
    state.imported.filter((r) => r.sourceId === source.id).map((r) => r.id),
    ids,
  );
  canonical(state);
  state.profiles.push({
    termKey: "2026-2027-1",
    timezone: "Asia/Shanghai",
    week1Monday: "2026-09-07",
    phases: [
      {
        effectiveFrom: "2026-09-01",
        periods: Array.from({ length: 12 }, (_, i) => ({
          periodNumber: i + 1,
          start: `${String(8 + i).padStart(2, "0")}:00`,
          end: `${String(8 + i).padStart(2, "0")}:45`,
        })),
      },
    ],
  });
  const csv = await readFile(
    new URL(
      "../../schedule-core/src/test/resources/schedule/hubei_2026-2027-1.csv",
      import.meta.url,
    ),
  );
  const preview = previewImport(csv, "湖北大学.csv", state);
  assert(preview.occurrences.length > 50);
  commitImport(state, preview, "CSV", source.id);
  canonical(state);
  assert.equal(state.sources.at(-1).id, source.id);
});
test("拒绝不支持 ICS、恶意文件名、超限、浮动与无界循环", () => {
  for (const payload of [
    "BEGIN:VCALENDAR\nVERSION:2.0\nEND:VCALENDAR",
    "BEGIN:VCALENDAR\nVERSION:2.0\nBEGIN:VEVENT\nDTSTART:20261021T080000\nDTEND:20261021T090000\nSUMMARY:x\nEND:VEVENT\nEND:VCALENDAR",
  ])
    assert.throws(() =>
      previewImport(Buffer.from(payload), "a.ics", emptyState()),
    );
  assert.throws(() =>
    previewImport(Buffer.alloc(1048577), "a.ics", emptyState()),
  );
  assert.throws(() =>
    previewImport(Buffer.from("x"), "../a.ics", emptyState()),
  );
});

test("远端成功但本地回执事务失败，重启精确回读恢复且后续编辑保留", async (t) => {
  const db = await database(t),
    r = remote();
  await edit(db, "a", "capture");
  const save = db.store.save.bind(db.store);
  let fail = true;
  db.store.save = async (name, frame) => {
    if (fail && frame.receipt && !frame.pending) {
      fail = false;
      throw Error("回执落盘失败");
    }
    return save(name, frame);
  };
  const put = r.put.bind(r);
  r.put = async (id, bytes) => {
    await put(id, bytes);
    await edit(db, "b", "上传期间编辑");
  };
  await assert.rejects(sync(db, r, { create: true }), /落盘失败/);
  assert(db.frame.pending.attempted);
  assert.equal(db.frame.local.entries.length, 2);
  const restored = await Workspace.open(
    await new Store(db.store.dir).init(),
    null,
  );
  r.put = put;
  await sync(restored, r);
  assert(!restored.frame.pending);
  assert.equal(restored.frame.local.entries.length, 2);
  assert.equal(new Set(r.puts).size, r.puts.length);
});
test("预检 GET 失败不产生 PUT 标记；进程重建仍保留同一候选", async (t) => {
  const db = await database(t),
    r = remote();
  await edit(db, "a");
  await db.tx((f) => {
    f.datasetId = randomUUID();
    db.stage(f, f.local, []);
  });
  const raw = db.frame.pending.raw;
  r.read = async () => {
    throw Error("预检断网");
  };
  await assert.rejects(sync(db, r), /断网/);
  assert.equal(db.frame.pending.attempted, false);
  assert.equal(r.puts.length, 0);
  const restored = await Workspace.open(
    await new Store(db.store.dir).init(),
    null,
  );
  assert.equal(restored.frame.pending.raw, raw);
  assert.equal(restored.frame.pending.attempted, false);
});
test("未开始下载的观察路径也在首次检查点保存；漏列仍逐个回查", async () => {
  const c = await fixture("empty"),
    a = { ...c, commitId: randomUUID(), parents: [c.commitId] },
    rows = new Map([c, a].map((c) => [c.commitId, encode(c)]));
  let saved;
  await assert.rejects(
    loadGraph(
      {
        list: async () => [...rows.keys()],
        read: async () => {
          throw Error("不应到达");
        },
      },
      {},
      async (deferred, verified) => {
        saved = { deferred, ...verified };
        throw Error("模拟进程退出");
      },
    ),
    /退出/,
  );
  assert.equal(saved.observed.length, 2);
  const reads = [];
  const graph = await loadGraph(
    {
      list: async () => [],
      read: async (id) => {
        reads.push(id);
        return rows.get(id);
      },
    },
    saved,
  );
  assert.equal(reads.length, 2);
  assert.equal(graph.tips[0], a.commitId);
});
test("不能证明等价的多重共同基线安全停止", async () => {
  const root = await fixture("empty"),
    left = {
      ...root,
      commitId: randomUUID(),
      parents: [root.commitId],
      state: { ...root.state, entries: [entry("a", "左")] },
    },
    right = {
      ...root,
      commitId: randomUUID(),
      parents: [root.commitId],
      state: { ...root.state, entries: [entry("a", "右")] },
    };
  const parents = [left.commitId, right.commitId].sort(),
    a = { ...left, commitId: randomUUID(), parents },
    b = { ...right, commitId: randomUUID(), parents };
  const rows = new Map(
    [root, left, right, a, b].map((c) => [c.commitId, encode(c)]),
  );
  await assert.rejects(
    loadGraph({
      list: async () => [...rows.keys()],
      read: async (id) => rows.get(id),
    }),
    /基线歧义/,
  );
});
test("同一数据集迁移端点必须证明全部历史，保留离线编辑和精确待发布输入", async (t) => {
  const db = await database(t),
    r = remote();
  await edit(db, "a", "原始");
  await sync(db, r, { create: true });
  await edit(db, "a", "离线");
  await db.tx((f) => db.stage(f, f.local, f.frontier));
  const before = db.snapshot(),
    moved = await verifyEndpointMove(db, { ...r, key: "new endpoint" });
  assert.equal(moved.datasetId, before.datasetId);
  assert.equal(db.frame.pending.raw, before.pending.raw);
  assert(same(db.frame.local, before.local));
  const other = await fixture("empty");
  await assert.rejects(
    verifyEndpointMove(db, {
      ...r,
      list: async () => [other.commitId],
      read: async () => encode(other),
    }),
    /改变|dataset|身份/,
  );
  const missing = { ...r, list: async () => [], read: async () => null };
  await assert.rejects(verifyEndpointMove(db, missing), /缺失/);
  assert.equal(db.frame.pending.raw, before.pending.raw);
});
