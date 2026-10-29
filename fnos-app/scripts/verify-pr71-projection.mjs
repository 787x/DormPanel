import { readFile, writeFile } from "node:fs/promises";
import { decode, businessHash } from "../src/v3-protocol.mjs";
import { projectWeek } from "../src/projection.mjs";
import assert from "node:assert/strict";
const [input, configFile] = process.argv.slice(2),
  state = decode(await readFile(input)).state,
  config = JSON.parse(await readFile(configFile, "utf8"));
assert.equal(businessHash(state), config.expectedHash);
config.expectedProjection = ["2026-09-07", "2026-09-21", "2026-10-05"].map(
  (week) => ({
    week,
    rows: projectWeek(state, week, "Asia/Shanghai")
      .map((r) =>
        Object.fromEntries(
          [
            "id",
            "seriesId",
            "sourceId",
            "date",
            "academicDate",
            "anchorDate",
            "start",
            "end",
            "title",
            "location",
            "teacher",
            "note",
            "periodLabel",
            "sharedRgb",
          ].map((k) => [k, r[k] ?? null]),
        ),
      )
      .sort((a, b) => a.start - b.start || a.id.localeCompare(b.id)),
  }),
);
await writeFile(configFile, JSON.stringify(config));
console.log(
  "Node projection oracle prepared; actual Android comparison still required",
);
