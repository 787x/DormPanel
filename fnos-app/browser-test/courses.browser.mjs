import test, { before, after } from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import { mkdir } from "node:fs/promises";
import { mkdtemp, rm, readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomUUID } from "node:crypto";
import { V3Maintenance } from "../src/v3-maintenance.mjs";
import { encode as encodeV3, decode as decodeV3 } from "../src/v3-protocol.mjs";
import { objectPath } from "../src/v3-store.mjs";
import { reference } from "../src/v3-history.mjs";
import { chromium } from "playwright";
import { expect } from "playwright/test";
import { createApp, PREFIX } from "../src/server.mjs";
import { encode, decode } from "../src/protocol.mjs";
import { loadGraph } from "../src/graph.mjs";
import { emptyState, same } from "../src/merge.mjs";
import { Workspace } from "../src/workspace.mjs";

let browser;
before(async () => {
  browser = await chromium.launch({
    channel:
      process.env.BROWSER_CHANNEL ||
      (process.platform === "win32" ? "msedge" : "chromium"),
  });
});
after(async () => {
  await browser?.close();
});

// PR61 review fixes use only the same isolated server and synthetic remote fixtures below.

// 只在独立临时实例中注入网关身份；生产服务仍拒绝 TCP 和伪造的客户端身份。
async function fixture(
  t,
  { authorized = false, proxyHost = null, remoteFactory, v3RemoteFactory } = {},
) {
  const dir = await mkdtemp(join(tmpdir(), "dp-connection-ui-"));
  const app = await createApp({
    dataDir: dir,
    configPath: join(dir, "config.json"),
    remoteFactory,
    v3RemoteFactory,
    authorizedRoots: authorized
      ? [{ id: "approved", name: "fnOS 已授权测试目录", path: dir }]
      : [],
  });
  const socketPath =
    process.platform === "win32"
      ? "\\\\.\\pipe\\dp-ui-" + randomUUID()
      : join(dir, "app.sock");
  await new Promise((r) => app.server.listen(socketPath, r));
  const proxy = http.createServer((req, res) => {
    const upstream = http.request(
      {
        socketPath,
        path: req.url,
        method: req.method,
        headers: {
          ...req.headers,
          ...(proxyHost ? { host: proxyHost } : {}),
          "x-trim-userid": "1000",
          "x-trim-isadmin": "true",
        },
      },
      (response) => {
        res.writeHead(response.statusCode, response.headers);
        response.pipe(res);
      },
    );
    upstream.on("error", () => {
      res.writeHead(502);
      res.end();
    });
    req.pipe(upstream);
  });
  await new Promise((r) => proxy.listen(0, "127.0.0.1", r));
  const context = await browser.newContext({
      viewport: { width: 1280, height: 800 },
    }),
    page = await context.newPage();
  const errors = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await page.route("**/api/directories?*", (route) =>
    route.fulfill({ json: { directories: ["测试子目录"] } }),
  );
  t.after(async () => {
    await context.close();
    await new Promise((r) => proxy.close(r));
    await new Promise((r) => app.server.close(r));
    await app.catalog.close();
    await rm(dir, { recursive: true, force: true });
  });
  const open = async () => {
    await page.goto(`http://127.0.0.1:${proxy.address().port}${PREFIX}/`);
    await page.locator("#sourcesPage").click();
  };
  const local = async () => {
    await open();
    await page.locator("#addLocal").click();
  };
  const save = page.locator("#editorForm [type=submit]");
  return { page, app, errors, open, local, save };
}

async function choosePrivate(f) {
  await f.page
    .getByRole("button", {
      name: "使用私有目录（自动创建专用文件夹）",
      exact: true,
    })
    .click();
}

