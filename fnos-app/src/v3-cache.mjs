import { open, mkdir, opendir, rename, unlink, lstat } from 'node:fs/promises';
import { join, dirname } from 'node:path';
import { randomUUID } from 'node:crypto';
import { Store } from './store.mjs';
import { ensure } from './protocol.mjs';
import { sha, hashValue, decode, MAX_OBJECT } from './v3-protocol.mjs';
import { file, match, objectPath } from './v3-store.mjs';

/** One owner/queue per dedicated app-private cache; no workspace.json/graph.raw changes.
 * Receipts are versioned atomic Store documents; blobs remain raw binary files.
 * No automatic cleanup: orphan/temp files might still be referenced by recovery journals. */
export class V3Cache {
  constructor(directory, budget, afterBlob = async () => {}) {
    ensure(directory.replaceAll('\\', '/').split('/').at(-1) === 'sync-v3-cache-v1');
    Object.assign(this, { directory, budget, afterBlob }); this.store = new Store(directory); this.expected = new Map(); this.entries = new Map(); this.queue = Promise.resolve(); this.ready = false;
  }
  receipt(path) { return sha(Buffer.from(path, 'ascii')) + '.json'; }
  entry(path, hash, size, value) {
    // Do not retain every decoded business snapshot on X08E/low-memory cache owners.
    const { state, ...metadata } = value; return { path, hash, size, ...metadata };
  }
  async read(name, limit) {
    const path = join(this.directory, name), stat = await lstat(path); ensure(stat.isFile() && !stat.isSymbolicLink() && stat.size <= limit);
    this.budget.charge(stat.size, 0); const f = await open(path, 'r');
    try {
      const b = Buffer.alloc(stat.size + 1); let n = 0;
      while (n < b.length) { const r = await f.read(b, n, b.length - n, n); if (!r.bytesRead) break; n += r.bytesRead; }
      ensure(n === stat.size); return b.subarray(0, n);
    } finally { await f.close(); }
  }
  async recover() {
    await this.queue; this.ready = false;
    await mkdir(this.directory, { recursive: true, mode: 0o700 }); ensure(!(await lstat(this.directory)).isSymbolicLink());
    if (process.platform !== 'win32') { const d = await open(dirname(this.directory), 'r'); try { await d.sync(); } finally { await d.close(); } }
    this.entries.clear(); this.expected.clear(); const names = [], listing = await opendir(this.directory);
    for await (const row of listing) { this.budget.charge(0, 0); ensure(names.length < 4096); names.push(row.name); }
    for (const name of names.filter(n => n.endsWith('.json'))) {
      const r = JSON.parse((await this.read(name, 512)).toString('utf8')); ensure(r.version === 1); file(r.path); hashValue(r.hash);
      ensure(this.receipt(r.path) === name); this.expected.set(r.path, r.hash);
    }
    for (const name of names.filter(n => n.endsWith('.dpv'))) {
      const raw = await this.read(name, MAX_OBJECT), hash = sha(raw); ensure(name === hash + '.dpv');
      const value = decode(raw), path = objectPath(value); ensure(!this.expected.has(path) || this.expected.get(path) === hash, 'v3 identity changed');
      if (!this.expected.has(path)) await this.store.save(this.receipt(path), { version: 1, path, hash });
      this.expected.set(path, hash); this.entries.set(path, this.entry(path, hash, raw.length, value));
    }
    this.ready = true; return this.index();
  }
  put(path, raw) {
    // Capture bytes before queuing, so caller mutation cannot alter the persisted intent.
    raw = Buffer.from(raw);
    const task = this.queue.then(async () => {
      ensure(this.ready, 'recover v3 cache before use'); this.budget.charge(raw.length, 0); const value = match(path, raw), hash = sha(raw);
      ensure(!this.expected.has(path) || this.expected.get(path) === hash, 'v3 identity changed');
      await this.store.save(this.receipt(path), { version: 1, path, hash }); this.expected.set(path, hash);
      let exists = false;
      try { const previous = await this.read(hash + '.dpv', MAX_OBJECT); ensure(previous.equals(raw)); exists = true; } catch (e) { if (e.code !== 'ENOENT') throw e; }
      if (!exists) {
        const temp = join(this.directory, '.' + randomUUID() + '.tmp'), f = await open(temp, 'wx', 0o600);
        try { await f.writeFile(raw); await f.sync(); } finally { await f.close(); }
        try {
          await rename(temp, join(this.directory, hash + '.dpv'));
          if (process.platform !== 'win32') { const d = await open(this.directory, 'r'); try { await d.sync(); } finally { await d.close(); } }
        } finally { await unlink(temp).catch(() => {}); }
      }
      await this.afterBlob(); const entry = this.entry(path, hash, raw.length, value); this.entries.set(path, entry); return structuredClone(entry);
    });
    this.queue = task.catch(() => {}); return task;
  }
  async get(path) {
    ensure(this.ready); file(path); const hash = this.expected.get(path); if (!hash) return null;
    try { const raw = await this.read(hash + '.dpv', MAX_OBJECT); ensure(sha(raw) === hash); match(path, raw); return raw; }
    catch (e) { if (e.code === 'ENOENT') return null; throw e; }
  }
  async load(path, store) { const cached = await this.get(path); if (cached) return cached; const raw = await store.read(path); if (raw) await this.put(path, raw); return raw; }
  index() { ensure(this.ready); return structuredClone([...this.entries.values()]); }
}

export class Observation {
  constructor(endpoint, round) { Object.assign(this, { endpoint, round }); this.observed = new Set(); this.rechecked = new Set(); this.closed = new Set(); }
  observe(path) { file(path); this.observed.add(path); }
  recheck(path, raw, cachedHash) { match(path, raw); ensure(sha(raw) === cachedHash); this.observe(path); this.rechecked.add(path); }
  recordVerifiedClosure(paths) { paths.forEach(file); paths.forEach(p => this.closed.add(p)); }
  evidence(path, localContentVerified) { return { localContentVerified, observedThisRound: this.observed.has(path), bytesRecheckedThisRound: this.rechecked.has(path), closureVerifiedThisRound: this.closed.has(path) }; }
}
