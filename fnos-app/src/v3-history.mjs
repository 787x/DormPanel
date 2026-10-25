import { ensure } from './protocol.mjs';
import { sha, businessHash, encode } from './v3-protocol.mjs';
import { objectPath, datasetScope, scopeInfo, file, match } from './v3-store.mjs';
import { merge, same } from './merge.mjs';
import { randomUUID } from 'node:crypto';

export const reference = (o, hash, role) => ({ role, type: o.type, epochId: o.epochId, epochNumber: o.epochNumber, objectId: o.objectId, hash });
export const refPath = (datasetId, r) => objectPath({ ...r, datasetId });
const causal = o => o.refs.filter(r => [1, 2, 3].includes(r.role));
const keyRefs = refs => JSON.stringify(refs.map(r => ({ ...r, role: 2 })).sort((a, b) => a.objectId < b.objectId ? -1 : 1));
const signature = m => sha(Buffer.from(JSON.stringify(m)));

/** One indexed row per known path, independent scan and audit queues. Listing children and
 * acknowledgement commit together. Neither a missing child nor a failed list deletes a witness. */
export class V3Discovery {
  constructor(journal, remote, datasetId) { Object.assign(this, { journal, remote, datasetId }); this.meta = journal.meta; }
  advance(progress) { if (progress) { this.meta.put('control', 'discoveryLane', { value: progress.lane }); if (progress.audit) this.meta.put('control', 'scopeAudit', progress.audit); } }
  observe(scope, children, progress = null) {
    const m = this.meta; ensure(children.length <= 256 && new Set(children).size === children.length);
    for (const child of children) { ensure(child.startsWith(scope) && !child.slice(scope.length).replace(/\/$/, '').includes('/')); if (child.endsWith('/')) ensure(scopeInfo(child).datasetId === this.datasetId); else file(child); }
    m.atomic(() => {
      for (const child of children) if (child.endsWith('/')) {
        if (!m.get('scope', child)) { m.put('scope', child, { path: child }); m.put('scan', child, { path: child }); }
      } else if (!m.get('observed', child)) { m.put('observed', child, { path: child }); m.put('todo', child, { path: child }); }
      m.put('listing', scope, { children, round: this.journal.roundId ?? null, endpoint: this.remote.base?.href ?? null }); m.remove('scan', scope); m.remove('error', scope);
      if (!m.page('scan', 0, 1).length) m.put('control', 'initialComplete', { value: true });
      this.advance(progress);
    });
  }
  async scan(limit = 8) {
    ensure(limit >= 0 && limit <= 32); const m = this.meta, root = datasetScope(this.datasetId);
    if (!m.get('scope', root)) m.atomic(() => { m.put('scope', root, { path: root }); m.put('scan', root, { path: root }); });
    for (let n = 0; n < limit; n++) {
      const pending = m.page('scan', 0, 1); let cursor = m.get('control', 'scopeAudit') ?? { seq: 0, ceiling: m.lastSeq('scope') };
      let audit = m.page('scope', cursor.seq, 1, cursor.ceiling)[0];
      if (!audit) { cursor = { seq: 0, ceiling: m.lastSeq('scope') }; audit = m.page('scope', 0, 1, cursor.ceiling)[0]; }
      const lane = m.get('control', 'discoveryLane')?.value ?? 0;
      const task = pending.length && lane === 0 ? pending[0] : audit;
      if (!task) break;
      const progress = { lane: 1 - lane, audit: task === audit ? { seq: task.seq, ceiling: cursor.ceiling } : null };
      try { this.observe(task.key, await this.remote.list(task.key), progress); }
      catch (e) { if (!/budget/i.test(e.message)) m.atomic(() => { m.put('error', task.key, { reason: String(e.message) }); this.advance(progress); }); throw e; }
    }
    return { initialComplete: !!m.get('control', 'initialComplete'), pending: !!m.page('scan', 0, 1).length, oldHistoryAuditComplete: false };
  }
}

/** Indexed immutable certificates, incremental frontier and persistent proof work queues.
 * SQLite checksums are verified only for rows actually used. No startup table enumeration. */
