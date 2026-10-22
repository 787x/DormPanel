import { mkdir, readFile, rename, open, unlink, realpath } from "node:fs/promises";
import { join, dirname, resolve } from "node:path";
import { randomUUID } from "node:crypto";
import { decode, ensure, hash, uuid } from "./protocol.mjs";
import { loadGraph } from "./graph.mjs";

export const json = (v) =>
  JSON.stringify(v, (_, x) => (typeof x === "bigint" ? x.toString() : x));
export class Store {
  constructor(dir) {
    this.dir = dir;
  }
  async save(name, data) {
    const created = await mkdir(this.dir, { recursive: true, mode: 0o700 });
    const p = join(this.dir, name),
      tmp = p + "." + randomUUID() + ".tmp";
    const f = await open(tmp, "wx", 0o600);
    try {
      await f.writeFile(json(data));
      await f.sync();
    } finally {
      await f.close();
    }
    try {
      await rename(tmp, p);
      // Linux 上 fsync 目录保证 rename 的崩溃持久性；Windows 不支持目录句柄。
      if (process.platform !== "win32") {
        const dir = await open(this.dir, "r");
        try {
          await dir.sync();
        } finally {
          await dir.close();
        }
        // 新工作区目录的父项也必须持久化，才能随后发布引用它的 catalog。
        if (created) {
          const stop = dirname(resolve(created));
          let parent = dirname(resolve(this.dir));
          for (;;) {
            const handle = await open(parent, 'r');
            try { await handle.sync(); } finally { await handle.close(); }
            if (parent === stop) break;
            const next = dirname(parent);
            if (next === parent) break;
            parent = next;
          }
        }
      }
    } finally {
      await unlink(tmp).catch(() => {});
    }
  }
  async read(name) {
    try {
      return JSON.parse(await readFile(join(this.dir, name), "utf8"));
    } catch (e) {
      if (e.code === "ENOENT") return null;
      throw e;
    }
  }
  async init() {
    await mkdir(this.dir, { recursive: true, mode: 0o700 });
    this.dir = await realpath(this.dir);
    this.identity = await this.read("identity.json");
    if (!this.identity) {
      this.identity = { writerId: randomUUID() };
      await this.save("identity.json", this.identity);
    }
    uuid(this.identity.writerId);
    if (!this.identity.installationId) {
      this.identity.installationId = randomUUID();
      await this.save("identity.json", this.identity);
    }
    uuid(this.identity.installationId);
    return this;
  }
  async restore() {
    const saved = await this.read("graph.json");
    if (!saved) return null;
    for (const [id, raw] of Object.entries(saved.raw ?? {})) {
      const bytes = Buffer.from(raw, "base64");
      const c = decode(bytes, id);
      ensure(
        hash(bytes).toString("hex") === saved.hashes[id] &&
          c.datasetId === saved.datasetId,
        "本地缓存身份异常",
      );
    }
    // 从原始内容重建闭包和前沿，不信任重复 JSON state/tips/status。
    const verified = await loadGraph(
      {
        list: async () => Object.keys(saved.raw ?? {}),
        read: async (id) =>
          saved.raw[id] ? Buffer.from(saved.raw[id], "base64") : null,
      },
      {
        datasetId: saved.datasetId,
        hashes: saved.hashes,
        deferred: saved.deferred,
      },
    );
    return {
      ...verified,
      checkedAt: saved.checkedAt,
      bindingKey: saved.bindingKey,
    };
  }
}
