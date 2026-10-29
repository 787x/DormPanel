import test from "node:test";
import assert from "node:assert/strict";
import { Workspace } from "../src/workspace.mjs";
import { courses, previewCourse } from "../src/course-management.mjs";
import { courseEditSchema } from "../src/operations.mjs";
import { emptyState } from "../src/merge.mjs";
import { businessHash } from "../src/v3-protocol.mjs";
const fixture = () =>
  new Workspace(
    {},
    {
      revision: 1,
      datasetId: null,
      local: {
        ...emptyState(),
        entries: [
          {
            id: "a",
            title: "数学",
            day: 1,
            startMinute: 480,
            endMinute: 540,
            location: "",
          },
          {
            id: "b",
            title: "数学",
            day: 1,
            startMinute: 480,
            endMinute: 540,
            location: "",
          },
        ],
        sharedRecords: [],
      },
      v3: { binding: { format: 4 } },
    },
  );
test("逻辑列表稳定身份、分页和旧格式能力", () => {
  const w = fixture(),
    p = courses(w, { limit: 1 });
  assert.equal(p.total, 2);
  assert.equal(p.next, 1);
  assert.notEqual(p.rows[0].key, courses(w, { offset: 1 }).rows[0].key);
  assert.throws(() => courses(w, { limit: 101 }));
  w.frame.v3.binding.format = 3;
  assert.equal(courses(w).rows[0].editable, false);
});
test("业务预览只读，不返回完整状态，颜色明确且预览过期拒绝", () => {
  const w = fixture(),
    before = businessHash(w.frame.local),
    edit = courseEditSchema.parse({
      target: { sourceId: null, series: ["a"] },
      common: { teacher: "老师" },
      colors: [{ seriesId: "a", rgb: null }],
    });
  const p = previewCourse(w, edit, 1);
  assert.equal(p.state, undefined);
  assert.equal(businessHash(w.frame.local), before);
  assert.equal(p.colors[0].rgb, null);
  assert.notEqual(p.businessHash, before);
  assert.throws(() => previewCourse(w, edit, 0));
  assert.throws(() => courseEditSchema.parse({ ...edit, state: {} }));
});

import { merge } from "../src/merge.mjs";
import { change } from "../src/shared-course-colors.mjs";
import { scheduleRecord } from "../src/shared-course-schedule.mjs";
import { durableRecords } from "../src/shared-records.mjs";
test("独立课程、教师与颜色合并；颜色竞争和时段删除编辑冲突", () => {
  const w = fixture(),
    base = w.frame.local;
  const a = w.courseEditCandidate(base, {
      target: { sourceId: null, series: ["a"] },
      common: { teacher: "教师" },
    }),
    b = change(base, null, "a", 0x123456);
  assert.equal(merge(base, a, b).conflicts.length, 0);
  assert.equal(
    merge(
      base,
      change(base, null, "a", 0x111111),
      change(base, null, "a", 0x222222),
    ).conflicts.length,
    1,
  );
  const other = w.courseEditCandidate(base, {
    target: { sourceId: null, series: ["b"] },
    common: { note: "独立" },
  });
  assert.equal(merge(base, a, other).conflicts.length, 0);
  const slot = {
    id: "s",
    sourceId: null,
    ownerSeries: "a",
    replacesSeries: null,
    week1: "2026-09-07",
    weeks: [1, 3],
    weekday: 2,
    startMinute: 480,
    endMinute: 540,
    periodStart: 0,
    periodEnd: 0,
    enabled: true,
    title: "数学",
    location: "A",
    teacher: "",
    note: "",
  };
  const withSlot = {
    ...base,
    sharedRecords: durableRecords([scheduleRecord(slot)]),
  };
  assert.equal(
    merge(withSlot, base, {
      ...base,
      sharedRecords: durableRecords([
        scheduleRecord({ ...slot, location: "B" }),
      ]),
    }).conflicts.length,
    1,
  );
});

