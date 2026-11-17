import { TextDecoder } from 'node:util';

export const LIST = 'todo.list';
export const TASK = 'todo.task';
export const MEMO = 'memo.text';

const limits = { [LIST]: [128, 260], [TASK]: [2048, 4141], [MEMO]: [256, 33032] };
const decoder = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true });
const check = (condition, message = '共享效率记录无效') => { if (!condition) throw new Error(message); };
const identity = value => {
  check(typeof value === 'string' && value.length === 36 &&
    /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value) &&
    value !== '00000000-0000-0000-0000-000000000000', '共享效率记录 ID 无效');
  return value;
};
const int = value => { const bytes = Buffer.alloc(4); bytes.writeInt32BE(value); return bytes; };
function field(text, limit, nonBlank = true) {
  check(typeof text === 'string' && text.length <= limit, `文本超过 ${limit} UTF-8 字节`);
  const bytes = Buffer.from(text, 'utf8');
  check(bytes.length <= limit, `文本超过 ${limit} UTF-8 字节`);
  check(decoder.decode(bytes) === text && !text.includes('\0'), '无效的纯文本');
  check(!nonBlank || /[^ \t-\r]/.test(text), '文本不能为空');
  return Buffer.concat([int(bytes.length), bytes]);
}
export function encodeProductivity(value) {
  const { typeId, id } = value;
  let payload;
  switch (typeId) {
    case LIST: payload = field(value.name, 256); break;
    case TASK:
      check(Number.isInteger(value.position) && value.position >= 0 && value.position <= 2147483647, '任务顺序无效');
      check(typeof value.completed === 'boolean', '任务完成状态无效');
      payload = Buffer.concat([Buffer.from(identity(value.listId), 'ascii'), int(value.position),
        Buffer.from([value.completed ? 1 : 0]), field(value.text, 4096)]);
      break;
    case MEMO: payload = Buffer.concat([field(value.title, 256), field(value.body, 32768, false)]); break;
    default: throw new Error('未知共享效率类型');
  }
  return { typeId, recordId: identity(id), schemaVersion: 1, required: false, dependencies: [], payload };
}
export const supportedProductivity = r => r.schemaVersion === 1 && Object.hasOwn(limits, r.typeId);
export function decodeProductivity(record) {
  check(supportedProductivity(record) && record.required === false && record.dependencies.length === 0);
  const { typeId, recordId: id, payload } = record;
  let position = 0;
  const take = size => {
    check(size >= 0 && size <= payload.length - position, '共享效率记录数据截断');
    const bytes = payload.subarray(position, position + size);
    position += size;
    return bytes;
  };
  const read = limit => {
    const size = take(4).readInt32BE();
    check(size <= limit, `文本超过 ${limit} UTF-8 字节`);
    return decoder.decode(take(size));
  };
  let value;
  switch (typeId) {
    case LIST: value = { typeId, id, name: read(256) }; break;
    case TASK: {
      const listId = take(36).toString('latin1');
      const index = take(4).readInt32BE();
      const flag = take(1)[0];
      check(flag <= 1);
      value = { typeId, id, listId, position: index, completed: flag === 1, text: read(4096) };
      break;
    }
    case MEMO: value = { typeId, id, title: read(256), body: read(32768) }; break;
  }
  check(position === payload.length && encodeProductivity(value).payload.equals(payload), '共享效率记录编码不规范');
  return value;
}
export const productivityAdapters = Object.entries(limits).map(([typeId, [maxRecords, maxPayload]]) => ({
  typeId, schemaVersion: 1, maxRecords, maxPayload,
  validate(record, records) {
    const value = decodeProductivity(record);
    if (typeId === TASK) check(records.some(r => r.typeId === LIST && r.schemaVersion === 1 && r.recordId === value.listId),
      '任务引用的 Todo 清单不存在');
  },
  relatedKeys: typeId === TASK ? record => [`shared:${LIST}/${decodeProductivity(record).listId}`] : undefined,
}));
const compare = (a, b) => (a < b ? -1 : a > b ? 1 : 0);
export function projectProductivity(records) {
  const values = records.filter(supportedProductivity).map(decodeProductivity);
  const byId = (a, b) => compare(a.id, b.id);
  return {
    lists: values.filter(v => v.typeId === LIST).sort(byId),
    tasks: values.filter(v => v.typeId === TASK).sort((a, b) =>
      compare(a.listId, b.listId) || a.position - b.position || byId(a, b)),
    memos: values.filter(v => v.typeId === MEMO).sort(byId),
  };
}
export function productivityConflictRows(keys, sides) {
  const indexes = sides.map(rows => new Map(rows.filter(supportedProductivity).map(r => [`shared:${r.typeId}/${r.recordId}`, r])));
  return [...keys].sort().flatMap(key => {
    const [base, local, remote] = indexes.map(index => index.has(key) ? decodeProductivity(index.get(key)) : null);
    return base || local || remote ? [{ key, base, local, remote }] : [];
  });
}
