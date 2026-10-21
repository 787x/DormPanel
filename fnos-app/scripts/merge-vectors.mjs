// 仅生成合成课表；Kotlin JVM 反向校验 Node 编码、关联合并和导入身份。
import { readFile, mkdir, writeFile } from "node:fs/promises";
import { decode, encode, overrideKey } from "../src/protocol.mjs";
import { merge } from "../src/merge.mjs";
import { canonical, applyEdit } from "../src/workspace.mjs";
import { previewImport, commitImport } from "../src/import.mjs";
const dir = new URL(
  "../../schedule-core/src/test/resources/pr38/",
  import.meta.url,
);
await mkdir(dir, { recursive: true });
const template = decode(
  await readFile(
    new URL(
      "../../schedule-core/src/test/resources/sync-v2/complex-root.dpc",
      import.meta.url,
    ),
  ),
);
const base = structuredClone(template.state),
  anchor = "2026-10-19";
applyEdit(base, {
  kind: "override",
  action: "upsert",
  sourceId: base.sources[0].id,
  seriesId: base.imported[0].seriesId,
  anchorDate: anchor,
  patch: { note: "原始" },
});
applyEdit(base, {
  kind: "override",
  action: "upsert",
  sourceId: null,
  seriesId: base.entries[0].id,
  anchorDate: null,
  patch: { location: "初始" },
});
const mutations = {
  unchanged: (s) => {},
  class: (s) => (s.entries[0].location = "A301"),
  otherClass: (s) => (s.entries[0].location = "B201"),
  event: (s) => (s.events = []),
  reset: (s) =>
    (s.classOverrides = s.classOverrides.filter((e) => e.anchorDate !== null)),
  sparse: (s) =>
    (s.classOverrides.find((e) => e.anchorDate !== null).note = ""),
  inherit: (s) =>
    (s.classOverrides.find((e) => e.anchorDate !== null).note = null),
  deleteSource: (s) => {
    s.sources = [];
    s.imported = [];
    s.classOverrides = s.classOverrides.filter((e) => e.sourceId === null);
  },
  deleteClass: (s) => {
    s.entries = [];
    s.classOverrides = s.classOverrides.filter((e) => e.sourceId !== null);
  },
  localOverride: (s) =>
    (s.classOverrides.find((e) => e.sourceId === null).location = "A303"),
  sourceEdit: (s) =>
    (s.classOverrides.find((e) => e.sourceId !== null).teacher = "教师"),
  sourceReplace: (s) => {
    s.sources[0].sha256 = "b".repeat(64);
    s.imported[0].location = "新地点";
  },
  importTime: (s) => (s.sources[0].importedAt = 999n),
  term: (s) =>
    (s.profiles.find((p) => p.termKey === s.sources[0].termKey).timezone =
      "Asia/Tokyo"),
  provenance: (s) => (s.customProfileKeys = []),
  day: (s) =>
    s.dayAdjustments.push({
      date: anchor,
      mode: "none",
      weekday: null,
      label: "",
    }),
  label: (s) => (s.dayAdjustments[0].label = "仅说明"),
  destination: (s) =>
    (s.classOverrides.find((e) => e.anchorDate !== null).date = "2026-10-20"),
};
const pairs = [
  ["class", "event"],
  ["class", "otherClass"],
  ["class", "class"],
  ["reset", "sparse"],
  ["inherit", "sparse"],
  ["deleteSource", "sourceEdit"],
  ["deleteClass", "localOverride"],
  ["deleteClass", "class"],
  ["sourceReplace", "sourceEdit"],
  ["sourceReplace", "sourceReplace"],
  ["importTime", "sourceReplace"],
  ["term", "sourceEdit"],
  ["provenance", "term"],
  ["day", "reset"],
  ["day", "sparse"],
  ["day", "destination"],
  ["label", "sparse"],
  ["unchanged", "reset"],
];
const cases = [];
let n = 1;
const save = async (name, state) => {
  const c = {
    ...template,
    commitId: `00000000-0000-4000-8000-${String(n++).padStart(12, "0")}`,
    parents: [],
    state: canonical(state),
  };
  await writeFile(new URL(name + ".dpc", dir), encode(c));
};
for (const [left, right] of pairs) {
  const name = left + "-" + right,
    l = structuredClone(base),
    r = structuredClone(base);
  mutations[left](l);
  mutations[right](r);
  await save(name + "-base", base);
  await save(name + "-input-local", l);
  await save(name + "-input-remote", r);
  const row = {
    name,
    conflicts: merge(base, l, r)
      .conflicts.map((c) => c.keys.sort())
      .sort(),
  };
  for (const choice of ["local", "remote"])
    await save(name + "-expected-" + choice, merge(base, l, r, choice).state);
  cases.push(row);
}
const imports = [];
for (const filename of ["wakeup.ics", "hubei_2026-2027-1.csv"]) {
  const state = structuredClone(template.state);
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
  const bytes = await readFile(
      new URL(
        "../../schedule-core/src/test/resources/schedule/" + filename,
        import.meta.url,
      ),
    ),
    p = previewImport(bytes, filename, state);
  commitImport(state, p, "合成导入", null, 1);
  const source = state.sources.at(-1);
  source.id = "pr38-import-source";
  const original = state.imported.filter(
    (r) => !template.state.imported.some((old) => old.id === r.id),
  );
  for (const r of original) {
    r.id = "pr38-import-source:" + r.id.slice(r.id.indexOf(":") + 1);
    r.sourceId = "pr38-import-source";
  }
  state.sources = state.sources.filter((s) => s.id === "pr38-import-source");
  state.imported = original;
  state.classOverrides = [];
  state.entries = [];
  state.events = [];
  const name = filename.endsWith(".csv") ? "import-csv" : "import-ics";
  await save(name, state);
  imports.push({ name, filename });
}
await writeFile(
  new URL("manifest.json", dir),
  JSON.stringify({ cases, imports }, null, 2) + "\n",
);
console.log(
  `已生成 ${cases.length} 组合并与 ${imports.length} 组导入跨语言向量`,
);
