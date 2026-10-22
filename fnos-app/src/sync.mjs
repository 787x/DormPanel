import { randomUUID } from "node:crypto";
import { decode, ensure, hash } from "./protocol.mjs";
import { loadGraph, graphPlan } from "./graph.mjs";
import { same, merge, mergePlan } from "./merge.mjs";
import { json } from "./store.mjs";

export async function verifyEndpointMove(db, remote) {
  const f = db.snapshot();
  ensure(f.datasetId && f.baseline, "尚未绑定数据集，不能迁移地址");
  const graph = await loadGraph(remote, {
    ...f.graph,
    ...f.checkpoint,
    datasetId: f.datasetId,
    hashes: { ...f.graph?.hashes, ...f.checkpoint.hashes },
    deferred: [
      ...new Set([
        ...(f.checkpoint.deferred ?? []),
        ...Object.keys(f.abandoned),
      ]),
    ],
  });
  ensure(
    graph.datasetId === f.datasetId && !graph.deferred.length,
    "新地址数据集不同或历史不完整",
  );
  graphPlan(graph, f.baseline, f.frontier);
  for (const [id, expected] of Object.entries(f.abandoned))
    ensure(graph.hashes[id] === expected, "新地址缺少中断路径或内容不符");
  if (f.pending?.attempted) {
    const bytes = Buffer.from(f.pending.raw, 'base64'), candidate = decode(bytes);
    ensure(graph.raw[candidate.commitId] === bytes.toString('base64'), '新地址缺少已尝试发布的候选或内容不符');
  }
  return graph;
}

