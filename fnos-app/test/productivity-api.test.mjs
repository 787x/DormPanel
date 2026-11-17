import test from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { spawn } from 'node:child_process';
import { Client } from '@modelcontextprotocol/client';
import { StdioClientTransport } from '@modelcontextprotocol/client/stdio';
import { productivityFixture } from '../test-fixtures/productivity-fixture.mjs';
import { callOperation } from '../src/agent-client.mjs';

const opId = () => `${Date.now()}-${randomUUID()}`;
const memoBody = ' \r\n' + '中'.repeat(10921) + '\n🙂';
const applied = result => {
  assert.equal(result.status, 200, JSON.stringify(result.data));
  assert.equal(result.data.writeApplied, true);
  return result.data;
};
const rejected = result => {
  assert(result.status >= 400, JSON.stringify(result.data));
  assert.equal(result.data.writeApplied, false);
};

test('HTTP 效率 CRUD、分页、文本预算、权限和完整删除范围', async t => {
  const f = await productivityFixture(t), { edit, read, ctx } = f;
  const list = randomUUID(), other = randomUUID(), a = randomUUID(), b = randomUUID(), m = randomUUID(), n = randomUUID();
  assert.equal((await read('todo.lists')).data.total, 0);
  const initial = ctx();
  const payload = { ...initial, action:'todo.createList', id:list, name:'每周任务' };
  const path = '/api/productivity/edit';
  const unauthenticated = await f.request('POST', path, payload, { 'x-trim-isadmin':'false' });
  assert.equal(unauthenticated.status, 403); rejected(unauthenticated);
  rejected(await f.request('POST', path, payload));
  rejected(await f.post(path, payload, { 'sec-fetch-site':'cross-site' }));
  rejected(await edit('todo.createList', { id:list, name:'中'.repeat(86) }));
  rejected(await edit('todo.createList', { id:list, name:'旧表单', unexpected:true }));
  rejected(await edit('todo.toggle', { id:list, completed:true }));
  rejected(await edit('todo.createList', { id:'none', name:'非法' }));
  rejected(await edit('todo.createList', { id:list, name:'非法' }, { ...initial, datasetId:randomUUID() }));
  rejected(await edit('todo.createList', { id:list, name:'非法' }, { ...initial, catalogVersion:initial.catalogVersion+1 }));
  applied(await edit('todo.createList', { id:list, name:'中'.repeat(85)+'!' }));
  rejected(await f.post(path, payload));
  rejected(await edit('todo.createList', { id:list, name:'重试' }));
  applied(await edit('todo.createList', { id:other, name:'独立清单' }));
  applied(await edit('todo.renameList', { id:list, name:'改名的清单' }));
  rejected(await edit('todo.createTask', { id:a, listId:randomUUID(), text:'无清单' }));
  applied(await edit('todo.createTask', { id:a, listId:list, text:'中'.repeat(1365)+'!' }));
  rejected(await edit('todo.createTask', { id:b, listId:list, text:'中'.repeat(1366) }));
  applied(await edit('todo.createTask', { id:b, listId:list, text:'第二个' }));
  applied(await edit('todo.editTask', { id:b, text:'第二个\n已改' }));
  rejected(await edit('todo.setCompleted', { id:a, completed:'true' }));
  applied(await edit('todo.setCompleted', { id:a, completed:true }));
  const first = (await read('todo.tasks', { id:list, limit:1 })).data;
  assert.deepEqual([first.rows[0].id, first.rows[0].completed, first.next], [a, true, 1]);
  assert.equal(first.list.remaining, 1);
  assert.equal((await read('todo.tasks', { id:list, offset:1, limit:1 })).data.rows[0].id, b);
  assert.equal((await read('todo.tasks', { id:other })).data.total, 0);
  assert.equal((await read('todo.lists', { limit:41 })).status, 400);
  assert.equal((await read('todo.lists', { offset:-1 })).status, 400);
  assert.equal((await read('todo.lists', { extra:true })).status, 400);
  assert.equal((await read('todo.get', { id:list })).data.taskIds.length, 2);
  const beforeDelete = ctx();
  rejected(await edit('todo.deleteList', { id:list, taskIds:[a] }));
  assert.deepEqual(ctx(), beforeDelete);
  applied(await edit('todo.setCompleted', { id:a, completed:false }));
  rejected(await edit('todo.deleteList', { id:list, taskIds:[a,b] }, beforeDelete));
  applied(await edit('todo.deleteTask', { id:b }));
  applied(await edit('todo.deleteList', { id:list, taskIds:[a] }));
  assert.equal((await read('todo.task', { id:a })).data.task, null);
  assert.deepEqual((await read('todo.lists')).data.rows.map(row => row.id), [other]);

  assert.equal(Buffer.byteLength(memoBody), 32771);
  rejected(await edit('memo.create', { id:m, title:'正文', body:memoBody }));
  const exact = memoBody.slice(0, -2) + '!';
  assert.equal(Buffer.byteLength(exact), 32768);
  applied(await edit('memo.create', { id:m, title:'正文', body:exact }));
  applied(await edit('memo.create', { id:n, title:'另一条', body:'' }));
  const memos = await read('memo.list');
  assert.equal(memos.data.total, 2);
  assert(memos.bytes < 4096 && memos.data.rows.every(row => !('body' in row)));
  assert.equal((await read('memo.get', { id:m })).data.memo.body, exact);
  applied(await edit('memo.rename', { id:m, title:'已改名' }));
  applied(await edit('memo.edit', { id:m, title:'一起保存', body:' \r\n🙂\n  ' }));
  assert.equal((await read('memo.get', { id:m })).data.memo.body, ' \r\n🙂\n  ');
  assert.equal((await read('memo.get', { id:n })).data.memo.body, '');
  applied(await edit('memo.delete', { id:n }));
  assert.equal((await read('memo.get', { id:n })).data.memo, null);
  assert.equal((await read('memo.list')).data.dirty, true);
  for (let i = 0; i < 6 && (await read('memo.list')).data.dirty; i++) {
    const round = await f.sync();
    assert.equal(round.status, 200, JSON.stringify(round.data));
  }
  const published = (await read('memo.list')).data;
  assert.equal(published.remoteConfirmed, true, JSON.stringify(published));
  assert.equal(published.otherDevicesReceived, 'unknown');
});

