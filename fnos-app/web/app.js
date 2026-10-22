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
      error: "离线或历史验证失败：" + s.error,
    };
    $("status").textContent =
      statuses[s.status] +
      "\n本地已保存" +
      (s.pending
        ? " · 提交待回读确认"
        : s.dirty
          ? " · 有待发布修改"
          : " · 无待发布本地修改") +
      (s.published ? "\n本机提交已验证发布：" + s.published.commitId : "");
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
      `历史：${s.historyCount} · 分支：${s.tips.length} · 异常：${s.deferred.length}`,
      `检查时间：${s.checkedAt ? new Intl.DateTimeFormat("zh-CN", { timeZone: s.zone, dateStyle: "short", timeStyle: "medium" }).format(new Date(s.checkedAt)) : "尚未检查"}`,
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
function dialog(title, build, save) {
  const scope = workspaceId, formVersion = version, formCatalogVersion = catalogVersion;
  $("editorTitle").textContent = title;
  $("fields").replaceChildren();
  $("formError").textContent = "";
  $("cancel").textContent = save ? "关闭" : "暂不处理";
  $("editorForm").querySelector("[type=submit]").hidden = !save;
  const read = build();
  $("editorForm").onsubmit = async (e) => {
    e.preventDefault();
    if (!save) return;
    try {
      if (scope !== workspaceId || formVersion !== version || formCatalogVersion !== catalogVersion) throw Error("表单版本或工作区已变化，请关闭后重新打开");
      const input = await read();
      if (scope !== workspaceId || formVersion !== version || formCatalogVersion !== catalogVersion) throw Error("表单版本或工作区已变化，请关闭后重新打开");
      await save(input);
      $("editor").close();
      await load();
    } catch (error) {
      $("formError").textContent = error.message;
    }
  };
  $("editor").showModal();
}
$("cancel").onclick = () => $("editor").close();
$("editor").addEventListener('close', () => {
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
        mode = field("模式（none 停课 / weekday 补课）", row.mode ?? "none"),
        day = field("补用星期（1–7）", row.weekday ?? 1, "number"),
        label = field("说明", row.label ?? "");
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
    root.append(el("h3", label));
    for (const row of rows) {
      const p = el(
        "p",
        row.title ??
          row.displayName ??
          row.termKey ??
          `${row.date} ${row.mode} ${row.label}`,
      );
      p.append(
        button("编辑", () => editor(row)),
        button("删除 / 重置", () =>
          remove(kind, row[key], row.title ?? row.displayName ?? row[key]),
        ),
      );
      root.append(p);
    }
  }
}
async function importFile() {
  const scope = workspaceId, importVersion = version;
  dialog(
    "导入预览",
    () => {
      const file = field("选择 ICS 或 CSV", "", "file");
      file.accept = ".ics,.csv";
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
      const info = el("pre");
      $("fields").append(info);
      let preview = null;
      file.onchange = async () => {
        try {
          const f = file.files[0];
          if (f.size > 1048576) throw Error("文件超过 1 MiB");
          const bytes = new Uint8Array(await f.arrayBuffer());
          let binary = "";
          for (const b of bytes) binary += String.fromCharCode(b);
          preview = await post("/api/import/preview", {
            workspaceId: scope, version: importVersion,
            filename: f.name,
            base64: btoa(binary),
          });
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
          preview = null;
          info.textContent = e.message;
        }
      };
      return async () => {
        if (!preview) throw Error("请先选择文件并检查预览");
        if (
          !(await confirmAction("确认导入以上预览？替换来源将保留原 sourceId 和已有覆盖。"))
        )
          throw Error("已取消");
        return {
          workspaceId: scope, version: preview.version,
          id: preview.id,
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
$("manage").onclick = () => ($("manager").hidden = !$("manager").hidden);
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
  const scope = workspaceId, m = await get('/api/manage');
  if (epoch !== loadEpoch || scope !== workspaceId) return;
  management = m; catalogVersion = m.revision;
  const context = { workspaceId: scope, version: m.workspaces.find(w => w.id === scope).version, catalogVersion: m.revision };
  $('workspaceSelect').replaceChildren(...m.workspaces.map(w => {
    const option = el('option', w.name + (w.conflict ? ' · 冲突' : w.pending ? ' · 待确认' : w.dirty ? ' · 未发布' : ''));
    option.value = w.id; return option;
  }));
  $('workspaceSelect').value = workspaceId;
  $('workspaceRows').replaceChildren(...m.workspaces.map(w => {
    const row = el('article', null, 'course');
    row.append(el('strong', w.name), el('p', `数据集 ${w.datasetId ?? '未绑定'} · ${w.dirty ? '有本地修改' : '本地已保存'} · ${w.pending ? '待发布候选' : '无候选'} · ${w.conflict ? '存在冲突' : '无冲突'} · ${w.busy ? '正在同步' : '空闲'}${w.recovery ? ' · 有恢复信息' : ''}`), el('p', `最近检查：${w.checkedAt ? new Date(w.checkedAt).toLocaleString() : '尚未检查'}${w.error ? ' · 异常：' + w.error : ''}`));
    row.append(button('切换', () => switchWorkspace(w.id)), button('修改名称', async () => {
      const name = await requestText('工作区名称', w.name); if (!name) return;
      await manageAction({ ...context, action: 'workspace.rename', targetId: w.id, targetVersion: w.version, name });
    }), button('选择访问端点', () => chooseEndpoint(w)), button('移除配置', async () => {
      const name = await requestText('只移除配置，保留课表文件。当前/唯一工作区和有修改、冲突、恢复信息的工作区不能移除。输入名称确认：' + w.name);
      if (name !== w.name) return;
      await manageAction({ ...context, action: 'workspace.remove', targetId: w.id, targetVersion: w.version, confirm: name });
    })); return row;
  }));
  $('connectionRows').replaceChildren(...m.connections.map(c => {
    const row = el('article', null, 'course');
    row.append(el('strong', c.name + ' · ' + c.type), el('p', c.type === 'webdav' ? c.url : `${m.roots.find(r => r.id === c.rootId)?.name ?? '授权已撤销'} / ${c.relative || '根目录'}`),
      button('编辑连接', () => connectionDialog(c.type, c)), button('测试权限与历史', async () => {
        try { const result = await post('/api/connections/test', { ...context, connectionId: c.id, name: c.name }); await notifyUser(`${result.permission}；${result.observed} 个提交；异常 ${result.deferred}`); }
        catch (e) { await notifyUser(e.message); }
      }), button('新建工作区', async () => {
        const name = await requestText('新工作区名称', c.name); if (!name) return;
        await manageAction({ ...context, action: 'workspace.add', connectionId: c.id, name });
      }), button('移除连接', async () => {
        if (await confirmAction('移除连接配置？仍被工作区使用时会拒绝。')) await manageAction({ ...context, action: 'connection.remove', connectionId: c.id });
      })); return row;
  }));
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
  dialog(c ? '编辑连接' : '新增连接', () => {
    const name = field('连接名称', c?.name ?? (type === 'local' ? '本地课程' : 'WebDAV 课程'));
    let read;
    if (type === 'webdav') {
      const url = field('HTTPS 共享父目录（末尾 /）', c?.url ?? ''), username = field('用户名', c?.username ?? ''), password = field('密码（留空保留已保存密码）', '', 'password'), cert = field('独立信任的服务器公钥证书 PEM（留空保留）', '', 'textarea'), system = field('恢复系统 CA', '', 'checkbox');
      $('fields').append(el('p', `独立证书信任：${c?.hasTrustedCertificate ? '已设置' : '未设置'}。修改地址、凭据或信任配置时，已绑定工作区须重新验证完整历史。`));
      read = () => ({ type, name: name.value, connectionId: c?.id, url: url.value, username: username.value, password: password.value, certificatePem: system.checked ? '' : cert.value || undefined });
    } else {
      const root = el('select'), path = el('select');
      for (const r of management.roots) { const o = el('option', r.name); o.value = r.id; root.append(o); }
      root.value = c?.rootId ?? 'private';
      let relative = c?.relative ?? '';
      const current = el('p');
      const browse = async () => {
        current.textContent = '选定目录：' + (relative || '/');
        try {
          const result = await get('/api/directories?rootId=' + encodeURIComponent(root.value) + '&relative=' + encodeURIComponent(relative));
          path.replaceChildren(el('option', '选择子目录…'));
          path.firstChild.value = '';
          for (const dir of result.directories) { const o = el('option', dir); o.value = dir; path.append(o); }
        } catch (e) { current.textContent += ' · 权限不可用：' + e.message; path.replaceChildren(); }
      };
      root.onchange = () => { relative = ''; browse(); };
      path.onchange = () => { if (path.value) { relative = relative ? relative + '/' + path.value : path.value; browse(); } };
      $('fields').append(el('p', '仅展示 FPK 私有目录及 fnOS 官方资源授权目录。应用中心撤销权限后会安全停止访问。新增私有连接默认自动创建独立目录；共享目录请选择专用空目录或已有 Sync v2 数据集父目录。'), root, current, path, button('上一级', () => { relative = relative.split('/').slice(0, -1).join('/'); browse(); }));
      browse();
      read = () => ({ type, name: name.value, connectionId: c?.id, rootId: root.value, relative, createPrivate: !c && root.value === 'private' && !relative });
    }
    $('fields').append(button('测试（不保存）', async () => {
      try {
        const input = read();
        if (input.createPrivate) throw Error('新私有目录请先保存连接，再测试权限；测试不会创建工作区');
        const result = await post('/api/connections/test', input);
        $('formError').textContent = `${result.permission}；${result.observed} 个提交；异常 ${result.deferred}`;
      } catch (e) { $('formError').textContent = e.message; }
    }));
    return read;
  }, input => post('/api/manage', { action: 'connection.save', ...input }));
}
$('workspaceSelect').onchange = () => switchWorkspace($('workspaceSelect').value);
function showSources(open) {
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
await load();