export class V3History {
  constructor(journal, cache, remote, descriptorPath, descriptorHash, budget) {
    Object.assign(this, { journal, cache, remote, descriptorPath, descriptorHash, budget }); this.meta = journal.meta;
    this.meta.budget = budget; this.loaded = new Map(); this.newObjects = 0; this.expanded = 0; this.work = 0; this.proofWork = 256;
    this.nodes = new Proxy({}, { get: (_, path) => typeof path === 'string' ? this.node(path) : undefined, ownKeys: () => { throw Error('v3 use paged metadata queries'); } });
  }
  node(path) {
    if (this.loaded.has(path)) return this.loaded.get(path);
    const node = this.meta.get('node', path); if (!node) return undefined;
    ensure(this.meta.get('identity', node.objectId)?.path === path, 'v3 metadata identity index corrupt');
    if (this.loaded.size >= 128) this.loaded.delete(this.loaded.keys().next().value); this.loaded.set(path, node); return node;
  }
  async open() {
    this.datasetId = scopeInfo(this.descriptorPath.slice(0, this.descriptorPath.lastIndexOf('/') + 1)).datasetId;
    const old = this.node(this.descriptorPath);
    if (old) ensure(old.type === 1 && old.hash === this.descriptorHash, 'v3 descriptor identity');
    else { const bytes = await this.cache.load(this.descriptorPath, this.remote, this.descriptorHash); ensure(bytes && sha(bytes) === this.descriptorHash, 'v3 descriptor'); const o = match(this.descriptorPath, bytes); ensure(o.type === 1); await this.record(this.descriptorPath, bytes, o); }
    return this;
  }
  async record(path, bytes, o) {
    await this.cache.pin(path, 'referenced-history');
    const { state, ...metadata } = o, m = this.meta, hash = sha(bytes), refs = causal(o), primary = refs[0] ? this.node(refPath(this.datasetId, refs[0])) : null;
    const jumps = primary ? [refPath(this.datasetId, refs[0])] : [];
    for (let n = 1; jumps[n - 1]; n++) { const next = this.node(jumps[n - 1]).jumps[n - 1]; if (!next) break; jumps.push(next); }
    const node = { ...metadata, hash, size: bytes.length, verifiedAt: Date.now(), businessHash: state ? businessHash(state) : null, root: o.type === 2 ? path : primary?.root ?? null, depth: primary ? primary.depth + 1 : 0, jumps };
    m.atomic(() => {
      const identity = m.get('identity', o.objectId), old = m.get('node', path); ensure(!identity || identity.path === path, 'v3 duplicate objectId'); ensure(!old || old.hash === hash, 'v3 identity changed');
      if (!old && o.type !== 1) ensure(m.count('members:' + o.epochId) < 256, 'v3 epoch capacity');
      if (o.type === 2) {
        const epoch = m.get('epoch', o.epochId); ensure(!epoch || epoch.root === path, 'v3 multiple genesis');
        if (o.epochNumber === 1) { const initial = m.get('control', 'initial'); ensure(!initial || initial.path === path, 'v3 multiple initial genesis'); m.put('control', 'initial', { path }); }
        m.put('epoch', o.epochId, epoch ?? { root: path, number: o.epochNumber, active: o.epochNumber === 1, frontierIndexed: true });
      }
      m.put('identity', o.objectId, { path, hash }); m.put('node', path, node); m.remove('todo', path); m.remove('error', path);
      if (o.type !== 1) { m.put('members:' + o.epochId, path, { path }); if (m.get('epoch', o.epochId)?.active) m.put('activate', path, { path }); }
      if ([2, 3].includes(o.type) && m.get('epoch', o.epochId)?.frontierIndexed) {
        const ns = 'epochFrontier:' + o.epochId; if (!m.get(ns + ':covered', path)) m.put(ns, path, { path });
        for (const r of o.refs.filter(r => r.role === 1)) { const p = refPath(this.datasetId, r); m.put(ns + ':covered', p, { by: path }); m.remove(ns, p); }
      }
    }); this.loaded.delete(path);
  }
  drainActivation() {
    for (let n = 0; n < 256; n++) {
      const task = this.meta.page('activate', 0, 1)[0]; if (!task) return;
      this.budget.work('activation', 256); this.tick(); const node = this.node(task.key), m = this.meta;
      m.atomic(() => {
        if ([2, 3].includes(node.type)) {
          if (!m.get('covered', task.key)) m.put('frontier', task.key, { path: task.key });
          for (const r of causal(node)) { const p = refPath(this.datasetId, r); m.put('covered', p, { by: task.key }); m.remove('frontier', p); }
        } else if (node.type === 4) {
          const target = node.refs.find(r => r.role === 5), epoch = m.get('epoch', target.epochId); ensure(epoch, 'v3 missing target epoch');
          if (!epoch.active) { epoch.active = true; m.put('epoch', target.epochId, epoch); for (const row of m.page('members:' + target.epochId, 0, 256)) m.put('activate', row.key, { path: row.key }); }
        }
        m.remove('activate', task.key);
      });
    }
    ensure(!this.meta.page('activate', 0, 1).length, 'v3 activation budget; progress saved');
  }
  tips() { this.drainActivation(); const rows = this.meta.page('frontier', 0, 65); ensure(rows.length <= 64, 'v3 tips budget'); return rows.map(r => r.key).sort(); }
  activeEpochs() { return [...new Set(this.tips().map(p => this.node(p).epochId))]; }
  epochCount(epoch) { return this.meta.count('members:' + epoch); }
  epochTips(epoch) {
    const m = this.meta, e = m.get('epoch', epoch); ensure(e, 'v3 missing epoch'); const ns = 'epochFrontier:' + epoch;
    if (!e.frontierIndexed) m.atomic(() => {
      for (const row of m.page('members:' + epoch, 0, 256)) { const node = this.node(row.key); this.tick(); if ([2, 3].includes(node.type)) { if (!m.get(ns + ':covered', row.key)) m.put(ns, row.key, { path: row.key }); for (const r of node.refs.filter(r => r.role === 1)) { const p = refPath(this.datasetId, r); m.put(ns + ':covered', p, { by: row.key }); m.remove(ns, p); } } }
      e.frontierIndexed = true; m.put('epoch', epoch, e);
    });
    const tips = m.page(ns, 0, 65).map(r => r.key).sort(); ensure(tips.length && tips.length <= 64, 'v3 source frontier budget'); return tips;
  }
  async auditObjects(limit = 1) {
    ensure(Number.isInteger(limit) && limit >= 0 && limit <= 32);
    for (let n = 0; n < limit; n++) {
      this.budget.work('object audit', 32); const m = this.meta; let cursor = m.get('control', 'objectAudit') ?? { seq: 0, ceiling: m.lastSeq('node') };
      let row = m.page('node', cursor.seq, 1, cursor.ceiling)[0]; if (!row) { cursor = { seq: 0, ceiling: m.lastSeq('node') }; row = m.page('node', 0, 1, cursor.ceiling)[0]; } if (!row) return;
      const progress = { seq: row.seq, ceiling: cursor.ceiling };
      try { const raw = await this.remote.read(row.key); ensure(raw && sha(raw) === row.value.hash, 'v3 observed object missing or changed'); await this.cache.put(row.key, raw); m.atomic(() => { m.remove('error', row.key); m.put('evidence', row.key, { hash: row.value.hash, round: this.budget.roundId, endpoint: this.remote.base?.href ?? null }); m.put('control', 'objectAudit', progress); }); }
      catch (e) { if (!/budget/i.test(e.message)) m.atomic(() => { m.put('error', row.key, { reason: String(e.message), expectedHash: row.value.hash }); m.put('control', 'objectAudit', progress); }); throw e; }
    }
  }
  async value(path, expectedHash = null) { const raw = await this.cache.load(path, this.remote, expectedHash ?? this.node(path)?.hash); ensure(raw, 'v3 missing ancestor'); const o = match(path, raw); ensure(!this.node(path) || this.node(path).hash === sha(raw), 'v3 identity changed'); return o; }
  tick() { this.budget.work('ancestry', 32768); }
  primaryAncestor(a, b) {
    const left = this.node(a); let right = this.node(b); ensure(left && right, 'v3 unverified ancestor'); if (left.depth > right.depth) return false;
    let distance = right.depth - left.depth, path = b, bit = 0;
    while (distance) { this.tick(); if (distance % 2) { path = this.node(path).jumps[bit]; if (!path) return false; } distance = Math.floor(distance / 2); bit++; }
    return path === a;
  }
  isAncestor(a, b) { return this.primaryAncestor(a, b) || this.commonCandidates([a, b]).includes(a); }
  frontier(paths) { const unique = [...new Set(paths)]; if (unique.length <= 1) return unique; return unique.filter(p => !unique.some(other => p !== other && this.isAncestor(p, other))).sort(); }
  common(paths) { return this.commonCandidates(paths)[0]; }
  commonCandidates(paths) {
    paths = [...new Set(paths)].sort(); ensure(paths.length && paths.length <= 128);
    for (const p of paths) if (paths.every(other => this.primaryAncestor(p, other))) return [p];
    const token = signature(paths), m = this.meta, ns = 'proof:' + token, queue = ns + ':queue', seen = ns + ':seen', tips = ns + ':tips', covered = ns + ':covered';
    let control = m.get('proof', token);
    if (!control) m.atomic(() => { control = { paths, done: false }; m.put('proof', token, control); paths.forEach((path, bit) => m.put(queue, bit + ':' + path, { path, bit })); });
    if (control.done) return control.result;
    while (true) {
      const task = m.page(queue, 0, 1)[0]; if (!task) break;
      this.budget.work('proof', 256); this.tick(); const { path, bit } = task.value, node = this.node(path); ensure(node, 'v3 unverified ancestor');
      m.atomic(() => {
        const bits = m.get(seen, path)?.bits ?? [];
        if (!bits.includes(bit)) {
          bits.push(bit); m.put(seen, path, { bits });
          if (bits.length === paths.length) {
            if (!m.get(covered, path)) m.put(tips, path, { path });
            for (const r of causal(node)) { const parent = refPath(this.datasetId, r); m.put(covered, parent, { value: true }); m.remove(tips, parent); }
          }
          for (const r of causal(node)) { const parent = refPath(this.datasetId, r); if (!(m.get(seen, parent)?.bits ?? []).includes(bit)) m.put(queue, bit + ':' + parent, { path: parent, bit }); }
        }
        m.remove(queue, task.key);
      });
    }
    const result = m.page(tips, 0, 65).map(r => r.key).sort(); ensure(result.length && result.length <= 64 && new Set(result.map(p => this.node(p).businessHash)).size === 1, 'v3 unknown/ambiguous common baseline');
    m.atomic(() => m.put('proof', token, { paths, done: true, result })); return result;
  }
  async merged(paths) {
    paths = this.frontier(paths).sort((a, b) => this.node(a).objectId < this.node(b).objectId ? -1 : 1); ensure(paths.length); if (paths.length === 1) return (await this.value(paths[0])).state;
    const base = (await this.value(this.common(paths))).state, branches = [], bytes = paths.reduce((n, p) => n + this.node(p).size, 0);
    this.budget.work('merge bytes', 64 * 1024 * 1024, bytes * paths.length);
    for (const p of paths) branches.push((await this.value(p)).state);
    for (let i = 0; i < branches.length; i++) for (let j = i + 1; j < branches.length; j++) ensure(!merge(base, branches[i], branches[j]).conflicts.length, 'v3 unresolved branch conflict');
    let result = branches[0]; for (const b of branches.slice(1)) { const next = merge(base, result, b); ensure(!next.conflicts.length, 'v3 unresolved branch conflict'); result = next.state; } return result;
  }
  async verify(path, expectedHash = null) {
    if (this.node(path)) { ensure(!expectedHash || this.node(path).hash === expectedHash, 'v3 reference identity changed'); this.meta.atomic(() => this.meta.remove('todo', path)); return this.node(path); }
    const m = this.meta; let control = m.get('control', 'walk');
    if (!control) m.atomic(() => { control = { root: path, depth: 1, id: randomUUID() }; m.put('control', 'walk', control); m.put('stack:' + control.id, '0', { path, expectedHash }); });
    ensure(control.id, 'v3 unsupported private validation cursor'); const stack = 'stack:' + control.id, visiting = 'visiting:' + control.id;
    try {
      while (control.depth) {
        this.tick(); const key = String(control.depth - 1), frame = m.get(stack, key); ensure(frame, 'v3 missing validation frame');
        if (this.node(frame.path)) { m.atomic(() => { m.remove(stack, key); control.depth--; m.put('control', 'walk', control); }); continue; }
        let fresh = null;
        if (!frame.refs) { this.budget.work('expansion', 256); fresh = await this.value(frame.path, frame.expectedHash); frame.refs = fresh.refs; m.atomic(() => { m.put(stack, key, frame); m.put(visiting, frame.path, { depth: control.depth }); }); }
        const child = frame.refs.map(r => refPath(this.datasetId, r)).find(p => !this.node(p));
        if (child) { ensure(!m.get(visiting, child), 'v3 cycle'); m.atomic(() => { m.put(stack, String(control.depth++), { path: child, expectedHash: frame.refs.find(r => refPath(this.datasetId, r) === child).hash }); m.put('control', 'walk', control); }); }
        else { await this.verifyReady(frame.path, frame.expectedHash, fresh); m.atomic(() => { m.remove(visiting, frame.path); m.remove(stack, key); control.depth--; m.put('control', 'walk', control); }); }
      }
      m.atomic(() => m.remove('control', 'walk'));
    } catch (e) { m.atomic(() => { m.put('error', control.root, { reason: String(e.message) }); if (!/budget|missing|unavailable|timed|Network|ENOENT/i.test(e.message)) { m.put('failedWalk', control.id, { ...control, reason: String(e.message) }); m.remove('control', 'walk'); } }); throw e; }
    if (!this.node(path)) return this.verify(path, expectedHash); ensure(!expectedHash || this.node(path).hash === expectedHash, 'v3 reference identity changed'); return this.node(path);
  }
  async verifyReady(path, expectedHash, fresh = null) {
    if (this.node(path)) return; this.budget.work('validation', 256);
    const bytes = fresh ? encode(fresh) : await this.cache.load(path, this.remote, expectedHash); ensure(bytes, 'v3 missing object'); const o = fresh ?? match(path, bytes); ensure(o.datasetId === this.datasetId && o.descriptorHash === this.descriptorHash && o.type !== 1, 'v3 descriptor identity');
    for (const r of o.refs) { const target = this.node(refPath(this.datasetId, r)); ensure(target && target.hash === r.hash && target.type === r.type && target.epochId === r.epochId && target.epochNumber === r.epochNumber && target.objectId === r.objectId && target.descriptorHash === this.descriptorHash, 'v3 reference identity'); }
    if (o.type === 2 && o.epochNumber > 1) { const source = o.refs.map(r => refPath(this.datasetId, r)); ensure(this.frontier(source).length === source.length, 'v3 checkpoint redundant frontier'); ensure(same(o.state, await this.merged(source)), 'v3 checkpoint extra edit'); }
    if (o.type === 3) { const parents = o.refs.filter(r => r.role === 1).map(r => this.node(refPath(this.datasetId, r))); ensure(parents.every(p => p.root && p.root === parents[0].root), 'v3 parent common root'); const late = o.refs.filter(r => r.role === 3); if (late.length) ensure(this.commonCandidates(o.refs.filter(r => [1, 3].includes(r.role)).map(r => refPath(this.datasetId, r))).includes(refPath(this.datasetId, o.refs.find(r => r.role === 4))), 'v3 unproven BASELINE'); }
    if (o.type === 4) { const target = this.node(refPath(this.datasetId, o.refs.find(r => r.role === 5))); ensure(keyRefs(target.refs) === keyRefs(o.refs.filter(r => r.role === 2)), 'v3 transition frontier mismatch'); }
    await this.record(path, bytes, o);
  }
}
