import { ensure } from './protocol.mjs';
import { sha, businessHash } from './v3-protocol.mjs';
import { objectPath, datasetScope, scopeInfo, file } from './v3-store.mjs';
import { merge, same } from './merge.mjs';

export const reference = (o, hash, role) => ({ role, type: o.type, epochId: o.epochId, epochNumber: o.epochNumber, objectId: o.objectId, hash });
export const refPath = (datasetId, r) => objectPath({ ...r, datasetId });
const causal = o => o.refs.filter(r => [1, 2, 3].includes(r.role));
const keyRefs = refs => JSON.stringify(refs.map(r => ({ ...r, role: 2 })).sort((a, b) => a.objectId.localeCompare(b.objectId)));
const certificate = m => sha(Buffer.from([m.type, m.datasetId, m.objectId, m.writerId, m.epochId, m.epochNumber, m.descriptorHash, m.hash, m.size, m.businessHash ?? '-', ...m.refs.map(r => [r.role, r.type, r.epochId, r.epochNumber, r.objectId, r.hash].join('|'))].join('\n'), 'ascii'));

/** Persistent observations are monotonic; a later incomplete listing cannot erase them.
 * Directory result + newly queued children + cursor are one atomic journal replacement.
 * Audit rotates over ALL prefixes, including root, even after initial discovery finishes. */
export class V3Discovery {
  constructor(journal, remote, datasetId) { Object.assign(this, { journal, remote, datasetId }); }
  async scan(limit = 8) {
    ensure(limit >= 0 && limit <= 32);
    await this.journal.transaction(s => { s.discovery ??= { scopes: [datasetScope(this.datasetId)], queue: [datasetScope(this.datasetId)], observed: [], audit: 0, failures: {} }; });
    let working = structuredClone(this.journal.value.discovery);
    try { for (let n = 0; n < limit; n++) {
      const d = working, initial = d.queue.length > 0;
      const scope = initial ? d.queue[0] : d.scopes[d.audit % d.scopes.length];
      let children;
      try { children = await this.remote.list(scope); }
      catch (e) { working.failures[scope] = String(e.message).slice(0, 512); throw e; }
      ensure(children.length <= 256 && new Set(children).size === children.length);
      {
        const next = structuredClone(working);
        for (const child of children) {
          ensure(child.startsWith(scope) && !child.slice(scope.length).replace(/\/$/, '').includes('/'));
          if (child.endsWith('/')) {
            const info = scopeInfo(child); ensure(info.datasetId === this.datasetId);
            if (!next.scopes.includes(child)) { next.scopes.push(child); next.queue.push(child); }
          } else { file(child); if (!next.observed.includes(child)) next.observed.push(child); }
        }
        // Finite memory/work cap is an explicit incomplete state, never a success claim.
        ensure(next.scopes.length <= 16384 && next.observed.length <= 65536, 'v3 discovery metadata budget');
        delete next.failures[scope]; if (initial) next.queue.shift(); else next.audit = (next.audit + 1) % next.scopes.length;
        if (!next.queue.length) next.initialComplete = true;
        working = next;
      }
    } } finally { await this.journal.transaction(s => { s.discovery = working; }); }
    return structuredClone(this.journal.value.discovery);
  }
}

/** Only metadata certificates persist. Full snapshots are loaded on demand from raw cache.
 * Certificates certify previously checked immutable closure, not remote availability this round.
 * A round verifies at most 256 new objects and limits ancestry and merge work independently. */
