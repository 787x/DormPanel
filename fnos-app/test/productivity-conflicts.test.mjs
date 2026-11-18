import test from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { spawn } from 'node:child_process';
import { Client } from '@modelcontextprotocol/client';
import { StdioClientTransport } from '@modelcontextprotocol/client/stdio';
import { productivityFixture } from '../test-fixtures/productivity-fixture.mjs';
import { businessHash } from '../src/v3-protocol.mjs';
import { productivityConflict } from '../src/productivity.mjs';
import { encodeProductivity, MEMO } from '../src/shared-productivity.mjs';
import { durableRecords } from '../src/shared-records.mjs';
import { emptyState } from '../src/merge.mjs';
import { readProductivity, validateSharedDecision } from '../src/productivity-api.mjs';

async function readFull(read) {
  let offset = 0, reviewCursor, page;
  do {
    page = await read({ scope:'all', offset, ...(reviewCursor ? { reviewCursor } : {}) });
    offset = page.next;
    reviewCursor = page.reviewCursor;
  } while (offset !== null);
  assert.match(page.confirmScope, /^[0-9a-f]{64}$/);
  return page;
}

for (const transport of ['CLI','MCP']) test(`${transport} 两条决定入口均核对完整共享冲突范围`, async t => {
  const local = await productivityFixture(t);
  const remote = await productivityFixture(t, { format:0, transport:local.transport });
  const memo = randomUUID();
  const saveMemo = (peer, action, input) => peer.edit(action, { id:memo, ...input });
  const saveCourse = (peer, title) => peer.post('/api/edit', { ...peer.ctx(), operation:{
    kind:'entry', action:'upsert', value:{ id:'course', title, day:1, startMinute:480, endMinute:540, location:'' },
  } });
  assert.equal((await saveMemo(local, 'memo.create', { title:'混合备忘', body:'基线' })).status, 200);
  assert.equal((await saveCourse(local, '基线课程')).status, 200);
  await local.settle();
  assert.equal((await remote.joinDataset()).status, 200);
  await remote.settle();
  for (const [index, peer] of [local, remote].entries()) {
    const side = index === 0 ? '本机' : '远端';
    assert.equal((await saveMemo(peer, 'memo.edit', { body:side+'备忘' })).status, 200);
    assert.equal((await saveCourse(peer, side+'课程')).status, 200);
  }
  await remote.settle();
  assert((await local.settle()).conflict);

  const env = { ...process.env, DORMPANEL_AGENT_SOCKET:local.agent };
  let call;
  if (transport === 'MCP') {
    const client = new Client({ name:'pr89-conflict-test', version:'1' });
    await client.connect(new StdioClientTransport({ command:process.execPath, args:['src/mcp.mjs'], env, stderr:'pipe' }));
    t.after(() => client.close());
    call = async (operation, input, operationId) => (await client.callTool({
      name:operation.replaceAll('.', '_'), arguments:{ ...input, ...(operationId ? { operationId } : {}) },
    })).structuredContent;
  } else {
    call = (operation, input, operationId) => new Promise((resolve, reject) => {
      const args = ['src/cli.mjs', ...operation.split('.'), '--json'];
      if (operationId) args.push('--operation-id', operationId);
      const child = spawn(process.execPath, args, { env });
      let stdout = '';
      child.stdout.setEncoding('utf8').on('data', chunk => { stdout += chunk; });
      child.on('error', reject);
      child.on('close', () => { try { resolve(JSON.parse(stdout)); } catch (error) { reject(error); } });
      child.stdin.end(JSON.stringify(input));
    });
  }
  const newId = () => `${Date.now()}-${randomUUID()}`;
  const workspace = { workspaceId:local.ctx().workspaceId };
  const read = async (input = {}) => {
    const result = await call('productivity.conflicts', { ...workspace, ...input });
    assert(result.ok, JSON.stringify(result));
    return result.data;
  };
  const snapshot = () => {
    const f = local.app.db.frame;
    return structuredClone({ hash:businessHash(f.local), conflict:f.conflict, pending:f.pending, decision:f.v3.decision, version:f.revision });
  };
  const deny = async (operation, input) => {
    const before = snapshot(), result = await call(operation, input, newId());
    assert.equal(result.ok, false, JSON.stringify(result));
    assert.deepEqual(snapshot(), before);
    return result;
  };
  const partial = await read();
  assert.equal(partial.scope, 'efficiency');
  assert.equal(partial.hidden, 1);
  assert.equal(partial.confirmScope, undefined);
  const input = { ...local.ctx(), token:partial.token, choice:'remote' };
  assert.equal((await deny('productivity.resolve', input)).error.code, 'PERMISSION');
  assert.equal((await local.post('/api/agent', { action:'permissions', permissions:['read','edit'] })).status, 200);
  for (const operation of ['productivity.resolve','conflicts.resolve']) {
    const rejected = await deny(operation, input);
    assert.equal(rejected.error.code, 'CONFIRMATION');
    assert.equal(rejected.writeApplied, false);
  }
  const first = await read({ scope:'all' });
  assert.equal(first.scope, 'all');
  assert.equal(first.groups, 2);
  assert.equal(first.confirmScope, undefined);
  assert.equal((await read({ scope:'all', offset:first.next })).confirmScope, undefined);
  const last = await read({ scope:'all', offset:first.next, reviewCursor:first.reviewCursor });
  assert.match(last.confirmScope, /^[0-9a-f]{64}$/);
  assert.equal(first.scopeId, last.scopeId);
  const rows = [...first.rows, ...last.rows];
  assert.equal(rows.find(row => row.typeId === 'memo.text').remote.body, '远端备忘');
  assert.equal(rows.find(row => row.typeId === 'class').remote.title, '远端课程');
  const confirmed = { ...input, confirmScope:last.confirmScope };
  await deny('productivity.resolve', { ...confirmed, version:input.version-1 });
  await deny('conflicts.resolve', { ...confirmed, datasetId:randomUUID() });
  await deny('productivity.resolve', { ...confirmed, workspaceId:randomUUID() });
  await deny('conflicts.resolve', { ...confirmed, token:randomUUID()+':'+input.token.slice(37) });
  await deny('productivity.resolve', { ...confirmed, token:workspace.workspaceId+':'+ '0'.repeat(64) });
  await deny('conflicts.resolve', { ...confirmed, confirmScope:'0'.repeat(64) });
  local.app.catalog.restoreGuards[workspace.workspaceId] = {};
  await deny('conflicts.resolve', confirmed);
  delete local.app.catalog.restoreGuards[workspace.workspaceId];
  if (transport === 'CLI') {
    await local.app.db.tx(frame => { frame.v3.conflict.conflicts.push({ keys:['shared:unknown.record/other'] }); });
    const unreadable = await read({ scope:'all' });
    assert.equal(unreadable.complete, false);
    assert.equal(unreadable.unreadable, 1);
    assert.equal(unreadable.reviewCursor, undefined);
    assert.equal((await deny('productivity.resolve', { ...confirmed, ...local.ctx() })).error.code, 'PROTECTED');
    await local.app.db.tx(frame => { frame.v3.conflict.conflicts.pop(); });
  }
  await local.app.db.tx(() => {});
  assert.equal((await deny('conflicts.resolve', { ...confirmed, ...local.ctx() })).error.code, 'STALE');
  const expired = await call('productivity.conflicts', { ...workspace, scope:'all', offset:first.next, reviewCursor:first.reviewCursor });
  assert.equal(expired.ok, false);

  if (transport === 'MCP') {
    const latest = await readFull(read);
    const db = local.app.db, runtime = db.activeV3, resolve = runtime.resolve, before = snapshot();
    runtime.resolve = (...args) => {
      // Another Workspace transaction is already queued when the Writer starts.
      void db.tx(() => {});
      return resolve(...args);
    };
    try {
      const raced = await call('conflicts.resolve', {
        ...local.ctx(), token:latest.token, confirmScope:latest.confirmScope, choice:'remote',
      }, newId());
      assert.equal(raced.writeApplied, false);
      assert.deepEqual(snapshot(), { ...before, version:before.version+1 });
    } finally { runtime.resolve = resolve; }
  }
  const current = await readFull(read);
  const operation = transport === 'CLI' ? 'productivity.resolve' : 'conflicts.resolve';
  const request = { ...local.ctx(), token:current.token, confirmScope:current.confirmScope, choice:'remote' };
  const operationId = newId(), committed = await call(operation, request, operationId);
  assert(committed.ok, JSON.stringify(committed));
  assert.equal(committed.data.writeApplied, true);
  if (operation === 'conflicts.resolve') assert.equal(committed.data.currentWorkspaceId, workspace.workspaceId);
  assert.equal(local.app.db.frame.conflict, null);
  assert.equal(local.app.db.frame.local.entries[0].title, '远端课程');
  assert.equal((await local.read('memo.get', { id:memo })).data.memo.body, '远端备忘');
  assert.deepEqual(await call('operations.receipt', { operationId }), committed);
  assert.deepEqual(await call(operation, request, operationId), committed);
});

