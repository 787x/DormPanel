// Run with playwright-core on NODE_PATH and a local Chromium executable:
// DORMPANEL_CHROMIUM=/path/to/chrome node --test homeassistant/tests/verify_frontend.cjs
const assert = require("node:assert/strict");
const path = require("node:path");
const { test, before, after } = require("node:test");
const { chromium } = require("playwright-core");

let browser;
before(async () => {
  browser = await chromium.launch({ executablePath: process.env.DORMPANEL_CHROMIUM });
});
after(async () => browser?.close());

async function fixture(t, admin = true, faults) {
  const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
  t.after(() => page.close());
  const errors = [];
  page.on("pageerror", error => errors.push(error.message));
  t.after(() => assert.deepEqual(errors, []));
  page.on("dialog", dialog => dialog.accept());
  if (faults) {
    const now = new Date("2026-01-01T00:00:00Z");
    await page.clock.install({ time: now });
    await page.clock.pauseAt(new Date(+now + 1000));
  }
  await page.setContent(`<style>
    body { margin: 0; font-family: sans-serif; }
    dormpanel-overview { width: 300px; }
    ha-card { display: block; background: var(--ha-card-background, white); }
  </style><dormpanel-panel></dormpanel-panel>
  <dormpanel-overview id="cardA"></dormpanel-overview><dormpanel-overview id="cardB"></dormpanel-overview>`);
  await page.addScriptTag({ path: path.resolve(__dirname, "../custom_components/dormpanel/frontend/panel.js") });
  await page.evaluate(({ is_admin, faults }) => {
    const values = {
      display_brightness: 52, card_opacity: 80, theme: "dark", follow_system: false,
      system_brightness: 40, media_volume: 33, system_automatic: false,
      blackout: false, keep_awake: false, start_after_boot: false,
    };
    const screen = (installation_id, display_name, capabilities, brightness) => ({
      installation_id, display_name, capabilities, last_seen: 1700000000, app_version: "fixture",
      control: { version: 1, online: true, values: { ...values, display_brightness: brightness },
        reasons: {}, mute_available: false },
    });
    const a = screen("a", "客厅设备", ["schedule_csv_v1", "schedule_xls_v1", "apk_install_v1"], 52);
    const b = screen("b", "书房设备", [], 23);
    const mock = window.mock = {
      a, b, screens: [a, b], transfers: [], commands: [], reads: 0, subscriptions: new Set(),
      uploads: [], listeners: new Map(), subscribed: 0, unsubscribed: 0,
      readFailures: 0, subscribeFailures: 0, ...faults,
      changed() { for (const callback of this.subscriptions) callback(); },
      emit(event) { for (const callback of this.listeners.get(event) || []) callback(); },
      connection: {
        connected: true,
        addEventListener(name, fn) {
          if (!mock.listeners.has(name)) mock.listeners.set(name, new Set());
          mock.listeners.get(name).add(fn);
        },
        removeEventListener(name, fn) { mock.listeners.get(name).delete(fn); },
        async subscribeEvents(fn, type) {
          if (type !== "dormpanel_transfer_changed") throw new Error(type);
          mock.subscribed++;
          if (mock.subscribeFailures > 0) {
            mock.subscribeFailures--;
            throw new Error("订阅暂时失败");
          }
          if (mock.holdSubscription) {
            mock.holdSubscription = false;
            await new Promise(resolve => { mock.releaseSubscription = resolve; });
          }
          mock.subscriptions.add(fn);
          return async () => { mock.unsubscribed++; mock.subscriptions.delete(fn); };
        },
        async sendMessagePromise(msg) {
          if (msg.type === "dormpanel/admin_state") {
            mock.reads++;
            if (mock.readFailures > 0) {
              mock.readFailures--;
              throw new Error("读取暂时失败");
            }
            const snapshot = structuredClone({ screens: mock.screens, transfers: mock.transfers });
            if (mock.holdRead) {
              mock.holdRead = false;
              await new Promise(resolve => { mock.releaseRead = resolve; });
            }
            return snapshot;
          }
          if (msg.type === "dormpanel/cancel_transfer") {
            mock.transfers = mock.transfers.filter(row => row.transfer_id !== msg.transfer_id);
            mock.changed();
            return;
          }
          if (msg.type === "dormpanel/remove_screen") {
            mock.screens = mock.screens.filter(row => row.installation_id !== msg.installation_id);
            mock.changed();
            return;
          }
          if (msg.type !== "dormpanel/control_set") throw new Error(msg.type);
          const device = mock.screens.find(row => row.installation_id === msg.installation_id);
          return new Promise((resolve, reject) => mock.commands.push({
            msg, fail(code) { reject({ code }); },
            ack(value = msg.value) {
              device.control.values[msg.field] = value;
              mock.changed();
              resolve({ value });
            },
            staleReceipt(value) { resolve({ value }); },
          }));
        },
      },
      hass(darkMode = false) {
        return {
          user: { is_admin }, themes: { darkMode }, connection: this.connection,
          async fetchWithAuth(url, { method, body }) {
            const kind = body.get("kind");
            const targets = JSON.parse(body.get("targets"));
            const retention = Number(body.get("retention"));
            const { name, size } = body.get("file");
            mock.uploads.push({ url, method, kind, targets, retention, name, size });
            if (mock.holdUpload) await new Promise(resolve => { mock.releaseUpload = resolve; });
            if (mock.rejectUpload) return new Response("", { status: 403 });
            mock.transfers.push({
              kind, transfer_id: String(mock.uploads.length), filename: name, file_available: true,
              created_at: 1700000000, expires_at: 1700000000 + retention,
              targets: targets.map(id => ({ ...mock.screens.find(s => s.installation_id === id), state: "pending" })),
            });
            mock.changed();
            return new Response(null, { status: 200 });
          },
        };
      },
    };
    document.querySelector("#cardA").setConfig({ device: "a" });
    document.querySelector("#cardB").setConfig({ device: "b" });
    for (const view of document.querySelectorAll("dormpanel-panel, dormpanel-overview")) view.hass = mock.hass();
  }, { is_admin: admin, faults });
  if (admin && !faults) await page.waitForFunction(() => document.querySelector("#cardB").screen?.installation_id === "b");
  return page;
}