test("同来源原始身份归组，跨来源、歧义系列与手动身份独立；包含停用规则", async () => {
  const { readFile } = await import("node:fs/promises"),
    { decode } = await import("../src/protocol.mjs");
  const state = decode(
    await readFile(
      new URL(
        "../../schedule-core/src/test/resources/sync-v2/complex-root.dpc",
        import.meta.url,
      ),
    ),
  ).state;
  const r = state.imported[0],
    source = state.sources.find((s) => s.id === r.sourceId);
  state.sources = [source, { ...source, id: "other-source" }];
  state.classOverrides = [];
  state.dayAdjustments = [];
  state.entries = ["local-a", "local-b"].map((id) => ({
    id,
    title: "数学",
    day: 1,
    startMinute: 480,
    endMinute: 540,
    location: "",
  }));
  state.imported = [
    ["raw-a", "A", r.sourceId, "数学"],
    ["raw-b", "B", r.sourceId, "数学"],
    ["raw-c", "C", r.sourceId, "数学"],
    ["raw-c2", "C", r.sourceId, "不同原名"],
    ["raw-d", "D", "other-source", "数学"],
  ].map(([id, seriesId, sourceId, title]) => ({
    ...r,
    id,
    seriesId,
    sourceId,
    title,
  }));
  state.sources = state.sources.map((source) => {
    const rows = state.imported.filter((r) => r.sourceId === source.id);
    return {
      ...source,
      occurrenceCount: rows.length,
      firstStart: rows.reduce(
        (n, r) => (r.start < n ? r.start : n),
        rows[0].start,
      ),
      lastEnd: rows.reduce((n, r) => (r.end > n ? r.end : n), rows[0].end),
    };
  });
  const slot = {
    id: "disabled",
    sourceId: r.sourceId,
    ownerSeries: "A",
    replacesSeries: null,
    week1: "2026-09-07",
    weeks: [1, 3],
    weekday: 2,
    startMinute: 480,
    endMinute: 540,
    periodStart: 0,
    periodEnd: 0,
    enabled: false,
    title: "数学",
    location: "A",
    teacher: "",
    note: "",
  };
  state.sharedRecords = durableRecords([scheduleRecord(slot)]);
  const db = new Workspace(
      {},
      { revision: 1, local: state, v3: { binding: { format: 4 } } },
    ),
    list = courses(db);
  assert.equal(list.total, 5);
  const grouped = list.rows.find((g) => g.series.includes("A"));
  assert.deepEqual(grouped.series, ["A", "B"]);
  assert.equal(grouped.slotCount, 3);
  assert.equal(list.local, undefined);
  const detail = courses(db, { key: grouped.key });
  assert.equal(detail.slots[0].enabled, false);
  assert.equal(detail.baselines.length, 2);
  assert.equal(list.rows.find((g) => g.series.includes("C")).series.length, 1);
  assert.notEqual(
    grouped.key,
    list.rows.find((g) => g.series.includes("D")).key,
  );
});

