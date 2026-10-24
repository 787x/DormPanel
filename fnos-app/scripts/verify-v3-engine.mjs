// Explicit isolated HTTPS probe. stdin: credentials + private evidence path. Never reads v2.
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { mkdtemp, readFile, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { WebDav } from '../src/webdav.mjs';
import { V3WebDavStore, Budget } from '../src/v3-store.mjs';
import { V3Journal, V3Publisher } from '../src/v3-transaction.mjs';
import { openV3Workspace } from '../src/v3-workspace.mjs';
import { V3History, reference } from '../src/v3-history.mjs';
import { encode, decode, sha } from '../src/v3-protocol.mjs';
import { objectPath } from '../src/v3-store.mjs';
import { IndexedV3Cache } from '../src/v3-index.mjs';
import { V3Engine } from '../src/v3-engine.mjs';
import { emptyState } from '../src/merge.mjs';
let text = ''; for await (const b of process.stdin) { text += b; assert(text.length < 32768); }
const input = JSON.parse(text), mode = input.mode ?? 'init';
let config, binding, origin;
if (mode === 'init') {
  const probe = new URL(`pr54-engine-${randomUUID()}/`, input.url), t = new WebDav(input); t.base = probe;
  await t.request('MKCOL', probe, 65536); config = { url: probe.href, username: input.username, password: input.password };
} else { ({ config, binding, origin } = input.config ? input : JSON.parse(await readFile(input.privatePath, 'utf8'))); }
const root = await mkdtemp(join(tmpdir(), 'dp-pr54-probe-')); let writer = randomUUID(), journal;
if (mode === 'receive-publish') { const adapter = await openV3Workspace(root, () => {}); journal = adapter.journal; writer = adapter.writerId; }
else journal = await new V3Journal(root).open();
let engine = new V3Engine(journal, writer), latePath = null;
if (mode === 'receive-publish') {
  assert(origin); const lateRoot = await mkdtemp(join(tmpdir(), 'dp-pr54-late-')), late = await new V3Journal(lateRoot).open(), remote = new V3WebDavStore(config, new Budget());
  const oldRaw = await remote.read(origin), old = decode(oldRaw), o = { ...old, type: 3, objectId: randomUUID(), writerId: randomUUID(), refs: [reference(old, sha(oldRaw), 1)], state: { ...old.state, entries: [...old.state.entries, { id: 'late', title: 'late writer', day: 5, startMinute: 780, endMinute: 840, location: '' }] } };
  latePath = objectPath(o); const p = new V3Publisher(late, remote);
  await p.stage(latePath, encode(o), { baseline: origin, local: o.state }); assert.equal(await p.resume(), 'READBACK_CONFIRMED'); await p.receipt(s => { s.local = o.state; s.baselinePath = latePath; }); await late.close();
}
if (mode === 'init') binding = await engine.initialize(new V3WebDavStore(config, new Budget()), { ...emptyState(), entries: [{ id: 'node', title: 'Node initial', day: 1, startMinute: 480, endMinute: 540, location: '' }] });
else { const { decode } = await import('../src/v3-protocol.mjs'); await engine.bind(decode(await new V3WebDavStore(config, new Budget()).read(binding.descriptorPath)), binding.descriptorHash); }
for (let n = 0; n < 16; n++) {
  const budget = new Budget(); try { await engine.round(new V3WebDavStore(config, budget), new IndexedV3Cache(root, budget), budget, { discoverySteps: 8 }); }
  catch (e) { if (!e.message.includes('initial discovery/validation incomplete')) throw e; }
}
if (mode !== 'init') assert(journal.value.local.entries.some(e => e.id === 'kotlin'), 'Kotlin edit missing');
let recoveryPuts = 0;
if (mode === 'receive-publish') {
  await engine.edit({ ...journal.value.local, entries: [...journal.value.local.entries.filter(e => e.id !== 'fnos'), { id: 'fnos', title: 'fnOS Node 22 ' + randomUUID(), day: 3, startMinute: 660, endMinute: 720, location: '' }] });
  const budget = new Budget(), remote = new V3WebDavStore(config, budget), put = remote.putOnce.bind(remote);
  remote.putOnce = async (p, raw) => { recoveryPuts++; await put(p, raw); throw Error('injected lost PUT response'); };
  await assert.rejects(engine.round(remote, new IndexedV3Cache(root, budget), budget), /lost PUT response/);
  await journal.close(); const adapter = await openV3Workspace(root, () => {}); journal = adapter.journal; assert.equal(adapter.writerId, writer); engine = new V3Engine(journal, writer);
  const recoveryBudget = new Budget(), recovery = new V3WebDavStore(config, recoveryBudget), recoverPut = recovery.putOnce.bind(recovery); recovery.putOnce = async (p, b) => { recoveryPuts++; return recoverPut(p, b); };
  await engine.round(recovery, new IndexedV3Cache(root, recoveryBudget), recoveryBudget);
  assert.equal(recoveryPuts, 1); assert.equal(journal.value.pending.phase, 'RECEIPTED');
}
if (mode === 'init') await writeFile(input.privatePath, JSON.stringify({ config, binding, origin: journal.value.baselinePath, isolatedRoot: root }), { mode: 0o600 });
let lateCausallyCovered = null;
if (latePath) {
  const budget = new Budget(), h = await new V3History(journal, new IndexedV3Cache(root, budget), new V3WebDavStore(config, budget), binding.descriptorPath, binding.descriptorHash, budget).open();
  lateCausallyCovered = h.isAncestor(latePath, journal.value.baselinePath); assert(lateCausallyCovered); assert(journal.value.local.entries.some(e => e.id === 'late'));
}
const budget = new Budget(), remote = new V3WebDavStore(config, budget), read = remote.read.bind(remote); let incrementalGETs = 0;
remote.read = async p => { incrementalGETs++; return read(p); }; await engine.round(remote, new IndexedV3Cache(root, budget), budget); assert.equal(incrementalGETs, 0);
console.log(JSON.stringify({ mode, result: 'PASS', isolatedPath: new URL(config.url).pathname, entries: journal.value.local.entries.map(e => e.id), epochs: journal.meta.count('epoch'), recoveryPuts, lateCausallyCovered, incrementalGETs }));
await journal.close();