test("课程管理列表、只读预览、取消和真实原子保存", async (t) => {
  const f = await fixture(t);
  await f.app.db.edit({
    kind: "entry",
    action: "upsert",
    value: {
      id: "course-a",
      title: "离散数学",
      day: 1,
      startMinute: 480,
      endMinute: 540,
      location: "教室 A",
    },
  });
  // 独立临时 Workspace：只注入显式能力，不接触日用绑定或远端。
  f.app.db.frame.local.sharedRecords = [];
  f.app.db.frame.v3 = { binding: { format: 4 } };
  await f.open();
  await f.page.locator("#coursesPage").click();
  await mkdir(new URL("../../docs/screenshots/pr71/", import.meta.url), {
    recursive: true,
  });
  await f.page.screenshot({
    path: new URL(
      "../../docs/screenshots/pr71/list.png",
      import.meta.url,
    ).pathname.replace(/^\/(\w:)/, "$1"),
  });
  await f.page
    .getByRole("button", { name: "编辑整门课程", exact: true })
    .click();
  await expect(f.page.locator(".course-manager")).toContainText("公共信息");
  await f.page.screenshot({
    path: fileURLToPath(
      new URL("../../docs/screenshots/pr71/editor.png", import.meta.url),
    ),
  });
  await f.page.getByRole("button", { name: "#8844cc", exact: true }).click();
  await f.page.screenshot({
    path: fileURLToPath(
      new URL("../../docs/screenshots/pr71/color.png", import.meta.url),
    ),
  });
  const before = f.app.db.frame.revision;
  await f.page
    .locator(".course-manager label")
    .filter({ hasText: "教师" })
    .first()
    .locator("input[type=checkbox]")
    .check();
  await f.page
    .locator(".course-manager label")
    .filter({ hasText: "教师" })
    .first()
    .locator("input:not([type=checkbox])")
    .fill("新教师");
  await f.page.getByRole("button", { name: "预览并保存", exact: true }).click();
  await expect(
    f.page.getByRole("button", { name: "确认一次原子保存", exact: true }),
  ).toBeVisible();
  assert.equal(f.app.db.frame.revision, before);
  await f.page.screenshot({
    path: fileURLToPath(
      new URL("../../docs/screenshots/pr71/preview.png", import.meta.url),
    ),
  });
  await f.page
    .getByRole("button", { name: "确认一次原子保存", exact: true })
    .click();
  await expect(
    f.page.getByRole("button", { name: "编辑整门课程", exact: true }),
  ).toBeVisible();
  assert.equal(f.app.db.frame.revision, before + 1);
  assert.equal(f.app.db.frame.local.classOverrides[0].teacher, "新教师");
  assert.deepEqual(f.errors, []);
});

async function editorFixture(t) {
  const f = await fixture(t);
  await f.app.db.edit({
    kind: "entry",
    action: "upsert",
    value: {
      id: "x",
      title: "保留草稿",
      day: 1,
      startMinute: 480,
      endMinute: 540,
      location: "",
    },
  });
  f.app.db.frame.local.sharedRecords = [];
  f.app.db.frame.v3 = { binding: { format: 4 } };
  await f.open();
  await f.page.locator("#coursesPage").click();
  await f.page
    .getByRole("button", { name: "编辑整门课程", exact: true })
    .click();
  return f;
}
test("颜色取消及窄屏离散周选择不写入", async (t) => {
  const f = await editorFixture(t),
    revision = f.app.db.frame.revision;
  await f.page.setViewportSize({ width: 390, height: 844 });
  await f.page.getByRole("button", { name: "#8844cc", exact: true }).click();
  await f.page.getByRole("button", { name: "新增时段", exact: true }).click();
  const weeks = f.page.locator(".week-picker input");
  await weeks.nth(0).check();
  await weeks.nth(2).check();
  await weeks.nth(4).check();
  assert.equal(await f.page.locator(".week-picker input:checked").count(), 3);
  await f.page.screenshot({
    path: fileURLToPath(
      new URL("../../docs/screenshots/pr71/weeks-narrow.png", import.meta.url),
    ),
  });
  await f.page.getByRole("button", { name: "取消草稿", exact: true }).click();
  await f.page.locator("#feedbackAccept").click();
  await expect(f.page.locator(".course-manager")).not.toBeVisible();
  assert.equal(f.app.db.frame.revision, revision);
  assert.equal(f.app.db.frame.local.sharedRecords.length, 0);
});
test("预览后外部 revision 改变拒绝，输入保留", async (t) => {
  const f = await editorFixture(t);
  const label = f.page
    .locator(".course-manager label")
    .filter({ hasText: "教师" })
    .first();
  await label.locator("input[type=checkbox]").check();
  await label.locator("input:not([type=checkbox])").fill("待保存教师");
  await f.page.getByRole("button", { name: "预览并保存", exact: true }).click();
  await f.app.db.edit({
    kind: "entry",
    action: "upsert",
    value: {
      id: "other",
      title: "并发课程",
      day: 2,
      startMinute: 600,
      endMinute: 660,
      location: "",
    },
  });
  const rev = f.app.db.frame.revision;
  await f.page
    .getByRole("button", { name: "确认一次原子保存", exact: true })
    .click();
  await expect(f.page.locator(".course-manager [role=alert]")).toContainText(
    "过期",
  );
  await expect(label.locator("input:not([type=checkbox])")).toHaveValue(
    "待保存教师",
  );
  assert.equal(f.app.db.frame.revision, rev);
  assert.equal(f.app.db.frame.local.classOverrides.length, 0);
});

