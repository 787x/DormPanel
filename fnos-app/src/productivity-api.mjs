import { z } from 'zod';
import { createHash, createHmac, randomBytes, timingSafeEqual } from 'node:crypto';
import { ensure } from './protocol.mjs';
import { json } from './store.mjs';
import { LIST, TASK, MEMO, decodeProductivity } from './shared-productivity.mjs';
import { wireRecords } from './shared-records.mjs';
import { productivityBlock, productivityConflict } from './productivity.mjs';

export const productivityRoute = '/api/productivity';
export const productivityEditRoute = '/api/productivity/edit';
export const productivityCheckRoute = '/api/productivity/check';
const id = z.string().length(36).regex(/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/)
  .refine(value => value !== '00000000-0000-0000-0000-000000000000');
const revision = z.number().int().nonnegative();
const hex = size => z.string().length(size).regex(/^[0-9a-f]+$/);
export const conflictConfirmation = hex(64).optional().describe('完整冲突检查最后一页的 confirmScope，确认决定应用于本次全部冲突组');
const scope = { workspaceId:id };
export const productivityWriteScope = { ...scope, datasetId:id, version:revision, catalogVersion:revision };
const page = { offset:revision.max(4096).default(0), limit:revision.min(1).max(40).default(20) };
const plain = (bytes, blank = false) => z.string().max(bytes).refine(value =>
  Buffer.byteLength(value) <= bytes && Buffer.from(value).toString() === value &&
  !value.includes('\0') && (blank || /[^ \t-\r]/.test(value)), '纯文本超限或无效');
const title = plain(256), task = plain(4096), memo = plain(32768, true);

export const productivityReads = {
  'todo.lists': { ...page },
  'todo.tasks': { id, ...page },
  'todo.get': { id },
  'todo.task': { id },
  'memo.list': { ...page },
  'memo.get': { id },
  'productivity.conflicts': { offset:revision.max(100000).default(0), scope:z.enum(['efficiency','all']).default('efficiency'),
    reviewCursor:hex(64).optional().describe('完整检查的上一页返回的 reviewCursor') },
};
const conflictToken = z.string().regex(/^[0-9a-f-]{36}:[0-9a-f]{64}$/);
const choice = z.enum(['local','remote']);
export const productivityWrites = {
  'todo.createList': { id, name:title },
  'todo.renameList': { id, name:title },
  'todo.deleteList': { id, taskIds:z.array(id).max(2048).refine(ids => new Set(ids).size === ids.length) },
  'todo.createTask': { id, listId:id, text:task },
  'todo.editTask': { id, text:task },
  'todo.setCompleted': { id, completed:z.boolean() },
  'todo.deleteTask': { id },
  'memo.create': { id, title, body:memo },
  'memo.rename': { id, title },
  'memo.edit': { id, title:title.optional(), body:memo },
  'memo.delete': { id },
  'productivity.resolve': { token:conflictToken, choice, confirmScope:conflictConfirmation },
};

function parse(variants, key, input, context) {
  const name = input[key];
  ensure(Object.hasOwn(variants, name), '未知共享效率操作');
  const result = z.strictObject({ ...context, [key]:z.literal(name), ...variants[name] }).safeParse(input);
  ensure(result.success, '请求字段无效、缺失或超过 UTF-8 字节限制');
  return result.data;
}
export function parseProductivityRead(params) {
  const query = Object.fromEntries(params);
  ensure(params.size === Object.keys(query).length, '读取参数重复');
  for (const key of ['offset','limit']) if (query[key] !== undefined) {
    ensure(/^\d{1,6}$/.test(query[key]), '分页参数无效');
    query[key] = Number(query[key]);
  }
  return parse(productivityReads, 'view', query, scope);
}
export const parseProductivityWrite = input => parse(productivityWrites, 'action', input, productivityWriteScope);

function pageOf(rows, { offset, limit }) {
  return { rows:rows.slice(offset, offset + limit), total:rows.length, offset,
    next:offset + limit < rows.length ? offset + limit : null };
}
const requireRecord = (rows, id) => {
  const record = rows.find(record => record.id === id);
  ensure(record, '记录已不存在');
  return record;
};

export function productivityStatus(catalog, db, workspaceId) {
  const frame = db.frame;
  const state = catalog.row(workspaceId), binding = frame.v3?.binding;
  const availability = !db.activeV3 || binding && binding.format !== 4 ? 'unsupported'
    : !binding || !frame.v3.baselinePath ? 'uninitialized' : 'ready';
  const recovery = !!(catalog.restoreGuards[workspaceId] || catalog.manifest.transition);
  const contextBlock = catalog.maintenance || recovery ? '正在恢复或等待恢复验证'
    : catalog.configuring ? '正在保存工作区配置'
    : catalog.manifest.activeId !== workspaceId ? '请先切换到此工作区'
    : catalog.protection(workspaceId) ? '换库保护期间只读'
    : catalog.busy.has(workspaceId) ? '工作区正在同步，请稍后读取' : null;
  const dirty = db.dirty(), pending = !!(frame.pending || frame.v3?.initialization?.length), conflict = !!frame.conflict;
  return {
    workspaceId, workspaceName:state.name, datasetId:frame.datasetId, version:frame.revision,
    catalogVersion:catalog.manifest.revision, availability, recovery, contextBlock, dirty, pending, conflict,
    syncError:!!frame.error, syncStatus:frame.graph?.status ?? 'unchecked',
    remoteConfirmed:!!frame.receipt && !dirty && !pending && !conflict,
    otherDevicesReceived:'unknown',
  };
}

