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

for (const kind of ["search", "page"])
  test("查询世代：" + kind + " 旧响应不得覆盖新搜索", async (t) => {
    const f = await fixture(t);
    f.app.db.frame.local = {
      ...emptyState(),
      entries: [
        ...Array.from({ length: 41 }, (_, i) => ({
          id: "a" + i,
          title: "A" + String(i).padStart(2, "0"),
          day: 1,
          startMinute: 480,
          endMinute: 540,
          location: "",
        })),
        {
          id: "b",
          title: "B最新结果",
          day: 1,
          startMinute: 480,
          endMinute: 540,
          location: "",
        },
      ],
      sharedRecords: [],
    };
    f.app.db.frame.v3 = { binding: { format: 4 } };
    let release, entered, delivered;
    const finished = new Promise((r) => (delivered = r));
    const gate = new Promise((r) => (release = r)),
      started = new Promise((r) => (entered = r));
    t.after(() => release());
    await f.page.route("**/api/courses?*", async (route) => {
      const u = new URL(route.request().url());
      if (
        kind === "search"
          ? u.searchParams.get("filter") === "A"
          : u.searchParams.get("offset") === "40"
      ) {
        const response = await route.fetch();
        entered();
        await gate;
        await route.fulfill({ response });
        delivered();
      } else await route.continue();
    });
    await f.open();
    await f.page.locator("#coursesPage").click();
    await expect(f.page.locator(".course-manager .card-grid")).toContainText(
      "A00",
    );
    const input = f.page
      .locator(".course-manager label")
      .filter({ hasText: "搜索课程或来源" })
      .locator("input");
    if (kind === "search") {
      await input.fill("A");
      await f.page.getByRole("button", { name: "搜索", exact: true }).click();
    } else
      await f.page.getByRole("button", { name: "下一页", exact: true }).click();
    await started;
    await input.fill("B");
    await f.page.getByRole("button", { name: "搜索", exact: true }).click();
    await expect(f.page.locator(".course-manager .card-grid")).toContainText(
      "B最新结果",
    );
    release();
    await finished;
    await f.page.evaluate(
      () =>
        new Promise((r) =>
          requestAnimationFrame(() => requestAnimationFrame(r)),
        ),
    );
    await expect
      .poll(async () =>
        f.page.locator(".course-manager .card-grid h3").allTextContents(),
      )
      .toEqual(["B最新结果"]);
    await expect(input).toHaveValue("B");
    assert.deepEqual(f.errors, []);
  });