test('旧格式、未初始化、恢复保护与提交边界拒绝', async t => {
  for (const format of [2,3,4]) {
    const f = await productivityFixture(t, { format });
    if (format === 4) {
      await f.createWorkspace('未绑定工作区');
      assert.equal((await f.read('todo.lists')).data.availability, 'uninitialized');
    } else assert.equal((await f.read('todo.lists')).data.availability, 'unsupported');
    rejected(await f.edit('todo.createList', { id:randomUUID(), name:'无资格' }, { ...f.ctx(), datasetId:f.ctx().datasetId ?? randomUUID() }));
    if (format === 4) {
      assert.equal((await f.post('/api/sync', { ...f.ctx(), createShared:true })).status, 400);
      assert.equal(f.ctx().datasetId, null);
    }
  }
  const f = await productivityFixture(t), id = randomUUID();
  const db = f.app.db;
  f.app.catalog.restoreGuards[f.ctx().workspaceId] = {};
  assert.equal((await f.read('todo.lists')).data.recovery, true);
  rejected(await f.edit('todo.createList', { id, name:'恢复期间' }));
  delete f.app.catalog.restoreGuards[f.ctx().workspaceId];
  const commit = db.store.commitWorkspace;
  db.store.commitWorkspace = (prepare, context) => commit(() => { prepare(); throw Error('transaction fault'); }, context);
  const changed = f.ctx();
  assert.equal((await f.edit('todo.createList', { id, name:'故障' })).data.writeApplied, null);
  db.store.commitWorkspace = commit;
  assert.deepEqual(f.ctx(), changed);
  assert.equal((await f.read('todo.get', { id })).data.list, null);
  applied(await f.edit('todo.createList', { id, name:'核对后重试' }));
});

