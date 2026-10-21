import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { decode, ensure, hash, uuid } from './protocol.mjs';
import { loadGraph } from './graph.mjs';

export const json = v => JSON.stringify(v, (_, x) => typeof x === 'bigint' ? x.toString() : x);
export class Store {
  constructor(dir) { this.dir = dir; }
  async save(name, data) { await mkdir(this.dir, { recursive: true, mode: 0o700 }); const p = join(this.dir, name); await writeFile(p + '.tmp', json(data), { mode: 0o600 }); await rename(p + '.tmp', p); }
  async read(name) { try { return JSON.parse(await readFile(join(this.dir, name), 'utf8')); } catch (e) { if (e.code === 'ENOENT') return null; throw e; } }
  async init() { this.identity = await this.read('identity.json'); if (!this.identity) { this.identity = { writerId: randomUUID() }; await this.save('identity.json', this.identity); } uuid(this.identity.writerId); return this; }
  async restore() {
    const saved = await this.read('graph.json'); if (!saved) return null;
    for (const [id, raw] of Object.entries(saved.raw ?? {})) { const bytes = Buffer.from(raw, 'base64'); const c = decode(bytes, id); ensure(hash(bytes).toString('hex') === saved.hashes[id] && c.datasetId === saved.datasetId, '本地缓存身份异常'); }
    // 从原始内容重建闭包和前沿，不信任重复 JSON state/tips/status。
    const verified = await loadGraph({ list: async () => Object.keys(saved.raw ?? {}), read: async id => saved.raw[id] ? Buffer.from(saved.raw[id], 'base64') : null }, { datasetId: saved.datasetId, hashes: saved.hashes, deferred: saved.deferred });
    return { ...verified, checkedAt: saved.checkedAt, bindingKey: saved.bindingKey };
  }
}
