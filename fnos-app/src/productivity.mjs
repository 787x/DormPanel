import { ensure } from './protocol.mjs';
import { stateBytes } from './v3-protocol.mjs';
import { Registry, courseColor, durableRecords, wireRecords } from './shared-records.mjs';
import { sharedTypes } from './shared-types.mjs';
import { records } from './merge.mjs';
import { decodeSchedule } from './shared-course-schedule.mjs';
import { LIST, TASK, MEMO, decodeProductivity, encodeProductivity, projectProductivity,
  supportedProductivity } from './shared-productivity.mjs';

const conflictSides = ['base','local','remote'];
const efficiencyTypes = [LIST, TASK, MEMO];
const scheduleTypes = ['class','event','date','edit','source','term'];

function reviewPages(row) {
  // Escape-sensitive limits apply to serialized JSON, including imported sources
  // whose related occurrences can span many pages.
  const text = conflictSides.map(side => JSON.stringify(row[side], (key, value) =>
    key === 'locator' ? undefined : typeof value === 'bigint' ? value.toString() : value, 2));
  const size = 64 * 1024;
  const count = Math.ceil(Math.max(...text.map(value => value.length)) / size);
  if (count <= 1) return [row];
  const boundary = (value, i) => /[\uDC00-\uDFFF]/.test(value.charAt(i)) ? i-1 : i;
  return Array.from({ length:count }, (_, page) => {
    const fragment = { ...row, part:page+1, parts:count };
    for (const [index, side] of conflictSides.entries()) {
      const value = text[index];
      fragment[side] = row[side] === null ? null :
        value.slice(boundary(value, page*size), boundary(value, (page+1)*size));
    }
    return fragment;
  });
}

export function productivityConflict(frame, full = false) {
  const conflict = frame.v3?.conflict;
  if (!conflict) return null;
  const groups = conflict.conflicts;
  const indexes = conflictSides.map(side => records(conflict[side]));
  const rows = [];
  let hidden = 0, unreadable = 0;
  for (const [group, entry] of groups.entries()) {
    const keys = entry.keys?.length ? entry.keys : [entry.key ?? 'unknown:'];
    for (const key of keys) {
      const separator = typeof key === 'string' ? key.indexOf(':') : -1;
      const prefix = separator > 0 ? key.slice(0, separator) : 'unknown';
      const [sharedType, recordId, extra] = prefix === 'shared' ? key.slice(7).split('/') : [];
      const validKey = prefix === 'shared' ? !!recordId && extra === undefined : separator > 0 && key.length > separator+1;
      const typeId = sharedType ?? prefix;
      const efficiency = efficiencyTypes.includes(sharedType);
      if (!efficiency && !full) { hidden++; continue; }
      const row = { group:group+1, key, typeId, recordId:recordId ?? (validKey ? key.slice(separator+1) : '无法识别'), unknownSides:[] };
      for (const [index, side] of conflictSides.entries()) {
        const value = indexes[index].get(key);
        try {
          if (!validKey || !efficiency && !scheduleTypes.includes(prefix) && !['course.schedule','course.color'].includes(sharedType))
            throw Error('Unsupported conflict record');
          if (!value) row[side] = null;
          else if (efficiency) row[side] = decodeProductivity(wireRecords([value])[0]);
          else if (sharedType === 'course.schedule') row[side] = decodeSchedule(value);
          else if (sharedType === 'course.color') {
            const color = wireRecords([value])[0];
            ensure(color.schemaVersion === courseColor.schemaVersion, '颜色记录版本无法读取');
            courseColor.validate(color);
            row[side] = { rgb:color.payload[0] === 0 ? null : color.payload.readUIntBE(1, 3) };
          } else row[side] = value;
        } catch {
          row[side] = null;
          row.unknownSides.push(side);
        }
      }
      if (row.unknownSides.length) unreadable++;
      if (full && !efficiency) for (const page of reviewPages(row)) rows.push(page);
      else rows.push(row);
    }
  }
  return { token:conflict.id, revision:frame.revision, groups:groups.length,
    rows, hidden, unreadable, complete:rows.length > 0 && rows.length <= 100001 && hidden === 0 && unreadable === 0 };
}

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
  return {
    ...projectProductivity(wireRecords(frame.local.sharedRecords ?? [])),
    datasetId: frame.v3?.binding?.datasetId ?? null,
    revision: frame.revision, writeBlock, conflict:productivityConflict(frame),
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
    case 'editMemo': next = put({ ...read(MEMO, edit.id), ...(edit.title === undefined ? {} : { title:edit.title }), body: edit.body }); break;
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
