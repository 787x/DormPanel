import ICAL from "ical.js";
import { randomUUID } from "node:crypto";
import { Temporal } from "@js-temporal/polyfill";
import { ensure, hash, date, zone } from "./protocol.mjs";

const digest = (text) => hash(Buffer.from(text)).toString("hex");
const at = (d, t, z) =>
  date(d).toPlainDateTime(t).toZonedDateTime(z).epochMilliseconds;
const profileMaterial = (p) =>
  `term=${p.termKey}\ntz=${p.timezone}\nw1=${p.week1Monday}\n` +
  [...p.phases]
    .sort((a, b) => (a.effectiveFrom < b.effectiveFrom ? -1 : 1))
    .map(
      (ph) =>
        `phase=${ph.effectiveFrom}` +
        [...ph.periods]
          .sort((a, b) => a.periodNumber - b.periodNumber)
          .map((t) => `|${t.periodNumber}:${t.start}-${t.end}`)
          .join("") +
        "\n",
    )
    .join("");
export function csvRows(text) {
  const rows = [];
  let row = [],
    field = "",
    quoted = false;
  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (quoted) {
      if (ch === '"' && text[i + 1] === '"') {
        field += '"';
        i++;
      } else if (ch === '"') quoted = false;
      else field += ch;
    } else if (ch === '"' && !field) quoted = true;
    else if (ch === ",") {
      row.push(field);
      field = "";
    } else if (ch === "\r" || ch === "\n") {
      if (ch === "\r" && text[i + 1] === "\n") i++;
      row.push(field);
      rows.push(row);
      row = [];
      field = "";
    } else field += ch;
  }
  ensure(!quoted, "CSV 引号未闭合");
  if (field || row.length) {
    row.push(field);
    rows.push(row);
  }
  return rows;
}
function csv(text, profiles) {
  const rows = csvRows(text),
    term = /学年学期\s*[:：]\s*([0-9]{4}-[0-9]{4}-[0-9])/.exec(text)?.[1];
  ensure(term && /个人课表/.test(text), "仅支持湖北大学个人课表 CSV");
  const p = profiles.find((p) => p.termKey === term);
  ensure(p, "请先配置学期 " + term);
  const dayName = [
    "星期一",
    "星期二",
    "星期三",
    "星期四",
    "星期五",
    "星期六",
    "星期天",
    "星期日",
  ];
  const header = rows.find(
    (row) => row.filter((x) => dayName.includes(x.trim())).length >= 5,
  );
  ensure(header, "CSV 缺少星期表头");
  const cols = header
    .map((v, i) => [i, dayName.indexOf(v.trim()) + 1])
    .filter(([, d]) => d > 0)
    .map(([i, d]) => [i, Math.min(d, 7)]);
  const occurrences = [],
    warnings = [],
    seen = new Set();
  for (const row of rows) {
    if (!/第[一二三四五六七八九十百零]+节/.test(row[0] ?? "")) continue;
    const periods = /\((\d{1,2}(?:,\d{1,2})+)小节\)/
      .exec(row[0])?.[1]
      .split(",")
      .map(Number);
    if (!periods) continue;
    for (const [col, day] of cols) {
      const cell = (row[col] ?? "").trim().replace(/\r\n?/g, "\n");
      if (!cell) continue;
      const m = /^(.+?)\n(\d+(?:-\d+)?)周\[(\d{2}(?:-\d{2})+)节\]/.exec(cell);
      if (!m) {
        warnings.push(
          "未导入缺少周次/节次的单元格：" + cell.split("\n")[0].slice(0, 40),
        );
        continue;
      }
      const weeks = m[2].split("-").map(Number),
        a = weeks[0],
        b = weeks.at(-1),
        nums = m[3].split("-").map(Number),
        title = m[1].trim();
      if (
        a < 1 ||
        b < a ||
        b > 30 ||
        nums.some((n, i) => n < 1 || n > 20 || (i && n <= nums[i - 1]))
      ) {
        warnings.push("未导入不支持的周次/节次：" + title);
        continue;
      }
      ensure(
        nums.every((n) => periods.includes(n)),
        "单元格节次与行声明不一致",
      );
      const key = [title, day, nums.join(","), a, b].join("\0"),
        seriesId = digest(key);
      if (seen.has(key)) {
        warnings.push("忽略重复课程：" + title);
        continue;
      }
      seen.add(key);
      const lines = cell
          .slice(m[0].length)
          .split("\n")
          .map((s) => s.trim())
          .filter(Boolean),
        location = lines[0] ?? "",
        description = lines.slice(1).join("\n");
      const periodLabel =
        "[" + nums.map((n) => String(n).padStart(2, "0")).join("-") + "节]";
      for (let w = a; w <= b; w++) {
        const d = date(p.week1Monday)
            .add({ days: (w - 1) * 7 + day - 1 })
            .toString(),
          ph = [...p.phases]
            .filter((ph) => ph.effectiveFrom <= d)
            .sort((a, b) => (a.effectiveFrom < b.effectiveFrom ? -1 : 1))
            .at(-1);
        const first = ph?.periods.find((t) => t.periodNumber === nums[0]),
          last = ph?.periods.find((t) => t.periodNumber === nums.at(-1));
        ensure(first && last, "学期阶段缺少所需节次");
        const start = at(d, first.start, p.timezone),
          end = at(d, last.end, p.timezone);
        ensure(end > start, "节次时间非法");
        occurrences.push({
          id: digest(seriesId + "\0" + start),
          seriesId,
          sourceId: "",
          uid: null,
          originalStart: start,
          title,
          start,
          end,
          timezone: p.timezone,
          location,
          description: [periodLabel, location, description]
            .filter(Boolean)
            .join("\n"),
          periodLabel,
        });
      }
    }
  }
  return {
    occurrences,
    warnings,
    calendarName: "湖北大学 " + term,
    termKey: term,
    profile: p,
  };
}

