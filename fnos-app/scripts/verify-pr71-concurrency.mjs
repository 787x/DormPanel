// Real isolated WebDAV scenarios, explicitly invoked with private config.
import { readFile, writeFile, mkdtemp } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";
import assert from "node:assert/strict";
import { openV3Workspace } from "../src/v3-workspace.mjs";
import { V3Engine } from "../src/v3-engine.mjs";
import { V3WebDavStore, Budget } from "../src/v3-store.mjs";
import { IndexedV3Cache } from "../src/v3-index.mjs";
import { decode, businessHash } from "../src/v3-protocol.mjs";
import { emptyState } from "../src/merge.mjs";
import { decode as legacyDecode } from "../src/protocol.mjs";
import {
  scheduleRecord,
  scheduleSlots,
} from "../src/shared-course-schedule.mjs";
import { durableRecords } from "../src/shared-records.mjs";
const [configFile, output] = process.argv.slice(2),
  config = JSON.parse(await readFile(configFile, "utf8"));
assert(/\/test\/pr71-isolated-[0-9a-f-]{36}\/$/.test(config.url));
config.sharedFormat = 1;
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
const report = [];
async function client() {
  const root = await mkdtemp(join(tmpdir(), "pr71-concurrent-")),
    adapter = await openV3Workspace(root, () => {});
  return {
    root,
    ...adapter,
    engine: new V3Engine(adapter.journal, adapter.writerId),
  };
}
async function rounds(c) {
  for (let i = 0; i < 5; i++) {
    const b = new Budget(),
      r = new V3WebDavStore(config, b);
    try {
      await c.engine.round(r, new IndexedV3Cache(c.root, b), b, {
        discoverySteps: 32,
      });
    } catch (e) {
      if (!e.message.includes("incomplete")) throw e;
    }
  }
}
const edit = (c, e) =>
    c.workspace.saveCourseEdit(e, c.workspace.frame.revision),
  target = { sourceId: null, series: ["x"] };
for (const name of [
  "different-courses",
  "teacher-color",
  "color-conflict",
  "slot-delete-edit",
  "source-reference",
]) {
  const a = await client(),
    b = await client();
  try {
    let initial = structuredClone(base),
      t = target,
      original = slot;
    if (name === "source-reference") {
      initial = legacyDecode(
        await readFile(
          new URL(
            "../../schedule-core/src/test/resources/sync-v2/complex-root.dpc",
            import.meta.url,
          ),
        ),
      ).state;
      initial.sharedRecords = [];
      initial.classOverrides = [];
      initial.dayAdjustments = [];
      const r = initial.imported[0];
      original = { ...slot, sourceId: r.sourceId, ownerSeries: r.seriesId };
      initial.sharedRecords = durableRecords([scheduleRecord(original)]);
      t = { sourceId: r.sourceId, series: [r.seriesId] };
    }
    const binding = await a.engine.initialize(
      new V3WebDavStore(config, new Budget()),
      initial,
    );
    await rounds(a);
    await b.engine.bind(
      decode(
        await new V3WebDavStore(config, new Budget()).read(
          binding.descriptorPath,
        ),
      ),
      binding.descriptorHash,
    );
    await rounds(b);
    if (name === "different-courses") {
      await edit(a, { target, common: { teacher: "A" } });
      await edit(b, {
        target: { sourceId: null, series: ["y"] },
        common: { teacher: "B" },
      });
    }
    if (name === "teacher-color") {
      await edit(a, { target, common: { teacher: "A" } });
      await edit(b, { target, colors: [{ seriesId: "x", rgb: 0x123456 }] });
    }
    if (name === "color-conflict") {
      await edit(a, { target, colors: [{ seriesId: "x", rgb: 0x111111 }] });
      await edit(b, { target, colors: [{ seriesId: "x", rgb: 0x222222 }] });
    }
    if (name === "slot-delete-edit") {
      await edit(a, { target, deleteIds: ["slot"] });
      await edit(b, { target, slots: [{ ...slot, location: "B" }] });
    }
    if (name === "source-reference") {
      await a.workspace.edit(
        {
          kind: "source",
          action: "upsert",
          id: t.sourceId,
          displayName: "并发来源",
          termKey: initial.sources.find((s) => s.id === t.sourceId).termKey,
        },
        a.workspace.frame.revision,
      );
      await edit(b, { target: t, slots: [{ ...original, location: "B" }] });
    }
    await rounds(a);
    await rounds(b);
    await rounds(a);
    const conflict = !!a.journal.value.conflict || !!b.journal.value.conflict;
    const expected = [
      "color-conflict",
      "slot-delete-edit",
      "source-reference",
    ].includes(name);
    assert.equal(conflict, expected, name);
    if (conflict) {
      const c = a.journal.value.conflict ? a : b;
      await c.engine.resolve(c.journal.value.conflict.id, "local");
      await rounds(c);
      await rounds(a);
      await rounds(b);
    }
    assert.equal(
      businessHash(a.workspace.frame.local),
      businessHash(b.workspace.frame.local),
      name + " recovered hash",
    );
    report.push({
      name,
      conflict,
      recovered: true,
      hash: businessHash(a.workspace.frame.local),
      datasetId: binding.datasetId,
    });
    console.log(name + " PASS");
  } finally {
    await a.journal.close();
    await b.journal.close();
  }
}
await writeFile(output, JSON.stringify(report, null, 2));
