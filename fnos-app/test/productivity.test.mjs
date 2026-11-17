import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile, mkdir, mkdtemp } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { emptyState, merge } from '../src/merge.mjs';
import { Registry, encode, wireRecords, durableRecords } from '../src/shared-records.mjs';
import { LIST, TASK, MEMO, encodeProductivity, projectProductivity, productivityConflictRows } from '../src/shared-productivity.mjs';
import { sharedTypes } from '../src/shared-types.mjs';
import { editProductivity } from '../src/productivity.mjs';
import { businessHash, stateBytes } from '../src/v3-protocol.mjs';
import { openV3Workspace } from '../src/v3-workspace.mjs';
import { V3Engine } from '../src/v3-engine.mjs';
import { Budget } from '../src/v3-store.mjs';
import { IndexedV3Cache } from '../src/v3-index.mjs';
import { sqliteSnapshot, verifyV3Backup } from '../src/v3-backup.mjs';

const vector = JSON.parse(await readFile(new URL('../../test-vectors/sync-v3-productivity/vector.json', import.meta.url), 'utf8'));
const [list, task, memo, emptyMemo] = vector.records;
const rows = vector.records.map(encodeProductivity);
const base = { ...emptyState(), sharedRecords: durableRecords(rows) };
const view = state => projectProductivity(wireRecords(state.sharedRecords));
const create = value => ({ action: 'create', value });

test('productivity canonical records, RAW, data hash and strict budgets match Kotlin', () => {
  assert.equal(encode(rows).toString('hex'), vector.recordBlockHex);
  assert.equal(stateBytes(base).toString('hex'), vector.rawHex);
  assert.equal(businessHash(base), vector.businessHash);
  assert.deepEqual(view(base).memos, [memo, emptyMemo]);
  const registry = new Registry(sharedTypes);
  for (const bad of [[...rows, rows[0]], rows.slice(1), rows.map(r => r.typeId === TASK ? { ...r, required: false } : r),
    rows.map(r => r.typeId === MEMO ? { ...r, payload: Buffer.concat([r.payload, Buffer.of(0)]) } : r)]) {
    assert.throws(() => registry.validate(bad));
  }
  for (const text of [' \n\t', '甲'.repeat(1366), '\ud800', 'a\0b']) assert.throws(() => encodeProductivity({ ...task, text }));
  assert.throws(() => registry.validate(Array.from({ length: 33 }, () =>
    encodeProductivity({ ...memo, id: randomUUID(), body: 'a'.repeat(32768) }))));
  assert.throws(() => registry.validate(Array.from({ length: 129 }, () =>
    encodeProductivity({ ...list, id: randomUUID() }))));
  const oldAdapters = sharedTypes.filter(a => a.typeId.startsWith('course.'));
  const oldRegistry = new Registry(oldAdapters);
  oldRegistry.validate(rows);
  assert.throws(() => oldRegistry.writable(rows), /Unsupported required capability/);
  oldRegistry.writable(rows.filter(r => r.typeId === MEMO));
  assert.throws(() => registry.validate(rows.map(r => r.typeId === TASK ? { ...r, dependencies: [] } : r)));
  const oldEdit = { ...base, entries: [{id: 'x', title: '旧客户端新课程', day: 1, startMinute: 480, endMinute: 540, location: ''}] };
  assert.deepEqual(merge(base, oldEdit, base, null, new Set(), oldAdapters).state.sharedRecords, base.sharedRecords);
});

test('productivity independent merge, same edits and delete-vs-edit retain both sides', () => {
  const secondList = { ...list, id: '00000000-0000-0000-0000-000000000005', name: '第二清单' };
  const secondTask = { ...task, id: '00000000-0000-0000-0000-000000000006' };
  const source = editProductivity(editProductivity(base, create(secondList)), create(secondTask));
  const local = editProductivity(editProductivity(source, { action: 'setTaskCompleted', id: task.id, completed: false }),
    { action: 'editMemo', id: memo.id, body: '本地\n第二行' });
  const remote = editProductivity(editProductivity(source, { action: 'editTask', id: secondTask.id, text: '远端' }),
    { action: 'renameMemo', id: emptyMemo.id, title: '远端改名' });
  const result = merge(source, local, remote);
  assert.equal(result.conflicts.length, 0);
  const combined = view(result.state);
  assert.deepEqual(combined.lists, [list, secondList]);
  assert.deepEqual(combined.tasks, [{...task, completed: false}, {...secondTask, text: '远端'}]);
  assert.equal(combined.memos[0].body, '本地\n第二行');
  assert.equal(combined.memos[1].title, '远端改名');
  assert.equal(merge(source, local, local).conflicts.length, 0);
  const different = editProductivity(editProductivity(source, { action: 'editMemo', id: memo.id, body: '另一份\r\n' }),
    { action: 'editTask', id: task.id, text: '同一任务的远端修改' });
  const conflict = merge(source, local, different);
  const [memoConflict, taskConflict] = productivityConflictRows(new Set(conflict.conflicts.flatMap(c => c.keys)),
    [source, local, different].map(s => wireRecords(s.sharedRecords)));
  assert.equal(memoConflict.local.body, '本地\n第二行');
  assert.equal(memoConflict.remote.body, '另一份\r\n');
  assert.equal(taskConflict.local.completed, false);
  assert.equal(taskConflict.remote.text, '同一任务的远端修改');
  const taskDeleted = editProductivity(source, { action: 'deleteTask', id: task.id });
  assert.equal(merge(source, taskDeleted, local).conflicts.length, 1);
  const memoDeleted = editProductivity(source, { action: 'deleteMemo', id: memo.id });
  assert.equal(merge(source, memoDeleted, different).conflicts.length, 1);
  assert.throws(() => editProductivity(base, { action: 'deleteList', id: list.id }), /所有任务/);
  const deleted = editProductivity(base, { action: 'deleteList', id: list.id, taskIds: [task.id] });
  const added = editProductivity(base, create(secondTask));
  assert.equal(merge(base, deleted, added).conflicts[0].keys.length, 3);
  assert.equal(view(merge(base, deleted, added, 'remote').state).tasks.length, 2);
});

