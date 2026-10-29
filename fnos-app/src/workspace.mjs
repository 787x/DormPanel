import {legacyKey} from './shared-course-colors.mjs';
import {businessHash} from './v3-protocol.mjs';
import {scheduleSlots, scheduleRecord, validateScheduleParents} from './shared-course-schedule.mjs';
import {Registry, wireRecords, durableRecords} from './shared-records.mjs';
import {sharedTypes} from './shared-types.mjs';
import {change as changeSharedCourseColor} from './shared-course-colors.mjs';
import {canonicalRecords} from './shared-records.mjs';
import { stat } from 'node:fs/promises';
import { join } from 'node:path';
import { randomUUID } from "node:crypto";
import {
  decode,
  encode,
  ensure,
  hash,
  overrideKey,
  schemas,
  termKey,
  uuid,
} from "./protocol.mjs";
import { emptyState, same } from "./merge.mjs";
import { json } from "./store.mjs";

// Request-local evidence only; never persisted or used to bypass a transaction.
const preparationRejections = new WeakSet();
export const rejectedBeforePersistence = error => error instanceof Error && preparationRejections.has(error);

// 单一原子文档 = 本地课程、基线、精确 capture、候选、attempt、冲突与观察日志。
// 网络永不处于事务内。队列内无外部异步回调，编辑可在上传期间提交。
export class Workspace {
  constructor(store, frame) {
    this.store = store;
    this.frame = frame;
    this.queue = Promise.resolve();
    this.writing = 0;
  }
  static async open(store, graph) {
    let frame = await store.read("workspace.json");
    if (!frame) {
      frame = {
        version: 1,
        revision: 0,
        local: graph?.state ?? emptyState(),
        baseline: graph?.state ?? null,
        frontier: graph?.state ? graph.tips : [],
        datasetId: graph?.datasetId ?? null,
        graph: graph ?? null,
        bindingKey: graph?.bindingKey ?? null,
        checkpoint: (await store.read("checkpoint.json")) ?? {},
        pending: null,
        abandoned: {},
        conflict: null,
        receipt: null,
        error: (await store.read("observation.json"))?.error ?? null,
      };
      await store.save("workspace.json", frame);
    }
    ensure(
      [1,2].includes(frame.version) && Number.isSafeInteger(frame.revision),
      "不支持本地数据库版本",
    );
    canonical(frame.local);
    if (frame.baseline) canonical(frame.baseline);
    if (frame.datasetId) {
      uuid(frame.datasetId);
      ensure(!frame.graph?.datasetId || frame.graph.datasetId === frame.datasetId, '工作区缓存数据集身份异常');
      ensure(!frame.checkpoint.datasetId || frame.checkpoint.datasetId === frame.datasetId, '工作区检查点数据集身份异常');
    }
    if (frame.pending) {
      const c = decode(Buffer.from(frame.pending.raw, "base64"));
      ensure(
        c.datasetId === frame.datasetId &&
          c.writerId === store.identity.writerId,
        "本地候选身份异常",
      );
      canonical(frame.pending.capture);
    }
    const db = new Workspace(store, frame);
    await db.saveSummary(true);
    return db;
  }
  async saveSummary(ifStale = false) {
    // 摘要始终是可丢弃缓存，失败时列表回退为未知。
    try {
      const stamp = await workspaceStamp(this.store), value = workspaceSummary(this);
      if (ifStale) {
        const cached = await this.store.read('summary.json').catch(() => null);
        if (cached?.schema === 1 && cached.stamp === stamp && json(cached.value) === json(value)) return;
      }
      await this.store.save('summary.json', { schema: 1, stamp, value });
    } catch { /* 已提交帧仍有效 */ }
  }
  snapshot() {
    return structuredClone(this.frame);
  }
  tx(change, expected, transactionContext) {
    try { this.guard?.(); } catch (e) { return Promise.reject(e); }
    this.writing++;
    const task = this.queue.then(async () => {
      let preparationError;
      const prepare = () => {
        try {
          this.guard?.();
          const next = structuredClone(this.frame);
          ensure(
            expected === undefined || expected === next.revision,
            "请求版本过期，请重新读取",
          );
          const value = change(next);
          ensure(!(value instanceof Promise), "本地事务不得执行异步操作");
          this.mutationGuard?.(this.frame, next);
          ensure(next.version!==2||next.local.sharedRecords!==undefined,'Shared workspace cannot downgrade');
          next.local = canonical(next.local);
          if(next.local.sharedRecords!==undefined)next.version=2;
          next.revision++;
          return { next, value };
        } catch (error) {
          preparationError=error;
          if (error instanceof Error) preparationRejections.add(error);
          throw error;
        }
      };
      let committed;
      try {
        if (this.store.commitWorkspace) {
          // SQLite adapters execute preparation and persistence in ONE synchronous
          // transaction. Publish the in-memory frame only after COMMIT succeeds.
          committed = this.store.commitWorkspace(prepare, transactionContext);
          ensure(!(committed instanceof Promise), "Workspace 提交钩子不得执行异步操作");
        } else {
          committed = prepare();
          await this.store.save("workspace.json", committed.next);
        }
      } catch (error) {
        if(error!==preparationError && error instanceof Error)preparationRejections.delete(error);
        throw error;
      }
      this.frame = committed.next;
      await this.saveSummary();
      return committed.value;
    });
    this.queue = task.finally(() => { this.writing--; }).catch(() => {});
    return task;
  }
  courseSchedules() { return scheduleSlots(this.frame.local); }
  courseEditCandidate(state,edit) {
    const {target,slots=[],deleteIds=[],overrides=[],common={},colors=[]}=edit;
    ensure(target && Array.isArray(target.series)&&target.series.length>0&&new Set(target.series).size===target.series.length,'必须明确选择系列集合');
    const series=new Set(target.series),source=target.sourceId;
    const identities=target.series.map(id=>legacyKey(state,source,id));
    ensure(source===null||new Set(identities).size===1,'导入系列不属于同一原始逻辑课程');
    const owns=s=>s.sourceId===source&&series.has(s.ownerSeries);
    const existing=new Map(scheduleSlots(state).map(s=>[s.id,s]));
    ensure(slots.every(owns)&&deleteIds.every(id=>existing.has(id)&&owns(existing.get(id))),'安排目标超出选择范围');
    ensure(new Set(deleteIds).size===deleteIds.length&&new Set(slots.map(s=>s.id)).size===slots.length,'重复时段身份');
    ensure(Object.keys(common).every(k=>['title','teacher','note'].includes(k)),'未知公共字段');
    let next=structuredClone(state);
    for(const id of target.series) next=this.courseScheduleCandidate(next,slots.filter(s=>s.ownerSeries===id),null,{sourceId:source,ownerSeries:id,deleteIds:deleteIds.filter(key=>existing.get(key).ownerSeries===id)});
    ensure(new Set(overrides.map(o=>o.seriesId)).size===overrides.length,'重复系列覆盖');
    for(const o of overrides){
      ensure(series.has(o.seriesId)&&['upsert','reset'].includes(o.action),'覆盖目标不合法');
      if(o.action==='upsert') {
        const p=o.patch??{};
        if(p.startMinute!=null||p.endMinute!=null||p.timingMode==='custom') ensure(p.timingMode==='custom'&&Number.isInteger(p.startMinute)&&Number.isInteger(p.endMinute)&&p.endMinute>p.startMinute,'自定义时间必须明确提供有效完整起止范围');
        if(p.periodStart!=null||p.periodEnd!=null||p.timingMode==='linked') {
          ensure(p.timingMode==='linked'&&Number.isInteger(p.periodStart)&&Number.isInteger(p.periodEnd)&&p.periodStart<=p.periodEnd,'节次编辑必须明确提供完整范围');
          const owner=state.sources.find(s=>s.id===source),profile=owner&&state.profiles.find(p=>p.termKey===termKey(owner));
          ensure(profile&&profile.phases.every(phase=>{const a=phase.periods.find(t=>t.periodNumber===p.periodStart),b=phase.periods.find(t=>t.periodNumber===p.periodEnd);return a&&b&&b.end>a.start;}),'来源作息不能解析所选节次');
        }
      }
      applyEdit(next,{kind:'override',sourceId:source,seriesId:o.seriesId,anchorDate:null,action:o.action,patch:o.patch});
    }
    if(Object.keys(common).length){
      for(const id of target.series){const old=next.classOverrides.find(o=>o.sourceId===source&&o.seriesId===id&&o.anchorDate===null);
        const fields=schemas.find(s=>s[0]==='classOverrides')[3].slice(4).map(([name])=>name);
        const patch=Object.fromEntries(fields.filter(k=>old?.[k]!=null).map(k=>[k,old[k]]));
        applyEdit(next,{kind:'override',sourceId:source,seriesId:id,anchorDate:null,action:'upsert',patch:{...patch,...common}});
      }
      const registry=new Registry(sharedTypes);let rows=wireRecords(next.sharedRecords);
      for(const slot of scheduleSlots(next).filter(owns)) rows=registry.replace(rows,scheduleRecord({...slot,...common}));
      next.sharedRecords=durableRecords(rows);
    }
    ensure(new Set(colors.map(c=>legacyKey(state,source,c.seriesId))).size===colors.length,'同一逻辑课程不能提交竞争的颜色操作');
    for(const color of colors){ensure(series.has(color.seriesId)&&Object.hasOwn(color,'rgb'),'颜色目标不合法');next=changeSharedCourseColor(next,source,color.seriesId,color.rgb);}
    next=canonical(next);new Registry(sharedTypes).validateState(next);return next;
  }
  previewCourseEdit(edit,expected) {
    this.guard?.();ensure(Number.isSafeInteger(expected)&&expected===this.frame.revision,'请求版本过期，请重新读取');
    ensure(this.frame.v3?.binding?.format===4,'Explicit shared dataset upgrade required');
    const state=this.courseEditCandidate(this.frame.local,edit);return {state,businessHash:businessHash(state),expectedRevision:expected};
  }
  saveCourseEdit(edit,expected) {
    ensure(Number.isSafeInteger(expected),'必须提供 revision');
    return this.tx(f=>{ensure(f.v3?.binding?.format===4,'Explicit shared dataset upgrade required');f.local=this.courseEditCandidate(f.local,edit);return {state:structuredClone(f.local),businessHash:businessHash(f.local)};},expected);
  }
  courseScheduleCandidate(state,slots,color,target) {
    ensure(target && (target.sourceId===null||typeof target.sourceId==='string') && typeof target.ownerSeries==='string' && Array.isArray(target.deleteIds),'必须明确课程范围和删除 ID');
    const registry=new Registry(sharedTypes),current=wireRecords(state.sharedRecords??[]);registry.writable(current);
    ensure(target.sourceId===null?state.entries.some(e=>e.id===target.ownerSeries):state.sources.some(s=>s.id===target.sourceId)&&state.imported.some(r=>r.sourceId===target.sourceId&&r.seriesId===target.ownerSeries),'目标课程不存在');
    const owns=s=>s.sourceId===target.sourceId&&s.ownerSeries===target.ownerSeries;
    const existing=new Map(scheduleSlots(state).map(s=>[s.id,s]));
    ensure(slots.every(owns)&&new Set(slots.map(s=>s.id)).size===slots.length,'安排超出目标课程范围');
    ensure(target.deleteIds.every(id=>existing.has(id)&&owns(existing.get(id)))&&new Set(target.deleteIds).size===target.deleteIds.length,'删除目标不存在或超出课程范围');
    ensure(slots.every(s=>!existing.has(s.id)||owns(existing.get(s.id))),'不能更改其他课程时段归属');
    ensure(slots.every(s=>!target.deleteIds.includes(s.id)),'同一时段不能同时保存和删除');
    let records=current;
    for(const id of target.deleteIds) records=registry.remove(records,'course.schedule',id);
    for(const slot of slots) records=registry.replace(records,scheduleRecord(slot));
    let next={...state,sharedRecords:durableRecords(records)};registry.validateState(next);
    if(color){ensure(color.sourceId===target.sourceId&&color.seriesId===target.ownerSeries&&Object.hasOwn(color,'rgb'),'颜色目标不匹配');next=changeSharedCourseColor(next,color.sourceId,color.seriesId,color.rgb);}
    return next;
  }
  previewCourseSchedules(slots,color,expected,target) {
    ensure(target&&Array.isArray(target.deleteIds),'必须明确课程范围和删除 ID');ensure(!color||color.sourceId===target.sourceId,'颜色来源不匹配');
    return this.previewCourseEdit({target:{sourceId:target.sourceId,series:[target.ownerSeries]},slots,deleteIds:target.deleteIds,colors:color?[{seriesId:color.seriesId,rgb:color.rgb}]:[]},expected);
  }
  saveCourseSchedules(slots,color,expected,target) {
    ensure(target&&Array.isArray(target.deleteIds),'必须明确课程范围和删除 ID');ensure(!color||color.sourceId===target.sourceId,'颜色来源不匹配');
    return this.saveCourseEdit({target:{sourceId:target.sourceId,series:[target.ownerSeries]},slots,deleteIds:target.deleteIds,colors:color?[{seriesId:color.seriesId,rgb:color.rgb}]:[]},expected);
  }
  setSharedCourseColor(sourceId,seriesId,rgb,expected) {
    return this.tx(f=>{ensure(f.v3?.binding?.format===4,'Explicit shared dataset upgrade required');f.local=changeSharedCourseColor(f.local,sourceId,seriesId,rgb);},expected);
  }
  edit(operation, expected) {
    return this.tx((f) => {
      applyEdit(f.local, operation);
    }, expected);
  }
  stage(f, state, parents) {
    this.assertWritable?.();
    ensure(!f.pending && f.datasetId, "已有待确认提交或未绑定");
    const c = {
      datasetId: f.datasetId,
      commitId: randomUUID(),
      writerId: this.store.identity.writerId,
      parents: [...parents].sort(),
      state,
    };
    const raw = encode(c);
    decode(raw);
    f.pending = {
      raw: raw.toString("base64"),
      capture: structuredClone(state),
      attempted: false,
    };
  }
  conflictToken(f = this.frame) {
    return f.conflict
      ? hash(
          Buffer.from(
            json({ plan: f.conflict, local: f.local, revision: f.revision }),
          ),
        ).toString("hex")
      : null;
  }
  dirty(f = this.frame) {
    return !same(f.baseline ?? emptyState(), f.local);
  }
}