const panel = "dormpanel-panel";
const ready = (page, selector) => page.waitForFunction(s => {
  const view = document.querySelector(s);
  return !view.relay.commands.has(view.screen.installation_id) && view.available;
}, selector);
const update = (page, fn) => page.evaluate(fn);
const commands = page => page.evaluate(() => mock.commands.map(command => command.msg));
const count = (page, n) => page.waitForFunction(n => mock.commands.length === n, n);

test("初次和运行中读取失败时，多个界面共享退避重试", async t => {
  const page = await fixture(t, true, { readFailures: 7 });
  const reads = () => page.evaluate(() => mock.reads);
  const allAvailable = () => page.evaluate(() => [...document.querySelectorAll("dormpanel-panel, dormpanel-overview")]
    .every(view => view.available));
  assert.equal(await reads(), 1);
  assert.equal(await allAvailable(), false);
  for (const [index, delay] of [1000, 2000, 4000, 8000, 16000, 30000, 30000].entries()) {
    await page.clock.runFor(delay - 1);
    assert.equal(await reads(), index + 1);
    await page.clock.runFor(1);
    assert.equal(await reads(), index + 2);
  }
  assert.equal(await allAvailable(), true);
  await update(page, () => {
    mock.readFailures = 1;
    mock.a.control.values.display_brightness = 73;
    mock.changed();
  });
  assert.equal(await allAvailable(), false);
  assert.match(await page.locator("#cardA main").innerText(), /读取设备与传输失败.*上次读取结果/);
  await update(page, () => { for (let i = 0; i < 20; i++) mock.changed(); });
  assert.equal(await reads(), 9);
  await page.clock.runFor(1000);
  assert.equal(await reads(), 10);
  assert.equal(await allAvailable(), true);
  assert.equal(await page.locator("#cardA #display_brightness").inputValue(), "73");
  await page.clock.runFor(31000);
  assert.deepEqual(await page.evaluate(() => [mock.reads, mock.subscribed, mock.commands.length]), [10, 1, 0]);
});

