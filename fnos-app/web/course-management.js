// 草稿仅在内存中。所有写入使用页面已有的写操作生命周期。
export function installCourseManagement(api) {
  const $ = (id) => document.getElementById(id),
    el = (tag, text) => {
      const e = document.createElement(tag);
      if (text !== undefined) e.textContent = text;
      return e;
    };
  const dialog = el("dialog"),
    host = el("div");
  dialog.className = "course-manager";
  dialog.append(host);
  document.body.append(dialog);
  let draft = null,
    scope = null,
    detail = null,
    preview = null,
    busy = false,
    ticket = 0;
  const valid = () => {
    const c = api.context();
    return (
      scope &&
      c.workspaceId === scope.workspaceId &&
      c.epoch === scope.epoch &&
      c.view === scope.view
    );
  };
  const button = (text, fn) => {
    const b = el("button", text);
    b.type = "button";
    b.onclick = async () => {
      try {
        await fn();
      } catch (e) {
        error.textContent = e.message;
        if (!e.fieldShown) error.scrollIntoView({ block: "center" });
      }
    };
    return b;
  };
  const colorLabel = (status) =>
    ({
      DEFAULT: "自动颜色（默认）",
      AUTO: "自动颜色",
      MANUAL: "手动颜色",
      UNSUPPORTED: "不支持的颜色版本",
    })[status] ?? "未知颜色状态";
  let slotPageOffset = 0;
  const slotPageSize = 20;
  let originalSlotMap = new Map();
  const copiedSlotIds = new Set();
  const weekText = (weeks) => {
    const parts = [];
    for (let i = 0; i < weeks.length; i++) {
      const a = weeks[i];
      let b = a;
      while (weeks[i + 1] === b + 1) b = weeks[++i];
      parts.push(a === b ? String(a) : a + "–" + b);
    }
    return parts.join("、") || "无有效周";
  };
  const originals = () => [...originalSlotMap.values()];
  const error = el("p");
  error.setAttribute("role", "alert");
  function field(parent, label, value, change, type = "text") {
    const l = el("label", label),
      i = el("input");
    i.type = type;
    if (type === "date" || type === "time") i.required = true;
    i.value = value ?? "";
    i.oninput = () => {
      changed();
      change(i.value);
    };
    l.append(i);
    parent.append(l);
    return i;
  }
  let locked = [];
  function setBusy(value) {
    busy = value;
    if (value) {
      locked = [...host.querySelectorAll("button,input,select")].map((e) => [
        e,
        e.disabled,
      ]);
      for (const [e] of locked) e.disabled = true;
    } else {
      for (const [e, disabled] of locked) e.disabled = disabled;
      locked = [];
    }
  }
  function changed() {
    preview = null;
    for (const node of host.querySelectorAll("[data-course-preview]"))
      node.remove();
  }
  async function close() {
    if (busy) return;
    const dirty =
      draft &&
      (Object.keys(draft.common).length ||
        draft.slots.length ||
        draft.deleteIds.length ||
        draft.overrides?.length ||
        draft.colors?.length);
    if (dirty && !(await api.confirmAction("放弃尚未保存的课程草稿？"))) return;
    ticket++;
    draft = null;
    preview = null;
    dialog.close();
  }
  dialog.addEventListener("cancel", (e) => {
    e.preventDefault();
    close();
  });
  window.addEventListener("beforeunload", (e) => {
    if (draft) {
      e.preventDefault();
      e.returnValue = "";
    }
  });
  async function list(offset = 0) {
    const t = ++ticket;
    scope = api.context();
    draft = null;
    preview = null;
    host.replaceChildren(el("h2", "课程管理"), error, button("关闭", close));
    const filter = field(host, "搜索课程或来源", "", () => {});
    const rows = el("div");
    rows.className = "card-grid";
    host.append(
      button("搜索", () => listQuery(0)),
      rows,
    );
    let queryEpoch = 0;
    async function listQuery(offset) {
      const queryTicket = ++queryEpoch;
      let result;
      try {
        result = await api.get(
          "/api/courses?" +
            new URLSearchParams({
              workspaceId: scope.workspaceId,
              offset,
              filter: filter.value,
            }),
        );
      } catch (error) {
        if (t !== ticket || queryTicket !== queryEpoch || !valid()) return;
        throw error;
      }
      if (t !== ticket || queryTicket !== queryEpoch || !valid()) return;
      rows.replaceChildren();
      if (!result.supported)
        rows.append(
          el(
            "p",
            "当前格式不支持共享课程管理。请先完成安全升级；原单条编辑仍可使用。",
          ),
        );
      for (const c of result.rows) {
        const card = el("article");
        card.className = "course";
        card.style.borderLeft =
          "6px solid #" + c.color.rgb.toString(16).padStart(6, "0");
        card.append(
          el("h3", c.title),
          el(
            "p",
            c.source +
              " · " +
              c.slotCount +
              " 个上课时段（含停用） · " +
              (result.supported
                ? colorLabel(c.color.status)
                : "旧格式不支持共享颜色"),
          ),
        );
        const open = button(c.editable ? "编辑整门课程" : "只读查看", () =>
          edit(c, result),
        );
        card.append(open);
        rows.append(card);
      }
      if (result.next !== null)
        rows.append(button("下一页", () => listQuery(result.next)));
    }
    await listQuery(offset);
  }
  async function edit(course, status) {
    const t = ++ticket;
    const d = await api.get(
      "/api/courses?" +
        new URLSearchParams({
          workspaceId: scope.workspaceId,
          key: course.key,
          limit: slotPageSize,
        }),
    );
    if (t !== ticket || !valid()) return;
    originalSlotMap = new Map(d.slots.map((s) => [s.id, s]));
    copiedSlotIds.clear();
    slotPageOffset = 0;
    detail = d;
    scope = { ...scope, version: d.version, catalogVersion: d.catalogVersion };
    draft = {
      target: { sourceId: course.sourceId, series: course.series },
      slots: [],
      deleteIds: [],
      common: {},
    };
    preview = null;
    render();
  }
  function render() {
    host.replaceChildren(
      el("h2", detail.course.title),
      el("p", detail.course.source),
      error,
    );
    error.textContent = "";
    if (!detail.supported || !detail.course.editable) {
      host.append(
        el(
          "p",
          detail.supported
            ? "当前课程只读：存在未知能力、冲突或工作区保护，请先检查状态"
            : "需要先安全升级至 DPV3/4",
        ),
        button("返回", () => {
          draft = null;
          list();
        }),
      );
      host.append(
        el("p", "当前颜色：" + colorLabel(detail.course.color.status)),
      );
      for (const s of detail.slots)
        host.append(
          el(
            "p",
            s.title +
              " · 星期 " +
              s.weekday +
              " · " +
              s.weeks.join("、") +
              " 周 · " +
              s.location +
              " · " +
              (s.enabled ? "启用" : "停用"),
          ),
        );
      slotPager();
      return;
    }
    const common = el("section");
    common.append(el("h3", "公共信息（勾选后才统一修改）"));
    for (const k of ["title", "teacher", "note"]) {
      const label = { title: "课程名称", teacher: "教师", note: "备注" }[k],
        values = [
          ...detail.baselines.filter((b) => !b.suppressed),
          ...originals(),
        ].map((r) => r[k] ?? "");
      const l = el("label"),
        check = el("input");
      check.type = "checkbox";
      check.checked = Object.hasOwn(draft.common, k);
      const input = el("input");
      input.placeholder =
        originalSlotMap.size < detail.total
          ? "更多时段未载入，请明确填写"
          : new Set(values).size > 1
            ? "各时段不同"
            : (values[0] ?? "");
      input.value = draft.common[k] ?? "";
      check.onchange = () => {
        changed();
        if (check.checked) draft.common[k] = input.value;
        else delete draft.common[k];
      };
      input.oninput = () => {
        changed();
        if (check.checked) draft.common[k] = input.value;
      };
      l.append(check, el("span", label), input);
      common.append(l);
    }
    host.append(common);
    const color = el("section");
    const colorEdit = draft.colors?.[0],
      shownRgb = colorEdit
        ? (colorEdit.rgb ?? detail.course.automaticRgb)
        : detail.course.color.rgb;
    color.style.borderLeft =
      "6px solid #" + shownRgb.toString(16).padStart(6, "0");
    const savedDescription =
      detail.course.color.status === "UNSUPPORTED"
        ? "不支持的颜色版本（只读）"
        : colorLabel(detail.course.color.status) +
          " #" +
          detail.course.color.rgb.toString(16).padStart(6, "0").toUpperCase();
    const pendingColor = el("p");
    pendingColor.dataset.colorDraft = "true";
    pendingColor.setAttribute("role", "status");
    const showColorDraft = () => {
      const edit = draft.colors?.[0];
      pendingColor.hidden = !edit;
      pendingColor.textContent = !edit
        ? ""
        : edit.rgb === null
          ? "待保存：恢复自动颜色（尚未保存）"
          : "待保存：手动颜色 #" +
            edit.rgb.toString(16).padStart(6, "0").toUpperCase() +
            "（尚未保存）";
    };
    color.append(
      el("h3", "共享颜色"),
      el("p", "当前：" + savedDescription),
      pendingColor,
    );
    showColorDraft();
    const updateColor = (rgb) => {
      draft.colors = [{ seriesId: draft.target.series[0], rgb }];
      const hex =
        "#" + (rgb ?? detail.course.automaticRgb).toString(16).padStart(6, "0");
      picker.value = hex;
      color.style.borderLeftColor = hex;
      showColorDraft();
      changed();
    };
    const picker = field(
      color,
      "手动颜色",
      "#" + shownRgb.toString(16).padStart(6, "0"),
      (v) => {
        if (/^#[0-9a-f]{6}$/i.test(v)) updateColor(parseInt(v.slice(1), 16));
      },
      "color",
    );
    for (const rgb of [
      0x336699, 0x8844cc, 0x008577, 0xc23b55, 0xb36b00, 0x536dfe,
    ]) {
      const b = button("#" + rgb.toString(16).padStart(6, "0"), () => {
        updateColor(rgb);
      });
      b.style.borderColor = "#" + rgb.toString(16).padStart(6, "0");
      color.append(b);
    }
    color.append(
      button("恢复自动颜色", () => {
        updateColor(null);
        error.textContent = "本次将恢复自动颜色，保存前仍可取消";
      }),
    );
    host.append(color);
    host.append(el("h3", "原始系列（原始导入保持不变）"));
    for (const b of detail.baselines) {
      const section = el("section");
      section.className = "slot-card";
      section.append(
        el("h4", b.title),
        el(
          "p",
          b.weekBasis === "teaching"
            ? "原系列教学周：" + b.weeks.join("、") + "（首周 " + b.week1 + "）"
            : b.weekBasis === "unbounded"
              ? "本地原课程无限重复；有限替换必须明确选择周次"
              : "未关联可靠学期，不能确定教学周；有限替换请明确选择第 1 周周一及有效周次",
        ),
      );
      const existing = detail.overrides.find((o) => o.seriesId === b.seriesId);
      section.append(
        el(
          "p",
          existing
            ? "存在全周覆盖；未修改字段保持原值"
            : "原始系列；指定周例外保持不变",
        ),
      );
      const patch = () => {
        draft.overrides ??= [];
        let o = draft.overrides.find((o) => o.seriesId === b.seriesId);
        if (!o) {
          o = { seriesId: b.seriesId, action: "upsert", patch: {} };
          draft.overrides.push(o);
        }
        if (o.action === "reset") {
          o.action = "upsert";
          o.patch = {};
        }
        return o.patch;
      };
      for (const [key, label, type] of [
        ["weekday", "星期（只改变本系列）", "number"],
        ["location", "地点（本系列）", "text"],
        ["teacher", "教师（本系列）", "text"],
        ["note", "备注（本系列）", "text"],
      ]) {
        field(
          section,
          label,
          draft.overrides?.find((o) => o.seriesId === b.seriesId)?.patch?.[
            key
          ] ??
            existing?.[key] ??
            b[key],
          (v) => {
            patch()[key] = type === "number" ? Number(v) : v;
          },
          type,
        );
        section.append(
          button("恢复继承：" + label, () => {
            patch()[key] = null;
            changed();
            error.textContent = "本字段将恢复原始继承";
          }),
        );
      }
      const mode = el("select");
      for (const [v, l] of [
        ["", "保持原时间"],
        ["custom", "自定义时间"],
        ["linked", "按节次"],
      ]) {
        const o = el("option", l);
        o.value = v;
        mode.append(o);
      }
      section.append(mode);
      const from = field(section, "开始时间 / 节次", "", () => {}, "time"),
        to = field(section, "结束时间 / 节次", "", () => {}, "time");
      from.disabled = true;
      to.disabled = true;
      mode.onchange = () => {
        for (const input of [from, to]) {
          input.disabled = !mode.value;
          input.type = mode.value === "linked" ? "number" : "time";
          input.value = "";
        }
        changed();
      };
      const timeValue = (input) =>
        mode.value === "linked"
          ? Number(input.value)
          : Number(input.value.slice(0, 2)) * 60 + Number(input.value.slice(3));
      section.append(
        button("应用完整时间范围到草稿", () => {
          if (!mode.value || from.value === "" || to.value === "")
            throw Error("必须选择模式并填写完整起止范围");
          const p = patch();
          for (const k of [
            "timingMode",
            "startMinute",
            "endMinute",
            "periodStart",
            "periodEnd",
          ])
            delete p[k];
          p.timingMode = mode.value;
          p[mode.value === "custom" ? "startMinute" : "periodStart"] =
            timeValue(from);
          p[mode.value === "custom" ? "endMinute" : "periodEnd"] =
            timeValue(to);
          changed();
          error.textContent = "完整时间范围已加入草稿";
        }),
        button("移除整条全周覆盖", () => {
          draft.overrides ??= [];
          draft.overrides = draft.overrides.filter(
            (o) => o.seriesId !== b.seriesId,
          );
          draft.overrides.push({ seriesId: b.seriesId, action: "reset" });
          changed();
          error.textContent = "本次将重置本系列全周覆盖，指定周例外保留";
        }),
        button("新增时段", () => newSlot(b, false)),
        button("有限周替换原始系列", async () => {
          if (
            await api.confirmAction(
              "替换后仅在明确选择的有效周上课，范围外不再上课。继续？",
            )
          )
            newSlot(b, true);
        }),
      );
      host.append(section);
    }
    for (const original of [
      ...detail.slots,
      ...draft.slots.filter((s) => !originals().some((o) => o.id === s.id)),
    ]) {
      if (draft.deleteIds.includes(original.id)) continue;
      const s = draft.slots.find((v) => v.id === original.id) ?? original;
      const card = el("section");
      card.className = "slot-card";
      card.dataset.slotId = s.id;
      card.append(
        el(
          "h4",
          s.title +
            " · " +
            (s.replacesSeries ? "替换规则" : "独立新增") +
            " · " +
            (s.enabled ? "启用" : "停用"),
        ),
      );
      const up = () => {
        if (!draft.slots.some((v) => v.id === s.id))
          draft.slots.push(structuredClone(s));
        return draft.slots.find((v) => v.id === s.id);
      };
      for (const [key, label, type] of [
        ["weekday", "星期 1–7", "number"],
        ["week1", "第 1 周周一", "date"],
        ["location", "地点", "text"],
        ["teacher", "本时段教师", "text"],
        ["note", "本时段备注", "text"],
      ])
        field(
          card,
          label,
          s[key],
          (v) => {
            up()[key] = type === "number" ? Number(v) : v;
          },
          type,
        );
      const timing = el("select");
      for (const [v, l] of [
        ["custom", "自定义时间"],
        ["linked", "按节次"],
      ]) {
        const o = el("option", l);
        o.value = v;
        timing.append(o);
      }
      timing.value = s.periodStart === 0 ? "custom" : "linked";
      timing.onchange = () => {
        const v = up();
        if (timing.value === "custom") {
          v.periodStart = 0;
          v.periodEnd = 0;
          v.startMinute = null;
          v.endMinute = null;
        } else {
          v.periodStart = null;
          v.periodEnd = null;
          v.startMinute = 0;
          v.endMinute = 1;
        }
        changed();
        render();
      };
      card.append(timing);
      const linked = s.periodStart !== 0;
      for (const [key, label] of linked
        ? [
            ["periodStart", "开始节次"],
            ["periodEnd", "结束节次"],
          ]
        : [
            ["startMinute", "开始时间"],
            ["endMinute", "结束时间（早于开始表示次日）"],
          ]) {
        const value = linked
          ? s[key]
          : Number.isInteger(s[key])
            ? String(Math.floor(s[key] / 60)).padStart(2, "0") +
              ":" +
              String(s[key] % 60).padStart(2, "0")
            : "";
        field(
          card,
          label,
          value,
          (v) => {
            up()[key] = linked
              ? Number(v)
              : v === ""
                ? null
                : Number(v.slice(0, 2)) * 60 + Number(v.slice(3));
          },
          linked ? "number" : "time",
        );
      }
      const weeks = el("div");
      weeks.className = "week-picker";
      for (let n = 1; n <= 104; n++) {
        const l = el("label", String(n)),
          c = el("input");
        c.type = "checkbox";
        c.checked = s.weeks.includes(n);
        c.onchange = () => {
          const v = up();
          v.weeks = c.checked
            ? [...v.weeks, n].sort((a, b) => a - b)
            : v.weeks.filter((w) => w !== n);
          changed();
        };
        l.prepend(c);
        weeks.append(l);
      }
      const slotError = el("p");
      slotError.className = "slot-error";
      slotError.setAttribute("role", "alert");
      card.append(el("p", "有效教学周（离散集合）"), slotError, weeks);
      const from = field(card, "范围起周", "", () => {}, "number"),
        to = field(card, "范围止周（必须明确填写）", "", () => {}, "number");
      for (const [label, parity] of [
        ["连续范围", null],
        ["单周", 1],
        ["双周", 0],
      ])
        card.append(
          button(label, () => {
            const a = Number(from.value),
              b = Number(to.value);
            if (a < 1 || b < a || b > 104)
              throw Error("请填写 1–104 的明确范围");
            up().weeks = Array.from(
              { length: b - a + 1 },
              (_, i) => a + i,
            ).filter((w) => parity === null || w % 2 === parity);
            changed();
            render();
          }),
        );
      card.append(
        button(s.enabled ? "停用安排" : "重新启用", () => {
          up().enabled = !s.enabled;
          changed();
          render();
        }),
        button("复制为独立新增时段", () => {
          const copiedId = crypto.randomUUID();
          copiedSlotIds.add(copiedId);
          draft.slots.push({
            ...structuredClone(up()),
            id: copiedId,
            replacesSeries: null,
          });
          changed();
          render();
        }),
        button(
          s.replacesSeries
            ? "移除替换规则（可能恢复原始课程）"
            : "删除独立新增时段",
          () => {
            draft.slots = draft.slots.filter((v) => v.id !== s.id);
            if (originals().some((v) => v.id === s.id))
              draft.deleteIds.push(s.id);
            changed();
            render();
          },
        ),
      );
      host.append(card);
    }
    slotPager();
    host.append(
      el(
        "p",
        "共 " +
          detail.total +
          " 个共享时段，本页 " +
          detail.slots.length +
          " 个；未提交时段保持原状，公共字段作用于整门课程。",
      ),
      button("取消草稿", close),
      button("预览并保存", review),
    );
  }
  function slotPager() {
    if (slotPageOffset > 0)
      host.append(
        button("上一页时段（保留草稿）", () =>
          slotPage(Math.max(0, slotPageOffset - slotPageSize)),
        ),
      );
    if (detail.next !== null)
      host.append(
        button("下一页时段（保留草稿）", () => slotPage(detail.next)),
      );
  }
  async function slotPage(offset) {
    if (busy) return;
    const t = ++ticket,
      d = await api.get(
        "/api/courses?" +
          new URLSearchParams({
            workspaceId: scope.workspaceId,
            key: detail.course.key,
            offset,
            limit: slotPageSize,
          }),
      );
    if (t !== ticket || !valid()) return;
    if (
      d.version !== detail.version ||
      d.catalogVersion !== detail.catalogVersion
    )
      throw Error("时段版本已过期，原草稿保留，请重新读取后核对");
    for (const slot of d.slots) originalSlotMap.set(slot.id, slot);
    detail.slots = d.slots;
    detail.next = d.next;
    slotPageOffset = offset;
    changed();
    render();
  }
  function newSlot(b, replacement) {
    draft.slots.push({
      id: crypto.randomUUID(),
      sourceId: draft.target.sourceId,
      ownerSeries: b.seriesId,
      replacesSeries: replacement ? b.seriesId : null,
      week1: replacement && b.weekBasis === "teaching" ? b.week1 : "",
      weeks: replacement && b.weekBasis === "teaching" ? b.weeks : [],
      weekday: b.day ?? 1,
      startMinute: b.startMinute ?? 480,
      endMinute: b.endMinute ?? 540,
      periodStart: 0,
      periodEnd: 0,
      enabled: true,
      title: b.title,
      location: b.location ?? "",
      teacher: b.teacher ?? "",
      note: b.note ?? "",
    });
    changed();
    render();
  }
  async function review() {
    if (busy) return;
    if (!valid()) throw Error("课程表或页面已变化，草稿失效，请重新读取后核对");
    const invalid = [...host.querySelectorAll("input")].find(
      (input) => !input.disabled && !input.checkValidity(),
    );
    if (invalid) {
      invalid.reportValidity();
      throw Object.assign(Error("请纠正标记字段后重新预览"), {
        fieldShown: true,
      });
    }
    const emptyWeeks = draft.slots.find(
      (s) => !originals().some((o) => o.id === s.id) && s.weeks.length === 0,
    );
    if (emptyWeeks) {
      const card = [...host.querySelectorAll("[data-slot-id]")].find(
        (c) => c.dataset.slotId === emptyWeeks.id,
      );
      const hint = card?.querySelector(".slot-error");
      if (hint) {
        hint.textContent = "新增时段必须明确选择有效教学周";
        hint.scrollIntoView({ block: "center" });
      }
      throw Object.assign(Error("新增时段必须明确选择有效教学周"), {
        fieldShown: true,
      });
    }
    setBusy(true);
    try {
      const context = api.requireEditing();
      if (
        context.workspaceId !== scope.workspaceId ||
        context.version !== scope.version
      )
        throw Error("编辑资格或版本已变化，请重新读取后核对草稿");
      const capturedDraft = JSON.stringify(draft),
        request = structuredClone(draft);
      for (const [key, value] of Object.entries(request.common)) {
        const fields = [
          ...detail.baselines.filter((b) => !b.suppressed),
          ...originals(),
        ];
        if (
          originalSlotMap.size === detail.total &&
          fields.every((r) => (r[key] ?? "") === value) &&
          !(request.overrides ?? []).some(
            (o) => o.action === "reset" || Object.hasOwn(o.patch ?? {}, key),
          ) &&
          !request.slots.some((s) => s[key] !== value)
        )
          delete request.common[key];
      }
      preview = await api.post("/api/courses/preview", {
        ...scope,
        datasetId: detail.datasetId,
        edit: request,
      });
      if (!valid()) throw Error("预览已失效");
      const output = el("section");
      output.dataset.coursePreview = "true";
      output.className = "course-review";
      output.append(el("h3", "保存预览"));
      const overview = el("div");
      overview.append(el("p", preview.warning));
      const slotDetails = el("details");
      slotDetails.append(el("summary", "时段与周次明细"));
      if (
        request.slots.length ||
        request.deleteIds.length ||
        (request.overrides ?? []).length
      )
        overview.append(
          el(
            "p",
            [
              request.slots.length
                ? "时段更新 " + request.slots.length + " 项"
                : "",
              request.deleteIds.length
                ? "移除规则/独立时段 " + request.deleteIds.length + " 项"
                : "",
              (request.overrides ?? []).length
                ? "原时段覆盖 " + request.overrides.length + " 项"
                : "",
            ]
              .filter(Boolean)
              .join("；"),
          ),
        );
      if (preview.restored.length)
        overview.append(
          el(
            "p",
            "原始系列将重新出现：" +
              preview.restored.map((r) => r.title).join("、"),
          ),
        );
      if (request.slots.some((s) => s.replacesSeries !== null && !s.enabled))
        overview.append(
          el("p", "停用替换规则仍抑制原始系列，原课程不会因此恢复。"),
        );
      if (request.slots.some((s) => s.replacesSeries !== null))
        overview.append(
          el("p", "有限周替换仅在选定周次上课，范围外原课程不再出现。"),
        );
      if (preview.exceptionCount)
        overview.append(
          el(
            "p",
            "保留指定周例外 " +
              preview.exceptionCount +
              " 条；例外可能继续覆盖本次公共字段。",
          ),
        );
      overview.append(
        el(
          "p",
          preview.total
            ? "最终课表存在变化：共 " +
                preview.total +
                " 条投影差异项；同一实例字段变化可能分别产生旧/新差异项。"
            : "预览范围内没有逐日期投影差异；业务记录是否变化以候选结果为准。",
        ),
        el(
          "p",
          "预览范围：" +
            preview.projectionScope
              .map((r) => r.start + " 至 " + r.end)
              .join("；") +
            "。范围有限，不代表已检查范围外所有日期。",
        ),
      );
      if (
        request.deleteIds.some((id) => {
          const old = originals().find((s) => s.id === id);
          return (
            old?.replacesSeries &&
            !preview.restored.some((r) => r.seriesId === old.ownerSeries)
          );
        })
      )
        overview.append(
          el("p", "旧替换规则已移除，但原系列仍受最终替换规则抑制，不会恢复。"),
        );
      for (const [field, value] of Object.entries(request.common))
        output.append(
          el(
            "p",
            { title: "统一名称", teacher: "统一教师", note: "统一备注" }[
              field
            ] +
              "：" +
              value,
          ),
        );
      for (const o of request.overrides ?? []) {
        const index =
          detail.baselines.findIndex((b) => b.seriesId === o.seriesId) + 1;
        const labels = {
          title: "名称",
          teacher: "教师",
          note: "备注",
          location: "地点",
          weekday: "星期",
          timingMode: "时间模式",
          startMinute: "开始分钟",
          endMinute: "结束分钟",
          periodStart: "开始节次",
          periodEnd: "结束节次",
        };
        slotDetails.append(
          el(
            "p",
            "原时段 " +
              index +
              "：" +
              (o.action === "reset"
                ? "移除整条全周覆盖"
                : Object.entries(o.patch ?? {})
                    .map(
                      ([k, v]) =>
                        (labels[k] ?? k) +
                        " → " +
                        (v === null ? "恢复继承" : v),
                    )
                    .join("；")),
          ),
        );
      }
      for (const c of request.colors ?? [])
        output.append(
          el(
            "p",
            c.rgb === null
              ? "颜色：恢复自动"
              : "颜色：手动颜色 #" +
                  c.rgb.toString(16).padStart(6, "0").toUpperCase(),
          ),
        );
      output.append(overview);
      for (const s of request.slots) {
        const old = originals().find((v) => v.id === s.id);
        slotDetails.append(
          el(
            "p",
            (old
              ? old.enabled && !s.enabled
                ? "停用"
                : !old.enabled && s.enabled
                  ? "重新启用"
                  : "修改"
              : copiedSlotIds.has(s.id)
                ? "复制"
                : "新增") +
              "时段：" +
              s.title +
              "；星期 " +
              s.weekday +
              "；教室 " +
              s.location +
              "；教学周 " +
              (old ? weekText(old.weeks) + " → " : "") +
              weekText(s.weeks),
          ),
        );
      }
      for (const id of request.deleteIds) {
        const s = originals().find((v) => v.id === id);
        slotDetails.append(
          el(
            "p",
            s?.replacesSeries
              ? (preview.restored.some((r) => r.seriesId === s.ownerSeries)
                  ? "移除替换规则，恢复原始基线："
                  : "移除旧替换规则，原系列仍受新规则抑制：") + s.title
              : "删除独立新增：" + s?.title,
          ),
        );
      }
      if (
        request.slots.length ||
        request.deleteIds.length ||
        (request.overrides ?? []).length
      )
        output.append(slotDetails);
      if (preview.exceptionCount) {
        const exceptions = el("details");
        exceptions.append(el("summary", "保留的指定周例外明细"));
        for (const e of preview.exceptions)
          exceptions.append(
            el(
              "p",
              "例外日期 " +
                e.anchorDate +
                "：" +
                (e.title ?? "") +
                " " +
                (e.location ?? "") +
                " " +
                (e.note ?? ""),
            ),
          );
        output.append(exceptions);
      }
      const projectionDetails = el("details");
      projectionDetails.dataset.projectionDetails = "true";
      projectionDetails.append(
        el("summary", "最终投影逐条差异（" + preview.total + " 条差异项）"),
        el(
          "p",
          preview.total > preview.projection.length
            ? "共 " +
                preview.total +
                " 条差异项，仅展示前 " +
                preview.projection.length +
                " 条"
            : "共 " +
                preview.total +
                " 条差异项，展示 " +
                preview.projection.length +
                " 条",
        ),
      );
      for (const p of preview.projection)
        projectionDetails.append(
          el(
            "p",
            p.action +
              " " +
              p.title +
              " " +
              new Date(p.start).toLocaleString() +
              "–" +
              new Date(p.end).toLocaleTimeString() +
              " " +
              p.location,
          ),
        );
      output.append(projectionDetails);
      const advanced = el("details");
      advanced.append(
        el("summary", "高级变更证据"),
        el(
          "pre",
          JSON.stringify(
            {
              changes: preview.changes,
              businessHash: preview.businessHash,
              scope: preview.projectionScope,
            },
            null,
            2,
          ),
        ),
      );
      output.append(advanced);
      if (preview.businessHash === preview.baseHash) {
        output.append(el("p", "没有实际变更，无需保存"));
        host.append(output);
        return;
      }
      const actions = el("div");
      actions.className = "course-review-actions";
      actions.append(
        button("确认一次原子保存", async () => {
          if (busy) return;
          if (!valid() || !preview || capturedDraft !== JSON.stringify(draft))
            throw Error("预览已过期，请重新预览");
          setBusy(true);
          try {
            await api.post("/api/courses/save", {
              ...scope,
              datasetId: detail.datasetId,
              edit: request,
              courseKey: detail.course.key,
              previewHash: preview.businessHash,
            });
            draft = null;
            preview = null;
            await api.load();
            await list();
          } finally {
            setBusy(false);
          }
        }),
      );
      output.append(actions);
      host.append(output);
      output.scrollIntoView();
    } finally {
      setBusy(false);
    }
  }
  for (const id of ["coursesPage", "managerCourses"])
    $(id).onclick = async () => {
      dialog.showModal();
      try {
        await list();
      } catch (e) {
        error.textContent = e.message;
        error.scrollIntoView({ block: "center" });
      }
    };
}
