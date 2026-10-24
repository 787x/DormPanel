import { json } from './store.mjs';
import { V3Metadata } from './v3-metadata.mjs';
import { ensure } from './protocol.mjs';
import { match } from './v3-store.mjs';
import { Budget } from './v3-store.mjs';
import { sha } from './v3-protocol.mjs';

/** Dedicated isolated engine state, one owner guarded by the existing Workspace writer.
 * Every transaction replaces the complete local/baseline/pending receipt atomically.
 * Network I/O always occurs outside the storage transaction. */
export class V3Journal {
  constructor(directory) { this.directory = directory; this.queue = Promise.resolve(); this.networkQueue = Promise.resolve(); }
  async open() {
    this.meta = new V3Metadata(this.directory);
    try { this.value = this.meta.get('state', 'frame') ?? { version: 2, pending: null }; ensure(this.value.version === 2); return this; }
    catch (e) { this.meta.close(); throw e; }
  }
  transaction(fn, local = false) {
    const p = this.queue.then(async () => {
      const next = structuredClone(this.value);
      const savedBudget = this.meta.budget; if (local) this.meta.budget = new Budget({ requests: 0 });
      try { this.meta.atomic(() => { const value = fn(next); ensure(!(value instanceof Promise), 'v3 transaction must be synchronous');
        ensure(!next.history && !next.discovery, 'v3 cumulative metadata must use paged index');
        this.meta.put('state', 'frame', next); });
      } finally { if (local) this.meta.budget = savedBudget; }
      this.value = next; return structuredClone(next);
    }); this.queue = p.catch(() => {}); return p;
  }
  async close() { await this.queue; this.meta.close(); }
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
    this.journal.meta.assertWritable();
    const p = this.journal.value.pending; ensure(p); const raw = Buffer.from(p.bytes, 'base64');
    ensure(sha(raw) === p.hash); match(p.path, raw);
    if (['READBACK_CONFIRMED', 'RECEIPTED'].includes(p.phase)) return p.phase;
    const old = await this.remote.read(p.path);
    if (old) {
      ensure(old.equals(raw), 'v3 identity anomaly; pending retained');
      await this.journal.transaction(s => { s.pending.phase = 'READBACK_CONFIRMED'; s.pending.confirmation = { round: this.journal.roundId ?? null, endpoint: this.remote.base?.href ?? null }; }); return 'READBACK_CONFIRMED';
    }
    if (p.phase !== 'STAGED') {
      await this.journal.transaction(s => { s.pending.phase = 'UNCERTAIN'; }); return 'UNCERTAIN';
    }
    this.remote.checkPutBudget?.(raw.length);
    // This awaited durable replacement is the write boundary, not an arbitrary callback.
    await this.journal.transaction(s => { s.pending.phase = 'ATTEMPTED'; s.pending.attempts++; });
    try {
      await this.remote.putOnce(p.path, raw);
      const confirmed = await this.remote.read(p.path); ensure(confirmed?.equals(raw), 'v3 uncertain readback');
      await this.journal.transaction(s => { s.pending.phase = 'READBACK_CONFIRMED'; s.pending.confirmation = { round: this.journal.roundId ?? null, endpoint: this.remote.base?.href ?? null }; }); return 'READBACK_CONFIRMED';
    } catch (e) {
      await this.journal.transaction(s => { s.pending.phase = 'UNCERTAIN'; s.pending.error = String(e.message).slice(0, 1024); }); throw e;
    }
  }
  async receipt(apply) {
    this.journal.meta.assertWritable();
    await this.journal.prepareReceipt?.(structuredClone(this.journal.value.pending));
    await this.journal.transaction(s => { ensure(s.pending.phase === 'READBACK_CONFIRMED');
      if (this.journal.prepareReceipt) ensure(this.journal.meta.get('node', s.pending.path)?.hash === s.pending.hash, 'v3 receipt requires durable ancestor index');
      apply(s, structuredClone(s.pending.capture)); s.pending.phase = 'RECEIPTED'; });
  }
}