test("订阅在同一连接中恢复并补读状态，正常重连沿用原有订阅", async t => {
  const page = await fixture(t, true, { subscribeFailures: 2 });
  const attempts = () => page.evaluate(() => [mock.subscribed, mock.subscriptions.size, mock.reads]);
  assert.deepEqual(await attempts(), [1, 0, 1]);
  assert.match(await page.locator("#cardA main").innerText(), /实时更新不可用/);
  await page.clock.runFor(1000);
  assert.deepEqual(await attempts(), [2, 0, 1]);
  await update(page, () => { mock.a.control.values.display_brightness = 68; });
  await page.clock.runFor(1999);
  assert.deepEqual(await attempts(), [2, 0, 1]);
  await page.clock.runFor(1);
  assert.deepEqual(await attempts(), [3, 1, 2]);
  assert.doesNotMatch(await page.locator("#cardA main").innerText(), /实时更新不可用/);
  assert.equal(await page.locator("#cardA #display_brightness").inputValue(), "68");
  await update(page, () => {
    mock.a.control.values.display_brightness = 84;
    mock.changed();
  });
  assert.equal(await page.locator("#cardA #display_brightness").inputValue(), "84");
  await update(page, () => {
    mock.connection.connected = false;
    mock.emit("disconnected");
    mock.connection.connected = true;
    mock.emit("ready");
  });
  await page.clock.runFor(31000);
  assert.deepEqual(await attempts(), [3, 1, 4]);
});

test("断线和最后一个界面卸载会取消重试并释放迟到的订阅", async t => {
  const page = await fixture(t, true, { subscribeFailures: 2, readFailures: 2 });
  const attempts = () => page.evaluate(() => [mock.subscribed, mock.subscriptions.size, mock.reads]);
  assert.deepEqual(await attempts(), [1, 0, 1]);
  await update(page, () => {
    mock.connection.connected = false;
    mock.emit("disconnected");
  });
  await page.clock.runFor(31000);
  assert.deepEqual(await attempts(), [1, 0, 1]);
  await update(page, () => {
    mock.connection.connected = true;
    mock.emit("ready");
  });
  assert.deepEqual(await attempts(), [2, 0, 2]);
  await update(page, () => {
    mock.views = [...document.querySelectorAll("dormpanel-panel, dormpanel-overview")];
    mock.views.forEach(view => view.remove());
  });
  await page.clock.runFor(31000);
  assert.deepEqual(await attempts(), [2, 0, 2]);
  assert.deepEqual(await page.evaluate(() => [...mock.listeners.values()].map(v => v.size)), [0, 0]);

  await update(page, () => {
    mock.holdSubscription = true;
    document.body.append(mock.views[0]);
    mock.views[0].remove();
    document.body.append(mock.views[1]);
  });
  assert.deepEqual(await attempts(), [3, 0, 3]);
  await update(page, () => mock.releaseSubscription());
  assert.deepEqual(await attempts(), [4, 1, 4]);
  await update(page, () => mock.views[1].remove());
  assert.equal(await page.evaluate(() => mock.unsubscribed), 2);
  assert.deepEqual(await attempts(), [4, 0, 4]);
});

