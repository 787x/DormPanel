import test from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { productivityFixture } from '../test-fixtures/productivity-fixture.mjs';
import { businessHash } from '../src/v3-protocol.mjs';
import { productivityConflict } from '../src/productivity.mjs';
import { encodeProductivity, MEMO } from '../src/shared-productivity.mjs';
import { durableRecords } from '../src/shared-records.mjs';
import { emptyState } from '../src/merge.mjs';

test('双 Writer 独立合并、关联删除冲突、三方内容和过期决定', async t => {
  const url = process.env.PR89_CONFLICT_DAV_URL;
  if (url) assert.match(url, /^http:\/\/192[.]168[.]3[.]\d+:[0-9]+\/pr89-[0-9a-f-]{36}\/$/);
  const local = await productivityFixture(t, { url, format:0 });
  const remote = await productivityFixture(t, { url, format:0, transport:local.transport });
  const initialization = await local.post('/api/sync', { ...local.ctx(), createShared:true });
  assert.equal(initialization.status, 200, JSON.stringify(initialization.data));
  const save = async (f, action, input) => {
    const response = await f.edit(action, input);
    assert.equal(response.data.writeApplied, true, JSON.stringify(response.data));
  };
  const id = randomUUID, list = id(), taskA = id(), taskB = id(), memo = id(), newTask = id();
  await save(local, 'todo.createList', { id:list, name:'隔离清单' });
  await save(local, 'todo.createTask', { id:taskA, listId:list, text:'甲' });
  await save(local, 'todo.createTask', { id:taskB, listId:list, text:'乙' });
  await save(local, 'memo.create', { id:memo, title:'隔离备忘', body:'共同基线\r\n' });
  await local.settle();
  assert.equal((await remote.joinDataset()).status, 200);
  await remote.settle();
  const converge = async () => {
    await local.settle(); await remote.settle(); await local.settle();
    assert.equal(businessHash(local.app.db.frame.local), businessHash(remote.app.db.frame.local));
  };
  await save(local, 'todo.editTask', { id:taskA, text:'甲本机编辑' });
  await save(remote, 'todo.setCompleted', { id:taskB, completed:true });
  await converge();
  assert.equal((await remote.read('todo.task', { id:taskA })).data.task.text, '甲本机编辑');
  assert.equal((await local.read('todo.task', { id:taskB })).data.task.completed, true);

  async function showConflict() {
    await remote.settle();
    assert.equal((await local.settle()).conflict, true);
    const response = (await local.read('productivity.conflicts')).data;
    assert(response.token && response.rows.length === 1);
    const rows = [...response.rows];
    for (let offset = 1; offset < response.total; offset++) rows.push(...(await local.read('productivity.conflicts', { offset })).data.rows);
    assert.equal((await local.edit('memo.rename', { id:memo, title:'禁止改写' })).data.writeApplied, false);
    const { token } = response;
    assert.equal((await local.edit('productivity.resolve', { token:local.ctx().workspaceId+':'+ '0'.repeat(64), choice:'remote' })).data.writeApplied, false);
    assert.equal((await local.edit('productivity.resolve', { token, choice:'remote' }, { ...local.ctx(), version:response.version-1 })).data.writeApplied, false);
    return { token, rows };
  }
  await save(local, 'todo.editTask', { id:taskA, text:'同一任务的本机修改' });
  await save(remote, 'todo.editTask', { id:taskA, text:'同一任务的远端修改' });
  let conflict = await showConflict();
  assert.deepEqual(['base','local','remote'].map(side => conflict.rows[0][side].text),
    ['甲本机编辑','同一任务的本机修改','同一任务的远端修改']);
  await save(local, 'productivity.resolve', { token:conflict.token, choice:'local' });
  await converge();
  assert.equal((await remote.read('todo.task', { id:taskA })).data.task.text, '同一任务的本机修改');

  await save(local, 'memo.edit', { id:memo, body:'本机正文\n' });
  await save(remote, 'memo.edit', { id:memo, body:'远端正文\r\n🙂' });
  conflict = await showConflict();
  assert.deepEqual(['base','local','remote'].map(side => conflict.rows[0][side].body), ['共同基线\r\n','本机正文\n','远端正文\r\n🙂']);
  const target = { ...local.ctx(), action:'productivity.resolve', token:conflict.token, choice:'remote' };
  const expectation = await local.post('/api/productivity/check', target);
  assert.equal(expectation.status, 200);
  const verify = () => local.post('/api/productivity/check', {
    workspaceId:target.workspaceId, datasetId:target.datasetId, expectation:expectation.data,
  });
  assert.deepEqual((await verify()).data.decisionState, 'original');
  assert.equal((await verify()).data.match, false);
  await save(local, 'productivity.resolve', { token:conflict.token, choice:'remote' });
  assert.equal((await verify()).data.recordedChoice, 'remote');
  assert.equal((await verify()).data.match, true);
  await converge();

  await save(local, 'memo.delete', { id:memo });
  await save(remote, 'memo.edit', { id:memo, body:'与删除冲突的编辑' });
  conflict = await showConflict();
  assert.equal(conflict.rows[0].local, null);
  assert.equal(conflict.rows[0].remote.body, '与删除冲突的编辑');
  await save(local, 'productivity.resolve', { token:conflict.token, choice:'remote' });
  await converge();

  await save(local, 'todo.deleteList', { id:list, taskIds:[taskA, taskB] });
  await save(remote, 'todo.createTask', { id:newTask, listId:list, text:'与删除冲突的新增' });
  conflict = await showConflict();
  assert(conflict.rows.some(row => row.base?.id === list && row.local === null && row.remote?.id === list));
  assert(conflict.rows.some(row => row.remote?.id === newTask && row.base === null && row.local === null));
  await save(local, 'productivity.resolve', { token:conflict.token, choice:'remote' });
  await converge();
  assert.deepEqual((await local.read('todo.get', { id:list })).data.taskIds, [taskA, taskB, newTask]);
  console.log(JSON.stringify({ transport:url ? 'WebDAV' : 'fixture', datasetId:local.ctx().datasetId,
    businessHash:businessHash(local.app.db.frame.local) }));
});

