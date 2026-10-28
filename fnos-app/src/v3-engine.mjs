import {sharedTypes} from './shared-types.mjs';
import {change as changeCourseColor,courseColorAdapter} from './shared-course-colors.mjs';
import {Registry,courseColor,wireRecords} from './shared-records.mjs';
const sharedRegistry=new Registry(sharedTypes);
import { randomUUID } from 'node:crypto';
import { ensure } from './protocol.mjs';
import { encode, decode, sha, ZERO, ZERO_HASH } from './v3-protocol.mjs';
import { objectPath, epochScope, Budget } from './v3-store.mjs';
import { IndexedV3Cache } from './v3-index.mjs';
import { V3Publisher } from './v3-transaction.mjs';
import { V3History, V3Discovery, reference, refPath } from './v3-history.mjs';
import { emptyState, merge, same, mergePlan } from './merge.mjs';

/** Sync core. Caller owns Workspace's exclusive writer lease for the lifetime.
 * Edits and receipts share one durable journal; an engine queue serializes network rounds.
 * Production activation and permissions belong to Catalog; v2 never calls this engine. */
export class V3Engine {
  constructor(journal, writerId, registry = sharedRegistry) { this.sharedRegistry=registry; ensure(!journal.value.writerId || journal.value.writerId === writerId, 'v3 writer identity changed'); Object.assign(this, { journal, writerId }); this.validatePersisted(journal.value); }
  encode(o){return encode(o,{registry:this.sharedRegistry});}
  decode(bytes){return decode(bytes,{registry:this.sharedRegistry});}
  validatePersisted(value){
    if(!value||typeof value!=='object')return;
    if(Array.isArray(value.sharedRecords))this.sharedRegistry.validate(wireRecords(value.sharedRecords));
    if(typeof value.bytes==='string'){const o=this.decode(Buffer.from(value.bytes,'base64'));if(o.state)this.sharedRegistry.validate(wireRecords(o.state.sharedRecords??[]));}
    for(const v of Object.values(value))if(v&&typeof v==='object')this.validatePersisted(v);
  }
  merge(base,local,remote,choice=null,forced=new Set()){return merge(base,local,remote,choice,forced,[...this.sharedRegistry.adapters.values()]);}
  serial(fn) { const p = this.journal.networkQueue.then(() => { this.journal.meta.assertWritable(); return fn(); }); this.journal.networkQueue = p.catch(() => {}); return p; }
  configure(remote, cache, budget) {
    ensure(cache.budget === budget && (!remote.budget || remote.budget === budget), 'v3 round must share one budget');
    this.journal.roundId = budget.roundId;
    this.journal.meta.budget = budget;
    this.journal.prepareReceipt = async p => {
      const b = this.journal.value.binding, witness = await cache.witness(p.path); ensure(!witness || witness.hash === p.hash, 'v3 pending cache identity changed');
      if (!witness || !await cache.hasBlob(p.path)) await cache.put(p.path, Buffer.from(p.bytes, 'base64'));
      const h = await new V3History(this.journal, cache, remote, b.descriptorPath, b.descriptorHash, budget,this.sharedRegistry).open();
      await h.verify(p.path, p.hash); h.drainActivation();
    };
  }
  async setCourseColor(sourceId,seriesId,rgb){return this.edit(changeCourseColor(this.journal.value.local,sourceId,seriesId,rgb),this.journal.value.revision);}
  async edit(state, expectedRevision = undefined) {
    const before=this.journal.value.local;
    const opaque=s=>(s?.sharedRecords??[]).filter(r=>!this.sharedRegistry.supported(r)).toSorted((a,b)=>(a.typeId+'/'+a.recordId).localeCompare(b.typeId+'/'+b.recordId));
    ensure(JSON.stringify(opaque(before))===JSON.stringify(opaque(state)),'Unknown optional records are read-only');
    if(before?.sharedRecords!==undefined)this.sharedRegistry.writable(wireRecords(before.sharedRecords));
    ensure((before?.sharedRecords!==undefined)===(state.sharedRecords!==undefined),'Use explicit isolated upgrade');
    if(state.sharedRecords!==undefined)this.sharedRegistry.writable(wireRecords(state.sharedRecords));
    // Domain validation before committing; immutable private envelope never reaches a server.
    this.encode({ type: 2, datasetId: this.journal.value.binding?.datasetId ?? '00000000-0000-0000-0000-000000000001', objectId: randomUUID(), writerId: this.writerId, epochId: randomUUID(), epochNumber: 1, descriptorHash: this.journal.value.binding?.descriptorHash ?? '1'.repeat(64), refs: [], state });
    return this.journal.transaction(s => { ensure(expectedRevision===undefined||s.revision===expectedRevision,'Stale revision'); ensure(!s.writerId || s.writerId === this.writerId); s.writerId = this.writerId; s.local = structuredClone(state); s.revision = (s.revision ?? 0) + 1; s.decision = null; }, true);
  }
  async bind(descriptor, hash, local = undefined, initialization = undefined) {
    ensure(descriptor.type === 1 && sha(this.encode(descriptor)) === hash);
    await this.journal.transaction(s => { ensure(!s.binding && (!s.writerId || s.writerId === this.writerId)); const existing = s.local ?? emptyState(), seed = local ?? (descriptor.format===4?{...existing,sharedRecords:[]}:existing); ensure(same(existing, emptyState()) || same(existing, seed), 'v3 binding must preserve unbound local edits'); s.writerId = this.writerId; s.binding = { datasetId: descriptor.datasetId, descriptorPath: objectPath(descriptor), descriptorHash: hash, ...(descriptor.format===4?{format:4}:{}) }; s.local = seed; s.unbasedLocal = !same(seed, emptyState()); s.baseline = null; s.baselinePath = null; s.revision ??= 0;
      if (initialization) s.initialization = initialization;
    }, true);
  }
  async initialize(remote, state = undefined) {
    return this.serial(async () => {
      if (this.journal.value.initialization?.length) {
        const budget = remote.budget ?? new Budget(); this.configure(remote, new IndexedV3Cache(this.journal.directory, budget), budget);
        await this.resumeInitialization(remote); return structuredClone(this.journal.value.binding);
      }
      state ??= this.journal.value.local ?? emptyState();
      const descriptor = { ...(state.sharedRecords!==undefined?{format:4}:{}), type: 1, datasetId: randomUUID(), objectId: randomUUID(), writerId: this.writerId, epochId: ZERO, epochNumber: 0, descriptorHash: ZERO_HASH, refs: [], state: null };
      const genesis = { type: 2, datasetId: descriptor.datasetId, objectId: randomUUID(), writerId: this.writerId, epochId: randomUUID(), epochNumber: 1, descriptorHash: sha(this.encode(descriptor)), refs: [], state };
      // Binding and BOTH exact candidates commit together, before any network I/O.
      await this.bind(descriptor, genesis.descriptorHash, state, [this.encode(descriptor).toString('base64'), this.encode(genesis).toString('base64')]);
      const budget = remote.budget ?? new Budget(); this.configure(remote, new IndexedV3Cache(this.journal.directory, budget), budget);
      await this.resumeInitialization(remote); return structuredClone(this.journal.value.binding);
    });
  }
  async publish(remote, object, capture) {
    ensure((object.format??(object.state?.sharedRecords!==undefined?4:3))===(this.journal.value.binding.format??3),'Dataset format mismatch');
    if(object.state?.sharedRecords!==undefined)this.sharedRegistry.writable(wireRecords(object.state.sharedRecords));
    const publisher = new V3Publisher(this.journal, remote), raw = this.encode(object), path = objectPath(object);
    if (remote.prepare) await remote.prepare(path.slice(0, path.lastIndexOf('/') + 1));
    await publisher.stage(path, raw, capture); return this.resumePublication(remote);
  }
  async resumePublication(remote) {
    const publisher = new V3Publisher(this.journal, remote), phase = await publisher.resume();
    ensure(phase === 'READBACK_CONFIRMED' || phase === 'RECEIPTED', 'v3 UNCERTAIN: read-only recovery required');
    if (phase === 'RECEIPTED') return;
    await publisher.receipt((s, c) => {
      const o = this.decode(Buffer.from(s.pending.bytes, 'base64')); if(o.state)this.sharedRegistry.validate(wireRecords(o.state.sharedRecords??[]));
      if (c.kind === 'commit') {
        const replay = this.merge(c.local, s.local, o.state);
        if (replay.conflicts.length) s.conflict = { id: sha(Buffer.from(JSON.stringify({ path: s.pending.path, revision: s.revision }))), revision: s.revision, base: c.local, local: s.local, remote: o.state, capturedLocal: s.local, conflicts: replay.conflicts, paths: [s.pending.path], receipt: true };
        else s.local = replay.state;
        s.baseline = o.state; s.baselinePath = s.pending.path; s.baselineHash = s.pending.hash; s.decision = null;
        s.convergenceRotations = 0;
        if (!replay.conflicts.length && c.revision === s.revision) s.settled = { tips: this.journal.meta.page('frontier', 0, 65).map(r => r.key).sort(), revision: s.revision };
      }
      if (c.kind === 'initial-genesis') { s.baseline = o.state; s.baselinePath = s.pending.path; s.baselineHash = s.pending.hash; s.activeEpoch = o.epochId; }
      if (c.kind === 'transition') s.activeEpoch = c.targetEpoch;
      s.receipt = { path: s.pending.path, hash: s.pending.hash, confirmation: s.pending.confirmation };
    });
  }
  async resumeInitialization(remote) {
    while (this.journal.value.initialization?.length) {
      const raw = this.journal.value.initialization[0], o = this.decode(Buffer.from(raw, 'base64'));
      if (this.journal.value.pending?.phase !== 'RECEIPTED' && this.journal.value.pending) await this.resumePublication(remote);
      if (this.journal.value.receipt?.path !== objectPath(o)) await this.publish(remote, o, { kind: o.type === 1 ? 'descriptor' : 'initial-genesis' });
      await this.journal.transaction(s => { s.initialization.shift(); });
    }
  }
  rotate(history, remote, sourceEpoch, tips) { return this.serial(() => this.rotateInternal(history, remote, sourceEpoch, tips)); }
  async rotateInternal(history, remote, sourceEpoch, tips) {
    history=await history.withRegistry(this.sharedRegistry);
    this.configure(remote, history.cache, history.budget);
    ensure(history.activeEpochs().includes(sourceEpoch) && JSON.stringify([...tips].sort()) === JSON.stringify(history.epochTips(sourceEpoch)), 'v3 stale/incomplete rotation frontier');
    const source = history.nodes[tips[0]], state = await history.merged(tips), refs = tips.map(p => reference(history.nodes[p], history.nodes[p].hash, 2));
    const genesis = { type: 2, datasetId: source.datasetId, objectId: randomUUID(), writerId: this.writerId, epochId: randomUUID(), epochNumber: source.epochNumber + 1, descriptorHash: source.descriptorHash, refs, state };
    const transition = { ...(source.format===4?{format:4}:{}), type: 4, datasetId: source.datasetId, objectId: randomUUID(), writerId: this.writerId, epochId: sourceEpoch, epochNumber: source.epochNumber, descriptorHash: source.descriptorHash, refs: [...refs, reference(genesis, sha(this.encode(genesis)), 5)], state: null };
    await this.journal.transaction(s => { s.rotation = { genesis: this.encode(genesis).toString('base64'), transition: this.encode(transition).toString('base64'), phase: 0 }; });
    await this.resumeRotation(remote);
  }
  async resumeRotation(remote) {
    const rotation = this.journal.value.rotation; if (!rotation) return;
    for (let step = rotation.phase; step < 2; step++) {
      const o = this.decode(Buffer.from(step === 0 ? rotation.genesis : rotation.transition, 'base64'));
      const pending = this.journal.value.pending;
      if (pending && pending.phase !== 'RECEIPTED') await this.resumePublication(remote);
      if (this.journal.value.receipt?.path !== objectPath(o)) await this.publish(remote, o, { kind: step === 0 ? 'checkpoint' : 'transition', targetEpoch: this.decode(Buffer.from(rotation.genesis, 'base64')).epochId });
      await this.journal.transaction(s => { s.rotation.phase = step + 1; });
    }
    await this.journal.transaction(s => { s.rotation = null; });
  }
  async resolve(id, choice) {
    ensure(['local', 'remote'].includes(choice));
    await this.journal.transaction(s => {
      const c = s.conflict; ensure(c?.id === id && c.revision === s.revision, 'v3 stale conflict decision');
      const keys = new Set(c.conflicts.flatMap(v => v.keys));
      const resolved = this.merge(c.base, c.local, c.remote, choice, keys);
      const replay = this.merge(c.base, c.capturedLocal, resolved.state, 'remote', keys);
      ensure(replay.conflicts.every(v => v.keys.every(k => keys.has(k))), 'v3 new local conflict requires separate decision');
      this.journal.meta.put('decision', id, { choice, input: c });
      s.local = replay.state; s.revision++; s.conflict = null; s.decision = { id, choice, remotePaths: c.paths, input: c };
    }, true);
  }
  round(remote, cache, budget, { discoverySteps = 8, auditObjects = 0 } = {}) {
    return this.serial(async () => {
      try {
        await cache.open();
        this.configure(remote, cache, budget);
        if (this.journal.value.initialization?.length) await this.resumeInitialization(remote);
        if (this.journal.value.pending && this.journal.value.pending.phase !== 'RECEIPTED') await this.resumePublication(remote);
        if (this.journal.value.rotation) await this.resumeRotation(remote);
        const s = this.journal.value, b = s.binding; ensure(b);
        const discovery = new V3Discovery(this.journal, remote, b.datasetId); await discovery.scan(discoverySteps);
        const history = await new V3History(this.journal, cache, remote, b.descriptorPath, b.descriptorHash, budget,this.sharedRegistry).open();
        await history.auditObjects(auditObjects);
        if (this.journal.value.receipt) { const r = this.journal.value.receipt; await history.verify(r.path); ensure(history.node(r.path).hash === r.hash, 'v3 receipt identity changed'); }
        if (this.journal.value.baselinePath) { const s = this.journal.value; await history.verify(s.baselinePath); ensure(!s.baselineHash || history.node(s.baselinePath).hash === s.baselineHash, 'v3 baseline identity changed'); }
        for (const epoch of history.activeEpochs().slice(-8)) {
          const listed = await remote.list(epochScope(b.datasetId, epoch));
          discovery.observe(epochScope(b.datasetId, epoch), listed);
        }
        for (let n = 0; n < 32; n++) { const task = this.journal.meta.page('todo', 0, 1)[0]; if (!task) break; await history.verify(task.key); }
        history.drainActivation();
        ensure(this.journal.meta.get('control', 'initialComplete') && !this.journal.meta.page('todo', 0, 1).length, 'v3 initial discovery/validation incomplete');
        ensure(!this.journal.meta.page('error', 0, 1).length, 'v3 unresolved observed history error');
        const epochs = history.activeEpochs(); ensure(epochs.length, 'v3 no activated epoch');
        const tips = history.tips(), current = this.journal.value;
        if (current.conflict?.receipt) {
          if (current.conflict.revision !== current.revision) await this.journal.transaction(s => {
            const c = s.conflict; if (!c?.receipt) return; const forced = new Set(c.conflicts.flatMap(v => v.keys));
            const result = this.merge(c.base, s.local, c.remote, null, forced); this.journal.meta.put('conflictHistory', c.id, c);
            s.conflict = { ...c, id: sha(Buffer.from(c.paths.join('\n') + '\n' + s.revision)), revision: s.revision, local: s.local, capturedLocal: s.local, conflicts: result.conflicts };
          });
          return { status: 'CONFLICT', publicationConfirmed: true, oldHistoryAuditComplete: false };
        }
        if (current.settled?.revision === current.revision && JSON.stringify(current.settled.tips) === JSON.stringify(tips)) return { status: 'OBSERVED', oldHistoryAuditComplete: false };
        // Technical carrier only; all other branches remain in tips and must be causally covered.
        let carrier = current.activeEpoch; if (!epochs.includes(carrier)) carrier = history.nodes[tips[0]].epochId;
        const highest = Math.max(...tips.map(p => history.nodes[p].epochNumber));
        if (!tips.some(p => history.nodes[p].epochId === carrier && history.nodes[p].epochNumber === highest)) carrier = history.nodes[tips.find(p => history.nodes[p].epochNumber === highest)].epochId;
        const parents = tips.filter(p => history.nodes[p].epochId === carrier), other = tips.filter(p => history.nodes[p].epochId !== carrier);
        ensure(parents.length, 'v3 carrier has no frontier');
        const decisionPaths = current.decision?.remotePaths;
        const decisionMatches = decisionPaths?.length && tips.every(p => decisionPaths.some(old => history.nodes[old]?.businessHash === history.nodes[p].businessHash && history.isAncestor(old, p))) && decisionPaths.every(old => tips.some(p => history.nodes[old]?.businessHash === history.nodes[p].businessHash && history.isAncestor(old, p)));
        let remoteState;
        try { remoteState = await history.merged(tips); }
        catch (e) {
          if (!String(e.message).includes('conflict')) throw e;
          if (decisionMatches) remoteState = current.local;
          else {
            budget.work('merge bytes', 64 * 1024 * 1024, tips.reduce((n, p) => n + history.nodes[p].size, 0) * tips.length);
            const base = (await history.value(history.common(tips))).state;
            const localBase = (await history.value(history.common([...tips, ...(current.baselinePath ? [current.baselinePath] : [])]))).state;
            const branches = [];
            for (const path of tips) branches.push({ commitId: history.nodes[path].objectId, path, state: (await history.value(path)).state });
            const plan = { base, localBase, branches }, result = mergePlan(plan, current.local,null,[...this.sharedRegistry.adapters.values()]);
            await this.saveConflict(localBase, current.local, result.remote, result.conflicts, tips, current.revision, plan); return { status: 'CONFLICT' };
          }
        }
        const basePath = current.baselinePath ? history.common([...tips, current.baselinePath]) : history.common(tips);
        ensure(current.baselinePath || !current.unbasedLocal && current.revision === 0, 'v3 local state has no proven baseline');
        const local = current.baselinePath ? current.local : remoteState;
        const base = (await history.value(basePath)).state, result = decisionMatches ? { state: local, conflicts: [] } : this.merge(base, local, remoteState);
        if (result.conflicts.length) { await this.saveConflict(base, current.local, remoteState, result.conflicts, tips, current.revision); return { status: 'CONFLICT' }; }
        if (current.conflict) await this.journal.transaction(s => { if (s.revision === current.revision && s.conflict?.id === current.conflict.id) { this.journal.meta.put('conflictHistory', s.conflict.id, s.conflict); s.conflict = null; } });
        const count = history.epochCount(carrier);
        ensure(count < 256, 'v3 epoch capacity');
        const resolveSourceFirst = !!decisionMatches && parents.length > 1;
        const equivalent = tips.every(p => history.nodes[p].businessHash === history.nodes[tips[0]].businessHash);
        if (other.length && equivalent && same(result.state, remoteState)) {
          await this.journal.transaction(next => { if (next.revision === current.revision) { next.local = result.state; next.baseline = remoteState; next.baselinePath = parents[0]; next.baselineHash = history.node(parents[0]).hash; next.equivalentFrontiers = tips; } });
          return { status: 'EQUIVALENT_FRONTIERS', pendingCausalFrontiers: tips, oldHistoryAuditComplete: false };
        }
        // Equal-generation candidates require a legal higher carrier before LATE can represent them.
        if (!resolveSourceFirst && (other.some(p => history.nodes[p].epochNumber >= history.nodes[parents[0]].epochNumber) || count >= 160)) {
          ensure(count < 255, 'v3 transition capacity');
          if (other.length) await this.journal.transaction(next => { ensure((next.convergenceRotations ?? 0) < 4, 'v3 convergence retry budget; candidates retained'); next.convergenceRotations = (next.convergenceRotations ?? 0) + 1; });
          await this.rotateInternal(history, remote, carrier, parents); return { status: 'ROTATED' };
        }
        if (same(result.state, remoteState) && equivalent && !other.length) {
          await this.journal.transaction(next => { if (next.revision === current.revision) { next.local = result.state; next.baseline = remoteState; next.baselinePath = tips[0]; next.baselineHash = history.node(tips[0]).hash; next.settled = { tips, revision: next.revision }; } });
          return { status: 'OBSERVED', oldHistoryAuditComplete: false };
        }
        ensure(count < 192 || resolveSourceFirst && count < 255, 'v3 resolved source needs room for commit and transition');
        const refs = parents.map(p => reference(history.nodes[p], history.nodes[p].hash, 1));
        const eligibleLate = other.filter(p => history.nodes[p].epochNumber < history.nodes[parents[0]].epochNumber);
        if (eligibleLate.length) {
          const common = history.common([...parents, ...eligibleLate]); ensure(history.nodes[common].epochNumber < history.nodes[parents[0]].epochNumber, 'v3 bridge baseline generation');
          refs.push(...eligibleLate.map(p => reference(history.nodes[p], history.nodes[p].hash, 3)), reference(history.nodes[common], history.nodes[common].hash, 4));
        }
        const source = history.nodes[parents[0]], o = { type: 3, datasetId: b.datasetId, objectId: randomUUID(), writerId: this.writerId, epochId: carrier, epochNumber: source.epochNumber, descriptorHash: b.descriptorHash, refs, state: result.state };
        await this.publish(remote, o, { kind: 'commit', local: current.local, revision: current.revision, decision: current.decision }); return { status: this.journal.value.conflict?.receipt ? 'CONFLICT' : 'PUBLISHED', publicationConfirmed: true, oldHistoryAuditComplete: false };
      } catch (e) { await this.journal.transaction(s => { s.error = String(e.message).slice(0, 1024); }); throw e; }
    });
  }
  async saveConflict(base, local, remote, conflicts, paths, revision, plan = null) {
    await this.journal.transaction(s => { ensure(s.revision === revision, 'v3 local edit changed; replan conflict'); const id = sha(Buffer.from(paths.join('\n') + '\n' + s.revision)); if (s.conflict && s.conflict.id !== id) this.journal.meta.put('conflictHistory', s.conflict.id, s.conflict); s.conflict = { id, revision: s.revision, base, local, remote, capturedLocal: s.local, conflicts, paths, plan }; });
  }
}
