import { z } from 'zod';
import { ensure } from './protocol.mjs';
import { LIST, TASK, MEMO } from './shared-productivity.mjs';
import { productivityBlock } from './productivity.mjs';

export const productivityRoute = '/api/productivity';
export const productivityEditRoute = '/api/productivity/edit';
const id = z.string().length(36).regex(/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/)
  .refine(value => value !== '00000000-0000-0000-0000-000000000000');
const revision = z.number().int().nonnegative();
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
  'productivity.conflicts': { offset:page.offset },
};
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
  'productivity.resolve': { token:z.string().regex(/^[0-9a-f-]{36}:[0-9a-f]{64}$/), choice:z.enum(['local','remote']) },
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
    ensure(/^\d{1,4}$/.test(query[key]), '分页参数无效');
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

export function readProductivity(catalog, db, input) {
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
    case 'productivity.conflicts': return { ...result,
      ...pageOf(view.conflict?.rows ?? [], { offset:input.offset, limit:1 }),
      token:view.conflict ? `${input.workspaceId}:${view.conflict.token}` : null };
  }
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