test('完整范围保留未知 schema、真正不存在及未展示的业务组', () => {
  const id = randomUUID(), key = `shared:${MEMO}/${id}`;
  const encoded = encodeProductivity({ typeId:MEMO, id, title:'标题', body:'正文' });
  const base = { ...emptyState(), sharedRecords:durableRecords([encoded]) };
  const local = { ...base, sharedRecords:[] };
  const remote = { ...base, sharedRecords:durableRecords([{ ...encoded, schemaVersion:2 }]) };
  const frame = { v3:{ conflict:{ id:'fixture', conflicts:[{ keys:[key] }, { keys:['class:removed'] }], base, local, remote } } };
  const partial = productivityConflict(frame), full = productivityConflict(frame, true);
  assert.equal(partial.hidden, 1);
  assert.equal(full.rows.length, 2);
  assert.equal(full.groups, 2);
  assert.equal(full.complete, false);
  assert.equal(full.rows[0].local, null);
  assert.deepEqual(full.rows[0].unknownSides, ['remote']);
  assert.equal(full.rows[1].typeId, 'class');
  assert.deepEqual(full.rows[1].unknownSides, []);
});

test('完整范围分页保留大型课程记录的全部可见内容', () => {
  const state = title => ({ ...emptyState(), entries:[{
    id:'long', title, day:1, startMinute:480, endMinute:540, location:'',
  }] });
  const title = '课程"🙂\n'.repeat(20000);
  const conflict = { id:'fixture', conflicts:[{ keys:['class:long'] }],
    base:state('共同基线'), local:state(title), remote:state(title.toUpperCase()) };
  const result = productivityConflict({ v3:{ conflict } }, true);
  assert.equal(result.complete, true);
  assert(result.rows.length > 1);
  assert.deepEqual(result.rows.map(row => row.part), result.rows.map((_, index) => index+1));
  assert.equal(JSON.parse(result.rows.map(row => row.local).join('')).title, title);
  assert(result.rows.every(row => Buffer.byteLength(JSON.stringify(row)) < 1024 * 1024));
});