function ics(text) {
  // 标准库负责 RFC 内容行、转义和递归解析；预检拒绝会改变既有 Android 导入语义的构造。
  const unfolded = text.replace(/\r?\n[ \t]/g, ""),
    stack = [];
  let properties = 0,
    versions = 0;
  for (const line of unfolded.split(/\r?\n/).filter(Boolean)) {
    ensure(
      line.length <= 16384 && ++properties <= 8192,
      "ICS 属性数量/大小超限",
    );
    const split = line.indexOf(":");
    ensure(split > 0, "ICS 属性格式无效");
    const head = line.slice(0, split).toUpperCase(),
      name = head.split(";")[0],
      value = line.slice(split + 1);
    if (name === "BEGIN") {
      const parent = stack.at(-1);
      ensure(
        !parent
          ? value === "VCALENDAR"
          : parent === "VCALENDAR"
            ? ["VEVENT", "VTIMEZONE"].includes(value)
            : parent === "VEVENT"
              ? value === "VALARM"
              : parent === "VTIMEZONE"
                ? ["STANDARD", "DAYLIGHT"].includes(value)
                : false,
        "不支持的 ICS 组件",
      );
      stack.push(value);
    } else if (name === "END") {
      ensure(stack.pop() === value, "ICS 组件未闭合");
    } else if (name === "VERSION") {
      ensure(value === "2.0" && ++versions === 1, "ICS VERSION 必须是 2.0");
    } else if (stack.includes("VTIMEZONE"))
      ensure(
        !["RRULE", "RDATE", "EXRULE", "EXDATE"].includes(name),
        "不支持循环 VTIMEZONE，请使用 IANA 时区",
      );
  }
  ensure(!stack.length && versions === 1, "ICS 不完整");
  const raw = ICAL.parse(text),
    cal = new ICAL.Component(raw);
  ensure(cal.name === "vcalendar", "需要一个 VCALENDAR");
  ensure(
    !cal.getFirstPropertyValue("method") ||
      String(cal.getFirstPropertyValue("method")).toUpperCase() === "PUBLISH",
    "不支持调度消息",
  );
  const events = cal.getAllSubcomponents("vevent");
  ensure(events.length >= 1 && events.length <= 256, "ICS 事件数量超限");
  const occurrences = [],
    warnings = ["嵌入式提醒不导入；保留来源时区与绝对时间。"],
    seriesKeys = new Set();
  let candidates = 0;
  function timestamp(time, prop) {
    ensure(time && !time.isDate, "课程必须有日期时间，不能为全天");
    const z =
      time.zone === ICAL.Timezone.utcTimezone
        ? "UTC"
        : prop.getParameter("tzid");
    ensure(z, "浮动时间需要显式 TZID 或 UTC");
    zone(z);
    const plain = Temporal.PlainDateTime.from(
      {
        year: time.year,
        month: time.month,
        day: time.day,
        hour: time.hour,
        minute: time.minute,
        second: time.second,
      },
      { overflow: "reject" },
    );
    return { ms: plain.toZonedDateTime(z).epochMilliseconds, z };
  }
  for (const [index, event] of events.entries()) {
    for (const name of [
      "dtstart",
      "dtend",
      "summary",
      "uid",
      "rrule",
      "recurrence-id",
    ])
      ensure(event.getAllProperties(name).length <= 1, "ICS 重复属性 " + name);
    ensure(
      !event.hasProperty("recurrence-id") &&
        !event.hasProperty("exrule") &&
        !event.hasProperty("duration") &&
        String(event.getFirstPropertyValue("status")).toUpperCase() !==
          "CANCELLED",
      "不支持覆盖/取消/EXRULE/DURATION，请导出 EXDATE 和 DTEND",
    );
    const sp = event.getFirstProperty("dtstart"),
      ep = event.getFirstProperty("dtend");
    ensure(sp && ep, "缺少 DTSTART/DTEND");
    const start = timestamp(sp.getFirstValue(), sp),
      end = timestamp(ep.getFirstValue(), ep),
      duration = end.ms - start.ms;
    ensure(duration > 0 && duration <= 86400000, "课程时长须在 24 小时以内");
    const title = String(event.getFirstPropertyValue("summary") ?? "").trim();
    ensure(title, "缺少 SUMMARY");
    const uid = event.getFirstPropertyValue("uid") ?? null,
      seriesId = `${uid ?? "event-" + index}\0${start.ms}`;
    ensure(!seriesKeys.has(seriesId), "重复 UID 与 DTSTART");
    seriesKeys.add(seriesId);
    const included = new Set([start.ms]),
      rule = event.getFirstPropertyValue("rrule");
    if (rule) {
      const original = event
          .getFirstProperty("rrule")
          .toICALString()
          .slice(6)
          .toUpperCase()
          .split(";"),
        parts = new Map();
      for (const bit of original) {
        const [k, v] = bit.split("=");
        ensure(
          ["FREQ", "COUNT", "UNTIL", "INTERVAL", "WKST", "BYDAY"].includes(k) &&
            !parts.has(k),
          "不支持或重复 RRULE 字段",
        );
        parts.set(k, v);
      }
      const freq = parts.get("FREQ"),
        interval = Number(parts.get("INTERVAL") ?? 1),
        count = parts.has("COUNT") ? Number(parts.get("COUNT")) : null;
      ensure(
        ["DAILY", "WEEKLY"].includes(freq) &&
          Number.isInteger(interval) &&
          interval >= 1 &&
          interval <= 366 &&
          !!count !== parts.has("UNTIL"),
        "RRULE 需要有限 COUNT 或 UNTIL",
      );
      ensure(
        count === null ||
          (Number.isInteger(count) && count >= 1 && count <= 10000),
        "COUNT 超限",
      );
      const byday = parts.get("BYDAY")?.split(",") ?? [];
      ensure(
        byday.length <= 7 &&
          byday.every((x) => /^(MO|TU|WE|TH|FR|SA|SU)$/.test(x)),
        "仅支持普通 BYDAY",
      );
      const until = parts.get("UNTIL");
      ensure(!until || /^\d{8}T\d{6}Z$/.test(until), "UNTIL 必须是 UTC");
      const untilMs = until
        ? timestamp(
            ICAL.Time.fromString(
              ICAL.design.icalendar.value["date-time"].fromICAL(until),
            ),
            sp,
          ).ms
        : null;
      const bound =
        untilMs === null
          ? (count + 1) *
            interval *
            (freq === "WEEKLY" ? 7 : byday.length ? 7 : 1)
          : Math.floor((untilMs - start.ms) / 86400000) + 1;
      ensure(bound >= 0 && bound <= 3660, "循环超过十年范围");
      if (freq === "DAILY" && interval % 7 === 0 && byday.length)
        ensure(
          byday.includes(
            ["SU", "MO", "TU", "WE", "TH", "FR", "SA"][
              sp.getFirstValue().dayOfWeek() - 1
            ],
          ),
          "无法产生循环日期",
        );
      const copy = rule.clone();
      copy.until = null;
      const iter = copy.iterator(sp.getFirstValue());
      while (true) {
        ensure(++candidates <= 20000, "循环候选超限");
        const next = iter.next();
        if (!next) break;
        const ms = timestamp(next, sp).ms;
        if (untilMs !== null && ms > untilMs) break;
        ensure(ms - start.ms <= 3660 * 86400000, "循环超过十年");
        included.add(ms);
        ensure(included.size <= 10000, "实例超限");
      }
    }
    for (const prop of event.getAllProperties("rdate"))
      for (const value of prop.getValues()) {
        ensure(value instanceof ICAL.Time, "不支持 RDATE PERIOD");
        included.add(timestamp(value, prop).ms);
      }
    for (const prop of event.getAllProperties("exdate"))
      for (const value of prop.getValues())
        included.delete(timestamp(value, prop).ms);
    const description = String(
        event.getFirstPropertyValue("description") ?? "",
      ).replace(/\r\n/g, "\n"),
      location = String(event.getFirstPropertyValue("location") ?? "").trim();
    const periodLabel =
      description
        .split("\n")
        .map((s) => s.trim())
        .find((s) => /^第\s*\d{1,2}\s*(?:[-–—]\s*\d{1,2}\s*)?节$/.test(s)) ??
      null;
    for (const ms of [...included].sort((a, b) => a - b)) {
      ensure(
        ++candidates <= 20000 && occurrences.length < 10000,
        "实例/候选超限",
      );
      occurrences.push({
        id: digest(seriesId + "\0" + ms),
        sourceId: "",
        seriesId,
        uid,
        originalStart: ms,
        title,
        start: ms,
        end: ms + duration,
        timezone: start.z,
        location,
        description,
        periodLabel,
      });
    }
  }
  return {
    occurrences,
    warnings,
    calendarName:
      cal.getFirstPropertyValue("name") ??
      cal.getFirstPropertyValue("x-wr-calname") ??
      null,
    termKey: null,
  };
}