test("原生颜色、色板与恢复自动即时预览相同草稿，取消不写", async (t) => {
  const f = await editorFixture(t),
    rev = f.app.db.frame.revision;
  const picker = f.page.locator(".course-manager input[type=color]"),
    section = picker.locator("..").locator("..");
  await picker.fill("#123abc");
  await expect(section).toHaveCSS("border-left-color", "rgb(18, 58, 188)");
  await f.page.getByRole("button", { name: "#8844cc", exact: true }).click();
  await expect(picker).toHaveValue("#8844cc");
  await expect(section).toHaveCSS("border-left-color", "rgb(136, 68, 204)");
  await f.page
    .getByRole("button", { name: "恢复自动颜色", exact: true })
    .click();
  const automatic = await picker.inputValue();
  await expect(section).toHaveCSS(
    "border-left-color",
    "rgb(" +
      [1, 3, 5].map((i) => parseInt(automatic.slice(i, i + 2), 16)).join(", ") +
      ")",
  );
  await f.page.getByRole("button", { name: "预览并保存", exact: true }).click();
  await expect(f.page.locator(".course-manager")).toContainText(
    "颜色：恢复自动",
  );
  await f.page.getByRole("button", { name: "取消草稿", exact: true }).click();
  await f.page.locator("#feedbackAccept").click();
  assert.equal(f.app.db.frame.revision, rev);
  assert.equal(f.app.db.frame.local.sharedRecords.length, 0);
});
import { importedCourse } from "../test-fixtures/course-review.mjs";
for (const configured of [true, false])
  test(
    "导入有限替换使用" + (configured ? "真实教学周" : "明确用户锚点"),
    async (t) => {
      const f = await fixture(t),
        state = await importedCourse({ configured });
      state.imported.reverse();
      f.app.db.frame.local = state;
      f.app.db.frame.v3 = { binding: { format: 4 } };
      const before = structuredClone(state);
      await f.open();
      await f.page.locator("#coursesPage").click();
      await f.page
        .getByRole("button", { name: "编辑整门课程", exact: true })
        .click();
      await expect(f.page.locator(".course-manager")).toContainText(
        configured
          ? "原系列教学周：4、6、10（首周 2026-09-07）"
          : "未关联可靠学期",
      );
      await f.page
        .getByRole("button", { name: "有限周替换原始系列", exact: true })
        .click();
      await f.page.locator("#feedbackAccept").click();
      const card = f.page.locator("[data-slot-id]");
      await expect(card).toHaveCount(1);
      await expect(card.locator("input[type=date]")).toHaveValue(
        configured ? "2026-09-07" : "",
      );
      assert.deepEqual(
        await card
          .locator(".week-picker input:checked")
          .evaluateAll((es) =>
            es.map((e) => Number(e.parentElement.textContent)),
          ),
        configured ? [4, 6, 10] : [],
      );
      if (!configured) {
        await card.locator("input[type=date]").fill("2026-09-07");
        for (const n of [3, 5, 9])
          await card.locator(".week-picker input").nth(n).check();
      }
      await f.page
        .getByRole("button", { name: "预览并保存", exact: true })
        .click();
      await expect(
        f.page.getByRole("button", { name: "确认一次原子保存", exact: true }),
      ).toBeVisible();
      await f.page
        .getByRole("button", { name: "确认一次原子保存", exact: true })
        .click();
      await expect(
        f.page.getByRole("button", { name: "编辑整门课程", exact: true }),
      ).toBeVisible();
      const { projectWeek } = await import("../src/projection.mjs"),
        { scheduleSlots } = await import("../src/shared-course-schedule.mjs");
      const saved = f.app.db.frame.local;
      assert.equal(scheduleSlots(saved)[0].week1, "2026-09-07");
      assert.deepEqual(scheduleSlots(saved)[0].weeks, [4, 6, 10]);
      for (const w of ["2026-09-28", "2026-10-12", "2026-11-09"])
        assert.deepEqual(
          projectWeek(saved, w).map((r) => [r.start, r.end]),
          projectWeek(before, w).map((r) => [r.start, r.end]),
        );
      assert.equal(projectWeek(saved, "2026-09-07").length, 0);
      assert.deepEqual(
        [...saved.imported].sort((a, b) => a.id.localeCompare(b.id)),
        [...before.imported].sort((a, b) => a.id.localeCompare(b.id)),
      );
      assert.deepEqual(saved.profiles, before.profiles);
    },
  );

test("关闭并切换工作区后旧课程查询不能污染重新打开的列表", async (t) => {
  const f = await fixture(t),
    a = f.app.catalog.manifest.activeId;
  await f.app.db.edit({
    kind: "entry",
    action: "upsert",
    value: {
      id: "a",
      title: "A旧工作区",
      day: 1,
      startMinute: 480,
      endMinute: 540,
      location: "",
    },
  });
  const id = randomUUID(),
    db = await Workspace.open(f.app.catalog.workspaceStore(id));
  await db.edit({
    kind: "entry",
    action: "upsert",
    value: {
      id: "b",
      title: "B当前工作区",
      day: 1,
      startMinute: 480,
      endMinute: 540,
      location: "",
    },
  });
  const manifest = structuredClone(f.app.catalog.manifest);
  manifest.workspaces.push({ id, name: "B工作区" });
  manifest.revision++;
  await f.app.catalog.commit(manifest);
  f.app.catalog.attach(id, db);
  let release, entered, delivered;
  const gate = new Promise((r) => (release = r)),
    started = new Promise((r) => (entered = r)),
    finished = new Promise((r) => (delivered = r));
  t.after(() => release());
  await f.page.route("**/api/courses?*", async (route) => {
    if (new URL(route.request().url()).searchParams.get("workspaceId") === a) {
      const response = await route.fetch();
      entered();
      await gate;
      await route.fulfill({ response });
      delivered();
    } else await route.continue();
  });
  await f.open();
  await f.page.locator("#coursesPage").click();
  await started;
  await f.page
    .locator(".course-manager")
    .getByRole("button", { name: "关闭", exact: true })
    .click();
  await f.page.locator("#workspaceSelect").selectOption(id);
  await expect(f.page.locator("#currentContext")).toContainText("B工作区");
  await f.page.locator("#coursesPage").click();
  await expect(f.page.locator(".course-manager .card-grid")).toContainText(
    "B当前工作区",
  );
  release();
  await finished;
  await f.page.evaluate(
    () =>
      new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))),
  );
  await expect(f.page.locator(".course-manager .card-grid h3")).toHaveText([
    "B当前工作区",
  ]);
  assert.deepEqual(f.errors, []);
});