test("课程提交回执丢失后只读核对哈希，不自动重发", async (t) => {
  const f = await editorFixture(t),
    revision = f.app.db.frame.revision;
  let writes = 0;
  await f.page.route("**/api/courses/save", async (route) => {
    writes++;
    await route.fetch();
    await route.abort("failed");
  });
  await f.page.getByRole("button", { name: "#8844cc", exact: true }).click();
  await f.page.getByRole("button", { name: "预览并保存", exact: true }).click();
  await f.page
    .getByRole("button", { name: "确认一次原子保存", exact: true })
    .click();
  await expect(f.page.locator("#writeStatus")).toContainText("结果不确定");
  await expect(f.page.locator("#writeStatus")).toContainText("与本次候选一致");
  assert.equal(f.app.db.frame.revision, revision + 1);
  await f.page
    .getByRole("button", { name: "确认一次原子保存", exact: true })
    .click();
  await expect(f.page.locator(".course-manager [role=alert]")).toContainText(
    "不能重复提交",
  );
  assert.equal(writes, 1);
});
test("迟到课程列表响应不能重新打开关闭的管理窗口", async (t) => {
  const f = await fixture(t);
  let release;
  const gate = new Promise((r) => (release = r));
  await f.page.route("**/api/courses?*", async (route) => {
    await gate;
    await route.continue();
  });
  await f.open();
  await f.page.locator("#coursesPage").click();
  await f.page
    .locator(".course-manager")
    .getByRole("button", { name: "关闭", exact: true })
    .click();
  release();
  await expect(f.page.locator(".course-manager")).not.toBeVisible();
  assert.deepEqual(f.errors, []);
});

test("新增离散周、复制、停用和删除独立时段使用独立草稿事务", async (t) => {
  const { scheduleSlots } = await import("../src/shared-course-schedule.mjs");
  const f = await editorFixture(t);
  await f.page.getByRole("button", { name: "新增时段", exact: true }).click();
  const card = f.page.locator(".slot-card").last();
  await card.locator("input[type=date]").fill("2026-09-07");
  for (const n of [0, 2, 4])
    await card.locator(".week-picker input").nth(n).check();
  async function save() {
    await f.page
      .getByRole("button", { name: "预览并保存", exact: true })
      .click();
    await f.page
      .getByRole("button", { name: "确认一次原子保存", exact: true })
      .click();
    await expect(
      f.page.getByRole("button", { name: "编辑整门课程", exact: true }),
    ).toBeVisible();
  }
  async function reopen() {
    await f.page
      .getByRole("button", { name: "编辑整门课程", exact: true })
      .click();
  }
  await save();
  let rows = scheduleSlots(f.app.db.frame.local);
  assert.equal(rows.length, 1);
  assert.deepEqual(rows[0].weeks, [1, 3, 5]);
  const original = rows[0].id;
  await reopen();
  await f.page
    .getByRole("button", { name: "复制为独立新增时段", exact: true })
    .click();
  await save();
  rows = scheduleSlots(f.app.db.frame.local);
  assert.equal(rows.length, 2);
  assert.equal(new Set(rows.map((s) => s.id)).size, 2);
  await reopen();
  await f.page
    .getByRole("button", { name: "停用安排", exact: true })
    .first()
    .click();
  await save();
  assert.equal(
    scheduleSlots(f.app.db.frame.local).filter((s) => !s.enabled).length,
    1,
  );
  await reopen();
  const toDelete = scheduleSlots(f.app.db.frame.local)[0].id;
  await f.page
    .getByRole("button", { name: "删除独立新增时段", exact: true })
    .first()
    .click();
  await save();
  assert.equal(scheduleSlots(f.app.db.frame.local).length, 1);
  assert.equal(
    scheduleSlots(f.app.db.frame.local).some((s) => s.id === toDelete),
    false,
  );
});

