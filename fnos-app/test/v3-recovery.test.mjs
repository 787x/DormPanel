import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { fork } from 'node:child_process';
import { once } from 'node:events';
import { setTimeout as pause } from 'node:timers/promises';
import { V3Journal, V3Publisher } from '../src/v3-transaction.mjs';
import { openV3Workspace } from '../src/v3-workspace.mjs';
import { V3Engine } from '../src/v3-engine.mjs';
import { encode, ZERO, ZERO_HASH } from '../src/v3-protocol.mjs';
import { objectPath } from '../src/v3-store.mjs';
import { V3LocalStore, Budget } from '../src/v3-store.mjs';
import { IndexedV3Cache } from '../src/v3-index.mjs';
import { LocalGrants } from '../src/local.mjs';
const descriptor = () => ({ type: 1, datasetId: randomUUID(), objectId: randomUUID(), writerId: randomUUID(), epochId: ZERO, epochNumber: 0, descriptorHash: ZERO_HASH, refs: [], state: null });
const scenarios = JSON.parse(await readFile(new URL('../../schedule-core/src/test/resources/sync-v3/recovery-scenarios.json', import.meta.url), 'utf8'));
for (const scenario of scenarios) test('kill: ' + scenario.phase + ' 后重新获得拥有权、精确恢复及 PUT 次数', async () => {
  const phase = scenario.phase;
  const root = await mkdtemp(join(tmpdir(), 'v3-kill-')), o = descriptor(), raw = encode(o), path = objectPath(o);
  await writeFile(join(root, 'input.json'), JSON.stringify({ path, bytes: raw.toString('base64') }));
  const worker = fork(new URL('./fixtures/v3-recovery-worker.mjs', import.meta.url), [root, phase], { stdio: ['ignore', 'ignore', 'pipe', 'ipc'] });
  try {
    let reached = false;
    for (let n = 0; n < 500; n++) { try { reached = (await readFile(join(root, 'boundary'), 'utf8')) === phase; } catch {} if (reached) break; await pause(20); }
    assert(reached, phase + ' boundary not reached');
    const lockBytes = await readFile(join(root, 'v3-owner.lock')); await assert.rejects(new V3Journal(root).open(), /writer|locked/); assert.deepEqual(await readFile(join(root, 'v3-owner.lock')), lockBytes);
    const exited = once(worker, 'exit'); worker.kill('SIGKILL'); await exited;
    const journal = await new V3Journal(root).open(); assert.equal(journal.value.local.userEdit, 'saved'); assert.equal(journal.value.pending.bytes, raw.toString('base64'));
    let puts = 0; try { puts = Number(await readFile(join(root, 'puts'), 'utf8')); } catch {}
    const remote = { read: async () => { try { return await readFile(join(root, 'remote')); } catch { return null; } }, putOnce: async (p, bytes) => { puts++; await writeFile(join(root, 'remote'), bytes); } };
    const publisher = new V3Publisher(journal, remote), result = await publisher.resume();
    if (phase === 'ATTEMPTED') { assert.equal(result, 'UNCERTAIN'); assert.equal(puts, 0); }
    else { if (result === 'READBACK_CONFIRMED') await publisher.receipt(s => { s.applied = (s.applied ?? 0) + 1; }); assert.equal(journal.value.applied, 1); assert.equal(puts, 1); }
    assert.equal(journal.value.pending.capture.exactCapture, 'saved'); await journal.close();
    assert.equal(puts, scenario.puts);
  } finally { worker.kill('SIGKILL'); }
});
test('workspace: 复用 revision/权限保护，课程和 v3 receipt 同一持久 Workspace', async () => {
  const root = await mkdtemp(join(tmpdir(), 'v3-workspace-')); let allowed = true;
  let adapter = await openV3Workspace(root, () => assert(allowed, 'denied'));
  const engine = new V3Engine(adapter.journal, adapter.writerId), o = descriptor(); await engine.bind(o, (await import('../src/v3-protocol.mjs')).sha(encode(o)));
  const old = adapter.journal.value.revision;
  await adapter.workspace.tx(f => { f.local.entries.push({ id: 'user', title: 'user', day: 1, startMinute: 480, endMinute: 540, location: '' }); });
  assert.equal(adapter.journal.value.revision, old + 1); assert.equal(adapter.journal.value.local.entries[0].id, 'user');
  allowed = false; await assert.rejects(engine.edit(adapter.journal.value.local), /denied/); allowed = true;
  await adapter.journal.close(); adapter = await openV3Workspace(root, () => assert(allowed)); assert.equal(adapter.journal.value.local.entries[0].id, 'user'); await adapter.journal.close();
});
test('local-engine: 官方授权目录复用持久协调器，丢失公开回执不再次 link', { skip: process.platform !== 'linux' }, async () => {
  const source = await mkdtemp(join(tmpdir(), 'v3-local-source-')), root = await mkdtemp(join(tmpdir(), 'v3-local-writer-')), grants = new LocalGrants([{ id: 'isolated', path: source }]);
  const selected = await grants.directory('isolated'), config = { rootId: 'isolated', relative: '', directoryIdentity: selected.stamp, protected: true }; await selected.fd.close();
  let adapter = await openV3Workspace(root, () => {}), writer = adapter.writerId, engine = new V3Engine(adapter.journal, writer);
  const make = b => new V3LocalStore(config, grants, b); await engine.initialize(make(new Budget()));
  const round = async () => { const b = new Budget(); return engine.round(make(b), new IndexedV3Cache(root, b), b, { discoverySteps: 32 }); };
  for (let n = 0; n < 3; n++) { try { await round(); } catch (e) { if (!e.message.includes('incomplete')) throw e; } }
  await engine.edit({ events: [], entries: [{ id: 'local', title: 'local', day: 1, startMinute: 480, endMinute: 540, location: '' }], sources: [], imported: [], profiles: [], customProfileKeys: [], dayAdjustments: [], classOverrides: [] });
  const budget = new Budget(), remote = make(budget), put = remote.putOnce.bind(remote); let writes = 0;
  remote.createForIsolatedTest = () => assert.fail('production engine must not invoke test primitive'); remote.putOnce = async (p, raw) => { writes++; await put(p, raw); throw Error('lost local publication response'); };
  await assert.rejects(engine.round(remote, new IndexedV3Cache(root, budget), budget), /lost local/); await adapter.journal.close();
  adapter = await openV3Workspace(root, () => {}); engine = new V3Engine(adapter.journal, writer);
  const recoveryBudget = new Budget(), recovery = make(recoveryBudget), recoverPut = recovery.putOnce.bind(recovery); recovery.putOnce = async (p, raw) => { writes++; return recoverPut(p, raw); };
  await engine.round(recovery, new IndexedV3Cache(root, recoveryBudget), recoveryBudget, { discoverySteps: 32 }); assert.equal(writes, 1); assert.equal(adapter.journal.value.local.entries[0].id, 'local'); assert.equal(adapter.journal.value.pending.phase, 'RECEIPTED'); await adapter.journal.close();
});
