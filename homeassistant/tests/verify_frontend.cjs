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

async function fixture(t, admin = true) {
  const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
  t.after(() => page.close());
  const errors = [];
  page.on("pageerror", error => errors.push(error.message));
  t.after(() => assert.deepEqual(errors, []));
  page.on("dialog", dialog => dialog.accept());
  await page.setContent(`<style>
    body { margin: 0; font-family: sans-serif; }
    dormpanel-overview { width: 300px; }
    ha-card { display: block; background: var(--ha-card-background, white); }
  </style><dormpanel-panel></dormpanel-panel>
  <dormpanel-overview id="cardA"></dormpanel-overview><dormpanel-overview id="cardB"></dormpanel-overview>`);
  await page.addScriptTag({ path: path.resolve(__dirname, "../custom_components/dormpanel/frontend/panel.js") });
  await page.evaluate(is_admin => {
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
          mock.subscriptions.add(fn);
          return async () => { mock.unsubscribed++; mock.subscriptions.delete(fn); };
        },
        async sendMessagePromise(msg) {
          if (msg.type === "dormpanel/admin_state") {
            mock.reads++;
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
  }, admin);
  if (admin) await page.waitForFunction(() => document.querySelector("#cardB").screen?.installation_id === "b");
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
