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
let token,
  version,
  local,
  profiles = [];
async function load() {
  try {
    const r = await fetch(
      `${base}/api/schedule?date=${encodeURIComponent($("date").value)}&zone=${encodeURIComponent($("zone").value)}&origin=${encodeURIComponent(location.origin)}`,
    );
    const s = await r.json();
    if (!r.ok) throw Error(s.error);
    token = s.csrf;
    version = s.version;
    const statuses = {
      unbound: "未配置 WebDAV；可以离线编辑。请在管理中配置。",
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
    local = state.local;
    version = state.version;
    renderRecords();
  } catch (e) {
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
  const r = await fetch(base + path);
  const v = await r.json();
  if (!r.ok) throw Error(v.error);
  return v;
}
async function post(path, data = {}) {
  const r = await fetch(base + path, {
    method: "POST",
    headers: { "Content-Type": "application/json", "X-DormPanel-CSRF": token },
    body: JSON.stringify({ version, ...data }),
  });
  const v = await r.json();
  if (!r.ok) throw Error(v.error);
  if (v.version !== undefined) version = v.version;
  return v;
}
async function runSync(options) {
  $("refresh").disabled = true;
  try {
    await post("/api/sync", options);
  } catch (e) {
    alert(e.message);
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
      await save(read());
      $("editor").close();
      await load();
    } catch (error) {
      $("formError").textContent = error.message;
    }
  };
  $("editor").showModal();
}
$("cancel").onclick = () => $("editor").close();
const edit = (operation) => post("/api/edit", { operation });
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
          if (confirm("确认重置所选范围？"))
            try {
              await edit({
                kind: "override",
                action: "reset",
                sourceId: c.sourceId,
                seriesId: c.seriesId,
                anchorDate: select.value === "all" ? null : c.anchorDate,
              });
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
  if (confirm("确认删除 / 重置 " + label + "？相关覆盖会随课程或来源删除。"))
    try {
      await edit({ kind, id, action: "delete" });
      await load();
    } catch (e) {
      alert(e.message);
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
      return () => {
        if (!preview) throw Error("请先选择文件并检查预览");
        if (
          !confirm("确认导入以上预览？替换来源将保留原 sourceId 和已有覆盖。")
        )
          throw Error("已取消");
        return {
          id: preview.id,
          displayName: name.value,
          targetId: target.value || null,
        };
      };
    },
    (input) => post("/api/import/confirm", input),
  );
}
async function configure() {
  try {
    const c = await get("/api/config");
    version = c.version;
    dialog(
      "WebDAV 配置",
      () => {
        $("fields").append(
          el(
            "p",
            `当前绑定：${c.url ?? "未配置"}；凭据：${c.hasCredentials ? "已保存" : "未设置"}。密码留空保留。`,
          ),
        );
        const url = field("HTTPS 共享目录 URL（末尾 /）", c.url),
          username = field("用户名（填写后端凭据）", ""),
          password = field("密码", "", "password"),
          confirmOld = field(
            "切换目录时手动填写原绑定地址：" + (c.bindingKey ?? "无"),
            "",
          );
        const cert = field(
            "显式信任的服务器公钥证书 PEM（仅从独立可信渠道取得；空白保留已有证书）",
            "",
            "textarea",
          ),
          systemCa = field("移除独立证书信任，恢复系统 CA", false, "checkbox"),
          relocate = field(
            "同一数据集地址迁移（完整验证历史并保留离线修改）",
            false,
            "checkbox",
          );
        systemCa.checked = false;
        relocate.checked = false;
        $("fields").append(
          el(
            "p",
            `当前独立证书信任：${c.hasTrustedCertificate ? "已设置" : "使用系统 CA"}。不会关闭 TLS 或证书有效期校验。`,
          ),
        );
        const read = () => ({
          url: url.value,
          username: username.value,
          password: password.value,
          confirm: confirmOld.value,
          certificatePem: systemCa.checked ? "" : cert.value || undefined,
          relocate: relocate.checked,
        });
        $("fields").append(
          button("测试连接（不保存）", async () => {
            try {
              const r = await post("/api/config/test", read());
              $("formError").textContent =
                `连接成功，观察到 ${r.observed} 个提交`;
            } catch (e) {
              $("formError").textContent = e.message;
            }
          }),
        );
        return read;
      },
      (input) => post("/api/config", input),
    );
  } catch (e) {
    alert(e.message);
  }
}
async function showConflicts() {
  try {
    const c = await get("/api/conflicts");
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
              if (!confirm(label + "？将保存待发布提交。")) return;
              try {
                await post("/api/resolve", { choice, token: c.token });
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
    alert(e.message);
  }
}
$("newCourse").onclick = () => editEntry();
$("newEvent").onclick = () => editEvent();
$("manage").onclick = () => ($("manager").hidden = !$("manager").hidden);
$("newProfile").onclick = () => editProfile();
$("adjustDate").onclick = () => adjustDate();
$("importFile").onclick = importFile;
$("config").onclick = configure;
$("conflicts").onclick = showConflicts;
$("bind").onclick = () => {
  if (confirm("确认接收此目录已有数据集？本地未发布数据会阻止绑定。"))
    runSync({ bind: true });
};
$("create").onclick = () => {
  if (confirm("确认在已配置的空目录创建独立数据集？不能用于覆盖已有历史。"))
    runSync({ create: true });
};
await load();