export function canonical(state) {
  const id = "00000000-0000-0000-0000-000000000000";
  const result = decode(
    encode({ datasetId: id, commitId: id, writerId: id, parents: [], state: Object.fromEntries(Object.entries(state).filter(([k])=>k!=='sharedRecords')) }),
  ).state;
  if(state.sharedRecords!==undefined)result.sharedRecords=canonicalRecords(state.sharedRecords);
  return result;
}
const remove = (rows, key, id) => rows.filter((r) => r[key] !== id);
export function applyEdit(s, op) {
  ensure(op && typeof op === "object", "缺少编辑操作");
  const { kind, action } = op;
  ensure(["upsert", "delete", "reset"].includes(action), "未知操作");
  if (kind === "override") {
    const { sourceId, seriesId, anchorDate } = op;
    ensure(sourceId === null || typeof sourceId === "string", "来源身份无效");
    ensure(
      typeof seriesId === "string" &&
        (anchorDate === null || typeof anchorDate === "string"),
      "覆盖身份无效",
    );
    const key = overrideKey(sourceId, seriesId, anchorDate);
    s.classOverrides = remove(s.classOverrides, "key", key);
    if (action === "upsert") {
      const fields = schemas
        .find((x) => x[0] === "classOverrides")[3]
        .slice(4)
        .map(([name]) => name);
      ensure(
        op.patch && Object.keys(op.patch).every((k) => fields.includes(k)),
        "未知覆盖字段",
      );
      s.classOverrides.push({
        key,
        sourceId,
        seriesId,
        anchorDate,
        ...Object.fromEntries(fields.map((k) => [k, op.patch[k] ?? null])),
      });
    }
  } else if (kind === "source") {
    const source = s.sources.find((r) => r.id === op.id);
    ensure(source, "来源不存在");
    if (action === "upsert") {
      ensure(
        typeof op.displayName === "string" && op.displayName.trim(),
        "来源名称不能为空",
      );
      source.displayName = op.displayName.trim();
      if (Object.hasOwn(op, "termKey")) source.termKey = op.termKey;
    } else {
      s.sources = remove(s.sources, "id", op.id);
      s.imported = remove(s.imported, "sourceId", op.id);
      s.classOverrides = remove(s.classOverrides, "sourceId", op.id);
    }
  } else {
    const list = {
      entry: "entries",
      event: "events",
      date: "dayAdjustments",
      profile: "profiles",
    }[kind];
    ensure(list, "未知编辑类型");
    const key =
        kind === "date" ? "date" : kind === "profile" ? "termKey" : "id",
      value = op.value,
      id = op.id ?? value?.[key] ?? randomUUID();
    s[list] = remove(s[list], key, id);
    if (action === "upsert") {
      ensure(
        value && (value[key] === undefined || value[key] === id),
        "身份不一致",
      );
      s[list].push({ ...value, [key]: id });
    }
    if (kind === "entry" && action !== "upsert")
      s.classOverrides = s.classOverrides.filter(
        (e) => e.sourceId !== null || e.seriesId !== id,
      );
    if (kind === "profile") {
      s.customProfileKeys = s.customProfileKeys.filter((k) => k !== id);
      if (action === "upsert") s.customProfileKeys.push(id);
    }
  }
}

export async function workspaceStamp(store) {
  const s = await stat(join(store.dir, 'workspace.json'), { bigint: true });
  return [s.dev, s.ino, s.size, s.mtimeNs, s.ctimeNs].join(':');
}
export function workspaceSummary(db) {
  const f = db.frame;
  return { unknown: false, version: f.revision, datasetId: f.datasetId, dirty: db.dirty(), pending: !!f.pending, conflict: !!f.conflict, error: f.error,
    checkedAt: f.graph?.checkedAt ?? null, recovery: !!f.restoreGuard || !!f.checkpoint.deferred?.length || Object.keys(f.abandoned).length > 0 };
}
