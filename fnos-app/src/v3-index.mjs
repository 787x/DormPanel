import { mkdir, open, lstat, rename, unlink } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { join, dirname } from 'node:path';
import { Store } from './store.mjs';
import { ensure } from './protocol.mjs';
import { sha, MAX_OBJECT, hashValue } from './v3-protocol.mjs';
import { match, file } from './v3-store.mjs';

/** v2 private index: two byte shards + full SHA leaf, direct lookup, no startup enumeration.
 * Receipts are authoritative immutable path witnesses, never an assertion about remote existence.
 * No GC: pins describe recovery ownership but no object is deleted, pinned or otherwise.
 * Old PR53 flat caches remain untouched; imports must use explicit paths from a recovery journal. */
export class IndexedV3Cache {
  constructor(root, budget) { this.directory = join(root, 'sync-v3-index-v2'); this.budget = budget; this.queue = Promise.resolve(); }
  location(path) { file(path); const key = sha(Buffer.from(path, 'ascii')); return join(this.directory, key.slice(0, 2), key.slice(2, 4), key.slice(4)); }
  blob(path) { return join(this.location(path), 'object.dpv'); }
  async open() { await mkdir(this.directory, { recursive: true, mode: 0o700 }); ensure(!(await lstat(this.directory)).isSymbolicLink()); return this; }
  async witness(path) {
    const dir = this.location(path); let s;
    try { s = await lstat(join(dir, 'witness.json')); } catch (e) { if (e.code === 'ENOENT') return null; throw e; }
    ensure(s.isFile() && !s.isSymbolicLink() && s.size <= 32768); this.budget.charge(s.size, 0);
    const w = await new Store(dir).read('witness.json');
    ensure(w.version === 2 && w.path === path && Array.isArray(w.pins)); hashValue(w.hash); return w;
  }
  serial(fn) { const p = this.queue.then(fn); this.queue = p.catch(() => {}); return p; }
  put(path, bytes) {
    bytes = Buffer.from(bytes);
    return this.serial(async () => {
      const value = match(path, bytes), hash = sha(bytes), previous = await this.witness(path);
      ensure(!previous || previous.hash === hash, 'v3 identity changed'); this.budget.charge(bytes.length, 0);
      const store = new Store(this.location(path));
      // Identity is durable before content; recovery never trusts an orphan blob.
      await store.save('witness.json', { version: 2, path, hash, pins: previous?.pins ?? [] });
      const name = this.blob(path); let f, temporary;
      try {
        const old = await this.get(path);
        if (old) ensure(old.equals(bytes), 'v3 cache corrupt; preserve witness');
        else {
          temporary = join(dirname(name), '.' + randomUUID() + '.tmp');
          f = await open(temporary, 'wx', 0o600); await f.writeFile(bytes); await f.sync(); await f.close(); f = null;
          await rename(temporary, name);
        }
      } catch (e) {
        throw e;
      } finally { await f?.close(); if (temporary) await unlink(temporary).catch(() => {}); }
      if (process.platform !== 'win32') { const d = await open(dirname(name), 'r'); try { await d.sync(); } finally { await d.close(); } }
      const { state, ...metadata } = value; return { path, hash, size: bytes.length, ...metadata };
    });
  }
  async get(path) {
    const w = await this.witness(path); if (!w) return null;
    let f;
    try {
      const s = await lstat(this.blob(path)); ensure(s.isFile() && !s.isSymbolicLink() && s.size <= MAX_OBJECT); this.budget.charge(s.size, 0);
      f = await open(this.blob(path), 'r'); const bytes = Buffer.alloc(s.size + 1); let n = 0;
      while (n < bytes.length) { const r = await f.read(bytes, n, bytes.length - n, n); if (!r.bytesRead) break; n += r.bytesRead; }
      ensure(n === s.size); const raw = bytes.subarray(0, n); ensure(sha(raw) === w.hash, 'v3 cache hash'); match(path, raw); return raw;
    } catch (e) { if (e.code === 'ENOENT') return null; throw e; } finally { await f?.close(); }
  }
  async load(path, remote) { const hit = await this.get(path); if (hit) return hit; const raw = await remote.read(path); if (raw) await this.put(path, raw); return raw; }
  pin(path, owner) {
    return this.serial(async () => { ensure(typeof owner === 'string' && owner.length > 0 && owner.length <= 160); const w = await this.witness(path); ensure(w); w.pins = [...new Set([...w.pins, owner])]; ensure(w.pins.length <= 128); await new Store(this.location(path)).save('witness.json', w); });
  }
}