test('DPV3/3 仍可通过原 HTTP 入口执行冲突决定', async t => {
  const local = await productivityFixture(t, { format:3 });
  const remote = await productivityFixture(t, { format:0, transport:local.transport });
  const save = async (target, title) => {
    const result = await target.post('/api/edit', { ...target.ctx(), operation:{
      kind:'entry', action:'upsert', value:{ id:'legacy', title, day:1, startMinute:480, endMinute:540, location:'' },
    } });
    assert.equal(result.status, 200, JSON.stringify(result.data));
  };
  await save(local, '基线');
  assert.equal((await local.sync()).status, 200);
  assert.equal((await remote.joinDataset()).status, 200);
  for (let i = 0; i < 8 && !remote.app.db.frame.v3.baselinePath; i++)
    assert.equal((await remote.sync()).status, 200);
  assert(remote.app.db.frame.v3.baselinePath);
  await save(local, '本机');
  await save(remote, '远端');
  assert.equal((await remote.sync()).status, 200);
  assert.equal((await local.sync()).status, 200);
  const token = local.app.db.conflictToken();
  assert(token);
  assert.equal((await local.post('/api/resolve', { ...local.ctx(), token:`${local.ctx().workspaceId}:${token}`, choice:'remote' })).status, 200);
  assert.equal(local.app.db.frame.local.entries[0].title, '远端');
});

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
    const { confirmScope } = await readFull(async input => (await local.read('productivity.conflicts', input)).data);
    assert.equal((await local.edit('productivity.resolve', { token:local.ctx().workspaceId+':'+ '0'.repeat(64), confirmScope, choice:'remote' })).data.writeApplied, false);
    assert.equal((await local.edit('productivity.resolve', { token, confirmScope, choice:'remote' }, { ...local.ctx(), version:response.version-1 })).data.writeApplied, false);
    return { token, rows, confirmScope };
  }
  await save(local, 'todo.editTask', { id:taskA, text:'同一任务的本机修改' });
  await save(remote, 'todo.editTask', { id:taskA, text:'同一任务的远端修改' });
  let conflict = await showConflict();
  assert.deepEqual(['base','local','remote'].map(side => conflict.rows[0][side].text),
    ['甲本机编辑','同一任务的本机修改','同一任务的远端修改']);
  assert.equal((await local.post('/api/resolve', {
    ...local.ctx(), token:conflict.token, confirmScope:conflict.confirmScope, choice:'local',
  })).data.writeApplied, true);
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
  await save(local, 'productivity.resolve', { token:conflict.token, confirmScope:conflict.confirmScope, choice:'remote' });
  assert.equal((await verify()).data.recordedChoice, 'remote');
  assert.equal((await verify()).data.match, true);
  await converge();

  await save(local, 'memo.delete', { id:memo });
  await save(remote, 'memo.edit', { id:memo, body:'与删除冲突的编辑' });
  conflict = await showConflict();
  assert.equal(conflict.rows[0].local, null);
  assert.equal(conflict.rows[0].remote.body, '与删除冲突的编辑');
  await save(local, 'productivity.resolve', { token:conflict.token, confirmScope:conflict.confirmScope, choice:'remote' });
  await converge();

  await save(local, 'todo.deleteList', { id:list, taskIds:[taskA, taskB] });
  await save(remote, 'todo.createTask', { id:newTask, listId:list, text:'与删除冲突的新增' });
  conflict = await showConflict();
  assert(conflict.rows.some(row => row.base?.id === list && row.local === null && row.remote?.id === list));
  assert(conflict.rows.some(row => row.remote?.id === newTask && row.base === null && row.local === null));
  await save(local, 'productivity.resolve', { token:conflict.token, confirmScope:conflict.confirmScope, choice:'remote' });
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