async function saveCourseDraft(page) {
  await page.getByRole("button", { name: "预览并保存", exact: true }).click();
  await page
    .getByRole("button", { name: "确认一次原子保存", exact: true })
    .click();
  await expect(
    page.getByRole("button", { name: "编辑整门课程", exact: true }),
  ).toBeVisible();
}
async function reopenCourse(page) {
  await page.getByRole("button", { name: "编辑整门课程", exact: true }).click();
  await expect(page.locator(".course-manager input[type=color]")).toBeVisible();
}
test("颜色状态明确区分已保存、同 RGB 手动草稿、恢复自动与取消", async (t) => {
  const f = await editorFixture(t),
    page = f.page,
    picker = page.locator(".course-manager input[type=color]"),
    status = page.locator("[data-color-draft]");
  const automatic = await picker.inputValue();
  await expect(page.locator(".course-manager")).toContainText(
    "当前：自动颜色（默认）",
  );
  await expect(status).toBeHidden();
  await picker.fill(automatic);
  await expect(status).toHaveText(
    "待保存：手动颜色 " + automatic.toUpperCase() + "（尚未保存）",
  );
  await saveCourseDraft(page);
  await reopenCourse(page);
  await expect(page.locator(".course-manager")).toContainText(
    "当前：手动颜色 " + automatic.toUpperCase(),
  );
  await expect(status).toBeHidden();
  await picker.fill("#123abc");
  await expect(status).toContainText("待保存：手动颜色 #123ABC");
  await page.getByRole("button", { name: "#8844cc", exact: true }).click();
  await expect(status).toContainText("#8844CC");
  await mkdir(new URL("../../docs/screenshots/pr71-ux/", import.meta.url), {
    recursive: true,
  });
  await page.screenshot({
    path: fileURLToPath(
      new URL(
        "../../docs/screenshots/pr71-ux/color-draft.png",
        import.meta.url,
      ),
    ),
  });
  await page.getByRole("button", { name: "恢复自动颜色", exact: true }).click();
  await expect(status).toHaveText("待保存：恢复自动颜色（尚未保存）");
  await expect(picker).toHaveValue(automatic);
  await saveCourseDraft(page);
  await reopenCourse(page);
  await expect(page.locator(".course-manager")).toContainText(
    "当前：自动颜色 #",
  );
  await expect(status).toBeHidden();
  const revision = f.app.db.frame.revision;
  await picker.fill("#abcdef");
  await page.getByRole("button", { name: "取消草稿", exact: true }).click();
  await page.locator("#feedbackAccept").click();
  assert.equal(f.app.db.frame.revision, revision);
  await page.locator("#coursesPage").click();
  await reopenCourse(page);
  await expect(status).toBeHidden();
  await expect(picker).toHaveValue(automatic);
});
for (const kind of ["teacher", "color"])
  test("大量" + kind + "差异默认摘要、明细折叠、范围与截断明确", async (t) => {
    const f = await editorFixture(t),
      page = f.page;
    if (kind === "teacher") {
      const { applyEdit } = await import("../src/workspace.mjs");
      applyEdit(f.app.db.frame.local, {
        kind: "override",
        action: "upsert",
        sourceId: null,
        seriesId: "x",
        anchorDate: "2026-10-26",
        patch: { teacher: "例外教师" },
      });
      const label = page
        .locator(".course-manager label")
        .filter({ hasText: "教师" })
        .first();
      await label.locator("input[type=checkbox]").check();
      await label.locator("input:not([type=checkbox])").fill("统一教师摘要");
    } else
      await page.getByRole("button", { name: "#8844cc", exact: true }).click();
    const response = page.waitForResponse((r) =>
      r.url().includes("/api/courses/preview"),
    );
    await page.getByRole("button", { name: "预览并保存", exact: true }).click();
    const data = await (await response).json(),
      review = page.locator("[data-course-preview]"),
      details = review.locator("[data-projection-details]");
    assert(data.total > 100);
    assert.equal(data.projection.length, 100);
    await expect(details).not.toHaveAttribute("open", "");
    await expect(details.locator("p").last()).toBeHidden();
    await expect(review).toContainText("共 " + data.total + " 条投影差异项");
    await expect(review).toContainText("预览范围：");
    await expect(review).toContainText("范围有限");
    await expect(
      review
        .locator("p")
        .filter({ hasText: "本地无限重复课程的范围外结果不作穷举" }),
    ).toBeVisible();
    if (kind === "teacher")
      await expect(
        review.locator("p").filter({ hasText: "例外可能继续覆盖本次公共字段" }),
      ).toBeVisible();
    await expect(
      page.getByRole("button", { name: "确认一次原子保存", exact: true }),
    ).toBeVisible();
    await review.evaluate((e) => e.scrollIntoView({ block: "start" }));
    await page.screenshot({
      path: fileURLToPath(
        new URL(
          "../../docs/screenshots/pr71-ux/preview-summary-wide-" +
            kind +
            ".png",
          import.meta.url,
        ),
      ),
    });
    await page.setViewportSize({ width: 430, height: 900 });
    await page.evaluate(
      () => (document.querySelector(".course-manager").style.fontSize = "22px"),
    );
    await review.evaluate((e) => e.scrollIntoView({ block: "start" }));
    await page.screenshot({
      path: fileURLToPath(
        new URL(
          "../../docs/screenshots/pr71-ux/preview-summary-" + kind + ".png",
          import.meta.url,
        ),
      ),
    });
    await details.locator("summary").focus();
    await page.keyboard.press("Enter");
    await expect(details).toHaveAttribute("open", "");
    await expect(details).toContainText(
      "共 " + data.total + " 条差异项，仅展示前 100 条",
    );
    await expect(details.locator("p").last()).toBeVisible();
    await details
      .locator("summary")
      .evaluate((e) => e.scrollIntoView({ block: "start" }));
    const confirmBox = await page
      .getByRole("button", { name: "确认一次原子保存", exact: true })
      .boundingBox();
    assert(
      confirmBox &&
        confirmBox.y >= 0 &&
        confirmBox.y + confirmBox.height <= 900,
    );
    await page.screenshot({
      path: fileURLToPath(
        new URL(
          "../../docs/screenshots/pr71-ux/preview-expanded-" + kind + ".png",
          import.meta.url,
        ),
      ),
    });
    await details.locator("summary").click();
    await expect(details.locator("p").last()).toBeHidden();
    await page.locator(".course-manager input[type=color]").fill("#abcdef");
    await expect(review).toHaveCount(0);
  });

