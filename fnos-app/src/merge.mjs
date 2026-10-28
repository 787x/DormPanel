import {sharedTypes} from './shared-types.mjs';
import {canonicalRecords,wireRecords} from './shared-records.mjs';
import { schemas, termKey, ensure } from "./protocol.mjs";

export const emptyState = () =>
  Object.fromEntries(schemas.map(([name]) => [name, []]));
const equal = (a, b) =>
  a === undefined || b === undefined
    ? a === b
    : JSON.stringify(a) === JSON.stringify(b);
// 先按协议编码规范化，消除 JSON 属性顺序和 I64 的表示差异。
function normalized(s) {
  return Object.fromEntries(
    schemas.map(([name, , key, fields]) => [
      name,
      [...s[name]]
        .sort((a, b) => {
          a = key ? a[key] : a;
          b = key ? b[key] : b;
          return a < b ? -1 : a > b ? 1 : 0;
        })
        .map((r) =>
          fields
            ? Object.fromEntries(
                fields.map(([f, type]) => [
                  f,
                  type === "l"
                    ? String(r[f])
                    : Array.isArray(type)
                      ? [...r[f]]
                          .sort((a, b) => (a[type[2]] < b[type[2]] ? -1 : 1))
                          .map((ph) => ({
                            effectiveFrom: ph.effectiveFrom,
                            periods: [...ph.periods]
                              .sort((a, b) => a.periodNumber - b.periodNumber)
                              .map((p) => ({
                                periodNumber: p.periodNumber,
                                start: p.start,
                                end: p.end,
                              })),
                          }))
                      : (r[f] ?? null),
                ]),
              )
            : r,
        ),
    ]),
  );
}
export function records(state) {
  const s = normalized(state),
    rows = new Map();
  for (const [list, prefix, key] of [
    ["events", "event", "id"],
    ["entries", "class", "id"],
    ["dayAdjustments", "date", "date"],
    ["classOverrides", "edit", "key"],
  ])
    for (const r of s[list]) rows.set(`${prefix}:${r[key]}`, r);
  for (const source of s.sources)
    rows.set(`source:${source.id}`, {
      source,
      occurrences: s.imported.filter((r) => r.sourceId === source.id),
    });
  for (const value of s.profiles)
    rows.set(`term:${value.termKey}`, {
      value,
      custom: s.customProfileKeys.includes(value.termKey),
    });
  for(const r of canonicalRecords(state.sharedRecords??[]))rows.set(`shared:${r.typeId}/${r.recordId}`,r);
  return rows;
}
const comparable = (key, value) =>
  key.startsWith("source:") && value
    ? { ...value, source: { ...value.source, importedAt: "0" } }
    : value;