test("读取失败和恢复保留各界面的上传草稿，仅发送最新兼容目标", async t => {
  const page = await fixture(t, true, {});
  const card = page.locator("#cardA");
  const checks = selector => page.locator(`${selector} .targets input:checked`).evaluateAll(inputs => inputs.map(el => el.value));
  await update(page, () => {
    const a = document.querySelector("#cardA");
    const b = document.querySelector("#cardB");
    a.adminArea.open = true;
    a.retention.value = "3600";
    a.targets.querySelectorAll("input").forEach(input => input.click());
    b.targets.querySelector('input[value="b"]').click();
  });
  const file = card.locator("input[type=file]");
  await file.setInputFiles({ name: "schedule.ics", mimeType: "text/calendar", buffer: Buffer.from("fixture") });
  await update(page, () => {
    mock.readFailures = 1;
    mock.changed();
  });
  assert.equal(await card.getByRole("button", { name: "发送至所选设备" }).isDisabled(), true);
  assert.equal(await card.locator("#blackout").isDisabled(), true);
  assert.deepEqual(await checks("#cardA"), ["a", "b"]);
  assert.deepEqual(await checks("#cardB"), ["b"]);
  assert.deepEqual(await checks(panel), []);
  await update(page, () => { mock.screens = [mock.a]; });
  await page.clock.runFor(1000);
  assert.deepEqual(await checks("#cardA"), ["a"]);
  assert.deepEqual(await checks("#cardB"), []);
  assert.equal(await file.evaluate(input => input.files[0].name), "schedule.ics");
  assert.equal(await card.getByLabel("临时保留").inputValue(), "3600");
  await update(page, () => {
    mock.holdUpload = true;
    document.querySelector("#cardA").submit.click();
    mock.readFailures = 1;
    mock.changed();
  });
  assert.deepEqual(await page.evaluate(() => mock.uploads.map(upload => upload.targets)), [["a"]]);
  assert.equal(await card.locator("progress").isVisible(), true);
  await page.clock.runFor(1000);
  assert.equal(await card.locator("progress").isVisible(), true);
  await update(page, () => {
    mock.holdUpload = false;
    mock.releaseUpload();
  });
  assert.equal(await card.locator("progress").isVisible(), false);

  await update(page, () => {
    mock.screens.push(mock.b);
    mock.changed();
  });
  await update(page, () => {
    const view = document.querySelector("#cardA");
    view.targets.querySelector('input[value="b"]').click();
    view.kind.value = "apk";
    view.kind.dispatchEvent(new Event("change"));
  });
  assert.deepEqual(await checks("#cardA"), ["a"]);
  await update(page, () => {
    mock.a.capabilities = [];
    mock.changed();
  });
  assert.deepEqual(await checks("#cardA"), []);
  assert.equal(await card.getByRole("button", { name: "发送至所选设备" }).isDisabled(), true);
});

test("10 项设置、实际回执、本机上报、拖动与三个实例共享订阅", async t => {
  const page = await fixture(t);
  assert.deepEqual(await page.evaluate(() => [mock.subscribed, mock.reads]), [1, 1]);
  const requested = [
    ["display_brightness", 100], ["card_opacity", 0], ["theme", "light"],
    ["system_brightness", 100], ["media_volume", 100], ["system_automatic", true],
    ["follow_system", true], ["blackout", true], ["keep_awake", true], ["start_after_boot", true],
  ];
  let number = 0;
  for (const selector of [panel, "#cardA"]) {
    await page.evaluate(() => {
      Object.assign(mock.a.control.values, {
        display_brightness: 52, card_opacity: 80, theme: "dark", system_brightness: 40,
        media_volume: 33, system_automatic: false, follow_system: false, blackout: false,
        keep_awake: false, start_after_boot: false,
      });
      mock.changed();
    });
    await page.waitForFunction(() => document.querySelector("#cardA").fields.get("theme").input.value === "dark");
    for (const [field, value] of requested) {
      const input = page.locator(`${selector} #${field}`);
      if (typeof value === "boolean") await input.click();
      else if (typeof value === "string") await input.selectOption(value);
      else await input.press(value === 0 ? "Home" : "End");
      await count(page, ++number);
      assert.deepEqual((await commands(page)).at(-1), {
        type: "dormpanel/control_set", field, value, installation_id: "a",
      });
      assert.match(await page.locator(`${selector} p[role=status]`).allTextContents().then(v => v.join()), /等待设备确认/);
      assert.equal(await page.locator("#cardB #blackout").isEnabled(), true);
      const actual = field === "media_volume" ? 93 : value;
      await page.evaluate(actual => mock.commands.at(-1).ack(actual), actual);
      await ready(page, selector);
      for (const surface of [panel, "#cardA"]) {
        const observed = page.locator(`${surface} #${field}`);
        if (typeof value === "boolean") assert.equal(await observed.isChecked(), actual);
        else assert.equal(await observed.inputValue(), String(actual));
      }
      if (field === "media_volume") assert.match(await input.locator("..").innerText(), /93%/);
    }
  }

  const range = page.locator("#cardA #card_opacity");
  await range.scrollIntoViewIfNeeded();
  const box = await range.boundingBox();
  await page.mouse.move(box.x + 12, box.y + box.height / 2);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width * 0.6, box.y + box.height / 2, { steps: 30 });
  const preview = await range.inputValue();
  await update(page, () => { mock.a.control.values.card_opacity = 14; mock.changed(); });
  await page.waitForFunction(() => document.querySelector("dormpanel-panel").fields.get("card_opacity").input.value === "14");
  assert.equal(await range.inputValue(), preview);
  assert.equal((await commands(page)).length, number);
  await page.mouse.up();
  await count(page, ++number);
  await page.evaluate(() => mock.commands.at(-1).ack());
  await ready(page, "#cardA");
  await range.focus();
  for (let i = 0; i < 10; i++) await page.keyboard.down("ArrowLeft");
  await update(page, () => { mock.a.control.values.card_opacity = 70; mock.changed(); });
  assert.equal((await commands(page)).length, number);
  await page.keyboard.up("ArrowLeft");
  await count(page, ++number);
  await page.evaluate(() => mock.commands.at(-1).ack());
  await ready(page, "#cardA");
  assert.equal(await page.evaluate(() => mock.subscribed), 1);
});

