import { Store, json } from './store.mjs';
import { ensure } from './protocol.mjs';
import { match } from './v3-store.mjs';
import { sha } from './v3-protocol.mjs';
import { lstat } from 'node:fs/promises';
import { join } from 'node:path';

/** Dedicated isolated engine state, one owner guarded by the existing Workspace writer.
 * Every transaction replaces the complete local/baseline/pending receipt atomically.
 * Network I/O always occurs outside the storage transaction. */
export class V3Journal {
  constructor(directory) { this.store = new Store(directory); this.queue = Promise.resolve(); this.networkQueue = Promise.resolve(); }
  async open() {
    try { const s = await lstat(join(this.store.dir, 'v3-engine-v1.json')); ensure(s.isFile() && !s.isSymbolicLink() && s.size <= 48 * 1024 * 1024, 'v3 journal size/type'); } catch (e) { if (e.code !== 'ENOENT') throw e; }
    this.value = await this.store.read('v3-engine-v1.json') ?? { version: 1, pending: null }; ensure(this.value.version === 1); this.failed = false; return this;
  }
  transaction(fn) {
    const p = this.queue.then(async () => {
      ensure(!this.failed, 'v3 journal requires reopen after persistence failure'); const next = structuredClone(this.value); await fn(next);
      ensure(Buffer.byteLength(json(next)) <= 48 * 1024 * 1024, 'v3 journal budget');
      try { await this.store.save('v3-engine-v1.json', next); } catch (e) { this.failed = true; throw e; }
      this.value = next; return structuredClone(next);
    }); this.queue = p.catch(() => {}); return p;
  }
}
export class V3Publisher {
  constructor(journal, remote) { Object.assign(this, { journal, remote }); }
  async stage(path, bytes, capture) {
    bytes = Buffer.from(bytes); match(path, bytes);
    await this.journal.transaction(s => {
      ensure(!s.pending || s.pending.phase === 'RECEIPTED', 'v3 pending publication');
      s.pending = { path, hash: sha(bytes), bytes: bytes.toString('base64'), capture, phase: 'STAGED', attempts: 0 };
    });
  }
  async resume() {
    const p = this.journal.value.pending; ensure(p); const raw = Buffer.from(p.bytes, 'base64');
    ensure(sha(raw) === p.hash); match(p.path, raw);
    if (['READBACK_CONFIRMED', 'RECEIPTED'].includes(p.phase)) return p.phase;
    const old = await this.remote.read(p.path);
    if (old) {
      ensure(old.equals(raw), 'v3 identity anomaly; pending retained');
      await this.journal.transaction(s => { s.pending.phase = 'READBACK_CONFIRMED'; }); return 'READBACK_CONFIRMED';
    }
    if (p.phase !== 'STAGED') {
      await this.journal.transaction(s => { s.pending.phase = 'UNCERTAIN'; }); return 'UNCERTAIN';
    }
    // This awaited durable replacement is the write boundary, not an arbitrary callback.
    await this.journal.transaction(s => { s.pending.phase = 'ATTEMPTED'; s.pending.attempts++; });
    try {
      await this.remote.putOnce(p.path, raw);
      const confirmed = await this.remote.read(p.path); ensure(confirmed?.equals(raw), 'v3 uncertain readback');
      await this.journal.transaction(s => { s.pending.phase = 'READBACK_CONFIRMED'; }); return 'READBACK_CONFIRMED';
    } catch (e) {
      await this.journal.transaction(s => { s.pending.phase = 'UNCERTAIN'; s.pending.error = String(e.message).slice(0, 1024); }); throw e;
    }
  }
  async receipt(apply) {
    await this.journal.transaction(s => { ensure(s.pending.phase === 'READBACK_CONFIRMED'); apply(s, structuredClone(s.pending.capture)); s.pending.phase = 'RECEIPTED'; });
  }
}
