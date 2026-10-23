import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { previewImport, commitImport } from "../src/import.mjs";
import { xlsSheets } from "../src/xls.mjs";
import { emptyState } from "../src/merge.mjs";
const fixture = (ext) =>
  readFileSync(
    new URL(
      "../../schedule-core/src/test/resources/schedule/hubei_2026-2027-1." +
        ext,
      import.meta.url,
    ),
  );
const state = () => {
  const s = emptyState();
  s.profiles.push({
    termKey: "2026-2027-1",
    timezone: "Asia/Shanghai",
    week1Monday: "2026-08-31",
    phases: [
      {
        effectiveFrom: "2026-08-31",
        periods: Array.from({ length: 11 }, (_, i) => ({
          periodNumber: i + 1,
          start: `${String(8 + i).padStart(2, "0")}:00`,
          end: `${String(8 + i).padStart(2, "0")}:45`,
        })),
      },
    ],
  });
  return s;
};
test("BIFF8 非首表、Unicode、多行课程与 CSV 全字段身份一致", () => {
  const s = state(),
    a = previewImport(fixture("csv"), "a.csv", s),
    b = previewImport(fixture("xls"), "a.xls", s);
  assert.equal(xlsSheets(fixture("xls")).length, 3);
  assert.equal(b.occurrences.length, 131);
  assert.equal(new Set(b.occurrences.map((x) => x.seriesId)).size, 13);
  assert.deepEqual(b.occurrences, a.occurrences);
  assert.notEqual(a.sha256, b.sha256);
  assert.equal(b.sha256, previewImport(fixture("xls"), "a.xls", s).sha256);
  commitImport(s, a, "CSV");
  const id = s.sources[0].id;
  commitImport(s, b, "XLS", id);
  assert.equal(s.sources[0].id, id);
  assert.equal(s.imported.length, 131);
  s.profiles[0].week1Monday = "2026-09-07";
  assert.notEqual(b.sha256, previewImport(fixture("xls"), "a.xls", s).sha256);
});
test("伪造 XLS、损坏 OLE、大小与链边界拒绝", () => {
  const s = state();
  for (const b of [
    Buffer.from("not excel"),
    fixture("csv"),
    fixture("xls").subarray(0, 700),
    Buffer.alloc(1048577),
  ])
    assert.throws(() => previewImport(b, "a.xls", s));
  for (const offset of [30, 44, 48, 76]) {
    const b = Buffer.from(fixture("xls"));
    b.writeUInt32LE(0x7fffffff, offset);
    assert.throws(() => previewImport(b, "a.xls", s));
  }
});
export { state };