export function previewImport(bytes, filename, state) {
  ensure(
    Buffer.isBuffer(bytes) && bytes.length <= 1024 * 1024,
    "导入文件不得超过 1 MiB",
  );
  ensure(
    typeof filename === "string" &&
      filename.length <= 255 &&
      !/[\\/\x00]/.test(filename),
    "文件名无效",
  );
  let text;
  try {
    text = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
  } catch {
    ensure(filename.toLowerCase().endsWith(".csv"), "ICS 需要 UTF-8");
    text = new TextDecoder("gb18030", { fatal: true }).decode(bytes);
  }
  const parsed = filename.toLowerCase().endsWith(".csv")
    ? csv(text, state.profiles)
    : ics(text);
  ensure(
    parsed.occurrences.length > 0 && parsed.occurrences.length <= 10000,
    "没有可导入课程或超限",
  );
  const sha256 = parsed.profile
    ? digest(
        digest(bytes) +
          "\0hubei-personal-csv-v1\0" +
          profileMaterial(parsed.profile),
      )
    : digest(bytes);
  return { ...parsed, filename, sha256 };
}
export function commitImport(
  state,
  preview,
  displayName,
  targetId,
  now = Date.now(),
) {
  ensure(
    typeof displayName === "string" && displayName.trim(),
    "来源名称不能为空",
  );
  ensure(
    !targetId || state.sources.some((s) => s.id === targetId),
    "待替换来源不存在",
  );
  const id = targetId ?? randomUUID(),
    rows = preview.occurrences.map((r) => ({
      ...r,
      id: `${id}:${r.id}`,
      sourceId: id,
    }));
  if (
    targetId &&
    state.sources.find((s) => s.id === id).sha256 === preview.sha256
  )
    return;
  state.sources = state.sources.filter((s) => s.id !== id);
  state.imported = state.imported.filter((r) => r.sourceId !== id);
  // 旧覆盖保持原身份，替换后不再匹配的覆盖仍保留。
  state.sources.push({
    id,
    displayName: displayName.trim(),
    filename: preview.filename,
    kind: "local_document",
    locator: null,
    calendarName: preview.calendarName,
    sha256: preview.sha256,
    importedAt: now,
    timezones: [...new Set(rows.map((r) => r.timezone))].sort().join(", "),
    occurrenceCount: rows.length,
    firstStart: Math.min(...rows.map((r) => r.start)),
    lastEnd: Math.max(...rows.map((r) => r.end)),
    termKey: preview.termKey,
  });
  state.imported.push(...rows);
}