test('冲突组或三方内容改变时，即使 token 与版本相同也拒绝旧确认', async () => {
  const workspaceId = randomUUID(), datasetId = randomUUID();
  const state = title => ({ ...emptyState(), entries:[{
    id:'course', title, day:1, startMinute:480, endMinute:540, location:'',
  }] });
  const conflict = { id:'a'.repeat(64), conflicts:[{ keys:['class:course'] }],
    base:state('基线'), local:state('本机'), remote:state('远端') };
  const db = { activeV3:true, dirty:() => true, productivity:() => ({ conflict:null }),
    frame:{ datasetId, revision:7, conflict, v3:{ binding:{ format:4 }, baselinePath:'fixture', conflict } } };
  const catalog = { row:() => ({ name:'隔离工作区' }), protection:() => null, restoreGuards:{},
    manifest:{ activeId:workspaceId, revision:3 }, busy:new Set() };
  const key = Buffer.alloc(32, 1), input = { workspaceId, datasetId, version:7, catalogVersion:3, choice:'remote' };
  const read = query => readProductivity(catalog, db, { ...query, workspaceId, view:'productivity.conflicts' }, key);
  const reviewed = await readFull(read);
  const request = { ...input, token:reviewed.token, confirmScope:reviewed.confirmScope };
  assert.equal(validateSharedDecision(catalog, db, request, key), conflict.id);
  conflict.remote.entries[0].title = '另一远端方案';
  assert.throws(() => validateSharedDecision(catalog, db, request, key), /范围确认已失效/);
  conflict.remote.entries[0].title = '远端';
  conflict.conflicts.push({ keys:['class:other'] });
  assert.throws(() => validateSharedDecision(catalog, db, request, key), /范围确认已失效/);
});
