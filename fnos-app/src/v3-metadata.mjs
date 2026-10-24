import { DatabaseSync } from 'node:sqlite';
import { mkdirSync, existsSync, lstatSync, openSync, fsyncSync, closeSync } from 'node:fs';
import { join, dirname, resolve } from 'node:path';
import { ensure } from './protocol.mjs';
import { sha, encode, decode } from './v3-protocol.mjs';
import { json } from './store.mjs';

/** Paged B-trees; no table scan on open. Owner lock is a separate SQLite byte-range
 * lock, also covered by Kotlin's whole-file FileChannel lock on the same inode.
 * SIGKILL releases the OS lock. Never unlink/replace a lock file. */
export class V3Metadata {
  constructor(directory) {
    ensure(!resolve(directory).split(/[\\/]/).some(part => ['dormpanel-sync-v2', 'dormpanel-sync-v3'].includes(part)), 'v3 metadata must remain outside protocol directories');
    const created = mkdirSync(directory, { recursive: true, mode: 0o700 }); ensure(!lstatSync(directory).isSymbolicLink());
    for (const name of ['workspace.json', 'catalog.json', 'graph.json', 'identity.json', 'schedule.db']) ensure(!existsSync(join(directory, name)), 'v3 private store refuses existing production/v2 root');
    ensure(!existsSync(join(directory, 'v3-engine-v1.json')), 'INCOMPATIBLE_V3_DRAFT_STATE: preserve old isolated journal; choose a new private test directory');
    for (const name of ['v3-owner.lock', 'v3-state-v2.sqlite']) if (existsSync(join(directory, name))) ensure(!lstatSync(join(directory, name)).isSymbolicLink());
    this.owner = new DatabaseSync(join(directory, 'v3-owner.lock'));
    try { this.owner.exec('BEGIN EXCLUSIVE'); } catch { this.owner.close(); throw Error('v3 writer already locked'); }
    try {
      // Persist directory creation only after ownership. SQLite subsequently fsyncs its DB/WAL.
      if (process.platform !== 'win32') {
        const stop = created ? dirname(resolve(created)) : resolve(directory); let current = resolve(directory);
        for (;;) { const fd = openSync(current, 'r'); try { fsyncSync(fd); } finally { closeSync(fd); } if (current === stop) break; current = dirname(current); }
      }
      this.db = new DatabaseSync(join(directory, 'v3-state-v2.sqlite'));
      this.db.exec(`PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL; PRAGMA cache_size=-2048; PRAGMA mmap_size=0;
        PRAGMA wal_autocheckpoint=64; PRAGMA journal_size_limit=1048576;
        CREATE TABLE IF NOT EXISTS v3_records(seq INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, ns TEXT NOT NULL, k TEXT NOT NULL, v TEXT NOT NULL, sum TEXT NOT NULL);
        CREATE UNIQUE INDEX IF NOT EXISTS index_v3_records_ns_k ON v3_records(ns,k);
        CREATE INDEX IF NOT EXISTS index_v3_records_ns_seq ON v3_records(ns,seq);`);
      this.statements = new Map(); this.metrics = { reads: 0, writes: 0, bytes: 0, nodeReads: 0 }; this.depth = 0;
      this.verifiedSnapshots = new Set();
      const version = this.get('format', 'version'); ensure(!version || version.value === 2, 'v3 unsupported private metadata version');
      if (!version) this.atomic(() => this.put('format', 'version', { value: 2 }));
    } catch (e) { try { this.db?.close(); } finally { try { this.owner.exec('ROLLBACK'); } finally { this.owner.close(); } } throw e; }
  }
  prepare(sql) { let statement = this.statements.get(sql); if (!statement) { statement = this.db.prepare(sql); this.statements.set(sql, statement); } return statement; }
  assertOpen() { ensure(!this.closed && !this.poisoned, 'v3 storage requires reopen'); }
  assertWritable() { this.assertOpen(); this.guard?.(); }
  decode(row, ns) {
    if (!row) return null; this.metrics.reads++; this.metrics.bytes += Buffer.byteLength(row.v);
    if (ns === 'node') this.metrics.nodeReads++;
    this.budget?.charge(Buffer.byteLength(row.v), 0); ensure(sha(Buffer.from(ns + '\0' + row.k + '\0' + row.v)) === row.sum, 'v3 metadata checksum corrupt');
    const value = JSON.parse(row.v); return ns === 'snapshot' ? value : this.unpack(value, new Map());
  }
  remember(hash) { if (this.verifiedSnapshots.size >= 128) this.verifiedSnapshots.delete(this.verifiedSnapshots.values().next().value); this.verifiedSnapshots.add(hash); }
  snapshot(bytes, kind) {
    const hash = sha(bytes);
    const exists = !!this.prepare("SELECT 1 AS found FROM v3_records WHERE ns='snapshot' AND k=?").get(hash);
    if (!exists) this.write('snapshot', hash, json({ bytes: bytes.toString('base64') }));
    else if (!this.verifiedSnapshots.has(hash)) { const row = this.get('snapshot', hash); ensure(sha(Buffer.from(row.bytes, 'base64')) === hash, 'v3 local snapshot hash'); }
    this.remember(hash); return { $v3Snapshot: hash, kind };
  }
  pack(value) {
    if (value && typeof value === 'object' && !Array.isArray(value) && ['events','entries','sources','imported','dayAdjustments','classOverrides','profiles','customProfileKeys'].every(k => Array.isArray(value[k]))) {
      const id = '00000000-0000-0000-0000-000000000001';
      return this.snapshot(encode({ type: 2, datasetId: id, objectId: id, writerId: id, epochId: id, epochNumber: 1, descriptorHash: '1'.repeat(64), refs: [], state: value }), 'node');
    }
    if (Array.isArray(value)) return value.map(v => this.pack(v));
    if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, this.pack(v)]));
    return value;
  }
  unpack(value, memo) {
    if (value?.$v3Snapshot && value.kind) {
      ensure(typeof value.$v3Snapshot === 'string' && value.kind === 'node', 'v3 unsupported private snapshot');
      if (!memo.has(value.$v3Snapshot)) { const body = this.get('snapshot', value.$v3Snapshot); ensure(body, 'v3 missing local snapshot'); const bytes = Buffer.from(body.bytes, 'base64'); ensure(sha(bytes) === value.$v3Snapshot, 'v3 local snapshot hash'); memo.set(value.$v3Snapshot, decode(bytes).state); this.remember(value.$v3Snapshot); }
      return memo.get(value.$v3Snapshot);
    }
    if (Array.isArray(value)) return value.map(v => this.unpack(v, memo));
    if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, this.unpack(v, memo)]));
    return value;
  }
  get(ns, key) { this.assertOpen(); return this.decode(this.prepare('SELECT k,v,sum FROM v3_records WHERE ns=? AND k=?').get(ns, key), ns); }
  put(ns, key, value) {
    this.assertOpen(); ensure(this.depth > 0, 'v3 metadata write outside transaction'); ensure(ns !== 'snapshot', 'v3 snapshot bodies are immutable'); const raw = json(this.pack(value));
    if (ns === 'state' || ns === 'workspace') ensure(Buffer.byteLength(raw) <= 48 * 1024 * 1024, 'v3 local control budget'); this.write(ns, key, raw);
  }
  write(ns, key, raw) {
    this.assertOpen(); ensure(this.depth > 0, 'v3 metadata write outside transaction');
    const sum = sha(Buffer.from(ns + '\0' + key + '\0' + raw));
    const changed = this.prepare('UPDATE v3_records SET v=?,sum=? WHERE ns=? AND k=?').run(raw, sum, ns, key).changes;
    if (!changed) this.prepare('INSERT INTO v3_records(ns,k,v,sum) VALUES(?,?,?,?)').run(ns, key, raw, sum);
    this.metrics.writes++;
  }
  remove(ns, key) { this.assertOpen(); ensure(this.depth > 0 && ns !== 'snapshot', 'v3 no snapshot GC'); this.prepare('DELETE FROM v3_records WHERE ns=? AND k=?').run(ns, key); this.metrics.writes++; }
  page(ns, after = 0, limit = 64, through = Number.MAX_SAFE_INTEGER) {
    this.assertOpen(); ensure(ns !== 'snapshot' && Number.isSafeInteger(after) && after >= 0 && Number.isInteger(limit) && limit >= 1 && limit <= 256);
    return this.prepare('SELECT seq,k,v,sum FROM v3_records WHERE ns=? AND seq>? AND seq<=? ORDER BY seq LIMIT ?').all(ns, after, through, limit).map(row => ({ seq: row.seq, key: row.k, value: this.decode(row, ns) }));
  }
  lastSeq(ns) { this.assertOpen(); return this.prepare('SELECT seq FROM v3_records WHERE ns=? ORDER BY seq DESC LIMIT 1').get(ns)?.seq ?? 0; }
  count(ns) { this.assertOpen(); return this.prepare('SELECT COUNT(*) AS n FROM v3_records WHERE ns=?').get(ns).n; }
  atomic(fn) {
    this.assertOpen(); this.guard?.(); if (this.depth) return fn(); this.db.exec('BEGIN IMMEDIATE'); this.depth++;
    let committing = false;
    try { const result = fn(); ensure(!(result instanceof Promise), 'v3 transaction must not await network'); this.fault?.('before-commit'); committing = true; this.db.exec('COMMIT'); this.depth--; this.fault?.('after-commit'); return result; }
    catch (e) { if (this.depth) { try { this.db.exec('ROLLBACK'); } catch {} this.depth = 0; } if (committing || e.code?.startsWith('ERR_SQLITE')) this.poisoned = true; throw e; }
  }
  close() { if (this.closed) return; try { this.db.close(); } finally { try { this.owner.exec('ROLLBACK'); } finally { this.owner.close(); this.closed = true; } } }
}