test("时段周次摘要区分停用、复制、删除恢复和重建替换；无变更不提交", async (t) => {
  const { scheduleRecord } = await import("../src/shared-course-schedule.mjs"),
    { durableRecords } = await import("../src/shared-records.mjs");
  const f = await fixture(t),
    page = f.page;
  await f.app.db.edit({
    kind: "entry",
    action: "upsert",
    value: {
      id: "x",
      title: "原课程",
      day: 1,
      startMinute: 480,
      endMinute: 540,
      location: "",
    },
  });
  f.app.db.frame.local.sharedRecords = durableRecords([
    scheduleRecord({
      id: "r1",
      sourceId: null,
      ownerSeries: "x",
      replacesSeries: "x",
      week1: "2026-09-07",
      weeks: [1, 2, 3, 4],
      weekday: 1,
      startMinute: 480,
      endMinute: 540,
      periodStart: 0,
      periodEnd: 0,
      enabled: true,
      title: "替换课程",
      location: "A",
      teacher: "",
      note: "",
    }),
  ]);
  f.app.db.frame.v3 = { binding: { format: 4 } };
  await f.open();
  await page.locator("#coursesPage").click();
  await reopenCourse(page);
  await page.getByRole("button", { name: "预览并保存", exact: true }).click();
  await expect(page.locator("[data-course-preview]")).toContainText(
    "没有实际变更，无需保存",
  );
  await expect(
    page.getByRole("button", { name: "确认一次原子保存", exact: true }),
  ).toHaveCount(0);
  await page.locator("[data-slot-id=r1] .week-picker input").nth(1).uncheck();
  await page.getByRole("button", { name: "预览并保存", exact: true }).click();
  let review = page.locator("[data-course-preview]");
  await review.getByText("时段与周次明细", { exact: true }).click();
  await expect(review).toContainText("教学周 1–4 → 1、3–4");
  await page.getByRole("button", { name: "停用安排", exact: true }).click();
  await page.getByRole("button", { name: "预览并保存", exact: true }).click();
  await expect(
    review
      .locator("p")
      .filter({ hasText: "停用替换规则仍抑制原始系列，原课程不会因此恢复。" }),
  ).toBeVisible();
  await page
    .getByRole("button", { name: "复制为独立新增时段", exact: true })
    .click();
  await page.getByRole("button", { name: "预览并保存", exact: true }).click();
  await review.getByText("时段与周次明细", { exact: true }).click();
  await expect(review).toContainText("复制时段：");
  await page
    .getByRole("button", {
      name: "移除替换规则（可能恢复原始课程）",
      exact: true,
    })
    .click();
  await page.getByRole("button", { name: "预览并保存", exact: true }).click();
  await expect(
    review.locator("p").filter({ hasText: "原始系列将重新出现：" }),
  ).toBeVisible();
  await page
    .getByRole("button", { name: "有限周替换原始系列", exact: true })
    .click();
  await page.locator("#feedbackAccept").click();
  const added = page.locator("[data-slot-id]").last();
  await added.locator("input[type=date]").fill("2026-09-07");
  await added.locator(".week-picker input").nth(0).check();
  await page.getByRole("button", { name: "预览并保存", exact: true }).click();
  await expect(
    review.locator("p").filter({ hasText: "原始系列将重新出现：" }),
  ).toHaveCount(0);
  await expect(
    review
      .locator("p")
      .filter({ hasText: "原系列仍受最终替换规则抑制，不会恢复" }),
  ).toBeVisible();
  await expect(
    review.locator("p").filter({ hasText: "有限周替换仅在选定周次上课" }),
  ).toBeVisible();
  assert.equal(f.app.db.frame.local.sharedRecords.length, 1);
});

test("不支持的已保存颜色版本保持只读且不显示待保存普通色", async (t) => {
  const f = await fixture(t),
    { change } = await import("../src/shared-course-colors.mjs");
  await f.app.db.edit({
    kind: "entry",
    action: "upsert",
    value: {
      id: "x",
      title: "未来颜色",
      day: 1,
      startMinute: 480,
      endMinute: 540,
      location: "",
    },
  });
  f.app.db.frame.local = change(
    { ...f.app.db.frame.local, sharedRecords: [] },
    null,
    "x",
    0x123456,
  );
  f.app.db.frame.local.sharedRecords[0].schemaVersion = 2;
  f.app.db.frame.v3 = { binding: { format: 4 } };
  await f.open();
  await f.page.locator("#coursesPage").click();
  await f.page.getByRole("button", { name: "只读查看", exact: true }).click();
  await expect(f.page.locator(".course-manager")).toContainText(
    "当前颜色：不支持的颜色版本",
  );
  await expect(f.page.locator(".course-manager input[type=color]")).toHaveCount(
    0,
  );
  await expect(
    f.page.getByRole("button", { name: "预览并保存", exact: true }),
  ).toHaveCount(0);
});
