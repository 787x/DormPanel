import { readFile } from "node:fs/promises";
import { Temporal } from "@js-temporal/polyfill";
import { decode, termKey } from "../src/protocol.mjs";
export async function importedCourse({
  configured = true,
  sunday = false,
} = {}) {
  const state = decode(
    await readFile(
      new URL(
        "../../schedule-core/src/test/resources/sync-v2/complex-root.dpc",
        import.meta.url,
      ),
    ),
  ).state;
  const row = state.imported[0],
    owner = state.sources.find((s) => s.id === row.sourceId),
    key = termKey(owner),
    zone = "America/New_York";
  state.entries = [];
  state.classOverrides = [];
  state.dayAdjustments = [];
  state.sharedRecords = [];
  state.profiles = state.profiles.map((p) =>
    p.termKey === key ? { ...p, timezone: zone, week1Monday: "2026-09-07" } : p,
  );
  state.imported = [4, 6, 10].map((week, i) => {
    const d = Temporal.PlainDate.from("2026-09-07").add({
      weeks: week - 1,
      days: sunday ? 6 : 0,
    });
    const start = BigInt(
      d.toPlainDateTime(sunday ? "23:30" : "08:00").toZonedDateTime(zone)
        .epochMilliseconds,
    );
    return {
      ...row,
      id: "review-" + i,
      seriesId: "review-series",
      title: "教学周审查课程",
      timezone: zone,
      start,
      originalStart: start,
      end: start + 3600000n,
      periodLabel: null,
    };
  });
  state.sources = [
    {
      ...owner,
      termKey: configured ? key : null,
      filename: "review.ics",
      calendarName: null,
      occurrenceCount: 3,
      firstStart: state.imported[0].start,
      lastEnd: state.imported[2].end,
    },
  ];
  return state;
}