export class V3History {
  constructor(journal, cache, remote, descriptorPath, descriptorHash, budget) {
    Object.assign(this, { journal, cache, remote, descriptorPath, descriptorHash, budget }); this.visiting = new Set(); this.newObjects = 0; this.expanded = 0; this.work = 0;
  }
  get nodes() { return this.journal.value.history?.nodes ?? {}; }
  async open() {
    await this.journal.transaction(s => { s.history ??= { nodes: {}, ids: {}, roots: {}, anomalies: {} }; });
    this.budget.charge(Buffer.byteLength(JSON.stringify(this.nodes)), 0);
    for (const [path, node] of Object.entries(this.nodes)) { ensure(node.certificate === certificate(node), 'v3 metadata certificate corrupt'); ensure(this.journal.value.history.ids[node.objectId] === path, 'v3 metadata identity index corrupt'); }
    const bytes = await this.cache.load(this.descriptorPath, this.remote); ensure(bytes && sha(bytes) === this.descriptorHash, 'v3 descriptor');
    const o = (await import('./v3-store.mjs')).match(this.descriptorPath, bytes); ensure(o.type === 1); this.datasetId = o.datasetId;
    await this.record(this.descriptorPath, bytes, o); return this;
  }
  async record(path, bytes, o) {
    const hash = sha(bytes); const { state, ...metadata } = o;
    await this.journal.transaction(s => {
      const h = s.history, previous = h.ids[o.objectId]; ensure(!previous || previous === path, 'v3 duplicate objectId');
      ensure(!h.nodes[path] || h.nodes[path].hash === hash, 'v3 identity changed');
      if (o.type !== 1 && !h.nodes[path]) ensure(Object.values(h.nodes).filter(v => v.epochId === o.epochId).length < 256, 'v3 epoch capacity');
      if (o.type === 2) { ensure(!h.roots[o.epochId] || h.roots[o.epochId] === path, 'v3 multiple genesis'); h.roots[o.epochId] = path; }
      if (o.type === 2 && o.epochNumber === 1) ensure(!Object.values(h.nodes).some(v => v.type === 2 && v.epochNumber === 1 && v.objectId !== o.objectId), 'v3 multiple initial genesis');
      h.ids[o.objectId] = path; const node = { ...metadata, hash, size: bytes.length, businessHash: state ? businessHash(state) : null }; node.certificate = certificate(node); h.nodes[path] = node; delete h.anomalies[path];
    });
  }
  async value(path) { const raw = await this.cache.load(path, this.remote); ensure(raw, 'v3 missing ancestor'); const { match } = await import('./v3-store.mjs'); const o = match(path, raw); ensure(!this.nodes[path] || this.nodes[path].hash === sha(raw), 'v3 identity changed'); return o; }
  tick() { this.budget.charge(0, 0); ensure(++this.work <= 32768, 'v3 ancestry budget'); }
  ancestors(path) {
    const seen = new Set(), queue = [path];
    while (queue.length) { this.tick(); const p = queue.pop(); if (seen.has(p)) continue; const node = this.nodes[p]; ensure(node, 'v3 unverified ancestor'); seen.add(p); queue.push(...causal(node).map(r => refPath(this.datasetId, r))); }
    return seen;
  }
  frontier(paths) {
    const unique = new Set(paths), covered = new Set();
    for (const p of unique) {
      const queue = causal(this.nodes[p]).map(r => refPath(this.datasetId, r)), visited = new Set();
      while (queue.length) { this.tick(); const a = queue.pop(); if (visited.has(a)) continue; visited.add(a); if (unique.has(a)) covered.add(a); else { ensure(this.nodes[a]); queue.push(...causal(this.nodes[a]).map(r => refPath(this.datasetId, r))); } }
    }
    const tips = [...unique].filter(p => !covered.has(p)); ensure(tips.length <= 64, 'v3 tips budget'); return tips.sort();
  }
  common(paths) { return this.commonCandidates(paths)[0]; }
  commonCandidates(paths) {
    ensure(paths.length > 0 && paths.length <= 64); const sets = paths.map(p => this.ancestors(p));
    const shared = new Set([...sets[0]].filter(p => sets.every(s => s.has(p)))), covered = new Set();
    for (const p of shared) for (const r of causal(this.nodes[p])) { this.tick(); const a = refPath(this.datasetId, r); if (shared.has(a)) covered.add(a); }
    const maximal = [...shared].filter(p => !covered.has(p)).sort();
    ensure(maximal.length && new Set(maximal.map(p => this.nodes[p].businessHash)).size === 1, 'v3 unknown/ambiguous common baseline'); return maximal;
  }
  async merged(paths) {
    paths = this.frontier(paths).sort((a, b) => this.nodes[a].objectId < this.nodes[b].objectId ? -1 : 1); ensure(paths.length); if (paths.length === 1) return (await this.value(paths[0])).state;
    const base = (await this.value(this.common(paths))).state, branches = [];
    const bytes = paths.reduce((n, p) => n + this.nodes[p].size, 0); ensure(bytes * paths.length <= 64 * 1024 * 1024, 'v3 merge budget');
    this.budget.charge(bytes * paths.length, 0);
    for (const p of paths) branches.push((await this.value(p)).state);
    for (let i = 0; i < branches.length; i++) for (let j = i + 1; j < branches.length; j++) ensure(!merge(base, branches[i], branches[j]).conflicts.length, 'v3 unresolved branch conflict');
    let result = branches[0]; for (const b of branches.slice(1)) { const m = merge(base, result, b); ensure(!m.conflicts.length, 'v3 unresolved branch conflict'); result = m.state; } return result;
  }
  async verify(path) {
    if (this.nodes[path]) return this.nodes[path];
    const walk = structuredClone(this.journal.value.history.walk ?? { root: path, stack: [{ path }] });
    try {
      while (walk.stack.length) {
        this.budget.charge(0, 0); const frame = walk.stack.at(-1);
        if (this.nodes[frame.path]) { walk.stack.pop(); continue; }
        if (!frame.refs) {
          ensure(++this.expanded <= 256, 'v3 active graph expansion budget');
          const o = await this.value(frame.path); frame.refs = o.refs;
        }
        const child = frame.refs.map(r => refPath(this.datasetId, r)).find(p => !this.nodes[p]);
        if (child) { ensure(!walk.stack.some(f => f.path === child), 'v3 cycle'); walk.stack.push({ path: child }); }
        else { await this.verifyReady(frame.path); walk.stack.pop(); }
      }
    } catch (e) {
      await this.journal.transaction(s => { s.history.anomalies[walk.root] = String(e.message).slice(0, 1024); });
      if (!/budget|missing|unavailable|timed|Network|ENOENT/i.test(e.message)) walk.stack = [];
      throw e;
    } finally { await this.journal.transaction(s => { s.history.walk = walk.stack.length ? walk : null; }); }
    if (!this.nodes[path]) return this.verify(path); return this.nodes[path];
  }
  async verifyReady(path) {
    if (this.nodes[path]) return this.nodes[path];
    ensure(!this.visiting.has(path), 'v3 cycle'); ensure(++this.newObjects <= 256, 'v3 active graph budget'); this.visiting.add(path);
    try {
      const bytes = await this.cache.load(path, this.remote); ensure(bytes, 'v3 missing object'); const { match } = await import('./v3-store.mjs'); const o = match(path, bytes);
      ensure(o.datasetId === this.datasetId && o.descriptorHash === this.descriptorHash && o.type !== 1, 'v3 descriptor identity');
      for (const r of o.refs) {
        const p = refPath(this.datasetId, r), target = this.nodes[p];
        ensure(target && target.hash === r.hash && target.type === r.type && target.epochId === r.epochId && target.epochNumber === r.epochNumber && target.objectId === r.objectId && target.descriptorHash === this.descriptorHash, 'v3 reference identity');
      }
      if (o.type === 2 && o.epochNumber > 1) {
        const source = o.refs.map(r => refPath(this.datasetId, r)); ensure(this.frontier(source).length === source.length, 'v3 checkpoint redundant frontier');
        ensure(same(o.state, await this.merged(source)), 'v3 checkpoint extra edit');
      }
      if (o.type === 3) {
        const parents = o.refs.filter(r => r.role === 1).map(r => refPath(this.datasetId, r));
        const roots = parents.map(p => [...this.ancestors(p)].filter(a => this.nodes[a].type === 2 && this.nodes[a].epochId === o.epochId));
        ensure(roots.every(r => r.length === 1 && r[0] === roots[0][0]), 'v3 parent common root');
        const late = o.refs.filter(r => r.role === 3); if (late.length) {
          const branches = [...parents, ...late.map(r => refPath(this.datasetId, r))], baseline = refPath(this.datasetId, o.refs.find(r => r.role === 4));
          ensure(this.commonCandidates(branches).includes(baseline), 'v3 unproven BASELINE');
        }
      }
      if (o.type === 4) {
        const target = await this.value(refPath(this.datasetId, o.refs.find(r => r.role === 5)));
        ensure(keyRefs(target.refs) === keyRefs(o.refs.filter(r => r.role === 2)), 'v3 transition frontier mismatch');
      }
      await this.record(path, bytes, o); return this.nodes[path];
    } catch (e) {
      await this.journal.transaction(s => { s.history.anomalies[path] = String(e.message).slice(0, 1024); }); throw e;
    } finally { this.visiting.delete(path); }
  }
  activeEpochs() {
    const activated = new Set(Object.values(this.nodes).filter(o => o.type === 2 && o.epochNumber === 1).map(o => o.epochId));
    const transitions = Object.values(this.nodes).filter(o => o.type === 4), queue = [...activated];
    while (queue.length) { const source = queue.shift(); for (const o of transitions.filter(t => t.epochId === source)) { const target = o.refs.find(r => r.role === 5).epochId; if (!activated.has(target)) { activated.add(target); queue.push(target); } } }
    return [...activated];
  }
}
