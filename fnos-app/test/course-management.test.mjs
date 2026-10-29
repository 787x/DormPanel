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