test("时段分页有界渲染，跨页草稿在同一事务保存且保留未提交时段", async (t) => {
  const { scheduleRecord, scheduleSlots } = await import(
      "../src/shared-course-schedule.mjs"
    ),
    { durableRecords } = await import("../src/shared-records.mjs");
  const f = await fixture(t);
  await f.app.db.edit({
    kind: "entry",
    action: "upsert",
    value: {
      id: "x",
      title: "分页课程",
      day: 1,
      startMinute: 480,
      endMinute: 540,
      location: "",
    },
  });
  const slots = Array.from({ length: 22 }, (_, i) => ({
    id: "slot-" + String(i).padStart(2, "0"),
    sourceId: null,
    ownerSeries: "x",
    replacesSeries: null,
    week1: "2026-09-07",
    weeks: [1],
    weekday: 2,
    startMinute: 480,
    endMinute: 540,
    periodStart: 0,
    periodEnd: 0,
    enabled: true,
    title: "时段 " + i,
    location: "原地点 " + i,
    teacher: "",
    note: "",
  }));
  f.app.db.frame.local.sharedRecords = durableRecords(
    slots.map(scheduleRecord),
  );
  f.app.db.frame.v3 = { binding: { format: 4 } };
  const rev = f.app.db.frame.revision;
  await f.open();
  await f.page.locator("#coursesPage").click();
  await f.page
    .getByRole("button", { name: "编辑整门课程", exact: true })
    .click();
  await expect(f.page.locator("[data-slot-id]")).toHaveCount(20);
  await f.page
    .locator('[data-slot-id="slot-00"] label')
    .filter({ hasText: "地点" })
    .locator("input")
    .fill("第一页修改");
  await f.page
    .getByRole("button", { name: "下一页时段（保留草稿）", exact: true })
    .click();
  await expect(f.page.locator("[data-slot-id]")).toHaveCount(2);
  await f.page
    .locator('[data-slot-id="slot-21"] label')
    .filter({ hasText: "地点" })
    .locator("input")
    .fill("第二页修改");
  await f.page.getByRole("button", { name: "预览并保存", exact: true }).click();
  await f.page
    .getByRole("button", { name: "确认一次原子保存", exact: true })
    .click();
  await expect(
    f.page.getByRole("button", { name: "编辑整门课程", exact: true }),
  ).toBeVisible();
  const saved = scheduleSlots(f.app.db.frame.local);
  assert.equal(saved.length, 22);
  assert.equal(saved.find((s) => s.id === "slot-00").location, "第一页修改");
  assert.equal(saved.find((s) => s.id === "slot-21").location, "第二页修改");
  assert.equal(saved.find((s) => s.id === "slot-01").location, "原地点 1");
  assert.equal(f.app.db.frame.revision, rev + 1);
});

test("无限原课程有限替换须确认；停用仍抑制，移除规则恢复基线", async (t) => {
  const { scheduleSlots } = await import("../src/shared-course-schedule.mjs"),
    { projectWeek } = await import("../src/projection.mjs");
  const f = await editorFixture(t),
    rev = f.app.db.frame.revision;
  await f.page
    .getByRole("button", { name: "有限周替换原始系列", exact: true })
    .click();
  await f.page.locator("#feedbackCancel").click();
  await expect(f.page.locator("[data-slot-id]")).toHaveCount(0);
  assert.equal(f.app.db.frame.revision, rev);
  await f.page
    .getByRole("button", { name: "有限周替换原始系列", exact: true })
    .click();
  await f.page.locator("#feedbackAccept").click();
  const card = f.page.locator("[data-slot-id]");
  await expect(card).toHaveCount(1);
  await expect(card.locator("input[type=date]")).toHaveValue("");
  assert.equal(await card.locator(".week-picker input:checked").count(), 0);
  await card.locator("input[type=date]").fill("2026-09-07");
  for (const n of [0, 2])
    await card.locator(".week-picker input").nth(n).check();
  async function save() {
    await f.page
      .getByRole("button", { name: "预览并保存", exact: true })
      .click();
    await f.page
      .getByRole("button", { name: "确认一次原子保存", exact: true })
      .click();
    await expect(
      f.page.getByRole("button", { name: "编辑整门课程", exact: true }),
    ).toBeVisible();
  }
  await save();
  assert.equal(scheduleSlots(f.app.db.frame.local)[0].replacesSeries, "x");
  assert.equal(projectWeek(f.app.db.frame.local, "2026-09-14").length, 0);
  await f.page
    .getByRole("button", { name: "编辑整门课程", exact: true })
    .click();
  await f.page.getByRole("button", { name: "停用安排", exact: true }).click();
  await save();
  assert.equal(projectWeek(f.app.db.frame.local, "2026-09-07").length, 0);
  await f.page
    .getByRole("button", { name: "编辑整门课程", exact: true })
    .click();
  await f.page
    .getByRole("button", {
      name: "移除替换规则（可能恢复原始课程）",
      exact: true,
    })
    .click();
  await save();
  assert.equal(scheduleSlots(f.app.db.frame.local).length, 0);
  assert.equal(projectWeek(f.app.db.frame.local, "2026-09-07").length, 1);
  assert.equal(f.app.db.frame.revision, rev + 3);
});
