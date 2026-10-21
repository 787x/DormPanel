import { decode, ensure, hash, uuid } from "./protocol.mjs";
import { same } from "./merge.mjs";

// 验证闭包与身份；只有完整图可进入三方合并。
export async function loadGraph(
  remote,
  previous = {},
  checkpoint = async () => {},
) {
  const listed = await remote.list();
  ensure(
    listed.length <= 256 && new Set(listed).size === listed.length,
    "列举数量或重复身份异常",
  );
  const known = previous.hashes ?? {},
    pending = new Set(previous.deferred ?? []);
  const discovery = [
    ...new Set([
      ...listed,
      ...Object.keys(known),
      ...pending,
      ...(previous.observed ?? []),
    ]),
  ].sort();
  ensure(discovery.length <= 256, "已观察历史数量超限");
  discovery.forEach(uuid);
  // 先持久化本轮所有观察路径，即使进程中断，下轮也必须回查。
  const persist = (deferred, metadata = {}) =>
    checkpoint(deferred, { ...metadata, observed: discovery });
  await persist([...pending], {
    datasetId: previous.datasetId ?? null,
    hashes: known,
  });
  const commits = new Map(),
    visiting = new Set(),
    failed = new Set(),
    raw = {},
    hashes = { ...known };
  let bytes = 0,
    datasetId = previous.datasetId ?? null;
  async function visit(id, required) {
    uuid(id);
    ensure(!visiting.has(id), "历史有环");
    if (commits.has(id)) return;
    if (failed.has(id)) {
      ensure(!required, "已引用祖先不可验证");
      return;
    }
    ensure(commits.size + visiting.size + failed.size < 256, "历史遍历超限");
    visiting.add(id);
    if (!known[id]) pending.add(id);
    ensure(pending.size <= 64, "暂不可验证路径超过 64");
    await persist([...pending], { datasetId, hashes });
    let b;
    try {
      b = await remote.read(id);
    } catch (e) {
      if (required || known[id] || !e.recoverable) throw e;
      failed.add(id);
      visiting.delete(id);
      return;
    }
    if (!b) {
      ensure(!required && !known[id], "已知提交或祖先缺失");
      failed.add(id);
      visiting.delete(id);
      return;
    }
    bytes += b.length;
    ensure(bytes <= 32 * 1024 * 1024, "历史字节数超限");
    const digest = hash(b).toString("hex");
    ensure(!known[id] || known[id] === digest, "已验证提交内容改变");
    let c;
    try {
      c = decode(b, id);
    } catch (e) {
      const intact =
        b.length >= 200 && hash(b.subarray(0, -32)).equals(b.subarray(-32));
      const unknown =
        b.length >= 8 &&
        b.subarray(0, 3).toString() === "DPC" &&
        b.readInt32BE(4) !== 2;
      if (required || known[id] || intact || unknown) throw e;
      failed.add(id);
      visiting.delete(id);
      return;
    }
    if (datasetId === null) datasetId = c.datasetId;
    ensure(c.datasetId === datasetId, "无关 dataset");
    // 内容身份已完整验证；祖先闭包仍在下方验证，成功前绝不展示 state。
    hashes[id] = digest;
    pending.delete(id);
    await persist([...pending], { datasetId, hashes });
    for (const p of c.parents) await visit(p, true);
    visiting.delete(id);
    commits.set(id, c);
    raw[id] = b.toString("base64");
    await persist([...pending], { datasetId, hashes });
  }
  for (const id of discovery) await visit(id, !!known[id]);
  ensure(
    [...commits.values()].filter((c) => c.parents.length === 0).length <= 1,
    "多个无关根",
  );
  const parents = new Set([...commits.values()].flatMap((c) => c.parents));
  const tips = [...commits.keys()].filter((id) => !parents.has(id)).sort();
  ensure(tips.length <= 64, "tips 超限");
  ensure(
    tips.reduce((n, id) => n + Buffer.from(raw[id], "base64").length, 0) *
      tips.length <=
      64 * 1024 * 1024,
    "分支工作预算超限",
  );
  const equivalent = tips.every((id) =>
    same(commits.get(id).state, commits.get(tips[0]).state),
  );
  let baseline = null;
  function ancestors(id, found = new Set()) {
    if (!found.has(id)) {
      found.add(id);
      commits.get(id).parents.forEach((p) => ancestors(p, found));
    }
    return found;
  }
  if (tips.length > 1 && !equivalent) {
    const sets = tips.map((id) => ancestors(id));
    const common = [...sets[0]].filter((id) => sets.every((s) => s.has(id)));
    const maximal = common.filter(
      (c) => !common.some((other) => c !== other && ancestors(other).has(c)),
    );
    ensure(maximal.length > 0, "无法证明共同基线");
    baseline = maximal.sort();
    ensure(
      baseline.every((id) =>
        same(commits.get(id).state, commits.get(baseline[0]).state),
      ),
      "多重基线歧义",
    );
  }
  ensure(pending.size <= 64, "暂不可验证路径超过 64");
  return {
    datasetId,
    hashes,
    raw,
    tips,
    baseline,
    deferred: [...pending].sort(),
    checkedAt: new Date().toISOString(),
    status: !tips.length
      ? pending.size
        ? "incomplete"
        : "empty"
      : !equivalent
        ? "branches"
        : pending.size
          ? "incomplete"
          : "verified",
    state: equivalent && tips.length ? commits.get(tips[0]).state : null,
  };
}

export function graphPlan(graph, baseline, frontier) {
  const commits = new Map(
    Object.entries(graph.raw).map(([id, raw]) => [
      id,
      decode(Buffer.from(raw, "base64"), id),
    ]),
  );
  const ancestors = (id, found = new Set()) => {
    ensure(commits.has(id), "已确认历史缺失");
    if (!found.has(id)) {
      found.add(id);
      commits.get(id).parents.forEach((p) => ancestors(p, found));
    }
    return found;
  };
  const common = (ids) => {
    const sets = ids.map((id) => ancestors(id)),
      shared = [...sets[0]].filter((id) => sets.every((s) => s.has(id)));
    const maximal = shared
      .filter(
        (id) =>
          !shared.some((other) => id !== other && ancestors(other).has(id)),
      )
      .sort();
    ensure(
      maximal.length &&
        maximal.every((id) =>
          same(commits.get(id).state, commits.get(maximal[0]).state),
        ),
      "共同基线缺失或歧义",
    );
    return commits.get(maximal[0]).state;
  };
  ensure(
    graph.tips.length > 0 &&
      frontier.every((old) => graph.tips.some((id) => ancestors(id).has(old))),
    "目录未观察到已确认历史",
  );
  const branches = graph.tips.map((id) => commits.get(id)),
    equivalent = branches.every((c) => same(c.state, branches[0].state));
  const base = equivalent ? branches[0].state : common(graph.tips);
  const localBase = !baseline
    ? base
    : (equivalent && same(baseline, base)) ||
        (frontier.length === graph.tips.length &&
          frontier.every((id) => graph.tips.includes(id))) ||
        frontier.every((old) =>
          graph.tips.every((id) => ancestors(id).has(old)),
        )
      ? baseline
      : common([...new Set([...graph.tips, ...frontier])]);
  return { base, localBase, branches };
}