import { importedCourse } from "../test-fixtures/course-review.mjs";
import { projectWeek } from "../src/projection.mjs";
function detailFor(db) {
  const g = courses(db).rows[0];
  return courses(db, { key: g.key });
}
function replacement(detail) {
  const b = detail.baselines[0];
  return {
    id: "replacement",
    sourceId: detail.course.sourceId,
    ownerSeries: b.seriesId,
    replacesSeries: b.seriesId,
    week1: b.week1,
    weeks: b.weeks,
    weekday: b.day,
    startMinute: b.startMinute,
    endMinute: b.endMinute,
    periodStart: 0,
    periodEnd: 0,
    enabled: true,
    title: b.title,
    location: "替换教室",
    teacher: "",
    note: "",
  };
}
test("可靠学期第 4/6/10 周：乱序和原始时区不改变教学锚点与正式替换日期", async () => {
  for (const sunday of [false, true]) {
    const state = await importedCourse({ sunday }),
      db = new Workspace(
        {},
        { revision: 1, local: state, v3: { binding: { format: 4 } } },
      );
    const before = structuredClone(state);
    const d = detailFor(db);
    assert.equal(d.baselines[0].week1, "2026-09-07");
    assert.deepEqual(d.baselines[0].weeks, [4, 6, 10]);
    state.imported.reverse();
    assert.deepEqual(detailFor(db).baselines, d.baselines);
    const slot = replacement(d),
      edit = {
        target: { sourceId: d.course.sourceId, series: d.course.series },
        slots: [slot],
      };
    const next = db.previewCourseEdit(edit, 1).state;
    for (const week of sunday
      ? ["2026-10-05", "2026-10-19", "2026-11-16"]
      : ["2026-09-28", "2026-10-12", "2026-11-09"]) {
      const a = projectWeek(before, week, "Asia/Shanghai"),
        b = projectWeek(next, week, "Asia/Shanghai");
      assert.equal(a.length, 1);
      assert.equal(b.length, 1);
      assert.equal(b[0].start, a[0].start);
      assert.equal(b[0].end, a[0].end);
      assert.equal(b[0].location, "替换教室");
    }
    assert.equal(projectWeek(next, "2026-09-07").length, 0);
    assert.equal(
      projectWeek(next, sunday ? "2026-09-28" : "2026-10-05").length,
      0,
    );
    assert.deepEqual(state.sources, before.sources);
    assert.deepEqual(state.profiles, before.profiles);
  }
});
test("无可靠学期关联不冒充教学周，亦不选择其他学期；本地无限重复保持空锚点", async () => {
  const state = await importedCourse({ configured: false }),
    db = new Workspace(
      {},
      { revision: 1, local: state, v3: { binding: { format: 4 } } },
    );
  const b = detailFor(db).baselines[0];
  assert.equal(b.week1, undefined);
  assert.deepEqual(b.weeks, []);
  assert.equal(b.weekBasis, "unconfigured");
  state.imported.reverse();
  assert.deepEqual(detailFor(db).baselines[0], b);
  assert.equal(detailFor(fixture()).baselines[0].weekBasis, "unbounded");
});
test("删除并重建替换以最终候选判断恢复；停用继续抑制；真正移除才恢复", () => {
  const db = fixture(),
    slot = {
      id: "r1",
      sourceId: null,
      ownerSeries: "a",
      replacesSeries: "a",
      week1: "2026-09-07",
      weeks: [1, 3],
      weekday: 2,
      startMinute: 480,
      endMinute: 540,
      periodStart: 0,
      periodEnd: 0,
      enabled: true,
      title: "替换",
      location: "R1",
      teacher: "",
      note: "",
    };
  db.frame.local.sharedRecords = durableRecords([scheduleRecord(slot)]);
  const target = { sourceId: null, series: ["a"] };
  for (const enabled of [true, false]) {
    const edit = {
      target,
      deleteIds: ["r1"],
      slots: [{ ...slot, id: "r2", enabled, location: "R2" }],
    };
    assert.deepEqual(previewCourse(db, edit, 1).restored, []);
    const next = db.previewCourseEdit(edit, 1).state;
    assert.equal(
      projectWeek(next, "2026-09-07").some((r) => r.id === "a"),
      false,
    );
    assert.equal(
      projectWeek(next, "2026-09-07").some((r) => r.id === "r2"),
      enabled,
    );
  }
  const edit = { target, deleteIds: ["r1"] };
  assert.deepEqual(
    previewCourse(db, edit, 1).restored.map((r) => r.seriesId),
    ["a"],
  );
  assert(
    projectWeek(db.previewCourseEdit(edit, 1).state, "2026-09-07").some(
      (r) => r.id === "a",
    ),
  );
  assert.deepEqual(
    previewCourse(db, { target, slots: [{ ...slot, enabled: false }] }, 1)
      .restored,
    [],
  );
});

test("复用 termKey 的既有 CSV 关联；完全缺少学期时不预填教学周", async () => {
  const state = await importedCourse(),
    old = state.sources[0].termKey;
  state.profiles = state.profiles.map((p) =>
    p.termKey === old ? { ...p, termKey: "2026-2027-1" } : p,
  );
  state.customProfileKeys = state.customProfileKeys.map((k) =>
    k === old ? "2026-2027-1" : k,
  );
  Object.assign(state.sources[0], {
    termKey: null,
    filename: "review.csv",
    calendarName: "湖北大学 2026-2027-1",
  });
  const db = new Workspace(
    {},
    { revision: 1, local: state, v3: { binding: { format: 4 } } },
  );
  assert.equal(detailFor(db).baselines[0].week1, "2026-09-07");
  assert.deepEqual(detailFor(db).baselines[0].weeks, [4, 6, 10]);
  Object.assign(state.sources[0], {
    filename: "review.ics",
    calendarName: null,
  });
  state.profiles = [];
  state.customProfileKeys = [];
  assert.equal(detailFor(db).baselines[0].week1, undefined);
  assert.deepEqual(detailFor(db).baselines[0].weeks, []);
});