test("权限与依赖、错误、设备切换、断线重连及迟到响应", async t => {
  const page = await fixture(t);
  const a = field => page.locator(`#cardA #${field}`);
  const status = page.locator("#cardA").locator("p[role=status]");
  await update(page, () => {
    mock.a.control.reasons = { system_brightness: "permission", system_automatic: "permission",
      display_brightness: "following", start_after_boot: "unsupported" };
    mock.a.control.values.follow_system = true;
    mock.changed();
  });
  await page.waitForFunction(() => document.querySelector("#cardA").fields.get("system_brightness").input.disabled);
  for (const field of ["display_brightness", "start_after_boot", "system_brightness", "system_automatic"]) {
    assert.equal(await a(field).isDisabled(), true);
  }
  assert.match(await a("system_brightness").locator("..").innerText(), /授权/);
  await update(page, () => {
    mock.a.control.reasons = { system_brightness: "automatic" };
    mock.a.control.values.system_automatic = true;
    mock.changed();
  });
  await page.waitForFunction(() => document.querySelector("#cardA").fields.get("system_automatic").input.disabled === false);
  assert.match(await a("system_brightness").locator("..").innerText(), /关闭系统自动亮度/);
  await a("media_volume").press("Home");
  await count(page, 1);
  assert.equal((await commands(page)).at(-1).value, 1);
  await page.evaluate(() => mock.commands.at(-1).ack(7));
  await ready(page, "#cardA");
  assert.equal(await a("media_volume").inputValue(), "7");

  let n = 1;
  for (const [code, expected] of [
    ["offline", /设备离线/], ["permission", /授权/], ["busy", /另一项设置/],
    ["timeout", /未在时限内确认/], ["execution_failed", /未能应用/],
  ]) {
    await a("keep_awake").click();
    await count(page, ++n);
    assert.equal(await page.locator(`${panel} #blackout`).isDisabled(), true);
    await page.evaluate(code => mock.commands.at(-1).fail(code), code);
    await ready(page, "#cardA");
    assert.match((await status.allTextContents()).join(), expected);
    assert.equal(await a("keep_awake").isChecked(), false);
  }
  await update(page, () => { mock.a.control.online = false; mock.changed(); });
  await page.waitForFunction(() => document.querySelector("#cardA").fields.get("theme").input.disabled);
  assert.match((await status.allTextContents()).join(), /尚未建立原生控制会话/);
  await update(page, () => { mock.a.control.online = true; mock.changed(); });
  await a("keep_awake").click();
  await count(page, ++n);
  await page.locator("#cardA select[aria-label='已注册设备']").selectOption("b");
  await page.evaluate(() => mock.commands.at(-1).ack());
  await page.waitForFunction(() => document.querySelector("dormpanel-panel").fields.get("keep_awake").input.checked);
  assert.equal(await a("keep_awake").isChecked(), false);
  assert.doesNotMatch((await status.allTextContents()).join(), /设备已确认/);

  // Native events from a drag begun on B must not turn into a command for A.
  await a("card_opacity").dispatchEvent("pointerdown");
  await a("card_opacity").evaluate(el => {
    el.value = "65";
    el.dispatchEvent(new Event("input", { bubbles: true }));
  });
  const picker = page.locator("#cardA select[aria-label='已注册设备']");
  await picker.selectOption("a");
  await a("card_opacity").evaluate(el => {
    el.value = "64";
    el.dispatchEvent(new Event("input", { bubbles: true }));
    el.dispatchEvent(new Event("change", { bubbles: true }));
  });
  assert.equal((await commands(page)).length, n);
  assert.equal(await a("card_opacity").inputValue(), "80");
  await picker.selectOption("b");

  await a("blackout").click();
  await count(page, ++n);
  await update(page, () => { mock.holdRead = true; mock.changed(); });
  await page.waitForFunction(() => mock.releaseRead);
  await update(page, () => {
    mock.connection.connected = false;
    mock.emit("disconnected");
  });
  assert.match((await status.allTextContents()).join(), /未收到设备确认/);
  assert.equal(await a("blackout").isDisabled(), true);
  await update(page, () => {
    mock.b.control.values.display_brightness = 86;
    mock.connection.connected = true;
    mock.emit("ready");
  });
  await page.waitForFunction(() => document.querySelector("#cardB").fields.get("display_brightness").input.value === "86");
  await update(page, () => {
    mock.releaseRead();
    mock.commands.at(-1).staleReceipt(true);
  });
  assert.equal(await a("display_brightness").inputValue(), "86");
  assert.equal(await a("blackout").isChecked(), false);
  assert.equal((await commands(page)).length, n);
  assert.equal(await page.evaluate(() => mock.subscribed), 1);
  await update(page, () => document.querySelector("#cardA").remove());
  assert.equal(await page.evaluate(() => mock.subscriptions.size), 1);
  await update(page, () => document.querySelectorAll("dormpanel-panel, #cardB").forEach(view => view.remove()));
  assert.deepEqual(await page.evaluate(() => [mock.unsubscribed, ...Array.from(mock.listeners.values(), v => v.size)]), [1, 0, 0]);
});