export class SyncEngine {
  constructor(workspace, remote, validateDataset = () => {}) {
    this.db = workspace;
    this.remote = remote;
    this.validateDataset = validateDataset;
  }
  async publish() {
    this.db.assertWritable?.();
    const { db, remote } = this,
      pending = db.frame.pending;
    if (!pending) return false;
    const bytes = Buffer.from(pending.raw, "base64"),
      c = decode(bytes),
      existing = await remote.read(c.commitId);
    if (existing === null && !pending.attempted) {
      ensure((await remote.list()).length < 256, "目录已达容量上限");
      await db.tx((f) => {
        ensure(
          f.pending?.raw === pending.raw && !f.pending.attempted,
          "候选已变化",
        );
        f.pending.attempted = true;
      });
      await remote.put(c.commitId, bytes);
      const check = await remote.read(c.commitId);
      ensure(check && check.equals(bytes), "发布回读未确认；保留待发布状态");
      decode(check, c.commitId);
    } else if (existing?.equals(bytes)) decode(existing, c.commitId);
    else {
      // 不对已尝试或已占用的 UUID 再 PUT；原预期哈希一直保留以发现恢复后的篡改。
      await db.tx((f) => {
        ensure(f.pending?.raw === pending.raw, "候选已变化");
        ensure(Object.keys(f.abandoned).length < 64, "待确认路径过多");
        f.abandoned[c.commitId] = hash(bytes).toString("hex");
        f.pending = null;
        db.stage(f, c.state, c.parents);
      });
      return false;
    }
    await db.tx((f) => {
      ensure(f.pending?.raw === pending.raw, "候选已变化");
      // capture 是上传时精确本地视图，期间的编辑按三方重放，绝不标记成已上传。
      const replay = merge(pending.capture, f.local, c.state);
      ensure(!replay.conflicts.length, "回执重放冲突");
      f.local = replay.state;
      f.baseline = c.state;
      f.frontier = [c.commitId];
      f.pending = null;
      f.receipt = { commitId: c.commitId, sha256: hash(bytes).toString("hex") };
      f.checkpoint = {
        ...f.checkpoint,
        datasetId: c.datasetId,
        hashes: {
          ...f.checkpoint.hashes,
          [c.commitId]: hash(bytes).toString("hex"),
        },
      };
    });
    return true;
  }
  async observe({ expectedRevision } = {}) {
    const { db, remote } = this,
      f = db.snapshot();
    ensure(
      !f.bindingKey || f.bindingKey === remote.key,
      "绑定地址变更，拒绝静默重新绑定",
    );
    const previous = {
      ...f.graph,
      ...f.checkpoint,
      datasetId: f.datasetId ?? f.checkpoint.datasetId,
      hashes: { ...f.graph?.hashes, ...f.checkpoint.hashes },
      deferred: [
        ...new Set([
          ...(f.graph?.deferred ?? []),
          ...(f.checkpoint.deferred ?? []),
        ]),
      ],
    };
    // 已中断路径可暂缓，但恢复为不同有效内容必须停止。
    const observed = {
      list: () => remote.list(),
      read: async (id) => {
        const bytes = await remote.read(id);
        if (bytes && f.abandoned[id]) {
          let valid = false;
          try {
            decode(bytes, id);
            valid = true;
          } catch {
            /* loadGraph 仍检查有效封装的非法语义 */
          }
          if (valid)
            ensure(
              hash(bytes).toString("hex") === f.abandoned[id],
              "中断路径恢复为不同内容",
            );
        }
        return bytes;
      },
    };
    previous.deferred = [
      ...new Set([...previous.deferred, ...Object.keys(f.abandoned)]),
    ];
    const graph = await loadGraph(
      observed,
      previous,
      async (deferred, verified) => {
        if (verified.datasetId) this.validateDataset(verified.datasetId);
        let stale = false;
        await db.tx((next) => {
          stale = expectedRevision !== undefined && next.revision !== expectedRevision;
          next.bindingKey = remote.key;
          next.checkpoint = {
            ...next.checkpoint,
            ...verified,
            bindingKey: remote.key,
            deferred,
          };
        });
        if (expectedRevision !== undefined) expectedRevision++;
        // 即使调用方输入已过期，也先保留观察证据；不能因拒绝操作而遗忘新路径。
        ensure(!stale, '观察期间工作区版本变化，请刷新');
      },
    );
    if (graph.datasetId) this.validateDataset(graph.datasetId);
    graph.bindingKey = remote.key;
    await db.tx((next) => {
      next.graph = graph;
      next.checkpoint = {
        datasetId: graph.datasetId,
        hashes: graph.hashes,
        deferred: graph.deferred,
        bindingKey: remote.key,
      };
      next.bindingKey = remote.key;
    }, expectedRevision);
    return graph;
  }
  async synchronize({ create = false, bind = false } = {}) {
    this.db.assertWritable?.();
    const { db, remote } = this;
    ensure(
      !db.frame.bindingKey || db.frame.bindingKey === remote.key,
      "绑定地址不一致",
    );
    for (let round = 0; round < 3; round++) {
      if (db.frame.pending) {
        if (!(await this.publish())) continue;
      }
      const graph = await this.observe();
      if (!graph.tips.length) {
        ensure(
          !graph.deferred.length && !db.frame.baseline,
          "远端历史未完整，不能创建根",
        );
        if (!create) return;
        if (remote.ensureDirectory) await remote.ensureDirectory();
        await db.tx((f) => {
          f.datasetId = randomUUID();
          db.stage(f, f.local, []);
        });
        continue;
      }
      if (!db.frame.baseline)
        ensure(
          bind && !db.dirty(),
          "首次绑定须确认且本地无修改；先导出或创建独立数据集",
        );
      await db.tx((f) => {
        ensure(
          !f.datasetId || f.datasetId === graph.datasetId,
          "数据集身份不一致",
        );
        const plan = graphPlan(graph, f.baseline, f.frontier);
        if (!f.baseline) {
          f.local = plan.localBase;
          f.baseline = plan.localBase;
        }
        const result = mergePlan(plan, f.local);
        f.datasetId = graph.datasetId;
        if (result.conflicts.length) {
          f.conflict = plan;
          return;
        }
        f.local = result.state;
        f.baseline = result.remote;
        f.frontier = graph.tips;
        f.conflict = null;
        if (
          !graph.tips.every((id) =>
            same(decode(Buffer.from(graph.raw[id], "base64")).state, f.local),
          )
        ) {
          ensure(Object.keys(graph.hashes).length < 256, "历史容量已满");
          db.stage(f, f.local, graph.tips);
        }
      });
      if (db.frame.conflict || !db.frame.pending) return;
    }
  }
  async resolve(choice, token, expected) {
    this.db.assertWritable?.();
    ensure(["local", "remote"].includes(choice), "未知冲突决策");
    const before = this.db.snapshot();
    ensure(
      expected === before.revision && token === this.db.conflictToken(before),
      "冲突输入已变化",
    );
    // 决策前必须重新观察；新分支必须重新展示，离线不能作远端决策。
    const graph = await this.observe();
    ensure(
      JSON.stringify(graph.tips) === JSON.stringify(before.graph.tips),
      "远端版本已变化，请重新同步和查看冲突",
    );
    await this.db.tx((f) => {
      ensure(
        token === this.db.conflictToken(f) ||
          (json(f.local) === json(before.local) &&
            json(f.conflict) === json(before.conflict)),
        "冲突输入已变化",
      );
      ensure(!f.pending && f.conflict, "冲突已失效");
      const result = mergePlan(f.conflict, f.local, choice);
      f.local = result.state;
      f.baseline = result.remote;
      f.frontier = graph.tips;
      f.conflict = null;
      this.db.stage(f, f.local, graph.tips);
    });
  }
}

// 恢复先做完整只读验证；候选是否出现仍由原 publish 恢复语义决定。
export async function verifyRestoredHistory(db, remote) {
  const f = db.frame;
  const graph = await loadGraph(remote, { ...f.checkpoint, datasetId: f.datasetId ?? f.checkpoint.datasetId,
    hashes: { ...f.graph?.hashes, ...f.checkpoint.hashes }, deferred: [...new Set([...(f.checkpoint.deferred ?? []), ...Object.keys(f.abandoned)])] });
  if (f.datasetId) { ensure(graph.datasetId === f.datasetId, '恢复后的数据集身份不匹配'); graphPlan(graph, f.baseline, f.frontier); }
  for (const [id, expected] of Object.entries(f.abandoned)) if (graph.hashes[id]) ensure(graph.hashes[id] === expected, '恢复后的中断历史变化');
  return graph;
}