test('Workspace offline save, stale revision, permission, rollback, reopen, exact readback and backup', async t => {
  const root = await mkdtemp(join(tmpdir(), 'pr87-node-'));
  const objects = new Map();
  const remote = {
    async read(path) { return objects.get(path) ?? null; },
    async putOnce(path, raw) { assert(!objects.has(path)); objects.set(path, Buffer.from(raw)); },
    async list(scope) {
      return [...new Set([...objects.keys()].filter(p => p.startsWith(scope)).map(p => {
        const suffix = p.slice(scope.length), slash = suffix.indexOf('/');
        return slash < 0 ? p : scope + suffix.slice(0, slash + 1);
      }))].sort();
    },
  };
  let allowed = true;
  const guard = () => assert(allowed, 'denied');
  const source = join(root, 'source');
  let adapter = await openV3Workspace(source, guard);
  t.after(() => adapter.journal.close());
  let engine = new V3Engine(adapter.journal, adapter.writerId);
  const round = (transport = remote) => {
    const budget = new Budget();
    return engine.round(transport, new IndexedV3Cache(source, budget), budget, { discoverySteps: 32 });
  };
  const save = edit => adapter.workspace.saveProductivity(edit, adapter.workspace.productivity());
  await assert.rejects(adapter.workspace.saveProductivity(create(list), { datasetId: randomUUID(), revision: 0 }));
  await engine.initialize(remote, { ...emptyState(), sharedRecords: [] });
  const stale = adapter.workspace.productivity();
  await save(create(list));
  await assert.rejects(adapter.workspace.saveProductivity(create(task), stale), /版本过期/);
  allowed = false;
  await assert.rejects(save(create(task)), /denied/);
  allowed = true;
  await save(create(task));
  await save(create(memo));
  const original = adapter.workspace.store.commitWorkspace;
  adapter.workspace.store.commitWorkspace = (prepare, context) => original(() => { prepare(); throw Error('rollback'); }, context);
  await assert.rejects(save({ action: 'renameList', id: list.id, name: '失败' }), /rollback/);
  adapter.workspace.store.commitWorkspace = original;
  assert.equal(adapter.workspace.productivity().lists[0].name, list.name);
  await round({ ...remote, list: async () => { throw Error('offline'); } }).then(
    () => assert.fail('expected offline failure'), error => assert.match(error.message, /offline/));
  assert.equal(adapter.workspace.productivity().memos[0].body, memo.body);
  let uncertainPath;
  await assert.rejects(round({
    ...remote,
    async putOnce(path, raw) { await remote.putOnce(path, raw); uncertainPath = path; },
    async read(path) { if (path === uncertainPath) throw Error('lost readback'); return remote.read(path); },
  }));
  assert(uncertainPath);
  assert.equal(adapter.journal.value.pending.path, uncertainPath);
  await adapter.journal.close();
  adapter = await openV3Workspace(source, guard);
  engine = new V3Engine(adapter.journal, adapter.writerId);
  assert.deepEqual(adapter.workspace.productivity().tasks, [task]);
  await save({ action: 'setTaskCompleted', id: task.id, completed: false });
  await round();
  await round();
  assert.equal(adapter.workspace.productivity().tasks[0].completed, false);
  assert.equal(adapter.journal.value.pending.phase, 'RECEIPTED');
  assert.equal(adapter.journal.value.receipt.hash, adapter.journal.value.baselineHash);

  const id = randomUUID(), relative = `workspaces/${id}/v3/v3-state-v2.sqlite`, restored = join(root, 'backup', 'workspaces', id, 'v3');
  await mkdir(restored, { recursive: true });
  await sqliteSnapshot(join(source, 'v3-state-v2.sqlite'), join(root, 'backup', relative));
  const identity = adapter.workspace.store.identity;
  assert((await verifyV3Backup(join(root, 'backup'), id, identity, [{name: relative}])).privateStateComplete);
  const restore = await openV3Workspace(restored, guard, identity);
  t.after(() => restore.journal.close());
  assert.deepEqual(restore.workspace.productivity(), adapter.workspace.productivity());

  const legacy = await openV3Workspace(join(root, 'legacy'), guard);
  t.after(() => legacy.journal.close());
  await new V3Engine(legacy.journal, legacy.writerId).initialize(remote, emptyState());
  assert.match(legacy.workspace.productivity().writeBlock, /DPV3\/4/);
  await assert.rejects(legacy.workspace.saveProductivity(create(list), legacy.workspace.productivity()), /DPV3\/4/);
});
