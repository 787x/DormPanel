class DormPanelPanel extends HTMLElement {
  constructor() {
    super();
    this.attachShadow({ mode: "open" });
    this.state = { screens: [], transfers: [] };
    this.subscription = null;
    this.loaded = false;
  }

  set hass(value) {
    this._hass = value;
    if (!this.loaded && value) {
      this.loaded = true;
      this.refresh();
      value.connection.subscribeEvents(() => this.refresh(), "dormpanel_transfer_changed")
        .then(unsubscribe => { this.subscription = unsubscribe; })
        .catch(() => {});
    }
  }

  get hass() { return this._hass; }
  disconnectedCallback() { if (this.subscription) this.subscription(); this.subscription = null; this.loaded = false; }
  async refresh() {
    try {
      this.state = await this.hass.connection.sendMessagePromise({ type: "dormpanel/admin_state" });
      this.render();
    } catch (error) { this.render(String(error)); }
  }
  element(tag, text, className) {
    const node = document.createElement(tag);
    if (text !== undefined) node.textContent = text;
    if (className) node.className = className;
    return node;
  }
  section(title) {
    const card = this.element("section", undefined, "card");
    card.append(this.element("h2", title));
    return card;
  }
  render(error = "") {
    const root = this.shadowRoot;
    root.replaceChildren();
    const style = this.element("style");
    style.textContent = `:host{display:block;background:var(--primary-background-color);color:var(--primary-text-color);min-height:100%;font-family:var(--paper-font-body1_-_font-family,Arial,sans-serif)}main{max-width:920px;margin:auto;padding:20px;display:grid;gap:16px}.card{background:var(--card-background-color,#fff);border-radius:12px;box-shadow:var(--ha-card-box-shadow,0 1px 4px #0002);padding:18px}h1,h2{margin:0 0 16px}label{display:block;margin:12px 0}button,input,select{font:inherit}button{min-height:40px;padding:8px 14px;border:0;border-radius:8px;background:var(--primary-color,#03a9f4);color:var(--text-primary-color,#fff);cursor:pointer}button.secondary{background:transparent;color:var(--primary-color,#03a9f4)}.row{display:flex;justify-content:space-between;gap:12px;align-items:center;padding:10px 0;border-top:1px solid var(--divider-color,#ddd)}.muted{color:var(--secondary-text-color,#666);font-size:.9em}.error{color:var(--error-color,#b00020)}.targets{display:grid;grid-template-columns:repeat(auto-fit,minmax(210px,1fr));gap:4px}@media(max-width:600px){main{padding:10px}.row{align-items:flex-start;flex-direction:column}}`;
    root.append(style);
    const main = this.element("main"); root.append(main);
    main.append(this.element("h1", "DormPanel"));
    if (error) main.append(this.element("p", error, "error"));
    for (const [kind, title, extension, limit, capability] of [
      ["schedule_ics", "Send timetable", ".ics", 1048576, "schedule_relay_v1"],
      ["apk", "Send APK", ".apk", 268435456, "apk_install_v1"]]) {
    const send = this.section(title);
    const fileLabel = this.element("label", `File (${extension}, up to ${kind === "apk" ? "256" : "1"} MiB)`);
    const file = this.element("input"); file.type = "file"; file.accept = extension; fileLabel.append(file); send.append(fileLabel);
    send.append(this.element("div", "Send to"));
    const targets = this.element("div", undefined, "targets");
    for (const screen of this.state.screens) {
      if (kind === "apk" && !(screen.capabilities || []).includes(capability)) continue;
      const label = this.element("label");
      const check = this.element("input"); check.type = "checkbox"; check.value = screen.installation_id;
      label.append(check, document.createTextNode(` ${screen.display_name} (${screen.installation_id.slice(0, 8)})`));
      targets.append(label);
    }
    send.append(targets);
    const retentionLabel = this.element("label", "Keep temporarily: ");
    const retention = this.element("select");
    for (const [seconds, label] of [[3600,"1 hour"],[86400,"24 hours"],[604800,"7 days"]]) {
      const option = this.element("option", label); option.value = seconds; option.selected = seconds === 86400; retention.append(option);
    }
    retentionLabel.append(retention); send.append(retentionLabel);
    const submit = this.element("button", "Send");
    const uploadStatus = this.element("p", ""); send.append(submit, uploadStatus);
    submit.onclick = async () => {
      const selected = [...targets.querySelectorAll("input:checked")].map(input => input.value);
      if (file.files.length !== 1 || !file.files[0].name.toLowerCase().endsWith(extension) || file.files[0].size > limit || file.files[0].size === 0 || !selected.length) {
        uploadStatus.textContent = `Choose one ${extension} file within the limit and at least one compatible screen.`; return;
      }
      submit.disabled = true; uploadStatus.textContent = "Uploading…";
      try {
        const form = new FormData(); form.append("kind", kind); form.append("targets", JSON.stringify(selected)); form.append("retention", retention.value); form.append("file", file.files[0]);
        const response = await this.hass.fetchWithAuth("/api/dormpanel/upload", { method: "POST", body: form });
        if (!response.ok) throw new Error(await response.text());
        uploadStatus.textContent = `${title} sent.`; file.value = ""; await this.refresh();
      } catch (err) { uploadStatus.textContent = `Upload failed: ${err}`; }
      finally { submit.disabled = false; }
    };
    main.append(send);
    }
    const screens = this.section("Registered screens");
    if (!this.state.screens.length) screens.append(this.element("p", "No DormPanel screen has registered yet."));
    for (const screen of this.state.screens) {
      const row = this.element("div", undefined, "row");
      const details = this.element("div");
      details.append(this.element("strong", screen.display_name), this.element("div", screen.installation_id, "muted"),
        this.element("div", `Last seen: ${new Date(screen.last_seen * 1000).toLocaleString()}`, "muted"));
      const remove = this.element("button", "Remove", "secondary");
      remove.onclick = async () => { if (!confirm(`Remove ${screen.display_name}?`)) return;
        await this.hass.connection.sendMessagePromise({type:"dormpanel/remove_screen", installation_id:screen.installation_id}); await this.refresh(); };
      row.append(details, remove); screens.append(row);
    }
    main.append(screens);
    const transfers = this.section("Recent / pending transfers");
    if (!this.state.transfers.length) transfers.append(this.element("p", "No transfers."));
    for (const transfer of this.state.transfers) {
      const row = this.element("div", undefined, "row"); const details = this.element("div");
      details.append(this.element("strong", `${transfer.kind || "schedule_ics"} · ${transfer.filename}`),
        this.element("div", `Expires: ${new Date(transfer.expires_at * 1000).toLocaleString()}`, "muted"));
      for (const target of transfer.targets) details.append(this.element("div", `${target.display_name}: ${target.state}`));
      const cancel = this.element("button", "Delete", "secondary");
      cancel.onclick = async () => { if (!confirm(`Delete ${transfer.filename}?`)) return;
        await this.hass.connection.sendMessagePromise({type:"dormpanel/cancel_transfer", transfer_id:transfer.transfer_id}); await this.refresh(); };
      row.append(details, cancel); transfers.append(row);
    }
    main.append(transfers);
  }
}
customElements.define("dormpanel-panel", DormPanelPanel);