test('Agent CLI 与 MCP 共用回执，批准只绑定精确删除范围', async t => {
  const f = await productivityFixture(t);
  const call = (name, input, options) => callOperation(name, input, { socketPath:f.agent, ...options });
  const list = randomUUID(), task = randomUUID(), memo = randomUUID();
  const env = { ...process.env, DORMPANEL_AGENT_SOCKET:f.agent };
  const args = { ...f.ctx(), id:list, name:'测试清单' }, operationId = opId();
  assert.equal((await call('todo.createList', args, { operationId })).error.code, 'PERMISSION');
  assert.equal((await f.post('/api/agent', { action:'permissions', permissions:['read','edit','maintenance'] })).status, 200);
  const cli = (words, input, options = []) => new Promise((resolve, reject) => {
    const child = spawn(process.execPath, ['src/cli.mjs', ...words, '--json', ...options], { env });
    let output = '';
    child.stdout.setEncoding('utf8').on('data', chunk => { output += chunk; });
    child.on('error', reject); child.on('close', () => resolve(JSON.parse(output)));
    child.stdin.end(JSON.stringify(input));
  });
  const fromCli = await cli(['todo','createList'], args, ['--operation-id',operationId]);
  assert(fromCli.ok, JSON.stringify(fromCli));
  assert.deepEqual(await call('todo.createList', args, { operationId }), fromCli);
  assert.deepEqual(await call('operations.receipt', { operationId }), fromCli);
  const client = new Client({ name:'pr89', version:'1' });
  await client.connect(new StdioClientTransport({ command:process.execPath, args:['src/mcp.mjs'], env, stderr:'pipe' }));
  t.after(() => client.close());
  const mcp = async (name, args) => (await client.callTool({ name, arguments:args })).structuredContent;
  const createTask = await mcp('todo_createTask', { ...f.ctx(), listId:list, id:task, text:'合成任务', operationId:opId() });
  assert(createTask.ok, JSON.stringify(createTask));
  const invoke = async (name, input) => {
    const result = await mcp(name, { ...f.ctx(), ...input, operationId:opId() });
    assert(result.ok, JSON.stringify(result));
  };
  await invoke('todo_renameList', { id:list, name:'已改名' });
  await invoke('todo_editTask', { id:task, text:'保留的任务' });
  await invoke('todo_setCompleted', { id:task, completed:true });
  const extraTask = randomUUID();
  await invoke('todo_createTask', { id:extraTask, listId:list, text:'待删除' });
  await invoke('todo_deleteTask', { id:extraTask });
  const body = '保留空白\r\n' + '甲'.repeat(10000);
  const created = await mcp('memo_create', { ...f.ctx(), id:memo, title:'测试', body, operationId:opId() });
  assert(created.ok, JSON.stringify(created));
  const scope = { workspaceId:f.ctx().workspaceId };
  assert.equal((await mcp('todo_get', { ...scope, id:list })).data.list.name, '已改名');
  assert.equal((await mcp('todo_tasks', { ...scope, id:list, limit:1 })).data.rows[0].completed, true);
  assert.equal((await mcp('memo_get', { ...scope, id:memo })).data.memo.body, body);
  await invoke('memo_rename', { id:memo, title:'改名' });
  const emptyMemo = randomUUID();
  await invoke('memo_create', { id:emptyMemo, title:'空正文', body:'' });
  assert.equal((await mcp('memo_list', { ...scope, limit:1 })).data.next, 1);
  await invoke('memo_delete', { id:emptyMemo });
  const deleteInput = { ...f.ctx(), id:list, taskIds:[task] }, deletionId = opId();
  assert.equal((await call('todo.deleteList', deleteInput, { operationId:deletionId })).error.code, 'APPROVAL');
  const request = await call('approvals.request', {
    operation:'todo.deleteList', input:deleteInput, targetOperationId:deletionId,
  }, { operationId:opId() });
  assert(request.ok, JSON.stringify(request));
  const { approvalId } = request.data;
  const audit = (await f.request('GET', '/api/agent')).data;
  assert.equal(audit.approvals[0].summary.preview.taskCount, 1);
  assert.match(audit.approvals[0].summary.preview.taskIdsHash, /^[0-9a-f]{64}$/);
  assert(!JSON.stringify(audit).includes(body));
  assert.equal((await f.post('/api/agent', { action:'approve', id:approvalId })).status, 200);
  assert.equal((await mcp('todo_deleteList', { ...deleteInput, taskIds:[], operationId:deletionId, approvalId })).error.code, 'APPROVAL');
  const deleted = await mcp('todo_deleteList', { ...deleteInput, operationId:deletionId, approvalId });
  assert(deleted.ok, JSON.stringify(deleted));
  assert.deepEqual(await call('operations.receipt', { operationId:deletionId }), deleted);
  assert.equal((await f.read('todo.task', { id:task })).data.task, null);

  const db = f.app.db, commit = db.store.commitWorkspace;
  db.store.commitWorkspace = (prepare, context) => commit(() => { prepare(); throw Error('transaction fault'); }, context);
  const uncertainId = opId(), change = { ...f.ctx(), id:memo, body:'不确定写入' };
  const uncertain = await mcp('memo_edit', { ...change, operationId:uncertainId });
  db.store.commitWorkspace = commit;
  assert.equal(uncertain.error.code, 'UNKNOWN');
  assert.equal(uncertain.writeApplied, null);
  assert.deepEqual(await call('memo.edit', change, { operationId:uncertainId }), uncertain);
  assert.deepEqual(await call('operations.receipt', { operationId:uncertainId }), uncertain);
  assert.equal((await f.read('memo.get', { id:memo })).data.memo.body, body);
});