function scopeIdentity(status, conflict) {
  const { workspaceId, datasetId, version, catalogVersion } = status;
  const hash = createHash('sha256').update(json([workspaceId, datasetId, version, catalogVersion,
    conflict.token, conflict.groups, conflict.rows.length]));
  for (const row of conflict.rows) hash.update('\n').update(json(row));
  return hash.digest('hex');
}

function reviewProof(key, kind, scopeId, offset) {
  return createHmac('sha256', key).update(json([kind, scopeId, offset])).digest('hex');
}
const validProof = (supplied, expected) => typeof supplied === 'string' && supplied.length === expected.length &&
  timingSafeEqual(Buffer.from(supplied), Buffer.from(expected));

function reviewPage(key, status, conflict, input, page) {
  if (!key || input.scope !== 'all' || !conflict?.complete) return {};
  const scopeId = scopeIdentity(status, conflict);
  if (input.reviewCursor !== undefined)
    ensure(input.offset > 0 && validProof(input.reviewCursor, reviewProof(key, 'page', scopeId, input.offset)),
      '冲突检查游标已失效，请从首页重新读取');
  if (!page.rows.length || input.offset > 0 && input.reviewCursor === undefined) return { scopeId };
  if (page.next !== null) return { scopeId, reviewCursor:reviewProof(key, 'page', scopeId, page.next) };
  return { scopeId, confirmScope:reviewProof(key, 'decision', scopeId, conflict.rows.length) };
}

export function validateSharedDecision(catalog, db, input, key) {
  validateProductivityContext(catalog, db, input, { decision:true });
  const status = productivityStatus(catalog, db, input.workspaceId);
  const conflict = productivityConflict(db.frame, true);
  ensure(conflict && input.token === `${input.workspaceId}:${conflict.token}`, '冲突 token 已失效或工作区不匹配');
  ensure(conflict.complete, '冲突范围无法完整展示，禁止作出全局决定');
  ensure(input.confirmScope, '请先完整读取冲突并提交全局范围确认');
  const expected = reviewProof(key, 'decision', scopeIdentity(status, conflict), conflict.rows.length);
  ensure(validProof(input.confirmScope, expected), '冲突范围确认已失效，请重新完整读取');
  return conflict.token;
}

export function readProductivity(catalog, db, input, reviewKey) {
  const status = productivityStatus(catalog, db, input.workspaceId);
  const view = db.productivity();
  const result = { ...status, writeBlock:status.contextBlock ?? view.writeBlock,
    conflictCount:view.conflict?.rows.length ?? 0 };
  if (status.availability !== 'ready') return result;
  const tasksFor = id => view.tasks.filter(task => task.listId === id);
  const listDetails = (list, tasks) => ({ ...list, total:tasks.length,
    remaining:tasks.filter(task => !task.completed).length });
  switch (input.view) {
    case 'todo.lists': return { ...result, ...pageOf(view.lists.map(list => listDetails(list, tasksFor(list.id))), input) };
    case 'todo.get': {
      const list = view.lists.find(list => list.id === input.id);
      const tasks = tasksFor(input.id);
      return { ...result, list:list ? listDetails(list, tasks) : null, taskIds:tasks.map(task => task.id) };
    }
    case 'todo.tasks': {
      const list = requireRecord(view.lists, input.id), tasks = tasksFor(input.id);
      return { ...result, list:listDetails(list, tasks), ...pageOf(tasks, input) };
    }
    case 'todo.task': return { ...result, task:view.tasks.find(task => task.id === input.id) ?? null };
    case 'memo.list': return { ...result, ...pageOf(view.memos.map(({ body, ...row }) =>
      ({ ...row, preview:Array.from(body.slice(0, 320)).slice(0, 160).join('') })), input) };
    case 'memo.get': return { ...result, memo:view.memos.find(memo => memo.id === input.id) ?? null };
    case 'productivity.conflicts': {
      const conflict = input.scope === 'all' ? productivityConflict(db.frame, true) : view.conflict;
      const page = pageOf(conflict?.rows ?? [], { offset:input.offset, limit:1 });
      return { ...result, ...page, scope:input.scope ?? 'efficiency', ...reviewPage(reviewKey, status, conflict, input, page),
        token:conflict ? `${input.workspaceId}:${conflict.token}` : null,
        groups:conflict?.groups ?? 0, hidden:conflict?.hidden ?? 0, unreadable:conflict?.unreadable ?? 0,
        complete:conflict?.complete ?? false };
    }
  }
}

