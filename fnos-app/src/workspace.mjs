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
  uuid,
} from "./protocol.mjs";
import { emptyState, same } from "./merge.mjs";
import { json } from "./store.mjs";

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
      frame.version === 1 && Number.isSafeInteger(frame.revision),
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
      const prepare = () => {
        this.guard?.();
        const next = structuredClone(this.frame);
        ensure(
          expected === undefined || expected === next.revision,
          "请求版本过期，请重新读取",
        );
        const value = change(next);
        ensure(!(value instanceof Promise), "本地事务不得执行异步操作");
        this.mutationGuard?.(this.frame, next);
        next.local = canonical(next.local);
        next.revision++;
        return { next, value };
      };
      let committed;
      if (this.store.commitWorkspace) {
        // SQLite adapters execute preparation and persistence in ONE synchronous
        // transaction. Publish the in-memory frame only after COMMIT succeeds.
        committed = this.store.commitWorkspace(prepare, transactionContext);
        ensure(!(committed instanceof Promise), "Workspace 提交钩子不得执行异步操作");
      } else {
        committed = prepare();
        await this.store.save("workspace.json", committed.next);
      }
      this.frame = committed.next;
      await this.saveSummary();
      return committed.value;
    });
    this.queue = task.finally(() => { this.writing--; }).catch(() => {});
    return task;
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
  return decode(
    encode({ datasetId: id, commitId: id, writerId: id, parents: [], state }),
  ).state;
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
