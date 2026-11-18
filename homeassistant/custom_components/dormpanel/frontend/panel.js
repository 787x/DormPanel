(() => {
  if (customElements.get("dormpanel-panel")) return;

  const formats = {
    schedule_ics: ["ICS 课表", ".ics", 1],
    schedule_csv: ["CSV 课表", ".csv", 1, "schedule_csv_v1"],
    schedule_xls: ["湖北大学 XLS 课表", ".xls", 1, "schedule_xls_v1"],
    apk: ["APK 安装包", ".apk", 256, "apk_install_v1"],
  };
  const outcomes = {
    pending: "等待设备处理",
    preview_ready: "设备已准备预览，等待本机确认",
    imported: "已导入",
    installed: "已安装",
    dismissed: "已取消或关闭",
    rejected_invalid: "校验失败，设备已拒绝文件",
    install_failed: "设备安装失败",
  };
  const controlGroups = [
    ["显示与外观", [
      ["display_brightness", "DormPanel 窗口亮度", "range", 1],
      ["card_opacity", "卡片背景不透明度", "range", 0],
      ["theme", "主题", "select"],
      ["follow_system", "跟随系统亮度", "checkbox"],
    ]],
    ["系统亮度与音量", [
      ["system_brightness", "系统屏幕亮度", "range", 1],
      ["media_volume", "媒体音量", "range", 0],
      ["system_automatic", "系统自动亮度", "checkbox"],
    ]],
    ["屏幕与启动", [
      ["blackout", "黑屏", "checkbox"],
      ["keep_awake", "屏幕常亮", "checkbox"],
      ["start_after_boot", "开机启动", "checkbox"],
    ]],
  ];
  const controlErrors = {
    offline: "设备离线或尚未建立原生控制会话。",
    unsupported: "设备不支持此操作。",
    permission: "请在 X08E 上授权 DormPanel 修改系统设置。",
    automatic: "关闭系统自动亮度后可手动调整。",
    following: "关闭跟随系统亮度后可手动调整。",
    busy: "设备正在执行另一项设置，请稍后再试。",
    timeout: "设备未在时限内确认，请核对实际状态后再试。",
    execution_failed: "设备未能应用此设置，请检查设备后再试。",
    invalid_value: "设备不接受此设置值。",
    unauthorized: "此操作需要 Home Assistant 管理员权限。",
  };
  const rangeKeys = new Set(["ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown",
    "Home", "End", "PageUp", "PageDown"]);
  const actualLabel = (type, value) => value == null ? "未上报"
    : type === "range" ? `${value}%`
    : type === "select" ? (value === "light" ? "浅色" : "深色")
    : value ? "开启" : "关闭";
  const connections = new WeakMap();
  const empty = { screens: [], transfers: [] };
  const date = seconds => new Date(seconds * 1000).toLocaleString();
  const message = error => error?.message || String(error);
  const setText = (element, value) => {
    if (element.textContent !== value) element.textContent = value;
  };

  function node(tag, content, attrs = {}) {
    const result = document.createElement(tag);
    if (content != null) result.textContent = content;
    for (const [name, value] of Object.entries(attrs)) result.setAttribute(name, value);
    return result;
  }

  function reconcile(container, items, key, update) {
    const existing = new Map([...container.children].map(element => [element.dataset.key, element]));
    const ordered = items.map(item => {
      const id = key(item);
      const element = update(item, existing.get(id));
      element.dataset.key = id;
      existing.delete(id);
      return element;
    });
    for (const element of existing.values()) element.remove();
    for (const [index, element] of ordered.entries()) {
      if (container.children[index] !== element) {
        container.insertBefore(element, container.children[index] || null);
      }
    }
  }

  // Both views share a single relay snapshot and event subscription per HA connection.
  class RelayState {
    constructor(connection) {
      this.connection = connection;
      this.views = new Set();
      this.commands = new Map();
      this.link = {};
      this.data = null;
      this.ready = () => {
        this.online = true;
        if (this.subscriptionError) this.subscribe();
        else if (this.unsubscribe) this.refresh();
      };
      this.disconnected = () => {
        this.online = false;
        this.fresh = false;
        this.error = "";
        this.request = undefined;
        this.link = {};
        this.commands.clear();
        this.notify();
      };
    }

    attach(view) {
      this.views.add(view);
      if (this.views.size === 1) {
        this.token = {};
        this.online = this.connection.connected !== false;
        this.connection.addEventListener("ready", this.ready);
        this.connection.addEventListener("disconnected", this.disconnected);
        this.subscribe();
      }
      view.updateRelay();
      return () => {
        this.views.delete(view);
        if (this.views.size) return;
        this.token = undefined;
        this.connection.removeEventListener("ready", this.ready);
        this.connection.removeEventListener("disconnected", this.disconnected);
        this.unsubscribe?.().catch(() => {});
        this.unsubscribe = this.request = undefined;
        this.error = this.subscriptionError = "";
        this.data = null;
        this.fresh = false;
      };
    }

    async subscribe() {
      const token = this.token;
      this.subscriptionError = "";
      try {
        const unsubscribe = await this.connection.subscribeEvents(() => {
          if (this.token === token) this.refresh();
        }, "dormpanel_transfer_changed");
        if (this.token !== token) {
          await unsubscribe();
          return;
        }
        this.unsubscribe = unsubscribe;
      } catch (error) {
        if (this.token !== token) return;
        this.subscriptionError = `实时更新不可用。 ${message(error)}`;
        this.notify();
      }
      // Read after registration so changes during setup cannot be missed.
      this.refresh();
    }

    async refresh() {
      if (!this.online || !this.token) return;
      if (this.request) {
        this.request.again = true;
        return this.request.done;
      }
      const request = this.request = {};
      request.done = this.readSnapshots(request);
      return request.done;
    }

    async readSnapshots(request) {
      try {
        do {
          request.again = false;
          try {
            const data = await this.connection.sendMessagePromise({ type: "dormpanel/admin_state" });
            if (this.request !== request) return;
            this.data = data;
            this.fresh = true;
            this.error = "";
          } catch (error) {
            if (this.request !== request) return;
            this.data = null;
            this.fresh = false;
            this.error = message(error);
          }
          this.notify();
        } while (request.again && this.request === request);
      } finally {
        if (this.request === request) this.request = undefined;
      }
    }

    notify() {
      for (const view of this.views) view.updateRelay();
    }
  }

  const style = `
    :host { display: block; min-width: 0; color: var(--primary-text-color, #202124); color-scheme: light dark; }
    :host(dormpanel-panel) { min-height: 100%; background: var(--primary-background-color); }
    main { max-width: 1080px; margin: auto; padding: clamp(12px, 3vw, 24px);
      display: grid; grid-template-columns: minmax(0, 1fr); gap: 12px; overflow-wrap: anywhere; }
    ha-card > main { padding: 12px; gap: 8px; }
    header, .actions { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
    header h1, header h2 { flex: 1; margin: 0; }
    h1 { font-size: 1.65rem; }
    h2 { font-size: 1.15rem; margin: 0 0 12px; }
    p { margin: 6px 0; }
    p:empty { display: none; }
    section, details {
      background: var(--ha-card-background, var(--card-background-color, transparent));
      border: 1px solid var(--divider-color, #8a8a8a55);
      border-radius: var(--ha-card-border-radius, 12px);
      padding: 12px; min-width: 0;
    }
    form { display: grid; gap: 10px; }
    summary { cursor: pointer; min-height: 44px; align-content: center; font-weight: 600; }
    label { display: block; }
    select, input[type=file], button { box-sizing: border-box; min-height: 44px; max-width: 100%; font: inherit; }
    input[type=file] { width: 100%; }
    button, select { padding: 6px 10px; border-radius: 8px; border: 1px solid var(--divider-color, #777);
      background: var(--card-background-color, transparent); color: inherit; }
    button { cursor: pointer; }
    button.primary { background: var(--primary-color, #06759c); color: var(--text-primary-color, white); }
    button:disabled { cursor: default; opacity: .55; }
    button:focus-visible, input:focus-visible, select:focus-visible, summary:focus-visible { outline: 2px solid var(--primary-color); }
    .field { display: grid; gap: 4px; }
    .controls { display: grid; gap: 8px; }
    .settings { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 15rem), 1fr)); gap: 8px 18px; }
    .setting { min-width: 0; padding: 4px 0; }
    .setting label { display: flex; gap: 8px; align-items: center; min-height: 36px; }
    .setting label span { flex: 1; min-width: 0; }
    .setting .actual { font-variant-numeric: tabular-nums; }
    .setting select { min-height: 36px; }
    input[type=range] { display: block; box-sizing: border-box; width: 100%; margin: 0; min-height: 28px; }
    input { accent-color: var(--primary-color); }
    input[type=checkbox] { width: 18px; height: 18px; flex: none; }
    .setting p { font-size: .875em; margin: 2px 0; }
    .compact .settings { grid-template-columns: repeat(auto-fit, minmax(min(100%, 8rem), 1fr)); }
    .compact .setting:has(input[type=range]) { grid-column: 1 / -1; }
    .device-line { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
    .device-line select { max-width: 100%; min-width: 0; }
    .device-line p { font-weight: 600; }
    .diagnostics { margin-top: 8px; }
    .diagnostics summary { min-height: 32px; }
    fieldset { min-width: 0; border: 1px solid var(--divider-color, #8a8a8a55); border-radius: 8px; }
    .targets { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 12rem), 1fr)); }
    .targets label { display: flex; align-items: center; gap: 8px; min-height: 44px; overflow-wrap: anywhere; }
    .row { border-top: 1px solid var(--divider-color, #8a8a8a55); padding: 10px 0; overflow-wrap: anywhere; }
    .muted { color: var(--secondary-text-color, #555); }
    .error { color: var(--error-color, #b00020); }
    [hidden] { display: none !important; }
  `;

  class DormPanelView extends HTMLElement {
    constructor() {
      super();
      this.attachShadow({ mode: "open" });
      this.build();
    }

    set hass(hass) {
      this._hass = hass;
      const scheme = hass?.themes?.darkMode ? "dark" : "light";
      if (this.style.colorScheme !== scheme) this.style.colorScheme = scheme;
      this.connect();
    }

    connectedCallback() { this.connect(); }

    disconnectedCallback() {
      this.detach();
    }

    detach() {
      this.release?.();
      this.release = undefined;
      this.relay = undefined;
      this.controlContext = undefined;
      this.controlAttempt = this.controlFeedback = undefined;
      this.clearDrafts();
    }

    connect() {
      const hass = this._hass;
      if (!this.isConnected) return;
      if (hass?.user?.is_admin && this.relay && this.relay.connection === hass.connection) return;
      this.detach();
      if (hass?.user?.is_admin && hass.connection) {
        this.relay = connections.get(hass.connection) || new RelayState(hass.connection);
        connections.set(hass.connection, this.relay);
        this.release = this.relay.attach(this);
      } else {
        this.updateRelay();
      }
    }

    build() {
      this.adminBusy = new WeakSet();
      const main = node("main");
      const header = node("header");
      const isCard = this.localName === "dormpanel-overview";
      if (isCard) main.className = "compact";
      if (!isCard) {
        this.menu = node("ha-menu-button");
        header.append(this.menu);
      }
      header.append(node(isCard ? "h2" : "h1", "DormPanel"));
      this.connectionStatus = node("p", "正在连接 Home Assistant…", { role: "status", class: "muted" });
      const devices = node("section");
      this.device = node("select", null, { "aria-label": "已注册设备" });
      this.device.onchange = () => {
        this.preferredDevice = this.device.value;
        this.renderDevice();
      };
      this.deviceStatus = node("p", null, { role: "status" });
      const deviceLine = node("div", null, { class: "device-line" });
      deviceLine.append(this.device, this.deviceStatus);
      this.deviceDetails = node("div");
      this.diagnostics = node("details", null, { class: "diagnostics" });
      this.diagnostics.open = !isCard;
      this.diagnostics.append(node("summary", "设备信息与诊断"), this.deviceDetails);
      this.management = node("details");
      this.management.open = !isCard;
      this.removeButton = node("button", "移除设备");
      this.removeButton.onclick = () => {
        const screen = this.screen;
        if (screen && confirm(`移除设备「${screen.display_name}」？`)) {
          this.adminAction("remove_screen", { installation_id: screen.installation_id }, this.removeButton);
        }
      };
      devices.append(deviceLine, this.diagnostics);
      this.management.append(node("summary", "设备管理"), this.removeButton);
      this.controls = node("div", null, { class: "controls" });
      this.buildControls();

      this.adminArea = node("details");
      this.adminArea.open = !isCard;
      this.adminArea.append(node("summary", "发送文件与传输记录"));
      this.adminNotice = node("p", null, { class: "muted" });
      this.adminContent = node("div");
      this.buildUpload();
      this.history = node("div");
      this.actionStatus = node("p", null, { role: "status" });
      this.adminContent.append(node("h2", "传输记录"), this.history);
      this.adminArea.append(this.adminNotice, this.adminContent);
      main.append(header, this.connectionStatus, devices, this.controls,
        this.adminArea, this.management, this.actionStatus);
      if (isCard) {
        const card = node("ha-card");
        card.append(main);
        this.shadowRoot.append(node("style", style), card);
      } else {
        this.shadowRoot.append(node("style", style), main);
      }
      this.updateRelay();
    }

    buildControls() {
      this.fields = new Map();
      for (const [heading, fields] of controlGroups) {
        const section = node("section");
        const grid = node("div", null, { class: "settings" });
        section.append(node("h2", heading), grid);
        for (const [field, title, type, min] of fields) {
          const input = node(type === "select" ? "select" : "input", null,
            { id: field, "aria-describedby": `${field}-actual ${field}-note` });
          const label = node("label", null, { for: field });
          label.append(node("span", title));
          const actual = node("small", null, { id: `${field}-actual`, class: "actual" });
          const note = node("p", null, { id: `${field}-note`, class: "muted" });
          const status = node("p", null, { role: "status" });
          const row = node("div", null, { class: "setting" });
          const setting = { min, type, input, actual, note, status };
          this.fields.set(field, setting);
          if (type === "select") {
            input.add(node("option", "浅色", { value: "light" }));
            input.add(node("option", "深色", { value: "dark" }));
          } else {
            input.type = type;
          }
          if (type === "range") {
            input.max = "100";
            input.step = "1";
            label.append(actual);
            row.append(label, input, note);
            this.bindRange(field, setting);
          } else {
            label.append(input);
            row.append(label, actual, note);
            input.onchange = () => this.commit(field, type === "select" ? input.value : input.checked);
          }
          row.append(status);
          grid.append(row);
        }
        this.controls.append(section);
      }
    }

    bindRange(field, setting) {
      const { input } = setting;
      const end = (commit = false) => {
        const draft = setting.draft;
        setting.draft = undefined;
        if (commit && draft?.changed) this.commit(field, input.valueAsNumber);
        else this.renderControls();
      };
      input.onpointerdown = () => {
        setting.canceled = false;
        setting.draft = {};
      };
      input.onpointerup = () => {
        const draft = setting.draft;
        // Native change normally commits the drag. A click on the current
        // value does not fire change and still needs to end the preview.
        setTimeout(() => { if (draft && setting.draft === draft) end(); }, 0);
      };
      input.onpointercancel = () => end();
      input.onkeydown = event => {
        if (rangeKeys.has(event.key)) {
          if (!event.repeat) setting.canceled = false;
          if (!setting.canceled) setting.draft ??= { keyboard: true };
        }
        if (event.key === "Escape" && setting.draft) {
          setting.canceled = true;
          end();
        }
      };
      input.onkeyup = event => {
        if (rangeKeys.has(event.key) && setting.draft?.keyboard) end(true);
      };
      input.onblur = () => { if (setting.draft) end(); };
      input.oninput = () => {
        if (setting.canceled) return this.renderControls();
        setting.draft ??= {};
        setting.draft.changed = true;
        this.renderControls();
      };
      input.onchange = () => { if (!setting.draft?.keyboard) end(true); };
    }

    clearDrafts() {
      for (const setting of this.fields.values()) {
        if (setting.draft) setting.canceled = true;
        setting.draft = undefined;
      }
    }

    controlReason(screen, field) {
      if (!this.available) return "offline";
      const control = screen?.control;
      if (!control?.version) return "unsupported";
      if (!control.online) return "offline";
      return control.reasons?.[field] || (control.values?.[field] == null ? "unsupported" : "");
    }

    renderControls() {
      const screen = this.screen;
      const control = screen?.control;
      const pending = this.relay?.commands.get(screen?.installation_id);
      if (this.controlAttempt && this.controlAttempt.link !== this.relay?.link) {
        this.controlFeedback = [this.controlAttempt.field, "Home Assistant 连接已断开，未收到设备确认。重连后请核对实际状态。", true];
        this.controlAttempt = undefined;
      }
      this.controls.hidden = !control?.version;
      const [feedbackField, feedback, failed] = this.controlFeedback || [];
      for (const [field, setting] of this.fields) {
        const { input, actual, note, status, type, min, draft } = setting;
        const reason = this.controlReason(screen, field);
        const value = this.available ? control?.values?.[field] : undefined;
        const noMute = field === "media_volume" && !control?.mute_available;
        const current = actualLabel(type, value);
        const preview = draft?.changed ? `预览 ${input.value}%` : "";
        setText(actual, (type === "checkbox" || type === "select") && value != null ? "" : preview || current);
        if (!draft) {
          const disabled = !!reason || !!pending;
          if (input.disabled !== disabled) input.disabled = disabled;
          if (type === "range") {
            const minimum = noMute ? "1" : String(min);
            if (input.min !== minimum) input.min = minimum;
            const position = String(Math.max(value ?? Number(minimum), Number(minimum)));
            if (input.value !== position) input.value = position;
          } else if (type === "select") {
            if (input.value !== (value ?? "")) input.value = value ?? "";
          } else {
            input.checked = value === true;
            input.indeterminate = value == null;
          }
        }
        setText(note, [
          preview ? `设备实际值 ${current}` : "",
          reason && reason !== "offline" ? controlErrors[reason] || "此设置当前不可用。" : "",
          !reason && noMute ? "此设备不支持 0% 静音。" : "",
        ].filter(Boolean).join(" "));
        setText(status, pending?.field === field ? "正在等待设备确认…"
          : !pending && feedbackField === field ? feedback : "");
        status.className = !pending && feedbackField === field && failed ? "error" : "muted";
      }
    }

    async commit(field, value) {
      const relay = this.relay;
      const screen = this.screen;
      const id = screen?.installation_id;
      const { type } = this.fields.get(field);
      const reason = this.controlReason(screen, field)
        || (relay.commands.has(id) ? "busy" : "")
        || (field === "media_volume" && value === 0 && !screen.control.mute_available ? "unsupported" : "");
      if (reason || value === screen.control.values[field]) {
        if (reason) this.controlFeedback = [field, controlErrors[reason] || "此设置当前不可用。", true];
        this.renderControls();
        return;
      }
      const attempt = { field, link: relay.link };
      const current = () => this.controlAttempt === attempt && relay.link === attempt.link;
      this.controlAttempt = attempt;
      this.controlFeedback = undefined;
      relay.commands.set(id, attempt);
      relay.notify();
      try {
        const result = await relay.connection.sendMessagePromise({
          type: "dormpanel/control_set", installation_id: id, field, value,
        });
        if (current()) this.controlFeedback = [field, `设备已确认（${actualLabel(type, result.value)}）。`];
      } catch (error) {
        if (current()) this.controlFeedback = [field, controlErrors[error?.code] || "未能确认设置结果，请核对设备状态。", true];
      } finally {
        // The receipt reports the outcome; the shared snapshot remains the
        // source for controls, including any more recent local device edits.
        if (relay.commands.get(id) === attempt) {
          await relay.refresh();
          if (relay.commands.get(id) === attempt) relay.commands.delete(id);
        }
        if (current()) this.controlAttempt = undefined;
        relay.notify();
      }
    }

    buildUpload() {
      const form = node("form");
      this.kind = node("select");
      for (const [value, [label]] of Object.entries(formats)) this.kind.add(node("option", label, { value }));
      this.kind.onchange = () => {
        this.file.value = "";
        this.uploadStatus.textContent = "";
        this.targets.replaceChildren();
        this.renderTargets();
      };
      this.file = node("input", null, { type: "file" });
      this.retention = node("select");
      for (const [value, label] of [[3600, "1 小时"], [86400, "24 小时"], [604800, "7 天"]]) {
        this.retention.add(node("option", label, { value }));
      }
      this.retention.value = "86400";
      this.targets = node("div", null, { class: "targets" });
      const fieldset = node("fieldset");
      fieldset.append(node("legend", "目标设备"), this.targets);
      const field = (label, input) => {
        const wrapper = node("label", label, { class: "field" });
        wrapper.append(input);
        return wrapper;
      };
      this.limit = node("p", null, { class: "muted", id: "file-limit" });
      this.file.setAttribute("aria-describedby", "file-limit");
      this.submit = node("button", "发送至所选设备", { class: "primary", type: "submit" });
      this.progress = node("progress", null, { "aria-label": "上传中", hidden: "" });
      this.uploadStatus = node("p", null, { role: "status" });
      form.append(field("文件类型", this.kind), field("选择文件", this.file), this.limit, fieldset,
        field("临时保留", this.retention), this.submit, this.progress, this.uploadStatus);
      form.onsubmit = event => {
        event.preventDefault();
        this.upload();
      };
      this.adminContent.append(form);
    }

    get screens() { return (this.relay?.data || empty).screens; }
    get available() {
      return !!(this.relay?.online && this.relay.fresh && this.relay.connection.connected !== false);
    }

    updateRelay() {
      const relay = this.relay;
      const admin = this._hass?.user?.is_admin;
      setText(this.connectionStatus, !this._hass ? "正在连接 Home Assistant…"
        : !admin ? "当前账户需要 Home Assistant 管理员权限才能控制设备、管理设备和传输文件。"
        : !relay?.online ? "Home Assistant 连接已断开。"
        : relay.error ? `读取设备与传输失败。 ${relay.error}`
        : !relay.fresh ? "正在读取设备与传输…"
        : relay.subscriptionError || "");
      this.connectionStatus.hidden = !this.connectionStatus.textContent;
      this.adminContent.hidden = !relay?.data;
      setText(this.adminNotice, this.available ? "" : "文件发送和记录操作当前不可用。");
      const options = this.screens.map(({ installation_id, display_name }) => [installation_id, display_name]);
      const deviceList = JSON.stringify(options);
      if (this.deviceList !== deviceList) {
        this.deviceList = deviceList;
        const selected = this.device.value;
        reconcile(this.device, options, ([id]) => id, ([id, name], option = node("option", null, { value: id })) => {
          setText(option, `${name} · ${id.slice(0, 8)}`);
          return option;
        });
        if (options.some(([id]) => id === selected)) this.device.value = selected;
      }
      if (this.screens.some(screen => screen.installation_id === this.preferredDevice)) {
        if (this.device.value !== this.preferredDevice) this.device.value = this.preferredDevice;
      } else if (!this.device.value && this.screens.length) {
        this.device.selectedIndex = 0;
      }
      this.device.disabled = !this.screens.length;
      if (!relay?.online) this.clearDrafts();
      this.renderDevice();
      this.renderTargets();
      this.renderHistory();
    }

    get screen() { return this.screens.find(screen => screen.installation_id === this.device.value); }

    renderDevice() {
      const screen = this.screen;
      if (this.controlContext !== screen?.installation_id) {
        this.controlContext = screen?.installation_id;
        this.controlAttempt = this.controlFeedback = undefined;
        this.clearDrafts();
      }
      this.renderControls();
      this.management.hidden = this.diagnostics.hidden = !screen;
      this.removeButton.hidden = !screen;
      this.removeButton.disabled = !this.available || this.adminBusy.has(this.removeButton);
      if (!screen) {
        setText(this.deviceStatus, this.relay?.data ? "尚无已注册设备。" : "");
        this.deviceDetails.replaceChildren();
        return;
      }
      const supported = Object.values(formats).filter(([, , , capability]) =>
        !capability || screen.capabilities?.includes(capability)).map(([label]) => label);
      const control = screen.control;
      const controlStatus = !this.available ? "原生控制状态未知"
        : control?.version
        ? (control.online ? "原生控制在线" : "设备离线或尚未建立原生控制会话")
        : "未声明原生控制能力";
      setText(this.deviceStatus, controlStatus);
      const diagnostics = [
        `应用版本 ${screen.app_version || "未知"} · 上次注册 ${date(screen.last_seen)}`,
        `原生控制协议 ${control?.version ?? "未声明"} · 可接收 ${supported.join("、")}`,
        `安装 ID ${screen.installation_id}`,
      ];
      if (!this.deviceDetails.hasChildNodes()) this.deviceDetails.append(...diagnostics.map(() => node("p")));
      diagnostics.forEach((line, index) => setText(this.deviceDetails.children[index], line));
    }

    renderTargets() {
      const [, ext, size, capability] = formats[this.kind.value];
      if (this.file.accept !== ext) this.file.accept = ext;
      setText(this.limit, `${ext}，最大 ${size} MiB`);
      const targets = this.screens.filter(screen => !capability || screen.capabilities?.includes(capability));
      this.submit.disabled = !this.available || this.uploading || !targets.length;
      const targetList = JSON.stringify(targets.map(({ installation_id, display_name }) => [installation_id, display_name]));
      if (targetList === this.targetList && this.targets.hasChildNodes()) return;
      this.targetList = targetList;
      if (!targets.length) {
        this.targets.replaceChildren(node("p", "没有兼容此文件类型的目标设备。"));
        return;
      }
      reconcile(this.targets, targets, screen => screen.installation_id, (screen, label) => {
        if (!label) {
          label = node("label");
          label.append(node("input", null, { type: "checkbox", value: screen.installation_id }), node("span"));
        }
        setText(label.lastElementChild, `${screen.display_name} · ${screen.installation_id.slice(0, 8)}`);
        return label;
      });
    }

    async upload() {
      const relay = this.relay;
      if (!this.available || this.uploading) return;
      const [label, extension, size] = formats[this.kind.value];
      const file = this.file.files[0];
      const targets = [...this.targets.querySelectorAll("input:checked")].map(input => input.value);
      if (!file?.size || file.size > size * 1048576 || !file.name.toLowerCase().endsWith(extension) || !targets.length) {
        this.uploadStatus.textContent = `请选择一个符合大小限制的 ${extension} 文件，并勾选目标设备。`;
        return;
      }
      const form = new FormData();
      form.append("kind", this.kind.value);
      form.append("targets", JSON.stringify(targets));
      form.append("retention", this.retention.value);
      form.append("file", file);
      this.uploading = true;
      this.progress.hidden = false;
      this.submit.disabled = this.file.disabled = this.kind.disabled = true;
      this.uploadStatus.textContent = "正在上传至 Home Assistant…";
      try {
        const response = await this._hass.fetchWithAuth("/api/dormpanel/upload", { method: "POST", body: form });
        if (response.status === 401 || response.status === 403) throw new Error("需要管理员权限");
        if (!response.ok) throw new Error(await response.text() || `HTTP ${response.status}`);
        this.uploadStatus.textContent = `${label}已保存到 Home Assistant，等待目标设备处理。`;
        this.file.value = "";
      } catch (error) {
        this.uploadStatus.textContent = `上传失败。 ${message(error)}`;
      } finally {
        this.uploading = this.file.disabled = this.kind.disabled = false;
        this.progress.hidden = true;
        this.renderTargets();
        relay.refresh();
      }
    }

    renderHistory() {
      const transfers = (this.relay?.data || empty).transfers;
      if (!transfers.length) {
        if (this.history.firstElementChild?.localName !== "p") {
          this.history.replaceChildren(node("p", "暂无传输记录。"));
        }
        return;
      }
      reconcile(this.history, transfers, transfer => transfer.transfer_id, (transfer, row) => {
        const [kind] = formats[transfer.kind || "schedule_ics"] || [transfer.kind];
        if (!row) {
          row = node("article", null, { class: "row" });
          row.append(node("strong"), node("p", null, { class: "muted" }), node("div"), node("button"));
        }
        const [title, timing, targets, cancel] = row.children;
        setText(cancel, transfer.file_available ? "取消并清理" : "清理记录");
        cancel.disabled = !this.available || this.adminBusy.has(cancel);
        cancel.onclick = () => {
          if (confirm(`清理「${transfer.filename}」？尚未处理的目标将无法继续领取文件。`)) {
            this.adminAction("cancel_transfer", { transfer_id: transfer.transfer_id }, cancel);
          }
        };
        setText(title, `${kind} · ${transfer.filename}`);
        setText(timing, `创建 ${date(transfer.created_at)}，到期 ${date(transfer.expires_at)}`);
        reconcile(targets, transfer.targets, target => target.installation_id, (target, line = node("p")) => {
          setText(line, `${target.display_name} · ${target.installation_id.slice(0, 8)} · ${outcomes[target.state] || target.state}`);
          return line;
        });
        return row;
      });
    }

    async adminAction(action, params, button) {
      const relay = this.relay;
      if (!this.available || button.disabled) return;
      this.adminBusy.add(button);
      button.disabled = true;
      this.actionStatus.textContent = "正在处理…";
      try {
        await relay.connection.sendMessagePromise({ type: `dormpanel/${action}`, ...params });
        this.actionStatus.textContent = action === "remove_screen" ? "设备已移除。" : "传输已清理。";
        await relay.refresh();
      } catch (error) {
        this.actionStatus.textContent = `操作失败。 ${message(error)}`;
      } finally {
        this.adminBusy.delete(button);
        button.disabled = !this.available;
      }
    }
  }

  class DormPanelOverview extends DormPanelView {
    static getStubConfig() { return { type: "custom:dormpanel-overview" }; }

    setConfig(config) {
      if (config.device && typeof config.device !== "string") throw new Error("device 必须为设备安装 ID");
      if (config.device !== this.configuredDevice) {
        this.preferredDevice = this.configuredDevice = config.device;
        this.device.value = config.device || "";
        this.updateRelay();
      }
    }

    getCardSize() { return 10; }
  }

  customElements.define("dormpanel-panel", DormPanelView);
  customElements.define("dormpanel-overview", DormPanelOverview);
  window.customCards ??= [];
  window.customCards.push({
    type: "dormpanel-overview",
    name: "DormPanel 概览",
    description: "控制 DormPanel 设备设置、发送课表和 APK，并查看传输结果。",
  });
})();
