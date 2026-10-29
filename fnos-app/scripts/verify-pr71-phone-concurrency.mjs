// Actual Phone Room participant + fnOS Workspace over isolated real NAS WebDAV.
import { readFile, writeFile, mkdtemp } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { openV3Workspace } from "../src/v3-workspace.mjs";
import { V3Engine } from "../src/v3-engine.mjs";
import { V3WebDavStore, Budget } from "../src/v3-store.mjs";
import { IndexedV3Cache } from "../src/v3-index.mjs";
import { businessHash } from "../src/v3-protocol.mjs";
import { emptyState } from "../src/merge.mjs";
import { decode as legacyDecode } from "../src/protocol.mjs";
import { scheduleRecord } from "../src/shared-course-schedule.mjs";
import { durableRecords } from "../src/shared-records.mjs";
const [configFile, serial, output] = process.argv.slice(2),
  config = JSON.parse(await readFile(configFile, "utf8"));
assert(/\/test\/pr71-isolated-[0-9a-f-]{36}\/$/.test(config.url));
config.sharedFormat = 1;
const work = await mkdtemp(join(tmpdir(), "pr71-phone-concurrency-")),
  phoneFile = join(work, "phone.private.json");
const slot = {
  id: "slot",
  sourceId: null,
  ownerSeries: "x",
  replacesSeries: null,
  week1: "2026-09-07",
  weeks: [1, 3],
  weekday: 2,
  startMinute: 480,
  endMinute: 540,
  periodStart: 0,
  periodEnd: 0,
  enabled: true,
  title: "课程 X",
  location: "A",
  teacher: "",
  note: "",
};
const base = {
  ...emptyState(),
  entries: ["x", "y"].map((id) => ({
    id,
    title: "课程 " + id,
    day: 1,
    startMinute: 480,
    endMinute: 540,
    location: "",
  })),
  sharedRecords: durableRecords([scheduleRecord(slot)]),
};
function adb(args) {
  return execFileSync("adb", ["-s", serial, ...args], {
    encoding: "utf8",
    timeout: 180000,
    maxBuffer: 2 * 1024 * 1024,
  });
}
async function phone(mode, binding, name, expectConflict) {
  await writeFile(
    phoneFile,
    JSON.stringify({
      ...config,
      url: config.url.replace("192.168.3.153:5006", "127.0.0.1:15006"),
      mode,
      binding,
      case: name,
      expectConflict,
    }),
  );
  adb([
    "push",
    phoneFile,
    "/sdcard/Android/data/com.dormpanel.timetable.testbed/files/pr71-concurrent.private.json",
  ]);
  const text = adb([
    "shell",
    "am",
    "instrument",
    "-w",
    "-e",
    "class",
    "com.dormpanel.app.schedule.testing.Pr71ConcurrentTest",
    "com.dormpanel.timetable.testbed.test/androidx.test.runner.AndroidJUnitRunner",
  ]);
  assert(text.includes("OK (1 test)"), text);
  return JSON.parse(
    adb([
      "shell",
      "cat",
      "/sdcard/Android/data/com.dormpanel.timetable.testbed/files/pr71-concurrent-result.json",
    ]),
  );
}
const report = [];
for (const name of [
  "different-courses",
  "teacher-color",
  "color-conflict",
  "slot-delete-edit",
  "source-reference",
]) {
  const root = await mkdtemp(join(tmpdir(), "pr71-cross-")),
    adapter = await openV3Workspace(root, () => {}),
    engine = new V3Engine(adapter.journal, adapter.writerId);
  async function rounds() {
    for (let i = 0; i < 6; i++) {
      const b = new Budget(),
        r = new V3WebDavStore(config, b);
      try {
        await engine.round(r, new IndexedV3Cache(root, b), b, {
          discoverySteps: 32,
        });
      } catch (e) {
        if (!e.message.includes("incomplete")) throw e;
      }
    }
  }
  try {
    let initial = structuredClone(base),
      target = { sourceId: null, series: ["x"] };
    if (name === "source-reference") {
      initial = legacyDecode(
        await readFile(
          new URL(
            "../../schedule-core/src/test/resources/sync-v2/complex-root.dpc",
            import.meta.url,
          ),
        ),
      ).state;
      initial.classOverrides = [];
      initial.dayAdjustments = [];
      const r = initial.imported[0];
      initial.sharedRecords = durableRecords([
        scheduleRecord({
          ...slot,
          sourceId: r.sourceId,
          ownerSeries: r.seriesId,
        }),
      ]);
      target = { sourceId: r.sourceId, series: [r.seriesId] };
    }
    const binding = await engine.initialize(
      new V3WebDavStore(config, new Budget()),
      initial,
    );
    await rounds();
    const expectConflict = [
      "color-conflict",
      "slot-delete-edit",
      "source-reference",
    ].includes(name);
    await phone("prepare", binding, name, expectConflict);
    let edit = { target, common: { teacher: "fnOS" } };
    if (name === "color-conflict")
      edit = { target, colors: [{ seriesId: "x", rgb: 0x111111 }] };
    if (name === "slot-delete-edit") edit = { target, deleteIds: ["slot"] };
    if (name === "source-reference")
      await adapter.workspace.edit(
        {
          kind: "source",
          action: "upsert",
          id: target.sourceId,
          displayName: "fnOS 并发来源",
          termKey: initial.sources.find((s) => s.id === target.sourceId)
            .termKey,
        },
        adapter.workspace.frame.revision,
      );
    else
      await adapter.workspace.saveCourseEdit(
        edit,
        adapter.workspace.frame.revision,
      );
    await rounds();
    const result = await phone("finish", binding, name, expectConflict);
    await rounds();
    assert.equal(
      businessHash(adapter.workspace.frame.local),
      result.hash,
      name,
    );
    report.push({
      name,
      phoneRoom: true,
      realWebDav: true,
      conflict: expectConflict,
      recovered: true,
      hash: result.hash,
      datasetId: binding.datasetId,
    });
    console.log(name + " Phone/fnOS PASS");
  } finally {
    await adapter.journal.close();
  }
}
await writeFile(output, JSON.stringify(report, null, 2));
