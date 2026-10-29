// Explicit isolated NAS only; config contains private credentials and stays outside Git.
import { readFile, writeFile, mkdtemp } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import assert from "node:assert/strict";
import { openV3Workspace } from "../src/v3-workspace.mjs";
import { V3Engine } from "../src/v3-engine.mjs";
import { V3WebDavStore, Budget } from "../src/v3-store.mjs";
import { IndexedV3Cache } from "../src/v3-index.mjs";
import { decode, businessHash } from "../src/v3-protocol.mjs";
import { courses, previewCourse } from "../src/course-management.mjs";
import { scheduleSlots } from "../src/shared-course-schedule.mjs";
const [configPath, phoneResult, output] = process.argv.slice(2),
  config = JSON.parse(await readFile(configPath, "utf8")),
  phone = JSON.parse(await readFile(phoneResult, "utf8"));
assert(
  /^https:\/\/192\.168\.3\.153:5006\/test\/pr71-isolated-[0-9a-f-]{36}\/$/.test(
    config.url,
  ),
);
config.sharedFormat = 1;
const root = await mkdtemp(join(tmpdir(), "pr71-node-")),
  store = await openV3Workspace(root, () => {}),
  engine = new V3Engine(store.journal, store.writerId);
const remote = (b) => new V3WebDavStore(config, b);
async function settle() {
  for (let i = 0; i < 16; i++) {
    const b = new Budget(),
      r = remote(b);
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
  const binding = phone.binding;
  await engine.bind(
    decode(await remote(new Budget()).read(binding.descriptorPath)),
    binding.descriptorHash,
  );
  await settle();
  const before = businessHash(store.workspace.frame.local);
  assert.equal(before, phone.hash);
  const slot = scheduleSlots(store.workspace.frame.local).find(
    (s) => s.id === "slot-a",
  );
  assert(slot);
  const edit = {
    target: { sourceId: null, series: ["x"] },
    slots: [{ ...slot, location: "fnOS 新教室", weeks: [1, 5] }],
    ...(config.teacher ? { common: { teacher: config.teacher } } : {}),
  };
  const exceptions = store.workspace.frame.local.classOverrides.filter(
    (o) => o.anchorDate !== null,
  );
  const preview = previewCourse(
    store.workspace,
    edit,
    store.workspace.frame.revision,
  );
  const saved = await store.workspace.saveCourseEdit(
    edit,
    store.workspace.frame.revision,
  );
  assert.equal(saved.businessHash, preview.businessHash);
  assert.deepEqual(
    saved.state.classOverrides.filter((o) => o.anchorDate !== null),
    exceptions,
  );
  assert.equal(
    scheduleSlots(store.workspace.frame.local).find((s) => s.id === "slot-b")
      .location,
    "B",
  );
  await settle();
  const report = {
    phoneHash: before,
    nodeHash: businessHash(store.workspace.frame.local),
    binding,
    exceptionCount: exceptions.length,
    slotCount: scheduleSlots(store.workspace.frame.local).length,
    workspace: root,
    receipt: store.journal.value.receipt ?? null,
    course: courses(store.workspace).rows[0],
  };
  await writeFile(output, JSON.stringify(report, null, 2));
  console.log(JSON.stringify(report));
} finally {
  await store.journal.close();
}
