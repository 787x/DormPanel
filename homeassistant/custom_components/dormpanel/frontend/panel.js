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
  const connections = new WeakMap();
  const empty = { screens: [], transfers: [] };
  const date = seconds => new Date(seconds * 1000).toLocaleString();
  const message = error => error?.message || String(error);

  function node(tag, content, attrs = {}) {
    const result = document.createElement(tag);
    if (content != null) result.textContent = content;
    for (const [name, value] of Object.entries(attrs)) result.setAttribute(name, value);
    return result;
  }

  // Both views share a single relay snapshot and event subscription per HA connection.
  class RelayState {
    constructor(connection) {
      this.connection = connection;
      this.views = new Set();
      this.data = null;
      this.online = connection.connected !== false;
      this.ready = () => {
        this.online = true;
        this.refresh();
      };
      this.disconnected = () => {
        this.online = false;
        this.data = null;
        this.error = "";
        this.notify();
      };
    }

    attach(view) {
      this.views.add(view);
      if (this.views.size === 1) {
        const token = this.token = {};
        this.connection.addEventListener("ready", this.ready);
        this.connection.addEventListener("disconnected", this.disconnected);
        this.connection.subscribeEvents(() => this.refresh(), "dormpanel_transfer_changed")
          .then(unsubscribe => {
            if (this.token !== token) return unsubscribe();
            this.unsubscribe = unsubscribe;
            // Re-read after registration to cover changes during the initial fetch.
            this.refresh();
          })
          .catch(error => {
            if (this.token !== token) return;
            this.subscriptionError = `实时传输更新不可用。 ${message(error)}`;
            this.notify();
          });
        this.refresh();
      }
      view.updateRelay();
      return () => {
        this.views.delete(view);
        if (this.views.size) return;
        this.token = undefined;
        this.connection.removeEventListener("ready", this.ready);
        this.connection.removeEventListener("disconnected", this.disconnected);
        this.unsubscribe?.().catch(() => {});
        connections.delete(this.connection);
      };
    }

    async refresh() {
      if (!this.online || !this.token) return;
      const token = this.token;
      if (this.request === token) {
        this.refreshPending = true;
        return;
      }
      this.request = token;
      try {
        do {
          this.refreshPending = false;
          try {
            const data = await this.connection.sendMessagePromise({ type: "dormpanel/admin_state" });
            if (this.token !== token || !this.online) return;
            this.data = data;
            this.error = "";
          } catch (error) {
            if (this.token !== token || !this.online) return;
            this.data = null;
            this.error = message(error);
          }
          this.notify();
        } while (this.refreshPending && this.token === token && this.online);
      } finally {
        this.request = undefined;
      }
    }

    notify() {
      for (const view of this.views) view.updateRelay();
    }
  }

  const style = `
    :host { display: block; color: var(--primary-text-color, #202124); }
    :host(dormpanel-panel) { min-height: 100%; background: var(--primary-background-color); }
    main { max-width: 1080px; margin: auto; padding: clamp(12px, 3vw, 24px); display: grid; gap: 16px; }
    ha-card > main { padding: 16px; gap: 12px; }
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
    }

    connect() {
      const hass = this._hass;
      if (!this.isConnected || !hass) return;
      if (hass.user?.is_admin && this.relay?.connection === hass.connection) return;
      this.detach();
      if (hass.user?.is_admin) {
        this.relay = connections.get(hass.connection) || new RelayState(hass.connection);
        connections.set(hass.connection, this.relay);
        this.release = this.relay.attach(this);
      } else {
        this.updateRelay();
      }
    }

    build() {
      const main = node("main");
      const header = node("header");
      const isCard = this.localName === "dormpanel-overview";
      if (!isCard) {
        this.menu = node("ha-menu-button");
        header.append(this.menu);
      }
      header.append(node(isCard ? "h2" : "h1", "DormPanel"));
      this.connectionStatus = node("p", "正在连接 Home Assistant…", { role: "status", class: "muted" });
      const devices = node("section");
      this.device = node("select", null, { "aria-label": "已注册设备" });
      this.device.onchange = () => this.renderDevice();
      this.deviceDetails = node("div");
      this.removeButton = node("button", "移除设备");
      this.removeButton.onclick = () => {
        const screen = this.screen;
        if (screen && confirm(`移除设备「${screen.display_name}」？`)) {
          this.adminAction("remove_screen", { installation_id: screen.installation_id }, this.removeButton);
        }
      };
      devices.append(node("h2", "设备概览"), this.device, this.deviceDetails, this.removeButton);

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
      main.append(header, this.connectionStatus, devices, this.adminArea, this.actionStatus);
      if (isCard) {
        const card = node("ha-card");
        card.append(main);
        this.shadowRoot.append(node("style", style), card);
      } else {
        this.shadowRoot.append(node("style", style), main);
      }
      this.updateRelay();
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

    updateRelay() {
      const relay = this.relay;
      const admin = this._hass?.user?.is_admin;
      const available = relay?.online && relay.data;
      this.connectionStatus.textContent = !this._hass ? "正在连接 Home Assistant…"
        : !admin ? "当前账户需要 Home Assistant 管理员权限才能访问设备管理和文件传输。"
        : !relay?.online ? "Home Assistant 连接已断开。"
        : relay.error ? `读取设备与传输失败。 ${relay.error}`
        : !relay.data ? "正在读取设备与传输…"
        : relay.subscriptionError || "";
      this.connectionStatus.hidden = !this.connectionStatus.textContent;
      this.adminContent.hidden = !available;
      this.adminNotice.textContent = available ? "" : "文件发送和传输记录当前不可用。";
      const selected = this.device.value || this.preferredDevice;
      this.device.replaceChildren(...this.screens.map(screen => node("option",
        `${screen.display_name} · ${screen.installation_id.slice(0, 8)}`, { value: screen.installation_id })));
      if (this.screens.some(screen => screen.installation_id === selected)) this.device.value = selected;
      this.device.disabled = !this.screens.length;
      this.renderDevice();
      this.renderTargets();
      this.renderHistory();
    }

    get screen() { return this.screens.find(screen => screen.installation_id === this.device.value); }

    renderDevice() {
      const screen = this.screen;
      this.removeButton.hidden = !screen;
      if (!screen) {
        this.deviceDetails.textContent = this.relay?.data ? "尚无已注册设备。" : "";
        return;
      }
      this.preferredDevice = screen.installation_id;
      const supported = Object.values(formats).filter(([, , , capability]) =>
        !capability || screen.capabilities?.includes(capability)).map(([label]) => label);
      const control = screen.control;
      const controlStatus = control?.version
        ? (control.online ? "原生控制在线" : "原生控制离线")
        : "未声明原生控制能力";
      this.deviceDetails.replaceChildren(
        node("p", controlStatus),
        node("p", `应用版本 ${screen.app_version || "未知"} · 上次注册 ${date(screen.last_seen)}`),
        node("p", `可接收 ${supported.join("、")}`, { class: "muted" }),
        node("p", `安装 ID ${screen.installation_id}`, { class: "muted" }),
      );
    }

    renderTargets() {
      const [, ext, size, capability] = formats[this.kind.value];
      this.file.accept = ext;
      this.limit.textContent = `${ext}，最大 ${size} MiB`;
      const targets = this.screens.filter(screen => !capability || screen.capabilities?.includes(capability));
      this.submit.disabled = this.uploading || !targets.length;
      const targetList = JSON.stringify(targets.map(({ installation_id, display_name }) => [installation_id, display_name]));
      if (targetList === this.targetList && this.targets.hasChildNodes()) return;
      this.targetList = targetList;
      const selected = new Set([...this.targets.querySelectorAll("input:checked")].map(input => input.value));
      const labels = targets.map(screen => {
        const label = node("label");
        const check = node("input", null, { type: "checkbox", value: screen.installation_id });
        check.checked = selected.has(screen.installation_id);
        label.append(check, document.createTextNode(`${screen.display_name} · ${screen.installation_id.slice(0, 8)}`));
        return label;
      });
      this.targets.replaceChildren(...(labels.length ? labels : [node("p", "没有兼容此文件类型的目标设备。")]));
    }

    async upload() {
      const relay = this.relay;
      if (!relay?.online || relay.error || this.uploading) return;
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
      const history = transfers.map(transfer => {
        const row = node("article", null, { class: "row" });
        const [kind] = formats[transfer.kind || "schedule_ics"] || [transfer.kind];
        const cancel = node("button", transfer.file_available ? "取消并清理" : "清理记录");
        cancel.onclick = () => {
          if (confirm(`清理「${transfer.filename}」？尚未处理的目标将无法继续领取文件。`)) {
            this.adminAction("cancel_transfer", { transfer_id: transfer.transfer_id }, cancel);
          }
        };
        row.append(node("strong", `${kind} · ${transfer.filename}`),
          node("p", `创建 ${date(transfer.created_at)}，到期 ${date(transfer.expires_at)}`, { class: "muted" }));
        for (const target of transfer.targets) {
          row.append(node("p", `${target.display_name} · ${target.installation_id.slice(0, 8)} · ${outcomes[target.state] || target.state}`));
        }
        row.append(cancel);
        return row;
      });
      this.history.replaceChildren(...(history.length ? history : [node("p", "暂无传输记录。")]));
    }

    async adminAction(action, params, button) {
      const relay = this.relay;
      if (!relay?.online || relay.error || button.disabled) return;
      button.disabled = true;
      this.actionStatus.textContent = "正在处理…";
      try {
        await relay.connection.sendMessagePromise({ type: `dormpanel/${action}`, ...params });
        this.actionStatus.textContent = action === "remove_screen" ? "设备已移除。" : "传输已清理。";
        relay.refresh();
      } catch (error) {
        this.actionStatus.textContent = `操作失败。 ${message(error)}`;
      } finally {
        button.disabled = false;
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

    getCardSize() { return 4; }
  }

  customElements.define("dormpanel-panel", DormPanelView);
  customElements.define("dormpanel-overview", DormPanelOverview);
  window.customCards ??= [];
  window.customCards.push({
    type: "dormpanel-overview",
    name: "DormPanel 概览",
    description: "查看已注册设备、发送课表和 APK，并跟踪各设备的处理结果。",
  });
})();
