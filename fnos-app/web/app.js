import { notifyUser, confirmAction, requestText } from './feedback.js';
const $ = (id) => document.getElementById(id),
  base = "/app/dormpanel-schedule-manager";
const now = () =>
  new Intl.DateTimeFormat("en-CA", {
    timeZone: $("zone").value,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(new Date());
const add = (d, n) => {
  const t = new Date(d + "T12:00:00Z");
  t.setUTCDate(t.getUTCDate() + n);
  return t.toISOString().slice(0, 10);
};
function el(tag, text, cls) {
  const e = document.createElement(tag);
  if (text != null) e.textContent = text;
  if (cls) e.className = cls;
  return e;
}
function paragraphs(id, rows) {
  $(id).replaceChildren(...rows.map((s) => el("p", s)));
}
let workspaceId, catalogVersion, management, switching = false, loadEpoch = 0;
let token,
  version,
  local,
  profiles = [];
async function load() {
  const epoch = ++loadEpoch;
  try {
    const r = await fetch(
      `${base}/api/schedule?${workspaceId ? "workspaceId=" + encodeURIComponent(workspaceId) + "&" : ""}date=${encodeURIComponent($("date").value)}&zone=${encodeURIComponent($("zone").value)}&origin=${encodeURIComponent(location.origin)}`,
    );
    const s = await r.json();
    if (!r.ok) throw Error(s.error);
    if (epoch !== loadEpoch) return;
    workspaceId = s.workspaceId;
    catalogVersion = s.catalogVersion;
    token = s.csrf;
    version = s.version;
    const statuses = {
      unbound: "未关联连接；可以离线编辑。请在数据源管理中配置。",
      unchecked: "尚未检查远端。",
      empty: "指定目录未发现 v2 课程数据。",
      verified: "已检查本轮观察到的版本。",
      incomplete: "存在暂不可验证历史，尚未完成全部版本检查。",
      branches: "已观察多个并发分支。",
      conflict: "存在待处理冲突；请在管理中查看。",
      error: "连接或同步检查失败；请在数据源管理查看详情。",
    };
    $("status").textContent =
      statuses[s.status] +
      "\n本地已保存" +
      (s.pending
        ? " · 提交待回读确认"
        : s.dirty
          ? " · 有待发布修改"
          : " · 无待发布本地修改") +
      (s.published ? "\n本机发布已回读确认；其他设备接收情况未知" : "");
    if (s.writeProtection) $('status').textContent += '\n' + s.writeProtection;
    $('managerStatus').textContent = $('status').textContent;
    $('welcome').dataset.unbound = String(s.status === 'unbound');
    $('welcome').hidden = s.status !== 'unbound' || !$('manager').hidden;
    $("status").dataset.error = ["incomplete", "conflict", "error"].includes(
      s.status,
    );
    $("range").textContent = `${s.first} — ${add(s.first, 6)}`;
    profiles = s.profiles;
    const selectedTerm = $("term").value;
    $("term").replaceChildren(
      ...profiles.map((p) => {
        const option = el("option", p.termKey);
        option.value = p.termKey;
        return option;
      }),
    );
    if (profiles.some((p) => p.termKey === selectedTerm))
      $("term").value = selectedTerm;
    $("jump").disabled = !profiles.length;
    const active = profiles.find((p) => p.termKey === $("term").value);
    if (active)
      $("teachingWeek").value =
        Math.floor(
          (Date.parse(s.first) - Date.parse(active.week1Monday)) / 604800000,
        ) + 1;
    $("week").replaceChildren(
      ...Array.from({ length: 7 }, (_, i) => {
        const d = add(s.first, i),
          col = el("section", null, "day");
        col.append(
          el("h2", ["周一", "周二", "周三", "周四", "周五", "周六", "周日"][i]),
          el("small", d),
        );
        const dateOf = (ms) =>
          new Intl.DateTimeFormat("en-CA", {
            timeZone: s.zone,
            year: "numeric",
            month: "2-digit",
            day: "2-digit",
          }).format(new Date(ms));
        const courses = s.courses.filter(
          (c) => dateOf(c.start) <= d && dateOf(c.end - 1) >= d,
        );
        for (const c of courses) {
          const card = el("article", null, "course"),
            fmt = (ms) =>
              new Intl.DateTimeFormat("zh-CN", {
                timeZone: s.zone,
                hour: "2-digit",
                minute: "2-digit",
                hourCycle: "h23",
              }).format(new Date(ms));
          card.tabIndex = 0;
          card.setAttribute("role", "button");
          card.setAttribute("aria-label", "编辑 " + c.title);
          card.onclick = () => editOccurrence(c);
          card.onkeydown = (e) => {
            if (e.key === "Enter") editOccurrence(c);
          };
          card.append(
            el("small", `${fmt(c.start)}–${fmt(c.end)}`),
            el("strong", c.title),
            el("span", c.location || "未设置教室"),
          );
          if (dateOf(c.start) !== dateOf(c.end - 1))
            card.append(
              el("small", `跨日 ${dateOf(c.start)} → ${dateOf(c.end - 1)}`),
            );
          for (const t of [
            c.periodLabel,
            c.teacher,
            c.note,
            c.description,
            c.copiedFrom && `调自 ${c.copiedFrom}`,
            c.allWeeksEdited && "已应用全周编辑",
            c.thisWeekEdited && "已应用指定周编辑",
          ])
            if (t) card.append(el("small", t));
          col.append(card);
        }
        if (!courses.length) col.append(el("p", "—"));
        return col;
      }),
    );
    paragraphs("details", [
      `数据集：${s.datasetId ?? "未绑定"}`,
      `Writer：${s.writerId}`,
      ...(s.published ? [`已发布提交：${s.published.commitId}`] : []),
      `历史：${s.historyCount} · 分支：${s.tips.length} · 异常：${s.deferred.length}`,
      `检查时间：${s.checkedAt ? new Intl.DateTimeFormat("zh-CN", { timeZone: s.zone, dateStyle: "short", timeStyle: "medium" }).format(new Date(s.checkedAt)) : "尚未检查"}`,
      ...(s.error ? [`错误：${s.error}`] : []),
      ...s.tips.map((id) => `tip ${id}`),
    ]);
    paragraphs(
      "profiles",
      s.profiles.length
        ? s.profiles.map((p) => {
            const week =
              Math.floor(
                (Date.parse(s.first) - Date.parse(p.week1Monday)) / 604800000,
              ) + 1;
            return (
              `${p.termKey} · 第 ${week} 教学周\n${p.timezone} · 第一周 ${p.week1Monday}\n` +
              p.phases
                .map(
                  (ph) =>
                    `${ph.effectiveFrom}：${ph.periods.map((t) => `${t.periodNumber}节 ${t.start}–${t.end}`).join(" / ")}`,
                )
                .join("\n")
            );
          })
        : ["暂无可显示配置"],
    );
    paragraphs(
      "sources",
      s.sources.map(
        (r) =>
          `${r.displayName} · ${r.filename}\n${r.occurrenceCount} 个实例 · ${r.kind}`,
      ),
    );
    paragraphs(
      "events",
      s.events.map((e) => `${e.title} · ${e.note}`),
    );
    const state = await get("/api/state");
    if (epoch !== loadEpoch || state.workspaceId !== workspaceId) return;
    local = state.local;
    version = state.version;
    renderRecords();
    await loadManagement(epoch);
  } catch (e) {
    if (epoch !== loadEpoch) return;
    $("status").textContent = e.message;
    $("status").dataset.error = true;
    $("managementStatus").textContent = '页面初始化失败：' + e.message + '。请点击“刷新状态”重试。';
  }
}
$("date").value = now();
$("date").onchange = load;
$("zone").onchange = load;
$("jump").onclick = () => {
  const p = profiles.find((p) => p.termKey === $("term").value),
    n = Number($("teachingWeek").value);
  if (p && Number.isInteger(n) && n >= 1 && n <= 1000) {
    $("date").value = add(p.week1Monday, (n - 1) * 7);
    load();
  }
};
$("prev").onclick = () => {
  $("date").value = add($("date").value, -7);
  load();
};
$("next").onclick = () => {
  $("date").value = add($("date").value, 7);
  load();
};
$("today").onclick = () => {
  $("date").value = now();
  load();
};
$("refresh").onclick = () => runSync({});

async function get(path) {
  const scope = workspaceId;
  const r = await fetch(base + path + (path.includes("?") ? "&" : "?") + "workspaceId=" + encodeURIComponent(scope));
  const v = await r.json();
  if (!r.ok) throw Error(v.error);
  return v;
}
async function post(path, data = {}) {
  const scope = data.workspaceId ?? workspaceId;
  const r = await fetch(base + path, {
    method: "POST",
    headers: { "Content-Type": "application/json", "X-DormPanel-CSRF": token },
    body: JSON.stringify({ workspaceId: scope, version, catalogVersion, ...data }),
  });
  const v = await r.json();
  if (!r.ok) throw Error(v.error);
  if (scope === workspaceId && v.version !== undefined) version = v.version;
  if (v.catalogVersion !== undefined) catalogVersion = v.catalogVersion;
  return v;
}
async function runSync(options) {
  $("refresh").disabled = true;
  try {
    await post("/api/sync", options);
  } catch (e) {
    await notifyUser(e.message);
  } finally {
    await load();
    $("refresh").disabled = false;
  }
}
function button(label, action) {
  const b = el("button", label);
  b.type = "button";
  b.onclick = action;
  if (/删除|移除/.test(label)) b.className = "danger";
  return b;
}
function field(name, value = "", type = "text") {
  const label = el("label", name),
    input = el(type === "textarea" ? "textarea" : "input");
  if (type !== "textarea") input.type = type;
  input.value = value ?? "";
  label.append(input);
  $("fields").append(label);
  return input;
}
let editorEpoch = 0;
function dialog(title, build, save) {
  const opener = document.activeElement;
  const epoch = ++editorEpoch, submit = $("editorForm").querySelector("[type=submit]");
  let scope = workspaceId, formVersion = version, formCatalogVersion = catalogVersion;
  let read, ready = true, saving = false;
  const current = () => epoch === editorEpoch && $("editor").open;
  const update = () => { if (current()) submit.disabled = !read || !ready || saving; };
  const form = {
    current,
    setReady(value) { ready = value; update(); },
    captureVersion() { scope = workspaceId; formVersion = version; formCatalogVersion = catalogVersion; },
  };
  $("editorTitle").textContent = title;
  $("cancel").textContent = save ? "关闭" : "暂不处理";
  submit.hidden = !save;
  submit.disabled = true;
  const initialize = async () => {
    read = null; ready = true;
    $("fields").replaceChildren(); $("formError").textContent = ""; update();
    try {
      const result = await build(form);
      if (!current()) return;
      read = result; update();
    } catch (error) {
      if (!current()) return;
      $("formError").textContent = '配置加载失败：' + error.message;
      $("fields").append(button('重试加载配置', initialize));
    }
  };
  $("editorForm").onsubmit = async (e) => {
    e.preventDefault();
    if (!save || !read || !ready || saving) return;
    saving = true; update();
    try {
      if (scope !== workspaceId || formVersion !== version || formCatalogVersion !== catalogVersion) throw Error("表单版本或工作区已变化，请关闭后重新打开");
      const input = await read();
      if (!current()) return;
      if (scope !== workspaceId || formVersion !== version || formCatalogVersion !== catalogVersion) throw Error("表单版本或工作区已变化，请关闭后重新打开");
      await save(input);
      if (!current()) return;
      $("editor").close();
      await load();
    } catch (error) {
      if (current()) $("formError").textContent = error.message;
    } finally {
      saving = false; update();
    }
  };
  $('editor').addEventListener('close', () => {
    (opener?.isConnected ? opener : $('sourcesPage')).focus();
  }, { once: true });
  $("editor").showModal();
  // 先给出可见反馈，构建异常和异步加载失败都留在可取消、可重试的弹窗内。
  void initialize();
}
$("cancel").onclick = () => $("editor").close();
$("editor").addEventListener('close', () => {
  ++editorEpoch;
  $("fields").replaceChildren();
  $("editorForm").onsubmit = null;
});
const edit = (operation, context = { workspaceId, version }) => post("/api/edit", { ...context, operation });
const time = (minute) =>
  `${String(Math.floor(minute / 60)).padStart(2, "0")}:${String(minute % 60).padStart(2, "0")}`;
const minute = (text) => {
  if (!/^\d{2}:\d{2}$/.test(text)) throw Error("时间格式应为 HH:mm");
  const [h, m] = text.split(":").map(Number);
  if (h > 23 || m > 59) throw Error("时间超限");
  return h * 60 + m;
};
function editEntry(row = {}) {
  dialog(
    row.id ? "编辑本地课程" : "新增本地课程",
    () => {
      const title = field("课程名", row.title),
        day = field("星期（1–7）", row.day ?? 1, "number"),
        start = field("开始", time(row.startMinute ?? 480), "time"),
        end = field("结束", time(row.endMinute ?? 590), "time"),
        location = field("地点", row.location);
      return () => ({
        id: row.id,
        title: title.value,
        day: Number(day.value),
        startMinute: minute(start.value),
        endMinute: minute(end.value),
        location: location.value,
      });
    },
    (value) => edit({ kind: "entry", action: "upsert", value }),
  );
}
function editEvent(row = {}) {
  dialog(
    row.id ? "编辑事件" : "新增事件",
    () => {
      const title = field("标题", row.title),
        start = field(
          "开始（含时区 ISO 时间，如 2026-10-21T08:00+08:00）",
          row.start ? new Date(Number(row.start)).toISOString() : "",
        ),
        end = field(
          "结束（含时区）",
          row.end ? new Date(Number(row.end)).toISOString() : "",
        ),
        note = field("备注", row.note, "textarea");
      return () => {
        const a = Date.parse(start.value),
          b = Date.parse(end.value);
        if (
          !Number.isFinite(a) ||
          !Number.isFinite(b) ||
          !/[zZ]|[+-]\d\d:\d\d$/.test(start.value) ||
          !/[zZ]|[+-]\d\d:\d\d$/.test(end.value)
        )
          throw Error("请填写带时区的日期时间");
        return {
          id: row.id,
          title: title.value,
          start: a,
          end: b,
          note: note.value,
        };
      };
    },
    (value) => edit({ kind: "event", action: "upsert", value }),
  );
}
function editOccurrence(c) {
  const context = { workspaceId, version };
  dialog(
    "课程详情：" + c.title,
    () => {
      const label = el("label", "编辑范围"),
        select = el("select");
      for (const [value, text] of [
        ["all", "全部周次"],
        ["week", "指定周 " + c.anchorDate],
      ]) {
        const o = el("option", text);
        o.value = value;
        select.append(o);
      }
      label.append(select);
      $("fields").append(label);
      const values = {};
      const fill = () => {
        Object.values(values).forEach((x) => x.label.remove());
        const anchor = select.value === "all" ? null : c.anchorDate,
          row =
            local.classOverrides.find(
              (e) =>
                e.sourceId === c.sourceId &&
                e.seriesId === c.seriesId &&
                e.anchorDate === anchor,
            ) ?? {};
        for (const [k, name, type] of [
          ["title", "标题", "text"],
          ["location", "地点", "text"],
          ["teacher", "教师", "text"],
          ["note", "备注", "text"],
          [
            select.value === "all" ? "weekday" : "date",
            select.value === "all" ? "星期（1–7）" : "目标日期（同一周）",
            select.value === "all" ? "number" : "date",
          ],
          ["periodStart", "起始节次", "number"],
          ["periodEnd", "结束节次", "number"],
          ["timingMode", "计时模式（linked / custom）", "text"],
          ["startMinute", "开始分钟（0–1439）", "number"],
          ["endMinute", "结束分钟（1–1439）", "number"],
        ]) {
          const input = field(name, row[k] ?? "", type),
            inherit = el("input");
          inherit.type = "checkbox";
          inherit.checked = row[k] == null;
          const label = input.parentElement;
          label.append(el("span", " 继承"), inherit);
          values[k] = { input, inherit, label, type };
        }
      };
      select.onchange = () => {
        for (const x of Object.values(values)) x.label.remove();
        for (const k of Object.keys(values)) delete values[k];
        fill();
      };
      fill();
      $("fields").append(
        el(
          "p",
          "勾选“继承”保存 null；取消勾选后留空文本表示显式清空。重置会删除该范围的覆盖，保留其他周次。",
        ),
      );
      $("fields").append(
        button("重置所选范围", async () => {
          if (await confirmAction("确认重置所选范围？"))
            try {
              await edit({
                kind: "override",
                action: "reset",
                sourceId: c.sourceId,
                seriesId: c.seriesId,
                anchorDate: select.value === "all" ? null : c.anchorDate,
              }, context);
              $("editor").close();
              await load();
            } catch (e) {
              $("formError").textContent = e.message;
            }
        }),
      );
      return () => ({
        kind: "override",
        action: "upsert",
        sourceId: c.sourceId,
        seriesId: c.seriesId,
        anchorDate: select.value === "all" ? null : c.anchorDate,
        patch: Object.fromEntries(
          Object.entries(values).map(([k, v]) => [
            k,
            v.inherit.checked
              ? null
              : v.type === "number"
                ? Number(v.input.value)
                : v.input.value,
          ]),
        ),
      });
    },
    (operation) => edit(operation),
  );
}
function editProfile(p = {}) {
  dialog(
    "学期、阶段作息与节次",
    () => {
      const key = field("学期标识", p.termKey),
        zone = field("时区", p.timezone ?? "Asia/Shanghai"),
        w1 = field("第一周周一", p.week1Monday ?? "", "date"),
        phases = field(
          "每行：生效日期 | 节次,开始,结束 | 节次,开始,结束",
          p.phases
            ?.map(
              (ph) =>
                ph.effectiveFrom +
                ph.periods
                  .map((t) => ` | ${t.periodNumber},${t.start},${t.end}`)
                  .join(""),
            )
            .join("\n") ?? "2026-09-07 | 1,08:00,08:45 | 2,08:55,09:40",
          "textarea",
        );
      if (p.termKey) key.disabled = true;
      return () => ({
        termKey: key.value,
        timezone: zone.value,
        week1Monday: w1.value,
        phases: phases.value
          .split("\n")
          .filter((s) => s.trim())
          .map((line) => {
            const [d, ...periods] = line.split("|").map((s) => s.trim());
            return {
              effectiveFrom: d,
              periods: periods.map((t) => {
                const [n, start, end] = t.split(",").map((s) => s.trim());
                return { periodNumber: Number(n), start, end };
              }),
            };
          }),
      });
    },
    (value) => edit({ kind: "profile", action: "upsert", value }),
  );
}
function adjustDate(row = {}) {
  dialog(
    "日期调整 / 停课与补课",
    () => {
      const d = field("目标日期", row.date ?? $("date").value, "date"),
        mode = el('select'),
        day = field("补用星期（1–7）", row.weekday ?? 1, "number"),
        label = field("说明", row.label ?? "");
      for (const [value, label] of [['none', '停课'], ['weekday', '补课（使用指定星期的课程）']]) {
        const option = el('option', label); option.value = value; mode.append(option);
      }
      mode.value = row.mode ?? 'none';
      const modeLabel = el('label', '调整方式'); modeLabel.append(mode); d.parentElement.after(modeLabel);
      day.disabled = mode.value === 'none'; mode.onchange = () => { day.disabled = mode.value === 'none'; };
      if (row.date) d.disabled = true;
      return () => ({
        date: d.value,
        mode: mode.value,
        weekday: mode.value === "none" ? null : Number(day.value),
        label: label.value,
      });
    },
    (value) => edit({ kind: "date", action: "upsert", value }),
  );
}
async function remove(kind, id, label) {
  const context = { workspaceId, version };
  if (await confirmAction("确认删除 / 重置 " + label + "？相关覆盖会随课程或来源删除。"))
    try {
      await edit({ kind, id, action: "delete" }, context);
      await load();
    } catch (e) {
      await notifyUser(e.message);
    }
}
function renderRecords() {
  const root = $("records");
  root.replaceChildren();
  for (const [kind, rows, label, key, editor] of [
    ["entry", local.entries, "本地课程", "id", editEntry],
    ["event", local.events, "事件", "id", editEvent],
    ["profile", local.profiles, "学期", "termKey", editProfile],
    [
      "source",
      local.sources,
      "来源",
      "id",
      (row) =>
        dialog(
          "来源管理",
          () => {
            const name = field("显示名", row.displayName),
              term = field("关联学期（空表示不指定）", row.termKey);
            return () => ({
              kind: "source",
              action: "upsert",
              id: row.id,
              displayName: name.value,
              termKey: term.value || null,
            });
          },
          edit,
        ),
    ],
    ["date", local.dayAdjustments, "日期调整", "date", adjustDate],
  ]) {
    const group = el("section", null, "record-group");
    group.append(el("h3", label));
    root.append(group);
    if (!rows.length) group.append(el("p", "暂无" + label, "hint"));
    for (const row of rows) {
      const p = el(
        "p",
        row.title ??
          row.displayName ??
          row.termKey ??
          `${row.date} ${row.mode === "none" ? "停课" : "补课"} ${row.label}`,
      );
      p.append(
        button("编辑", () => editor(row)),
        button("删除 / 重置", () =>
          remove(kind, row[key], row.title ?? row.displayName ?? row[key]),
        ),
      );
      group.append(p);
    }
  }
}
async function importFile() {
  const scope = workspaceId, importVersion = version;
  dialog(
    "导入预览",
    (form) => {
      const file = field("选择 ICS、CSV 或 XLS", "", "file");
      file.accept = ".ics,.csv,.xls";
      const name = field("来源显示名", "课程表"),
        target = el("select");
      target.append(el("option", "新增来源"));
      target.firstChild.value = "";
      for (const s of local.sources) {
        const o = el("option", "替换 " + s.displayName);
        o.value = s.id;
        target.append(o);
      }
      $("fields").append(target);
      const info = el("pre", "请选择文件，预览成功后才能保存。"), retry = button('重试预览', () => previewFile(selectedFile));
      info.setAttribute('role', 'status'); retry.hidden = true;
      $("fields").append(info, retry);
      let preview = null, selectedFile = null, previewError = '请先选择文件', previewEpoch = 0;
      form.setReady(false);
      const previewFile = async (f) => {
        const epoch = ++previewEpoch, current = () => form.current() && epoch === previewEpoch;
        preview = null; previewError = '正在检查文件预览，请稍候'; form.setReady(false);
        retry.hidden = true; $('formError').textContent = '';
        if (!f) { info.textContent = previewError = '未选择文件，请选择 ICS、CSV 或 XLS。'; return; }
        info.textContent = `已选择 ${f.name}，正在检查预览…`;
        try {
          if (f.size > 1048576) throw Error("文件超过 1 MiB");
          const bytes = new Uint8Array(await f.arrayBuffer());
          if (!current()) return;
          let binary = "";
          for (const b of bytes) binary += String.fromCharCode(b);
          const result = await post("/api/import/preview", {
            workspaceId: scope, version: importVersion,
            filename: f.name,
            base64: btoa(binary),
          });
          if (!current()) return;
          preview = result; previewError = ''; form.setReady(true);
          info.textContent =
            `${preview.filename} · ${preview.count} 个实例\n` +
            preview.warnings.join("\n") +
            "\n" +
            preview.samples
              .map(
                (r) =>
                  `${r.title} ${new Date(r.start).toISOString()} ${r.location}`,
              )
              .join("\n");
        } catch (e) {
          if (!current()) return;
          previewError = '预览失败：' + e.message;
          info.textContent = `已选择 ${f.name}\n${previewError}` + (/请先配置学期/.test(e.message) ? '\n请关闭此窗口，在管理中新增对应学期及节次时间，然后重新导入。' : '');
          $('formError').textContent = previewError; retry.hidden = false;
        }
      };
      file.onchange = () => { selectedFile = file.files[0] ?? null; void previewFile(selectedFile); };
      return async () => {
        if (!preview) throw Error(previewError || '请先完成文件预览');
        const confirmed = preview;
        if (
          !(await confirmAction("确认导入以上预览？替换来源将保留原 sourceId 和已有覆盖。" + (() => { const r = preview.replacements?.find(r => r.sourceId === target.value); return r ? `\n替换 ${r.count} 个原实例 → ${preview.count} 个新实例。\n可能不再匹配的课程：${r.unmatchedTitles.join("、") || "无"}` : ""; })()))
        )
          throw Error("已取消");
        if (confirmed !== preview) throw Error('所选文件已变化，请检查最新预览');
        return {
          workspaceId: scope, version: confirmed.version,
          id: confirmed.id,
          displayName: name.value,
          targetId: target.value || null,
        };
      };
    },
    (input) => post("/api/import/confirm", input),
  );
}
async function showConflicts() {
  try {
    const c = await get("/api/conflicts");
    if (c.workspaceId !== workspaceId) return;
    version = c.version;
    dialog(
      "同步冲突",
      () => {
        $("fields").append(
          el(
            "p",
            c.conflicts.length
              ? `${c.conflicts.length} 个关联冲突；远端方案按提交 UUID 稳定折叠，确认后才采用。独立修改继续保留。`
              : "暂无冲突",
          ),
        );
        for (const conflict of c.conflicts) {
          const names = conflict.keys.map((key) => {
            const v =
              conflict.local[key] ?? conflict.remote[key] ?? conflict.base[key];
            if (!v) return "已删除 / 已重置";
            if (key.startsWith("class:")) return "课程 " + v.title;
            if (key.startsWith("event:")) return "事件 " + v.title;
            if (key.startsWith("source:"))
              return "来源 " + v.source.displayName;
            if (key.startsWith("term:")) return "学期 " + v.value.termKey;
            if (key.startsWith("date:")) return "日期调整 " + v.date;
            const title =
              local.entries.find((r) => r.id === v.seriesId)?.title ??
              local.imported.find(
                (r) => r.sourceId === v.sourceId && r.seriesId === v.seriesId,
              )?.title ??
              v.seriesId;
            return `课程编辑 ${title} · ${v.anchorDate ?? "全部周次"}`;
          });
          const section = el("details");
          section.append(
            el("summary", [...new Set(names)].join("、")),
            el(
              "p",
              "此关联单位须整体决定；null 表示继承，空文本表示显式清空，记录缺失表示删除或重置。",
            ),
            el("pre", JSON.stringify(conflict, null, 2)),
          );
          $("fields").append(section);
        }
        for (const branch of c.branches) {
          const section = el("details");
          section.append(
            el(
              "summary",
              `完整远端分支 ${branch.commitId} / ${branch.writerId}`,
            ),
            el("pre", JSON.stringify(branch.state, null, 2)),
          );
          $("fields").append(section);
        }
        const result = el("details");
        result.append(
          el("summary", "已展示的远端方案（完整）"),
          el("pre", JSON.stringify(c.remote, null, 2)),
        );
        $("fields").append(result);
        for (const [choice, label] of [
          ["local", "保留本地"],
          ["remote", "采用远端方案"],
        ])
          $("fields").append(
            button(label, async () => {
              if (!(await confirmAction(label + "？将保存待发布提交。"))) return;
              try {
                await post("/api/resolve", { choice, token: c.token, workspaceId: c.workspaceId, version: c.version });
                $("editor").close();
                await load();
              } catch (e) {
                $("formError").textContent = e.message;
              }
            }),
          );
        return () => null;
      },
      null,
    );
  } catch (e) {
    await notifyUser(e.message);
  }
}
$("newCourse").onclick = () => editEntry();
$("newEvent").onclick = () => editEvent();
$('manage').onclick = () => {
  $('manager').hidden = !$('manager').hidden;
  $('welcome').hidden = $('welcome').dataset.unbound !== 'true' || !$('manager').hidden;
  $('manage').setAttribute('aria-expanded', String(!$('manager').hidden));
  if (!$('manager').hidden) $('manager').scrollIntoView({ block: 'start' });
};
$('managerCourse').onclick = () => editEntry();
$('managerEvent').onclick = () => editEvent();
$('welcomeSources').onclick = () => showSources(true);
$("newProfile").onclick = () => editProfile();
$("adjustDate").onclick = () => adjustDate();
$("importFile").onclick = importFile;
$("config").onclick = () => showSources(true);
$("conflicts").onclick = showConflicts;
$("bind").onclick = async () => {
  const context = { workspaceId, version };
  if (await confirmAction("确认接收此目录已有数据集？本地未发布数据会阻止绑定。"))
    runSync({ ...context, bind: true });
};
$("create").onclick = async () => {
  const context = { workspaceId, version };
  if (await confirmAction("确认在已配置的空目录创建独立数据集？不能用于覆盖已有历史。"))
    runSync({ ...context, create: true });
};
async function loadManagement(epoch = loadEpoch) {
  const scope = workspaceId;
  $('managementStatus').textContent = '正在读取管理数据…';
  let m;
  try { m = await get('/api/manage'); }
  catch (error) {
    if (epoch === loadEpoch && scope === workspaceId) $('managementStatus').textContent = '管理数据加载失败：' + error.message + '。可点击“刷新状态”或重新打开连接弹窗重试。';
    throw error;
  }
  if (epoch !== loadEpoch || scope !== workspaceId) return;
  const active = m.workspaces.find(w => w.id === scope);
  if (!active) {
    $('managementStatus').textContent = '工作区尚未就绪，请刷新状态后重试。';
    throw Error('工作区尚未就绪，请刷新状态后重试');
  }
  $('managementStatus').textContent = '';
  management = m; catalogVersion = m.revision;
  const context = { workspaceId: scope, version: active.version, catalogVersion: m.revision };
  $('workspaceSelect').replaceChildren(...m.workspaces.map(w => {
    const option = el('option', w.name + (w.unknown ? ' · 待检查' : w.conflict ? ' · 冲突' : w.pending ? ' · 待确认' : w.dirty ? ' · 未发布' : ''));
    option.value = w.id; return option;
  }));
  $('workspaceSelect').value = workspaceId;
  $('workspaceRows').replaceChildren(...m.workspaces.map(w => {
    const row = el('article', null, 'source-card'), c = m.connections.find(c => c.id === w.connectionId);
    const type = !c ? '仅本地编辑 · 未连接存储' : c.type === 'webdav' ? 'WebDAV' : c.rootId === 'private' ? '应用私有目录' : 'NAS 本地目录';
    row.append(el('h3', w.name), el('span', w.id === workspaceId ? '当前使用' : '已保存', 'badge'), el('p', type + (c ? ' · ' + c.name : '')));
    const state = w.unknown ? '状态待检查；切换后读取' : [w.busy ? '正在同步检查' : '', w.dirty ? '有本地修改待发布' : '本地已保存', w.pending ? '发布结果待回读确认' : '', w.conflict ? '存在冲突，请处理' : '', w.recovery ? '有恢复信息，请检查详情' : '', w.error ? '连接或同步异常，请检查详情' : ''].filter(Boolean).join(' · ');
    row.append(el('p', state, w.error || w.conflict ? 'warning' : ''), el('small', `最近检查：${w.checkedAt ? new Date(w.checkedAt).toLocaleString() : '尚未检查'}。其他设备接收情况未知。`));
    const switchButton = button(w.id === workspaceId ? '正在使用' : '切换', () => switchWorkspace(w.id)); switchButton.disabled = w.id === workspaceId;
    row.append(switchButton);
    const more = el('details'); more.append(el('summary', '更多操作'));
    more.append(button('修改名称', async () => {
      const name = await requestText('课程表名称（工作区）', w.name); if (!name) return;
      await manageAction({ ...context, action: 'workspace.rename', targetId: w.id, targetVersion: w.version, name });
    }), button('选择访问端点', () => chooseEndpoint(w)), button('移除配置', async () => {
      const name = await requestText('只移除配置，保留课表文件。当前/唯一工作区和有修改、冲突、恢复信息的工作区不能移除。输入名称确认：' + w.name);
      if (name !== w.name) return;
      await manageAction({ ...context, action: 'workspace.remove', targetId: w.id, targetVersion: w.version, confirm: name });
    }));
    if (w.id === workspaceId) {
      if (c) more.append(el('p', '此存储位置中有课表时接收已有数据集；空目录则可创建独立数据集。'), button('接收已有课表…', () => $('bind').click()), button('在空目录创建共享课表…', () => $('create').click()));
      more.append(button('查看冲突', showConflicts));
    }
    if (w.unknown) for (const b of more.querySelectorAll('button')) b.disabled = true;
    const diagnostics = el('details'); diagnostics.append(el('summary', '高级诊断'), el('pre', JSON.stringify(w, null, 2)));
    row.append(more, diagnostics); return row;
  }));
  $('connectionRows').replaceChildren(...m.connections.map(c => {
    const row = el('article', null, 'source-card');
    row.append(el('h3', c.name), el('span', c.type === 'webdav' ? 'WebDAV' : c.rootId === 'private' ? '应用私有目录' : 'NAS 本地目录', 'badge'), el('p', c.type === 'webdav' ? c.url : `${m.roots.find(r => r.id === c.rootId)?.name ?? '授权已撤销'} / ${c.relative || '根目录'}`));
    const owners = m.workspaces.filter(w => w.connectionId === c.id);
    row.append(el('p', owners.length ? '用于：' + owners.map(w => w.name).join('、') : '尚未关联课程表；可新建工作区后接收或创建共享课表。'));
    row.append(button('新建工作区', async () => {
      const name = await requestText('新课程表名称（独立工作区，不会迁移或合并已有课程）', c.name); if (!name) return;
      await manageAction({ ...context, action: 'workspace.add', connectionId: c.id, name });
    }));
    const more = el('details'); more.append(el('summary', '连接操作'));
    more.append(button('编辑连接', () => connectionDialog(c.type, c)), button('测试权限与历史', async () => {
      try { const result = await post('/api/connections/test', { ...context, connectionId: c.id, name: c.name }); await notifyUser(`${result.permission}；${result.observed} 个提交；异常 ${result.deferred}`); }
      catch (e) { await notifyUser(e.message); }
    }), button('移除连接', async () => {
      if (await confirmAction('移除连接配置？不会删除课表文件；仍被工作区使用时会拒绝。')) await manageAction({ ...context, action: 'connection.remove', connectionId: c.id });
    })); row.append(more); return row;
  }));
  if (!m.connections.length) $('connectionRows').append(el('p', '尚未添加存储位置。可以继续本地编辑，或添加下面任一种连接。'));

  return m;
}
async function manageAction(input) {
  try { const result = await post('/api/manage', input); await load(); return result; }
  catch (e) { await notifyUser(e.message); await load(); }
}
async function switchWorkspace(id) {
  if (!id || id === workspaceId || switching) return;
  switching = true; $('workspaceSelect').disabled = true;
  for (const id of ['dashboard', 'dashboardActions', 'dataSources']) $(id).inert = true;
  $('editor').close();
  try {
    await post('/api/manage', { action: 'workspace.switch', targetId: id });
    ++loadEpoch; workspaceId = id; local = null;
    $('editor').close(); await load();
  } catch (e) { await notifyUser(e.message); await load(); }
  finally {
    switching = false; $('workspaceSelect').disabled = false;
    for (const id of ['dashboard', 'dashboardActions', 'dataSources']) $(id).inert = false;
  }
}
function chooseEndpoint(w) {
  dialog('工作区访问端点', () => {
    const select = el('select');
    for (const c of management.connections) { const o = el('option', c.name); o.value = c.id; select.append(o); }
    select.value = w.connectionId ?? '';
    $('fields').append(el('p', '已有数据集必须完整验证身份、已知提交和历史闭包。不同数据集请创建独立工作区。'), select);
    return () => ({ action: 'workspace.endpoint', targetId: w.id, targetVersion: w.version, connectionId: select.value });
  }, input => post('/api/manage', input));
}
function connectionDialog(type, c = null) {
  dialog(c ? '编辑连接' : (type === 'local' ? '添加本地目录连接' : '添加 WebDAV 连接'), async form => {
    const name = field('连接名称', c?.name ?? (type === 'local' ? '本地课程' : 'WebDAV 课程'));
    let read;
    if (type === 'webdav') {
      const url = field('HTTPS 共享父目录（末尾 /）', c?.url ?? ''), username = field('用户名', c?.username ?? ''), password = field('密码（留空保留已保存密码）', '', 'password'), cert = field('独立信任的服务器公钥证书 PEM（留空保留）', '', 'textarea'), system = field('恢复系统 CA', '', 'checkbox');
      $('fields').append(el('p', `独立证书信任：${c?.hasTrustedCertificate ? '已设置' : '未设置'}。修改地址、凭据或信任配置时，已绑定工作区须重新验证完整历史。`));
      read = () => ({ type, name: name.value, connectionId: c?.id, url: url.value, username: username.value, password: password.value, certificatePem: system.checked ? '' : cert.value || undefined });
    } else {
      const root = el('select'); root.setAttribute('aria-label', '存储范围'); root.disabled = true;
      let relative = c?.relative ?? '', browseEpoch = 0, directoryReady = false, selected = null;
      const current = el('p', '正在加载授权目录…'); current.setAttribute('role', 'status');
      const chosen = el('p', '尚未选择文件夹'), crumbs = el('nav'), list = el('div', null, 'folder-list');
      chosen.id = 'selectedFolder'; crumbs.setAttribute('aria-label', '文件夹路径');
      const up = button('上一级', () => { relative = relative.split('/').slice(0, -1).join('/'); void browse(); }); up.disabled = true;
      const retry = button('重试读取目录', () => { void browse(); }); retry.hidden = true;
      const choose = button('选择此文件夹', () => {
        if (!directoryReady) return;
        selected = { rootId: root.value, relative };
        chosen.textContent = '已选择：' + locationText() + (privateFolder() ? '（保存时自动创建专用文件夹）' : '');
        form.setReady(true);
      }); choose.disabled = true; choose.className = 'primary';
      const privateFolder = () => !c && root.value === 'private' && !relative;
      const locationText = () => (root.selectedOptions[0]?.textContent ?? '') + ' / ' + (relative || '根目录');
      form.setReady(false);
      const browse = async (restore = false) => {
        const request = ++browseEpoch, selectedRoot = root.value, selectedPath = relative;
        const active = () => form.current() && request === browseEpoch;
        directoryReady = false; selected = null; form.setReady(false); choose.disabled = true;
        chosen.textContent = '尚未选择文件夹'; list.replaceChildren(); retry.hidden = true;
        up.disabled = !selectedPath;
        crumbs.replaceChildren(button(root.selectedOptions[0]?.textContent ?? '根目录', () => { relative = ''; void browse(); }));
        const parts = selectedPath.split('/').filter(Boolean);
        parts.forEach((part, i) => crumbs.append(el('span', '/'), button(part, () => { relative = parts.slice(0, i + 1).join('/'); void browse(); })));
        current.textContent = '正在读取目录：' + locationText(); $('formError').textContent = '';
        try {
          const result = await get('/api/directories?rootId=' + encodeURIComponent(selectedRoot) + '&relative=' + encodeURIComponent(selectedPath));
          if (!active()) return;
          for (const dir of result.directories) list.append(button('📁 ' + dir, () => { relative = selectedPath ? selectedPath + '/' + dir : dir; void browse(); }));
          if (!result.directories.length) list.append(el('p', '此文件夹没有子目录，可以选择当前文件夹。'));
          current.textContent = '当前路径：' + locationText();
          directoryReady = true; choose.disabled = false;
          choose.textContent = privateFolder() ? '使用私有目录（自动创建专用文件夹）' : '选择此文件夹';
          if (restore) choose.click();
        } catch (e) {
          if (!active()) return;
          current.textContent = '目录加载失败：' + e.message;
          $('formError').textContent = '请重试、选择其他授权目录或关闭。权限不足时请检查 fnOS 应用设置。'; retry.hidden = false;
        }
      };
      root.onchange = () => { relative = ''; void browse(); };
      const rootLabel = el('label', '存储范围'); rootLabel.append(root);
      $('fields').append(el('p', '私有目录仅供本应用使用；选择其根目录时，保存连接会自动创建专用文件夹。NAS 目录只显示 fnOS 已授权范围，请选择专用空文件夹或已有共享课表的父文件夹。'), rootLabel, crumbs, up, current, list, retry, choose, chosen);
      const m = await loadManagement();
      if (!form.current()) return;
      if (!m || !workspaceId || !token) throw Error('工作区尚未就绪，请重试');
      if (!Array.isArray(m.roots) || !m.roots.length) throw Error('没有可用的授权目录');
      for (const r of m.roots) { const o = el('option', r.name); o.value = r.id; root.append(o); }
      root.value = c?.rootId ?? 'private'; root.disabled = false;
      if (!root.value) throw Error('原目录未授权或授权已撤销，请关闭并重新配置');
      form.captureVersion();
      void browse(!!c);
      read = () => {
        if (!directoryReady) throw Error('请先成功读取所选目录');
        if (!selected) throw Error('请点击“选择此文件夹”确认位置');
        return { type, name: name.value, connectionId: c?.id, ...selected, createPrivate: privateFolder() };
      };
    }
    $('fields').append(el('p', '保存连接不会创建课程数据集；之后可在存储位置中新建工作区。'), button('测试（不保存）', async () => {
      try {
        const input = read();
        if (input.createPrivate) throw Error('新私有目录请先保存连接，再测试权限；测试不会创建工作区');
        const result = await post('/api/connections/test', input);
        if (form.current()) $('formError').textContent = `${result.permission}；${result.observed} 个提交；异常 ${result.deferred}`;
      } catch (e) { if (form.current()) $('formError').textContent = e.message; }
    }));
    return read;
  }, input => post('/api/manage', { action: 'connection.save', ...input }));
}
$('workspaceSelect').onchange = () => switchWorkspace($('workspaceSelect').value);
function showSources(open) {
  $('history').hidden = true;
  $('backups').hidden = true;
  $('dataSources').hidden = !open;
  $('dashboard').hidden = open;
  $('dashboardActions').hidden = open;
  $('pageTitle').textContent = open ? '数据源管理' : '课程表管理';
  $('sourcesPage').textContent = open ? '返回课表' : '数据源管理';
}
$('sourcesPage').onclick = () => showSources($('dataSources').hidden);
$('addWebDav').onclick = () => connectionDialog('webdav');
$('addLocal').onclick = () => connectionDialog('local');
$('refreshSources').onclick = () => load();
let backupToken = null, backupDirectoryIdentity = null;
const backupLocation = () => ({ rootId: $('backupRoot').value, relative: $('backupRelative').value, directoryIdentity: backupDirectoryIdentity });
async function backupJob(action) {
  const buttons = [...$('backups').querySelectorAll('button')];
  buttons.forEach(b => b.disabled = true); $('backupStatus').textContent = '正在处理…';
  try { await action(); }
  catch (e) { $('backupStatus').textContent = e.message; }
  finally { $('backupPassword').value = ''; buttons.forEach(b => b.disabled = false); $('backupRestore').disabled = !backupToken; }
}
function renderBackupPreview(p) {
  const root = $('backupPreview'); root.replaceChildren();
  root.append(el('h3', '已验证的备份内容'), el('p', `${new Date(p.createdAt).toLocaleString()} · FPK ${p.appVersion} · 格式 ${p.formatVersion} · ${p.workspaceCount} 个工作区 · ${p.fileCount} 个文件 · ${p.bytes} 字节`), el('p', p.coverage));
  for (const d of p.dependencies) {
    const row = el('article', null, 'course');
    row.append(el('strong', d.workspaceName), el('p', `${d.courseCount} 门课程 · ${d.pending ? '有待确认候选' : '无候选'} · ${d.conflict ? '有未解决冲突' : '无冲突'}`), el('p', d.included ? '已包含：应用状态和私有本地提交' : d.type === 'unbound' ? '已包含：离线应用状态；未关联外部数据' : '需外部访问：' + (d.type === 'webdav' ? d.endpoint : d.endpoint.relative || '授权目录')));
    const status = p.external?.find(x => x.workspaceId === d.workspaceId);
    if (status) row.append(el('p', status.verified ? status.status : '外部数据暂不可验证：' + status.reason));
    const details = el('details'); details.append(el('summary', '身份、已知历史与依赖详情'), el('pre', JSON.stringify(d, null, 2))); row.append(details); root.append(row);
  }
  if (p.externalStatus) root.append(el('p', p.externalStatus));
}
async function browseBackups() {
  backupDirectoryIdentity = null; backupToken = null; $('backupPreview').textContent = '';
  const dirs = await get('/api/directories?rootId=' + encodeURIComponent($('backupRoot').value) + '&relative=' + encodeURIComponent($('backupRelative').value));
  $('backupChild').replaceChildren(el('option', '选择子目录…'));
  $('backupChild').firstChild.value = '';
  for (const name of dirs.directories) { const o = el('option', name); o.value = name; $('backupChild').append(o); }
  const result = await post('/api/backups/list', backupLocation()); backupDirectoryIdentity = result.directoryIdentity;
  $('backupRows').replaceChildren(...result.backups.map(b => {
    const row = el('article', null, 'course');
    row.append(el('strong', b.name), el('p', `${b.size} 字节 · 文件时间 ${new Date(b.modifiedAt).toLocaleString()} · ${b.status}`), button('验证并预览恢复', () => backupJob(async () => {
      backupToken = null;
      const preview = await post('/api/backups/verify', { ...backupLocation(), name: b.name, password: $('backupPassword').value });
      backupToken = preview.token;
      renderBackupPreview(preview);
      $('backupStatus').textContent = '认证解密、文件 SHA-256 和应用状态验证通过。请检查覆盖范围与外部依赖。';
    })));
    return row;
  }));
  $('backupStatus').textContent = `${result.backups.length} 个加密备份；未输入口令的文件不标为已验证。` + (result.lastRestore?.ok ? ' 最近恢复完成：' + new Date(result.lastRestore.restoredAt).toLocaleString() : '');
}
$('backupsPage').onclick = () => backupJob(async () => {
  $('history').hidden = true; $('backups').hidden = false; $('dataSources').hidden = true; $('dashboard').hidden = true; $('dashboardActions').hidden = true;
  $('pageTitle').textContent = '备份与恢复';
  const old = $('backupRoot').value;
  $('backupRoot').replaceChildren(...management.roots.filter(r => r.id !== 'private').map(r => { const o = el('option', r.name); o.value = r.id; return o; }));
  if ([...$('backupRoot').options].some(o => o.value === old)) $('backupRoot').value = old;
  await browseBackups();
});
$('backupRoot').onchange = () => backupJob(async () => { $('backupRelative').value = ''; await browseBackups(); });
$('backupChild').onchange = () => backupJob(async () => { if ($('backupChild').value) { $('backupRelative').value = [$('backupRelative').value, $('backupChild').value].filter(Boolean).join('/'); await browseBackups(); } });
$('backupUp').onclick = () => backupJob(async () => { $('backupRelative').value = $('backupRelative').value.split('/').slice(0, -1).join('/'); await browseBackups(); });
$('backupBrowse').onclick = () => backupJob(browseBackups);
$('backupCreate').onclick = () => backupJob(async () => {
  const result = await post('/api/backups/create', { ...backupLocation(), password: $('backupPassword').value });
  await browseBackups(); renderBackupPreview(result); $('backupStatus').textContent = '备份已保存并从实际文件重新认证验证。请妥善保管口令。';
});
$('backupRestore').onclick = () => backupJob(async () => {
  const token = backupToken;
  const confirm = await requestText('将用已验证备份替换当前全部应用工作区和连接。恢复前状态会保留，外部历史不会改写。输入“恢复应用数据”确认：');
  if (confirm !== '恢复应用数据') {
    await post('/api/backups/cancel', { token }); backupToken = null;
    $('backupStatus').textContent = '已取消恢复并撤销预览'; return;
  }
  const result = await post('/api/backups/restore', { token, confirm });
  backupToken = null; workspaceId = undefined; await load(); $('backupStatus').textContent = result.result;
});
await load();

let historyPreview = null, historyScope = null, historyEligible = false;
async function historyJob(action) {
  const scope = workspaceId;
  const buttons = [...$('history').querySelectorAll('button')];
  buttons.forEach(b => b.disabled = true);
  try { await action(); }
  catch (e) { $('historyStatus').textContent = e.message; historyPreview = null; await load(); }
  finally { buttons.forEach(b => b.disabled = false); $('historyConfirm').disabled = !historyPreview || workspaceId !== scope; $('historyPreview').disabled = !historyEligible; }
}
function historyJSON(value) { return el('pre', JSON.stringify(value, null, 2)); }
function historyRecord(table, r) {
  const card = el('article', null, 'source-card');
  if (typeof r === 'string') { card.append(el('strong', r)); return card; }
  const clock = n => n == null ? '未设置' : String(Math.floor(n/60)).padStart(2,'0')+':'+String(n%60).padStart(2,'0');
  const instant = n => n == null ? '未设置' : new Date(Number(n)).toLocaleString('zh-CN', {timeZone:$('zone').value});
  card.append(el('strong', r.title ?? r.displayName ?? r.termKey ?? r.date ?? r.key ?? r.id));
  if (table === 'entries') card.append(el('p', `每周${r.day} · ${clock(r.startMinute)}–${clock(r.endMinute)} · ${r.location || '未设置教室'}`));
  if (table === 'events' || table === 'imported') card.append(el('p', `${instant(r.start)} → ${instant(r.end)} · ${r.location ?? ''}`));
  if (table === 'imported') card.append(el('p', `来源 ${r.sourceId} · 系列 ${r.seriesId} · 原始发生 ${instant(r.originalStart)} · ${r.timezone}`));
  if (table === 'sources') card.append(el('p', `${r.filename} · ${r.occurrenceCount} 个原始实例 · 学期 ${r.termKey ?? '按来源推导 / 未关联'}；关联覆盖在覆盖集合中保留。`));
  if (table === 'classOverrides') card.append(el('p', `${r.anchorDate === null ? '全周覆盖' : '指定周 '+r.anchorDate} · ${r.date ? '调整到 '+r.date : r.weekday ? '改为周'+r.weekday : '保留原日期'} · ${r.timingMode === 'linked' ? '关联节次 '+r.periodStart+'–'+r.periodEnd : r.startMinute != null ? clock(r.startMinute)+'–'+clock(r.endMinute) : '保留原时间'} · ${r.location ?? '保留原教室'}`));
  if (table === 'dayAdjustments') card.append(el('p', `${r.mode === 'none' ? '停课' : '按周'+r.weekday+'补课'} · ${r.label}`));
  if (table === 'profiles') card.append(el('p', `第一周周一 ${r.week1Monday} · ${r.timezone} · ${r.phases.length} 个作息阶段（节次完整字段见详情）`));
  const fields = el('details'); fields.append(el('summary','完整字段与业务身份'),historyJSON(r)); card.append(fields);return card;
}

function renderHistoryDiff(d, append = false, more = null) {
  const output = $('historyOutput'); if (!append) output.replaceChildren();
  if (!append) {
    output.append(el('h3', '目标状态相对起始状态的变化'));
    for (const c of Object.values(d.counts)) if (c.added + (c.restored ?? 0) + c.deleted + c.modified) output.append(el('p', `${c.label}：新增 ${c.added} · 恢复 ${c.restored ?? 0} · 删除 ${c.deleted} · 修改 ${c.modified}`));
    for (const g of d.groups ?? []) output.append(el('p', `关联组「${g.title}」${g.termKey ? ' · 学期 '+g.termKey : ''}：新增 ${g.added} · 恢复 ${g.restored ?? 0} · 删除 ${g.deleted} · 修改 ${g.modified}`));
    output.append(el('p', `共 ${d.total} 条记录变化。来源、原始实例和覆盖通过关联组标识对应；来源替换须一起检查这些关联。`));
  }
  for (const r of d.rows) {
    const row = el('details'); row.append(el('summary', `${r.label} · ${{added:'新增',restored:'恢复',deleted:'删除',modified:'修改'}[r.action]} · ${r.title}`), el('p', '关联组：' + r.group), el('p','变化前'), r.before === undefined ? el('p','无此记录') : historyRecord(r.table,r.before), el('p','目标状态'), r.after === undefined ? el('p','删除此记录') : historyRecord(r.table,r.after)); output.append(row);
  }
  if (d.next !== null && more) output.append(button('继续查看下 40 条', e => { e.currentTarget.remove(); return historyJob(() => more(d.next)); }));
}
async function historyRead() {
  await migrationRead();
  historyPreview = null; historyEligible = false; historyScope = workspaceId; $('historyOutput').replaceChildren();
  const h = await get('/api/history'); if (historyScope !== workspaceId) return;
  version = h.version;
  $('historyStatus').textContent = `${h.count} / ${h.capacity} 个已验证提交 · 原始字节 ${h.bytes} · 前沿 ${h.tips.length} · 缓存视图，需主动检查远端。${h.warning ?? ''} ${h.error ?? ''} ${h.blockers.join('；')}`;
  $('historyRows').replaceChildren();
  for (const [i, r] of h.rows.entries()) {
    const row = el('article', null, 'course');
    row.append(el('strong', `因果层 ${r.level} · 版本 ${i + 1}${r.tip ? ' · 当前前沿' : ''}${r.matchesCurrent ? ' · 与当前课程相同' : ''}${r.parents.length > 1 ? ' · 合并版本' : ''}`));
    row.append(el('p', `版本 ${r.id.slice(0,8)} · Writer ${r.writer.slice(0,8)} · ${r.parents.length ? '← 父版本 '+r.parents.map(id => h.rows.findIndex(x => x.id === id)+1).join('、') : '根版本'} · ${h.rows.filter(x => x.parents.includes(r.id)).length > 1 ? '分叉点' : '因果链'}`));
    const comparisons = r.comparisons ?? [{ parentId: r.parents[0] ?? null, changes: r.changes, total: r.total }];
    for (const comparison of comparisons) {
      row.append(el('p', `相对${comparison.parentId ? '父版本 '+(h.rows.findIndex(x => x.id === comparison.parentId)+1)+'（'+comparison.parentId.slice(0,8)+'）' : '空快照（根版本）'}：${comparison.total} 条变化`));
      for (const c of Object.values(comparison.changes)) if (c.added+c.deleted+c.modified) row.append(el('small', `${c.label}：+${c.added} / −${c.deleted} / 修改 ${c.modified}；`));
    }
    const detail = el('details'); detail.append(el('summary', '高级校验详情'), historyJSON(r)); row.append(detail);
    row.append(button('只读查看完整快照', () => historyJob(async () => {
      $('historyOutput').replaceChildren(el('h3', '完整快照 · 按集合分页只读查看'));
      const tables = { entries:'本地课程',events:'日历事件',sources:'来源',imported:'原始实例',classOverrides:'全周 / 指定周覆盖',dayAdjustments:'停课 / 补课',profiles:'学期与节次',customProfileKeys:'自定义身份' };
      for (const [table, label] of Object.entries(tables)) {
        const group = el('section'); group.append(el('h4', label)); $('historyOutput').append(group);
        const read = async offset => { const v = await get(`/api/history/snapshot?id=${r.id}&table=${table}&offset=${offset}`); group.append(el('p', `记录 ${offset} / ${v.total}`), ...v.rows.map(row => historyRecord(table,row)));  if (v.next !== null) group.append(button('继续查看', e => { e.currentTarget.remove(); return historyJob(() => read(v.next)); })); };
        group.append(button('展开此集合', () => historyJob(() => read(0))));
      }
    })));
    $('historyRows').append(row);
  }
  for (const key of ['historyFrom','historyTo']) $(key).replaceChildren(...h.rows.map(r => { const o = el('option', `层 ${r.level} · ${r.id.slice(0,8)}${r.tip ? ' · 前沿' : ''}`); o.value = r.id; return o; }));
  historyEligible = h.rollbackEnabled; $('historyPreview').disabled = !historyEligible;
}
$('historyPage').onclick = () => historyJob(async () => {
  $('history').hidden = false; $('backups').hidden = true; $('dataSources').hidden = true; $('dashboard').hidden = true; $('dashboardActions').hidden = true; $('pageTitle').textContent = '历史与版本';
  await loadManagement();
  $('historyRoot').replaceChildren(...management.roots.filter(r => r.id !== 'private').map(r => { const o = el('option',r.name); o.value = r.id; return o; }));
  await historyRead();
});
$('historyCheck').onclick = () => historyJob(async () => { await post('/api/history/check'); await historyRead(); });
$('historyDiff').onclick = () => historyJob(async () => {
  historyPreview = null;
  const from = $('historyFrom').value, to = $('historyTo').value;
  const read = async offset => { const d = await get(`/api/history/diff?from=${from}&to=${to}&offset=${offset}`); renderHistoryDiff(d, offset > 0, read); };
  await read(0);
});
$('historyPreview').onclick = () => historyJob(async () => {
  historyPreview = await post('/api/history/preview', { target: $('historyTo').value });
  const preview = historyPreview;
  const more = async offset => renderHistoryDiff(await get(`/api/history/preview-diff?token=${preview.token}&offset=${offset}`), true, more);
  renderHistoryDiff(preview.diff, false, more);
  $('historyStatus').textContent = historyPreview.unchanged ? '目标与当前相同，不会创建提交。' : '请检查新增 / 恢复、修改与删除。确认时将重新验证远端和全部输入。';
});
for (const key of ['historyFrom','historyTo','workspaceSelect']) $(key).addEventListener('change', () => { historyPreview = null; $('historyConfirm').disabled = true; if (key === 'workspaceSelect') $('history').hidden = true; });
$('historyConfirm').onclick = () => historyJob(async () => {
  const p = historyPreview, scope = workspaceId;
  if (!p || scope !== historyScope) throw Error('工作区已变化，请刷新');
  if (!await confirmAction('将以此完整历史快照发布新版本，预览中的删除也会生效。旧提交仍然保留。', '确认回退')) return;
  await post('/api/history/rollback', { token:p.token, target:p.target, confirm:p.target }); historyPreview = null;
  await load(); await historyRead(); $('historyStatus').textContent += ' 回退候选已按原协议处理；其他设备接收情况未知。';
});
for (const [id, action] of [['historyArchive','archive'],['historyCompact','compact']]) $(id).onclick = () => historyJob(async () => {
  historyPreview = null;
  if (!await confirmAction(action === 'compact' ? '将先完整归档，再在另一个新目录准备单根数据集。不会切换客户端或删除旧库。' : '将完整课程历史复制到受保护的新目录，请保护 NAS 授权。', '确认导出')) return;
  const result = await post('/api/history/' + action, { rootId:$('historyRoot').value, relative:$('historyRelative').value });
  $('historyOutput').replaceChildren(historyJSON(result)); $('historyStatus').textContent = result.message;
});

async function migrationRead() {
  let panel = $('datasetMigration');
  if (!panel) { panel = el('section'); panel.id = 'datasetMigration'; $('history').append(panel); }
  const result = await get('/api/migration');
  panel.replaceChildren(el('h3', '迁移到精简数据集'), el('p', result.warning));
  const form = el('div');
  const candidate = el('input'); candidate.placeholder = 'PR46 compact-UUID 相对目录'; candidate.id = 'migrationCandidate'; candidate.setAttribute('aria-label', '精简候选相对目录');
  const connection = el('select'); connection.id = 'migrationConnection'; connection.setAttribute('aria-label', 'Android 实际使用的目标 WebDAV 连接');
  const management = await get('/api/manage');
  connection.append(...management.connections.filter(c => c.type === 'webdav').map(c => { const o = el('option', `${c.name} · ${c.url}`); o.value = c.id; return o; }));
  const job = async data => {
    await post('/api/migration', data);
    // 切换会由后端改变活动工作区；重新读取它，避免继续携带旧工作区身份。
    if (data.action === 'switch') workspaceId = undefined;
    await load();
    await historyRead();
  };
  form.append(el('p', '1. 先用上方功能归档并准备候选，再添加指向该候选的独立 WebDAV 连接。选择 Android 实际使用的地址，后端将通过它读取全图。准备成功即保护新旧工作区；重启后从本页继续。'), candidate, connection,
    button('检查归档与候选，准备换库', () => historyJob(() => job({ action: 'prepare', rootId: $('historyRoot').value, candidateRelative: candidate.value, targetConnectionId: connection.value }))));
  panel.append(form);
  for (const m of result.records) {
    const card = el('article'); card.append(el('h4', `阶段：${m.phase}`), el('p', `旧库 ${m.sourceDatasetId} → 新库 ${m.targetDatasetId}`), el('p', `旧 ${m.oldCount} 个提交 → 新 ${m.newCount} 个根。旧目录原始 DPC 可回收估算 ${m.reclaimEstimateBytes} 字节（仅旧副本，不含保留归档）；实际已回收 0 字节。`), el('p', `最后检查：${m.lastCheck}`));
    if (m.failure) card.append(el('p', `失败：${m.failure}。恢复网络/正确原始字节后重新检查；禁止跨 dataset 合并。`));
    card.append(el('p', `归档位置：${m.archive.relative}。旧工作区保留完整缓存、身份与历史。`));
    card.append(el('p', `旧图实际观察到的 Writer：${(m.sourceWriters ?? []).join('、')}。这不表示全部客户端；同一 Writer 可能有多份安装副本，管理员须另行登记全部设备。`));
    const action = (name, data = {}) => job({ action: name, migrationId: m.id, migrationVersion: m.revision, ...data });
    const steps = [ ['prepared', '2–4. 已登记所有已知 Writer，确认停写', 'freeze', m.sourceDatasetId], ['frozen', '5. 切换 FPK（仍保持新旧停写）', 'switch', m.targetDatasetId], ['switching', '恢复 FPK 切换', 'switch', m.targetDatasetId], ['switched', '6–7. 已逐台验收，标记旧库退役并开放新库', 'retire', m.sourceDatasetId] ];
    for (const [phase, label, name, identity] of steps) if (m.phase === phase) card.append(button(label, () => historyJob(async () => {
      const confirm = await requestText(`请输入 ${identity} 确认。管理员勾选不证明全部设备永久停写。`);
      if (confirm !== null) await action(name, { confirm });
    })));
    card.append(button('重新检查两库、归档与目标地址', () => historyJob(() => action('recheck'))));
    if (!['retired','cancelled'].includes(m.phase)) card.append(button('取消（切换后不覆盖旧库）', () => historyJob(async () => {
      if (await confirmAction('取消后仅原工作区恢复写入；保留新工作区与恢复证据。确认所有设备仍停写，尚未在新库独立编辑？')) await action('cancel', { confirm: m.sourceDatasetId, writersStopped: true });
    })));
    const list = el('ul');
    for (const d of m.devices) list.append(el('li', `${d.name} / ${d.type} · 停写确认 ${d.stopped ? '是' : '否'} · 新库验收 ${d.accepted ? '是' : '未完成'} · ${d.origin} · ${d.evidence} · 最后登记 ${d.lastCheck?.at ?? '未记录'} ${d.lastCheck?.status ?? ''}`));
    card.append(el('p', '设备清单（人工维护；Android 本机结果请在设备换库页查看后记录）：'), list);
    if (!['retired','cancelled'].includes(m.phase)) {
      const name = el('input'); name.placeholder = '设备名称（同名可更新登记）'; name.setAttribute('aria-label', '迁移设备名称');
      const type = el('select'); for (const label of ['X08E','Phone','其他']) { const o = el('option', label); o.value = label; type.append(o); }
      const stopped = el('input'); stopped.type = 'checkbox'; const accepted = el('input'); accepted.type = 'checkbox';
      const stopLabel = el('label', '管理员确认已停写旧库'); stopLabel.append(stopped);
      const acceptLabel = el('label', '管理员已核对 Android 本机结果'); acceptLabel.append(accepted);
      const evidence = el('input'); evidence.placeholder = '本机阶段、datasetId、Writer 及验收记录'; evidence.setAttribute('aria-label', '本机迁移验收记录');
      card.append(name, type, stopLabel, acceptLabel, evidence, button('登记 / 更新设备', () => historyJob(() => action('device', { name: name.value, type: type.value, stopped: stopped.checked, accepted: accepted.checked, evidence: evidence.value }))));
    }
    card.append(el('p', '人工清理指导：全部客户端完成换库、NAS 管理员已在存储或服务端禁止旧目录写入、完整归档已复制到独立安全位置并验证后，再由管理员在 fnOS 文件管理器人工处理旧目录。先保留本日志及全部哈希，核对目录身份，禁止删除归档或新库；本应用不执行 WebDAV 批量 DELETE。'));
    panel.append(card);
  }
}

async function loadAgentAccess() {
  const r = await fetch(base + '/api/agent');
  const state = await r.json(); if (!r.ok) throw Error(state.error);
  const panel = $('agentPanel'); panel.replaceChildren();
  const choices = [];
  for (const permission of ['edit', 'admin', 'maintenance']) {
    const label = el('label', permission + ' '), box = document.createElement('input');
    box.type = 'checkbox'; box.checked = state.permissions.includes(permission); label.append(box); panel.append(label); choices.push([permission, box]);
  }
  const save = el('button', '保存 Agent 权限');
  save.onclick = async () => {
    try {
      if (!await confirmAction('授予的权限适用于所有以可信包用户连接的本地客户端。高影响操作仍需独立批准。')) return;
      await post('/api/agent', { action:'permissions', permissions:choices.filter(([,box]) => box.checked).map(([p]) => p) }); await loadAgentAccess();
    } catch (e) { await notifyUser(e.message); }
  }; panel.append(save);
  for (const p of state.approvals) {
    const item = el('article'), summary = el('pre', JSON.stringify({ operation:p.operation, operationId:p.operationId, summary:p.summary, expires:new Date(p.expires).toLocaleString(), approvedBy:p.approvedBy, consumed:p.consumed }, null, 2));
    item.append(summary);
    if (!p.approvedBy && !p.consumed) {
      const approve = el('button', '核对并批准此具体操作');
      approve.onclick = async () => {
        try {
          if (!await confirmAction('请核对上方工作区、数据集、版本和操作摘要。批准十分钟内一次有效。')) return;
          await post('/api/agent', { action:'approve', id:p.id }); await loadAgentAccess();
        } catch (e) { await notifyUser(e.message); }
      }; item.append(approve);
    }
    panel.append(item);
  }
  panel.append(el('pre', JSON.stringify(state.audit.slice(-20), null, 2)));
}
$('agentReload').onclick = () => loadAgentAccess().catch(e => notifyUser(e.message));
