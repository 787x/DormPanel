import { ensure } from './protocol.mjs';
import { stateBytes } from './v3-protocol.mjs';
import { Registry, durableRecords, wireRecords } from './shared-records.mjs';
import { sharedTypes } from './shared-types.mjs';
import { LIST, TASK, MEMO, decodeProductivity, encodeProductivity, projectProductivity,
  productivityConflictRows, supportedProductivity } from './shared-productivity.mjs';

export function productivityBlock(frame) {
  if (frame.v3?.binding?.format !== 4 || !frame.local.sharedRecords) return '共享效率数据需要 DPV3/4';
  if (!frame.v3.baselinePath) return '请先完成共享数据集初始化或加入验证';
  if (frame.v3.conflict) return '请先处理共享数据冲突';
  try { new Registry(sharedTypes).writable(wireRecords(frame.local.sharedRecords)); }
  catch (error) { return error.message; }
  return null;
}

export function productivityView(frame, guard) {
  let writeBlock = productivityBlock(frame);
  try { guard?.(); } catch (error) { writeBlock = error.message; }
  const c = frame.v3?.conflict;
  const conflict = c ? {
    token: c.id, revision: frame.revision,
    rows: productivityConflictRows(new Set(c.conflicts.flatMap(row => row.keys ?? [row.key])),
      [c.base, c.local, c.remote].map(state => wireRecords(state.sharedRecords ?? []))),
  } : null;
  return {
    ...projectProductivity(wireRecords(frame.local.sharedRecords ?? [])),
    datasetId: frame.v3?.binding?.datasetId ?? null,
    revision: frame.revision, writeBlock, conflict,
  };
}

export function editProductivity(state, edit) {
  const registry = new Registry(sharedTypes);
  const rows = wireRecords(state.sharedRecords);
  const find = (type, id) => {
    const row = rows.find(r => r.typeId === type && r.recordId === id);
    ensure(row, '记录已不存在');
    return row;
  };
  const read = (type, id) => decodeProductivity(find(type, id));
  const put = value => registry.replace(rows, encodeProductivity(value));
  const remove = type => {
    read(type, edit.id);
    return registry.remove(rows, type, edit.id);
  };
  let next;
  switch (edit.action) {
    case 'create': {
      const record = encodeProductivity(edit.value);
      ensure(rows.every(r => r.typeId !== record.typeId || r.recordId !== record.recordId), '创建 ID 已使用，请重新读取');
      next = registry.replace(rows, record);
      break;
    }
    case 'renameList': next = put({ ...read(LIST, edit.id), name: edit.name }); break;
    case 'editTask': next = put({ ...read(TASK, edit.id), text: edit.text }); break;
    case 'setTaskCompleted': next = put({ ...read(TASK, edit.id), completed: edit.completed }); break;
    case 'renameMemo': next = put({ ...read(MEMO, edit.id), title: edit.title }); break;
    case 'editMemo': next = put({ ...read(MEMO, edit.id), body: edit.body }); break;
    case 'deleteTask': next = remove(TASK); break;
    case 'deleteMemo': next = remove(MEMO); break;
    case 'deleteList': {
      read(LIST, edit.id);
      const taskIds = edit.taskIds ?? [];
      ensure(Array.isArray(taskIds) && new Set(taskIds).size === taskIds.length, '任务删除范围无效');
      const tasks = rows.filter(r => r.typeId === TASK);
      ensure(tasks.every(supportedProductivity), '有未知任务版本，无法验证清单删除范围');
      const actual = new Set(tasks.map(decodeProductivity).filter(v => v.listId === edit.id).map(v => v.id));
      ensure(taskIds.length === actual.size && taskIds.every(id => actual.has(id)), '请明确选择清单下的所有任务');
      next = rows.filter(r => !(r.typeId === LIST && r.recordId === edit.id) && !(r.typeId === TASK && actual.has(r.recordId)));
      registry.writable(next);
      break;
    }
    default: throw new Error('未知共享效率操作');
  }
  const result = { ...state, sharedRecords: durableRecords(next) };
  stateBytes(result);
  return result;
}