test("文件发送、记录清理、设备移除、窄屏和深浅主题", async t => {
  const page = await fixture(t);
  const card = "#cardA";
  const cardLocator = page.locator(`${card} main`);
  await cardLocator.getByText("发送文件与传输记录").click();
  assert.equal(await page.evaluate(() => document.querySelector("#cardB").adminArea.open), false);
  for (const [kind, ext] of [["schedule_ics", "ics"], ["schedule_csv", "csv"], ["schedule_xls", "xls"], ["apk", "apk"]]) {
    await cardLocator.getByLabel("文件类型").selectOption(kind);
    const checks = cardLocator.locator(".targets input");
    assert.equal(await checks.count(), ext === "ics" ? 2 : 1);
    for (const check of await checks.all()) await check.check();
    await cardLocator.getByLabel("临时保留").selectOption("3600");
    const file = cardLocator.locator("input[type=file]");
    await file.setInputFiles({ name: "wrong.txt", mimeType: "text/plain", buffer: Buffer.from("x") });
    await cardLocator.getByRole("button", { name: "发送至所选设备" }).click();
    assert.match(await cardLocator.innerText(), /请选择一个符合大小限制/);
    if (ext === "ics") {
      await file.setInputFiles({ name: "large.ics", mimeType: "text/calendar", buffer: Buffer.alloc(1048577) });
      await cardLocator.getByRole("button", { name: "发送至所选设备" }).click();
      assert.equal(await page.evaluate(() => mock.uploads.length), 0);
      await update(page, () => { mock.holdUpload = true; });
    }
    await file.setInputFiles({ name: `course.${ext}`, mimeType: "application/octet-stream", buffer: Buffer.from("fixture") });
    await update(page, () => { mock.a.control.values.card_opacity++; mock.changed(); });
    assert.equal(await file.evaluate(el => el.files[0].name), `course.${ext}`);
    await cardLocator.getByRole("button", { name: "发送至所选设备" }).click();
    if (ext === "ics") {
      assert.equal(await cardLocator.locator("progress").isVisible(), true);
      await update(page, () => { mock.holdUpload = false; mock.releaseUpload(); });
    }
    const transfer = cardLocator.getByRole("button", { name: "取消并清理" });
    await transfer.waitFor();
    assert.match(await cardLocator.innerText(), /等待设备处理/);
    assert.deepEqual(await page.evaluate(() => mock.uploads.at(-1)), {
      url: "/api/dormpanel/upload", method: "POST", kind, targets: ext === "ics" ? ["a", "b"] : ["a"],
      retention: 3600, name: `course.${ext}`, size: 7,
    });
    if (ext === "ics") {
      await transfer.focus();
      await update(page, () => {
        mock.transfers[0].targets[0].state = "imported";
        mock.changed();
      });
      await cardLocator.getByText(/客厅设备.*已导入/).waitFor();
      assert.equal(await transfer.evaluate(el => el.getRootNode().activeElement === el), true);
    }
    await transfer.click();
    await transfer.waitFor({ state: "detached" });
    await cardLocator.getByText("暂无传输记录。").waitFor();
  }
  await update(page, () => { mock.rejectUpload = true; });
  await cardLocator.locator("input[type=file]").setInputFiles({
    name: "course.apk", mimeType: "application/octet-stream", buffer: Buffer.from("fixture"),
  });
  await cardLocator.getByRole("button", { name: "发送至所选设备" }).click();
  await cardLocator.getByText(/上传失败.*需要管理员权限/).waitFor();
  await cardLocator.getByText("设备管理").click();
  await cardLocator.getByRole("button", { name: "移除设备" }).click();
  await page.waitForFunction(() => document.querySelector("#cardA").screen?.installation_id === "b");

  for (const [width, dark] of [[1280, false], [320, true], [320, false], [1280, true]]) {
    await page.setViewportSize({ width, height: 800 });
    await page.evaluate(dark => {
      for (const view of document.querySelectorAll("dormpanel-panel, dormpanel-overview")) view.hass = mock.hass(dark);
      document.body.style.setProperty("--primary-text-color", dark ? "white" : "#222");
      document.body.style.setProperty("--ha-card-background", dark ? "#202124" : "white");
    }, dark);
    for (const view of [panel, card]) {
      const layout = await page.locator(view).evaluate(view => {
        const main = view.shadowRoot.querySelector("main");
        return { fits: main.scrollWidth <= main.clientWidth, scheme: getComputedStyle(main).colorScheme,
          text: getComputedStyle(main).color };
      });
      assert.deepEqual(layout, { fits: true, scheme: dark ? "dark" : "light",
        text: dark ? "rgb(255, 255, 255)" : "rgb(34, 34, 34)" });
    }
  }
});

test("非管理员没有控制、管理或上传入口，也不创建 WS 订阅", async t => {
  const page = await fixture(t, false);
  const card = page.locator("#cardA main");
  await card.getByText("发送文件与传输记录").click();
  assert.match(await card.innerText(), /需要 Home Assistant 管理员权限/);
  assert.equal(await card.locator("input[type=file]").isVisible(), false);
  assert.equal(await card.locator("#theme").isVisible(), false);
  assert.equal(await card.getByRole("button", { name: "移除设备" }).count(), 0);
  assert.deepEqual(await page.evaluate(() => [mock.subscribed, mock.reads, mock.commands.length]), [0, 0, 0]);
});