const expectedFields = {
  'todo.createList':['name'], 'todo.renameList':['name'],
  'todo.createTask':['listId','text','completed'], 'todo.editTask':['text'], 'todo.setCompleted':['completed'],
  'memo.create':['title','body'], 'memo.rename':['title'], 'memo.edit':['title','body'],
};
const digest = (salt, value) => createHash('sha256').update(salt).update(JSON.stringify(value)).digest('hex');

export function productivityExpectation(rawInput) {
  const input = parseProductivityWrite(rawInput);
  const { action, id, taskIds, token, choice, version } = input;
  if (token) return { action, token, choice, version };
  if (!expectedFields[action]) return { action, id, ...(taskIds ? { taskIds } : {}) };
  const salt = randomBytes(16).toString('hex');
  const values = action === 'todo.createTask' ? { ...input, completed:false } : input;
  const fields = Object.fromEntries(expectedFields[action].filter(key => values[key] !== undefined)
    .map(key => [key, digest(salt, values[key])]));
  return { action, id, salt, fields };
}

const checkScope = z.strictObject({ workspaceId:id, datasetId:id, expectation:z.object({ action:z.string() }).passthrough() });
export const parseProductivityCheck = input => checkScope.parse(input);

export function checkProductivityExpectation(frame, workspaceId, expected) {
  const { action } = expected;
  if (action === 'productivity.resolve') {
    const target = z.strictObject({ action:z.literal(action), token:conflictToken, choice, version:revision }).parse(expected);
    const original = target.token.slice(37), current = frame.v3.conflict?.id, decision = frame.v3.decision;
    ensure(target.token.startsWith(workspaceId+':'), '冲突与工作区不匹配');
    const decisionState = current === original ? 'original' : current ? 'new' : 'absent';
    const match = current === original ? false : decision?.id === original && frame.revision > target.version
      ? target.choice === decision.choice : null;
    return { match, decisionState, recordedChoice:decision?.id === original ? decision.choice : null };
  }
  ensure(Object.hasOwn(productivityWrites, action), '未知共享效率操作');
  const fields = expectedFields[action];
  const isListDelete = action === 'todo.deleteList';
  const shape = { action:z.literal(action), id,
    ...(fields ? { salt:hex(32), fields:z.strictObject(Object.fromEntries(fields.map(key =>
      [key, action === 'memo.edit' && key === 'title' ? hex(64).optional() : hex(64)]))) } : {}),
    ...(isListDelete ? { taskIds:productivityWrites[action].taskIds } : {}) };
  const target = z.strictObject(shape).parse(expected);
  const type = action.startsWith('memo.') ? MEMO : action.endsWith('List') ? LIST : TASK;
  const rows = wireRecords(frame.local.sharedRecords);
  const record = rows.find(row => row.typeId === type && row.recordId === target.id);
  if (isListDelete) {
    const taskIds = new Set(target.taskIds);
    const tasks = rows.filter(row => row.typeId === TASK).map(decodeProductivity);
    const remainingTasks = tasks.filter(task => task.listId === target.id || taskIds.has(task.id)).length;
    return { match:!record && remainingTasks === 0, exists:!!record, remainingTasks };
  }
  if (!fields) return { match:!record, exists:!!record };
  if (!record) return { match:false, exists:false };
  const value = decodeProductivity(record);
  const compared = Object.fromEntries(Object.entries(target.fields).map(([key, hash]) =>
    [key, digest(target.salt, value[key]) === hash]));
  return { match:Object.values(compared).every(Boolean), exists:true, compared };
}

export function validateProductivityContext(catalog, db, input, { decision = false } = {}) {
  const status = productivityStatus(catalog, db, input.workspaceId);
  ensure(input.datasetId === status.datasetId && input.version === status.version &&
    input.catalogVersion === status.catalogVersion, '共享效率身份或版本过期，请重新读取');
  db.guard?.();
  db.assertWritable?.();
  ensure(!status.contextBlock, status.contextBlock);
  ensure(status.availability === 'ready', '请先验证 DPV3/4 共享数据集');
  if (!decision) {
    const writeBlock = productivityBlock(db.frame);
    ensure(!writeBlock, writeBlock);
  }
}

export function productivityEdit(db, input) {
  const { id, name, title, body, text, listId, completed, taskIds } = input;
  const create = value => ({ action:'create', value:{ id, ...value } });
  switch (input.action) {
    case 'todo.createList': return create({ typeId:LIST, name });
    case 'todo.createTask': {
      const tasks = db.productivity().tasks.filter(task => task.listId === listId);
      const position = (tasks.at(-1)?.position ?? -1) + 1;
      return create({ typeId:TASK, listId, position:Math.min(2147483647, position), completed:false, text });
    }
    case 'memo.create': return create({ typeId:MEMO, title, body });
    case 'todo.renameList': return { action:'renameList', id, name };
    case 'todo.deleteList': return { action:'deleteList', id, taskIds };
    case 'todo.editTask': return { action:'editTask', id, text };
    case 'todo.setCompleted': return { action:'setTaskCompleted', id, completed };
    case 'todo.deleteTask': return { action:'deleteTask', id };
    case 'memo.rename': return { action:'renameMemo', id, title };
    case 'memo.edit': return { action:'editMemo', id, title, body };
    case 'memo.delete': return { action:'deleteMemo', id };
  }
}
