import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { V3Journal } from '../src/v3-transaction.mjs';
import { V3Engine } from '../src/v3-engine.mjs';
import { V3History } from '../src/v3-history.mjs';
import { IndexedV3Cache } from '../src/v3-index.mjs';
import { Budget } from '../src/v3-store.mjs';
import { decode } from '../src/v3-protocol.mjs';
import { emptyState } from '../src/merge.mjs';

class Remote {
  objects = new Map(); puts = new Map();
  async read(path) { return this.objects.get(path) ?? null; }
  async putOnce(path, raw) { assert(!this.objects.has(path)); this.puts.set(path, (this.puts.get(path) ?? 0) + 1); this.objects.set(path, raw); }
  async list(scope) { return [...new Set([...this.objects.keys()].filter(p => p.startsWith(scope)).map(p => { const tail = p.slice(scope.length), slash = tail.indexOf('/'); return scope + (slash < 0 ? tail : tail.slice(0, slash + 1)); }))].sort(); }
}
for (const boundary of ['index-before-commit', 'index-after-commit', 'genesis-index', 'transition-index', 'activation-index', 'active-receipt']) test('index-fault: ' + boundary + ' 精确候选、祖先索引和业务回执恢复', async () => {
  const root = await mkdtemp(join(tmpdir(), 'v3-index-boundary-')), writer = randomUUID(), remote = new Remote(); let journal = await new V3Journal(root).open(), engine = new V3Engine(journal, writer);
  const binding = await engine.initialize(remote); const round = async () => { const b = new Budget(); return engine.round(remote, new IndexedV3Cache(root, b), b, { discoverySteps: 32 }); };
  for (let n = 0; n < 3; n++) { try { await round(); } catch (e) { if (!e.message.includes('incomplete')) throw e; } }
  const state = { ...emptyState(), entries: [{ id: 'saved', title: 'saved', day: 1, startMinute: 480, endMinute: 540, location: '' }] };
  await engine.edit(state); const rotating = !boundary.startsWith('index-'); if (rotating) await round();
  const oldEpoch = journal.value.activeEpoch; let armed = true, candidatePath;
  journal.meta.fault = point => {
    if (!armed) return; const frame = journal.meta.get('state', 'frame'), p = frame?.pending; if (!p) return;
    const o = decode(Buffer.from(p.bytes, 'base64')), indexed = !!journal.meta.get('node', p.path), target = o.refs.find(r => r.role === 5);
    let match = false;
    if (boundary.startsWith('index-')) match = o.type === 3 && p.phase === 'READBACK_CONFIRMED' && indexed && point === (boundary === 'index-before-commit' ? 'before-commit' : 'after-commit');
    if (boundary === 'genesis-index') match = o.type === 2 && p.phase === 'READBACK_CONFIRMED' && indexed && point === 'after-commit';
    if (boundary === 'transition-index') match = o.type === 4 && p.phase === 'READBACK_CONFIRMED' && indexed && !journal.meta.get('epoch', target.epochId).active && point === 'after-commit';
    if (boundary === 'activation-index') match = o.type === 4 && p.phase === 'READBACK_CONFIRMED' && journal.meta.get('epoch', target.epochId).active && point === 'after-commit';
    if (boundary === 'active-receipt') match = o.type === 4 && p.phase === 'RECEIPTED' && point === 'after-commit';
    if (match) { armed = false; candidatePath = p.path; throw Error('injected boundary ' + boundary); }
  };
  if (rotating) {
    const b = new Budget(), h = await new V3History(journal, new IndexedV3Cache(root, b), remote, binding.descriptorPath, binding.descriptorHash, b).open();
    await assert.rejects(engine.rotate(h, remote, oldEpoch, h.epochTips(oldEpoch)));
  } else await assert.rejects(round());
  assert.equal(armed, false); await journal.close(); journal = await new V3Journal(root).open(); engine = new V3Engine(journal, writer);
  assert.equal(journal.value.local.entries[0].id, 'saved'); assert.equal(remote.puts.get(candidatePath), 1);
  if (boundary === 'index-before-commit') assert.equal(journal.meta.get('node', candidatePath), null); else assert(journal.meta.get('node', candidatePath));
  if (rotating && boundary !== 'active-receipt') assert.equal(journal.value.activeEpoch, oldEpoch);
  await round(); await round(); assert.equal(remote.puts.get(candidatePath), 1); assert.equal(journal.value.local.entries[0].id, 'saved');
  const receipt = journal.value.receipt; assert.equal(journal.meta.get('node', receipt.path).hash, receipt.hash); assert.equal(journal.value.pending.phase, 'RECEIPTED');
  if (rotating) assert.notEqual(journal.value.activeEpoch, oldEpoch); await journal.close();
});
