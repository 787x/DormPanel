// 产品查询只返回有界业务视图；正式候选与事务由 Workspace 负责。
import { Registry, wireRecords } from "./shared-records.mjs";
import { sharedTypes } from "./shared-types.mjs";
import { identity, display } from "./shared-course-colors.mjs";
import { scheduleSlots } from "./shared-course-schedule.mjs";
import { businessHash } from "./v3-protocol.mjs";
import { projectWeek, addDays } from "./projection.mjs";
import { Temporal } from "@js-temporal/polyfill";
import { ensure, termKey } from "./protocol.mjs";
export function courses(db, input = {}) {
  const state = db.frame.local,
    groups = new Map(),
    slots = scheduleSlots(state);
  const add = (sourceId, seriesId, row) => {
    const key = identity(state, sourceId, seriesId);
    let g = groups.get(key);
    if (!g) {
      g = {
        key,
        sourceId,
        series: [],
        title:
          state.classOverrides.find(
            (o) =>
              o.sourceId === sourceId &&
              o.seriesId === seriesId &&
              o.anchorDate === null,
          )?.title ?? row.title,
        source:
          state.sources.find((s) => s.id === sourceId)?.displayName ??
          "本地课程",
        color: display(state, sourceId, seriesId),
        automaticRgb: display(
          {
            ...state,
            sharedRecords: (state.sharedRecords ?? []).filter(
              (r) => r.typeId !== "course.color" || r.recordId !== key,
            ),
          },
          sourceId,
          seriesId,
        ).rgb,
        slotCount: 0,
      };
      groups.set(key, g);
    }
    if (!g.series.includes(seriesId)) g.series.push(seriesId);
  };
  for (const e of state.entries) add(null, e.id, e);
  const seen = new Set();
  for (const r of state.imported) {
    const k = JSON.stringify([r.sourceId, r.seriesId]);
    if (!seen.has(k)) {
      seen.add(k);
      add(r.sourceId, r.seriesId, r);
    }
  }
  const supported = db.frame.v3?.binding?.format === 4;
  let writable = supported;
  try {
    db.assertWritable?.();
    ensure(!db.frame.conflict, "冲突期间只读");
    new Registry(sharedTypes).writable(wireRecords(state.sharedRecords ?? []));
  } catch {
    writable = false;
  }
  let rows = [...groups.values()].map((g) => ({
    ...g,
    slotCount:
      g.series.length +
      slots.filter(
        (s) =>
          s.sourceId === g.sourceId &&
          g.series.includes(s.ownerSeries) &&
          s.replacesSeries === null,
      ).length,
    editable: writable && g.color.status !== "UNSUPPORTED",
  }));
  if (input.filter)
    rows = rows.filter((g) => (g.title + g.source).includes(input.filter));
  rows.sort(
    (a, b) =>
      a.title.localeCompare(b.title, "zh") || a.key.localeCompare(b.key),
  );
  const offset = Number(input.offset ?? 0),
    limit = Number(input.limit ?? 40);
  ensure(
    Number.isInteger(offset) &&
      offset >= 0 &&
      offset <= 50000 &&
      Number.isInteger(limit) &&
      limit >= 1 &&
      limit <= 100,
    "分页超限",
  );
  const status = {
    version: db.frame.revision,
    datasetId: db.frame.datasetId,
    businessHash: businessHash(state),
    supported,
  };
  if (input.key) {
    const g = groups.get(input.key);
    ensure(g, "课程不存在");
    const owned = (s) =>
      s.sourceId === g.sourceId && g.series.includes(s.ownerSeries);
    const all = slots.filter(owned);
    return {
      ...status,
      course: rows.find((r) => r.key === g.key) ?? g,
      slots: all.slice(offset, offset + limit),
      total: all.length,
      next: offset + limit < all.length ? offset + limit : null,
      baselines: g.series
        .map((seriesId) => {
          const r =
            g.sourceId === null
              ? state.entries.find((e) => e.id === seriesId)
              : state.imported
                  .filter(
                    (e) => e.sourceId === g.sourceId && e.seriesId === seriesId,
                  )
                  .sort(
                    (a, b) =>
                      Number(a.start) - Number(b.start) ||
                      a.id.localeCompare(b.id),
                  )[0];
          const override = state.classOverrides.find(
            (o) =>
              o.sourceId === g.sourceId &&
              o.seriesId === seriesId &&
              o.anchorDate === null,
          );
          const actual =
            g.sourceId === null
              ? []
              : state.imported.filter(
                  (e) => e.sourceId === g.sourceId && e.seriesId === seriesId,
                );
          actual.sort(
            (a, b) =>
              Number(a.start) - Number(b.start) || a.id.localeCompare(b.id),
          );
          const dates = actual.map((e) =>
            Temporal.Instant.fromEpochMilliseconds(
              Number(e.start),
            ).toZonedDateTimeISO(e.timezone),
          );
          const owner = state.sources.find((s) => s.id === g.sourceId);
          const profile =
            owner && state.profiles.find((p) => p.termKey === termKey(owner));
          const week1 = profile?.week1Monday;
          const weekBasis =
            g.sourceId === null
              ? "unbounded"
              : profile
                ? "teaching"
                : "unconfigured";
          const weeks = week1
            ? [
                ...new Set(
                  dates.map(
                    (d) =>
                      Math.floor(
                        Temporal.PlainDate.from(week1).until(d.toPlainDate())
                          .days / 7,
                      ) + 1,
                  ),
                ),
              ].sort((a, b) => a - b)
            : [];
          const effective = {
            ...r,
            ...Object.fromEntries(
              Object.entries(override ?? {}).filter(([k, v]) => v !== null),
            ),
          };
          return {
            seriesId,
            ...effective,
            day: effective.weekday ?? r.day ?? dates[0]?.dayOfWeek,
            startMinute:
              effective.startMinute ??
              (dates[0] ? dates[0].hour * 60 + dates[0].minute : undefined),
            endMinute:
              effective.endMinute ??
              (r.end
                ? (() => {
                    const d = Temporal.Instant.fromEpochMilliseconds(
                      Number(r.end),
                    ).toZonedDateTimeISO(r.timezone);
                    return d.hour * 60 + d.minute;
                  })()
                : undefined),
            week1,
            weekBasis,
            weeks,
            suppressed: all.some((s) => s.replacesSeries === seriesId),
          };
        })
        .map(
          ({
            id,
            sourceId,
            seriesId,
            title,
            day,
            startMinute,
            endMinute,
            location,
            teacher,
            note,
            week1,
            weekBasis,
            weeks,
            suppressed,
          }) => ({
            seriesId: seriesId ?? id,
            title,
            day,
            startMinute,
            endMinute,
            location,
            teacher,
            note,
            week1,
            weekBasis,
            weeks,
            suppressed,
          }),
        ),
      overrides: state.classOverrides.filter(
        (o) =>
          o.anchorDate === null &&
          o.sourceId === g.sourceId &&
          g.series.includes(o.seriesId),
      ),
    };
  }
  return {
    ...status,
    rows: rows.slice(offset, offset + limit),
    total: rows.length,
    next: offset + limit < rows.length ? offset + limit : null,
  };
}
export function previewCourse(db, edit, version) {
  const before = db.frame.local,
    p = db.previewCourseEdit(edit, version),
    after = p.state;
  const changes = [];
  for (const table of ["classOverrides", "sharedRecords"]) {
    const key = (r) => r.key ?? r.typeId + ":" + r.recordId;
    const a = new Map(before[table].map((r) => [key(r), r])),
      b = new Map(after[table].map((r) => [key(r), r]));
    for (const id of new Set([...a.keys(), ...b.keys()]))
      if (JSON.stringify(a.get(id)) !== JSON.stringify(b.get(id)))
        changes.push({
          kind: table,
          action: !a.has(id) ? "新增" : !b.has(id) ? "移除" : "修改",
          id,
        });
  }
  const projection = [];
  let total = 0;
  const starts = new Set([
    ...before.profiles.map((p) => p.week1Monday),
    ...before.imported
      .filter(
        (r) =>
          r.sourceId === edit.target.sourceId &&
          edit.target.series.includes(r.seriesId),
      )
      .map((r) => {
        const d = Temporal.Instant.fromEpochMilliseconds(Number(r.start))
          .toZonedDateTimeISO(r.timezone)
          .toPlainDate();
        return d.subtract({ days: d.dayOfWeek - 1 }).toString();
      }),
    ...(edit.slots ?? []).map((s) => s.week1),
    ...scheduleSlots(before)
      .filter(
        (s) =>
          s.sourceId === edit.target.sourceId &&
          edit.target.series.includes(s.ownerSeries),
      )
      .map((s) => s.week1),
  ]);
  // 每个显式学期最多 104 周；业务明细最多返回 100 条。
  if (!starts.size && edit.target.sourceId === null) {
    const today = Temporal.Now.plainDateISO("Asia/Shanghai");
    starts.add(today.subtract({ days: today.dayOfWeek - 1 }).toString());
  }
  const ordered = [...starts].sort(),
    windows = [];
  for (const d of ordered)
    if (!windows.some((a) => d < addDays(a, 728))) windows.push(d);
  ensure(windows.length <= 8, "预览学期数量超限");
  for (const start of windows)
    for (let week = 0; week < 104; week++) {
      const d = addDays(start, week * 7),
        a = projectWeek(before, d),
        b = projectWeek(after, d);
      const key = (r) =>
        JSON.stringify([
          r.id,
          r.start,
          r.end,
          r.title,
          r.location,
          r.teacher,
          r.note,
          r.sharedRgb,
        ]);
      const aa = new Set(a.map(key)),
        bb = new Set(b.map(key));
      for (const [rows, other, action] of [
        [a, bb, "移除"],
        [b, aa, "新增"],
      ])
        for (const row of rows)
          if (!other.has(key(row))) {
            total++;
            if (projection.length < 100) projection.push({ action, ...row });
          }
    }
  const exceptions = before.classOverrides.filter(
    (o) =>
      o.anchorDate !== null &&
      o.sourceId === edit.target.sourceId &&
      edit.target.series.includes(o.seriesId),
  );
  const suppressedAfter = new Set(
    scheduleSlots(after)
      .filter((s) => s.replacesSeries !== null)
      .map((s) => JSON.stringify([s.sourceId, s.replacesSeries])),
  );
  const restored = scheduleSlots(before)
    .filter(
      (s) =>
        s.replacesSeries !== null &&
        !suppressedAfter.has(JSON.stringify([s.sourceId, s.replacesSeries])),
    )
    .map((s) => ({ title: s.title, seriesId: s.ownerSeries }));
  return {
    exceptions: exceptions.slice(0, 100),
    exceptionCount: exceptions.length,
    restored,
    businessHash: p.businessHash,
    baseHash: businessHash(before),
    version,
    changes: changes.slice(0, 100),
    changeCount: changes.length,
    projection,
    total,
    overrides: edit.overrides ?? [],
    colors: edit.colors ?? [],
    common: edit.common ?? {},
    slots: edit.slots ?? [],
    deleteIds: edit.deleteIds ?? [],
    projectionScope: windows.map((start) => ({
      start,
      end: addDays(start, 727),
    })),
    unboundedLocal: edit.target.sourceId === null,
    warning:
      "本地无限重复课程的范围外结果不作穷举。移除替换规则可能恢复原始课程；停用替换仍抑制原始系列。有限替换范围外不再上课。指定周例外保持原锚点。",
  };
}