const rowEqual = (key, a, b) => equal(comparable(key, a), comparable(key, b));
export function same(a, b) {
  const l = records(a),
    r = records(b);
  return (
    l.size === r.size && [...l].every(([k, v]) => rowEqual(k, v, r.get(k)))
  );
}
export function merge(base, local, remote, choice = null, forced = new Set(), adapters = sharedTypes) {
  const b = records(base),
    l = records(local),
    r = records(remote),
    states = [base, local, remote];
  const keys = [...new Set([...b.keys(), ...l.keys(), ...r.keys()])].sort(),
    parents = new Map(keys.map((k) => [k, k]));
  const root = (key) => {
    while (parents.get(key) !== key) key = parents.get(key);
    return key;
  };
  const join = (a, c) => {
    if (parents.has(a) && parents.has(c)) parents.set(root(c), root(a));
  };
  const types=new Map(adapters.map(a=>[a.typeId,a]));
  const prepared=new Map(states.map(state=>[state,new Map(adapters.filter(a=>(state.sharedRecords??[]).some(r=>r.typeId===a.typeId)).map(a=>[a.typeId,a.prepareRelations?.(state)]))]));
  for(const state of states)for(const record of state.sharedRecords??[]){const adapter=types.get(record.typeId);if(adapter?.schemaVersion!==record.schemaVersion)continue;
   for(const parent of adapter.relatedKeys?.(wireRecords([record])[0],state,prepared.get(state).get(record.typeId))??[])if(!rowEqual(parent,l.get(parent),b.get(parent))||!rowEqual(parent,r.get(parent),b.get(parent)))join(parent,`shared:${record.typeId}/${record.recordId}`);
  }
  for (const edit of states.flatMap((s) => s.classOverrides)) {
    const p =
      edit.sourceId === null
        ? `class:${edit.seriesId}`
        : `source:${edit.sourceId}`;
    if (b.has(p) && (!l.has(p) || !r.has(p))) join(p, `edit:${edit.key}`);
  }
  for (const source of states.flatMap((s) => s.sources)) {
    const term = termKey(source),
      p = `term:${term}`;
    if (
      term !== null &&
      (!rowEqual(p, l.get(p), b.get(p)) || !rowEqual(p, r.get(p), b.get(p)))
    ) {
      join(p, `source:${source.id}`);
      for (const edit of states
        .flatMap((s) => s.classOverrides)
        .filter((e) => e.sourceId === source.id))
        join(p, `edit:${edit.key}`);
    }
  }
  for (const d of states.flatMap((s) => s.dayAdjustments)) {
    const key = `date:${d.date}`,
      composition = (m) =>
        m.has(key) ? { ...m.get(key), label: "" } : undefined;
    if (
      !equal(composition(l), composition(b)) ||
      !equal(composition(r), composition(b))
    )
      for (const e of states.flatMap((s) => s.classOverrides))
        if (e.anchorDate === d.date || e.date === d.date)
          join(key, `edit:${e.key}`);
  }
  const groups = new Map();
  for (const k of keys) {
    const p = root(k);
    if (!groups.has(p)) groups.set(p, []);
    groups.get(p).push(k);
  }
  const merged = new Map(),
    conflicts = [];
  for (const group of groups.values()) {
    const eq = (a, c) => group.every((k) => rowEqual(k, a.get(k), c.get(k)));
    let selected;
    if (!group.some((k) => forced.has(k)) && eq(l, r)) selected = r;
    else if (!group.some((k) => forced.has(k)) && eq(l, b)) selected = r;
    else if (!group.some((k) => forced.has(k)) && eq(r, b)) selected = l;
    else {
      ensure(conflicts.length < 256, "冲突单位超过 256；保留本地修改");
      const take = (map) =>
        Object.fromEntries(group.map((k) => [k, map.get(k) ?? null]));
      conflicts.push({
        key: group[0],
        keys: group,
        base: take(b),
        local: take(l),
        remote: take(r),
      });
      selected = choice === "remote" ? r : l;
    }
    for (const k of group) if (selected.has(k)) merged.set(k, selected.get(k));
  }
  const state = emptyState();
  if(states.some(s=>s.sharedRecords!==undefined))state.sharedRecords=[];
  for (const [key, v] of [...merged].sort(([a], [b]) => (a < b ? -1 : 1))) {
    const type = key.slice(0, key.indexOf(":"));
    if(type === "shared")state.sharedRecords.push(v);
    else if (type === "source") {
      state.sources.push(v.source);
      state.imported.push(...v.occurrences);
    } else if (type === "term") {
      state.profiles.push(v.value);
      if (v.custom) state.customProfileKeys.push(v.value.termKey);
    } else
      state[
        {
          event: "events",
          class: "entries",
          date: "dayAdjustments",
          edit: "classOverrides",
        }[type]
      ].push(v);
  }
  return { state, conflicts };
}

// 与 ScheduleV2Plan 相同：逐对检测同基线冲突，然后 UUID 顺序生成待确认远端方案。
export function mergePlan(plan, local, choice = null) {
  const branches = [...plan.branches].sort((a, b) =>
      a.commitId < b.commitId ? -1 : 1,
    ),
    found = new Map(),
    details = [];
  const remember = (c) => {
    const old = found.get(c.key);
    ensure(old || found.size < 256, "冲突单位超限");
    found.set(c.key, {
      ...c,
      keys: [...new Set([...(old?.keys ?? []), ...c.keys])],
    });
  };
  for (let i = 0; i < branches.length; i++)
    for (let j = i + 1; j < branches.length; j++) {
      const conflicts = merge(
        plan.base,
        branches[i].state,
        branches[j].state,
      ).conflicts;
      conflicts.forEach(remember);
      // 完整分支保存在 plan；摘要仅存分支身份和相关 keys，避免 N² 复制大快照。
      if (conflicts.length)
        details.push({
          left: branches[i].commitId,
          right: branches[j].commitId,
          keys: [...new Set(conflicts.flatMap((c) => c.keys))],
        });
    }
  let remote = branches[0].state;
  for (const branch of branches.slice(1)) {
    const next = merge(plan.base, remote, branch.state, "remote");
    remote = next.state;
    next.conflicts.forEach(remember);
  }
  const result = merge(
    plan.localBase,
    local,
    remote,
    choice,
    new Set([...found.values()].flatMap((c) => c.keys)),
  );
  const displayed = new Map(result.conflicts.map((c) => [c.key, c]));
  for (const c of found.values())
    if (!displayed.has(c.key)) displayed.set(c.key, c);
  ensure(displayed.size <= 256, "冲突单位超限");
  return {
    state: result.state,
    conflicts: [...displayed.values()],
    remote,
    details,
  };
}
