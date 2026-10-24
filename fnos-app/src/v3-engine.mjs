import { randomUUID } from 'node:crypto';
import { ensure } from './protocol.mjs';
import { encode, decode, sha, ZERO, ZERO_HASH } from './v3-protocol.mjs';
import { objectPath, epochScope } from './v3-store.mjs';
import { V3Publisher } from './v3-transaction.mjs';
import { V3History, V3Discovery, reference, refPath } from './v3-history.mjs';
import { emptyState, merge, same, mergePlan } from './merge.mjs';

/** Isolated engine only. Caller owns Workspace's exclusive writer lease for the lifetime.
 * Edits and receipts share one durable journal; an engine queue serializes network rounds.
 * No production binding, server route or v2 workspace ever invokes this class. */
export class V3Engine {
  constructor(journal, writerId) { ensure(!journal.value.writerId || journal.value.writerId === writerId, 'v3 writer identity changed'); Object.assign(this, { journal, writerId }); }
  serial(fn) { const p = this.journal.networkQueue.then(fn); this.journal.networkQueue = p.catch(() => {}); return p; }
  async edit(state) {
    // Domain validation before committing; immutable private envelope never reaches a server.
    encode({ type: 2, datasetId: this.journal.value.binding.datasetId, objectId: randomUUID(), writerId: this.writerId, epochId: randomUUID(), epochNumber: 1, descriptorHash: this.journal.value.binding.descriptorHash, refs: [], state });
    return this.journal.transaction(s => { s.local = structuredClone(state); s.revision = (s.revision ?? 0) + 1; s.decision = null; });
  }
  async bind(descriptor, hash, local = emptyState()) {
    ensure(descriptor.type === 1 && sha(encode(descriptor)) === hash);
    await this.journal.transaction(s => { ensure(!s.binding); s.writerId = this.writerId; s.binding = { datasetId: descriptor.datasetId, descriptorPath: objectPath(descriptor), descriptorHash: hash }; s.local = local; s.unbasedLocal = !same(local, emptyState()); s.baseline = null; s.baselinePath = null; s.revision = 0; });
  }
  async initialize(remote, state = emptyState()) {
    return this.serial(async () => {
      const descriptor = { type: 1, datasetId: randomUUID(), objectId: randomUUID(), writerId: this.writerId, epochId: ZERO, epochNumber: 0, descriptorHash: ZERO_HASH, refs: [], state: null };
      const genesis = { type: 2, datasetId: descriptor.datasetId, objectId: randomUUID(), writerId: this.writerId, epochId: randomUUID(), epochNumber: 1, descriptorHash: sha(encode(descriptor)), refs: [], state };
      await this.bind(descriptor, genesis.descriptorHash, state);
      await this.journal.transaction(s => { s.initialization = [encode(descriptor).toString('base64'), encode(genesis).toString('base64')]; });
      await this.resumeInitialization(remote); return structuredClone(this.journal.value.binding);
    });
  }
  async publish(remote, object, capture) {
    const publisher = new V3Publisher(this.journal, remote), raw = encode(object), path = objectPath(object);
    if (remote.prepare) await remote.prepare(path.slice(0, path.lastIndexOf('/') + 1));
    await publisher.stage(path, raw, capture); return this.resumePublication(remote);
  }
  async resumePublication(remote) {
    const publisher = new V3Publisher(this.journal, remote), phase = await publisher.resume();
    ensure(phase === 'READBACK_CONFIRMED' || phase === 'RECEIPTED', 'v3 UNCERTAIN: read-only recovery required');
    if (phase === 'RECEIPTED') return;
    await publisher.receipt((s, c) => {
      const o = decode(Buffer.from(s.pending.bytes, 'base64'));
      if (c.kind === 'commit') {
        const replay = merge(c.local, s.local, o.state);
        if (replay.conflicts.length) s.conflict = { id: sha(Buffer.from(JSON.stringify({ path: s.pending.path, revision: s.revision }))), revision: s.revision, base: c.local, local: s.local, remote: o.state, capturedLocal: s.local, conflicts: replay.conflicts, paths: [s.pending.path], receipt: true };
        else s.local = replay.state;
        s.baseline = o.state; s.baselinePath = s.pending.path; s.decision = null;
        s.convergenceRotations = 0;
      }
      if (c.kind === 'initial-genesis') { s.baseline = o.state; s.baselinePath = s.pending.path; s.activeEpoch = o.epochId; }
      if (c.kind === 'transition') s.activeEpoch = c.targetEpoch;
      s.receipt = { path: s.pending.path, hash: s.pending.hash };
    });
  }
  async resumeInitialization(remote) {
    while (this.journal.value.initialization?.length) {
      const raw = this.journal.value.initialization[0], o = decode(Buffer.from(raw, 'base64'));
      if (this.journal.value.pending?.phase !== 'RECEIPTED' && this.journal.value.pending) await this.resumePublication(remote);
      if (this.journal.value.receipt?.path !== objectPath(o)) await this.publish(remote, o, { kind: o.type === 1 ? 'descriptor' : 'initial-genesis' });
      await this.journal.transaction(s => { s.initialization.shift(); });
    }
  }
  rotate(history, remote, sourceEpoch, tips) { return this.serial(() => this.rotateInternal(history, remote, sourceEpoch, tips)); }
  async rotateInternal(history, remote, sourceEpoch, tips) {
    const source = history.nodes[tips[0]], state = await history.merged(tips), refs = tips.map(p => reference(history.nodes[p], history.nodes[p].hash, 2));
    const genesis = { type: 2, datasetId: source.datasetId, objectId: randomUUID(), writerId: this.writerId, epochId: randomUUID(), epochNumber: source.epochNumber + 1, descriptorHash: source.descriptorHash, refs, state };
    const transition = { type: 4, datasetId: source.datasetId, objectId: randomUUID(), writerId: this.writerId, epochId: sourceEpoch, epochNumber: source.epochNumber, descriptorHash: source.descriptorHash, refs: [...refs, reference(genesis, sha(encode(genesis)), 5)], state: null };
    await this.journal.transaction(s => { s.rotation = { genesis: encode(genesis).toString('base64'), transition: encode(transition).toString('base64'), phase: 0 }; });
    await this.resumeRotation(remote);
  }
  async resumeRotation(remote) {
    const rotation = this.journal.value.rotation; if (!rotation) return;
    for (let step = rotation.phase; step < 2; step++) {
      const o = decode(Buffer.from(step === 0 ? rotation.genesis : rotation.transition, 'base64'));
      const pending = this.journal.value.pending;
      if (pending && pending.phase !== 'RECEIPTED') await this.resumePublication(remote);
      if (this.journal.value.receipt?.path !== objectPath(o)) await this.publish(remote, o, { kind: step === 0 ? 'checkpoint' : 'transition', targetEpoch: decode(Buffer.from(rotation.genesis, 'base64')).epochId });
      await this.journal.transaction(s => { s.rotation.phase = step + 1; });
    }
    await this.journal.transaction(s => { s.rotation = null; });
  }
  async resolve(id, choice) {
    ensure(['local', 'remote'].includes(choice));
    await this.journal.transaction(s => {
      const c = s.conflict; ensure(c?.id === id && c.revision === s.revision, 'v3 stale conflict decision');
      const keys = new Set(c.conflicts.flatMap(v => v.keys));
      const resolved = merge(c.base, c.local, c.remote, choice, keys);
      const replay = merge(c.base, c.capturedLocal, resolved.state, 'remote', keys);
      ensure(replay.conflicts.every(v => v.keys.every(k => keys.has(k))), 'v3 new local conflict requires separate decision');
      s.local = replay.state; s.revision++; s.conflict = null; s.decision = { id, choice, remotePaths: c.paths, input: c };
    });
  }
  round(remote, cache, budget, { discoverySteps = 8 } = {}) {
    return this.serial(async () => {
      try {
        await cache.open();
        if (this.journal.value.initialization?.length) await this.resumeInitialization(remote);
        if (this.journal.value.pending && this.journal.value.pending.phase !== 'RECEIPTED') await this.resumePublication(remote);
        if (this.journal.value.rotation) await this.resumeRotation(remote);
        const s = this.journal.value, b = s.binding; ensure(b);
        const discovery = new V3Discovery(this.journal, remote, b.datasetId); await discovery.scan(discoverySteps);
        const history = await new V3History(this.journal, cache, remote, b.descriptorPath, b.descriptorHash, budget).open();
        if (this.journal.value.receipt) await history.verify(this.journal.value.receipt.path);
        if (this.journal.value.baselinePath) await history.verify(this.journal.value.baselinePath);
        for (const epoch of history.activeEpochs().slice(-8)) {
          const listed = await remote.list(epochScope(b.datasetId, epoch));
          await this.journal.transaction(next => { for (const p of listed) if (!next.discovery.observed.includes(p)) next.discovery.observed.push(p); });
        }
        const observed = this.journal.value.discovery.observed;
        if (observed.length) {
          const cursor = this.journal.value.objectAudit ?? 0, path = observed[cursor % observed.length];
          const raw = await remote.read(path); ensure(raw, 'v3 observed object missing'); await cache.put(path, raw);
          await this.journal.transaction(next => { next.objectAudit = (cursor + 1) % observed.length; });
        }
        for (const p of observed) if (p !== b.descriptorPath) await history.verify(p);
        ensure(this.journal.value.discovery.initialComplete, 'v3 initial discovery incomplete');
        const epochs = history.activeEpochs(); ensure(epochs.length, 'v3 no activated epoch');
        const all = Object.entries(history.nodes).filter(([, o]) => [2, 3].includes(o.type) && epochs.includes(o.epochId)).map(([p]) => p);
        const tips = history.frontier(all); const current = this.journal.value;
        // Technical carrier only; all other branches remain in tips and must be causally covered.
        let carrier = current.activeEpoch; if (!epochs.includes(carrier)) carrier = history.nodes[tips[0]].epochId;
        const highest = Math.max(...tips.map(p => history.nodes[p].epochNumber));
        if (!tips.some(p => history.nodes[p].epochId === carrier && history.nodes[p].epochNumber === highest)) carrier = history.nodes[tips.find(p => history.nodes[p].epochNumber === highest)].epochId;
        const parents = tips.filter(p => history.nodes[p].epochId === carrier), other = tips.filter(p => history.nodes[p].epochId !== carrier);
        ensure(parents.length, 'v3 carrier has no frontier');
        const decisionPaths = current.decision?.remotePaths;
        const decisionMatches = decisionPaths?.length && tips.every(p => decisionPaths.some(old => history.nodes[old]?.businessHash === history.nodes[p].businessHash && history.ancestors(p).has(old))) && decisionPaths.every(old => tips.some(p => history.nodes[old]?.businessHash === history.nodes[p].businessHash && history.ancestors(p).has(old)));
        let remoteState;
        try { remoteState = await history.merged(tips); }
        catch (e) {
          if (!String(e.message).includes('conflict')) throw e;
          if (decisionMatches) remoteState = current.local;
          else {
            budget.charge(tips.reduce((n, p) => n + history.nodes[p].size, 0) * tips.length, 0);
            const base = (await history.value(history.common(tips))).state;
            const localBase = (await history.value(history.common([...tips, ...(current.baselinePath ? [current.baselinePath] : [])]))).state;
            const branches = [];
            for (const path of tips) branches.push({ commitId: history.nodes[path].objectId, path, state: (await history.value(path)).state });
            const plan = { base, localBase, branches }, result = mergePlan(plan, current.local);
            await this.saveConflict(localBase, current.local, result.remote, result.conflicts, tips, current.revision, plan); return { status: 'CONFLICT' };
          }
        }
        const basePath = current.baselinePath ? history.common([...tips, current.baselinePath]) : history.common(tips);
        ensure(current.baselinePath || !current.unbasedLocal && current.revision === 0, 'v3 local state has no proven baseline');
        const local = current.baselinePath ? current.local : remoteState;
        const base = (await history.value(basePath)).state, result = decisionMatches ? { state: local, conflicts: [] } : merge(base, local, remoteState);
        if (result.conflicts.length) { await this.saveConflict(base, current.local, remoteState, result.conflicts, tips, current.revision); return { status: 'CONFLICT' }; }
        const count = Object.values(history.nodes).filter(o => o.epochId === carrier).length;
        ensure(count < 256, 'v3 epoch capacity');
        const equivalent = tips.every(p => history.nodes[p].businessHash === history.nodes[tips[0]].businessHash);
        if (other.length && equivalent && same(result.state, remoteState)) {
          await this.journal.transaction(next => { if (next.revision === current.revision) { next.local = result.state; next.baseline = remoteState; next.baselinePath = parents[0]; next.equivalentFrontiers = tips; } });
          return { status: 'EQUIVALENT_FRONTIERS', pendingCausalFrontiers: tips, oldHistoryAuditComplete: false };
        }
        // Equal-generation candidates require a legal higher carrier before LATE can represent them.
        if (other.some(p => history.nodes[p].epochNumber >= history.nodes[parents[0]].epochNumber) || count >= 160) {
          ensure(count < 255, 'v3 transition capacity');
          if (other.length) await this.journal.transaction(next => { ensure((next.convergenceRotations ?? 0) < 4, 'v3 convergence retry budget; candidates retained'); next.convergenceRotations = (next.convergenceRotations ?? 0) + 1; });
          await this.rotateInternal(history, remote, carrier, parents); return { status: 'ROTATED' };
        }
        if (same(result.state, remoteState) && equivalent && !other.length) {
          await this.journal.transaction(next => { if (next.revision === current.revision) { next.local = result.state; next.baseline = remoteState; next.baselinePath = tips[0]; } });
          return { status: 'OBSERVED', oldHistoryAuditComplete: false };
        }
        ensure(count < 192, 'v3 rotation must precede publication');
        const refs = parents.map(p => reference(history.nodes[p], history.nodes[p].hash, 1));
        if (other.length) {
          const common = history.common(tips); ensure(history.nodes[common].epochNumber < history.nodes[parents[0]].epochNumber, 'v3 bridge baseline generation');
          refs.push(...other.map(p => reference(history.nodes[p], history.nodes[p].hash, 3)), reference(history.nodes[common], history.nodes[common].hash, 4));
        }
        const source = history.nodes[parents[0]], o = { type: 3, datasetId: b.datasetId, objectId: randomUUID(), writerId: this.writerId, epochId: carrier, epochNumber: source.epochNumber, descriptorHash: b.descriptorHash, refs, state: result.state };
        const raw = encode(o); await cache.put(objectPath(o), raw); await cache.pin(objectPath(o), 'pending');
        await this.publish(remote, o, { kind: 'commit', local: current.local, revision: current.revision, decision: current.decision }); return { status: 'PUBLISHED', oldHistoryAuditComplete: false };
      } catch (e) { await this.journal.transaction(s => { s.error = String(e.message).slice(0, 1024); }); throw e; }
    });
  }
  async saveConflict(base, local, remote, conflicts, paths, revision, plan = null) {
    await this.journal.transaction(s => { ensure(s.revision === revision, 'v3 local edit changed; replan conflict'); s.conflict = { id: sha(Buffer.from(paths.join('\n') + '\n' + s.revision)), revision: s.revision, base, local, remote, capturedLocal: s.local, conflicts, paths, plan }; });
  }
}
