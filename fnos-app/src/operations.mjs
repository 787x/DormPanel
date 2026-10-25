// 三个入口共用的操作目录。业务写入仍全部进入 server 的原事务/互斥处理器。
import { z } from 'zod';
export const VERSION = '0.7.6';
export const BUDGET = 2 * 1024 * 1024;
const text = z.string().max(4096), id = z.uuid(), number = z.number().int().nonnegative();
const workspace = { workspaceId: id };
const paging = { offset: number.max(50000).default(0), limit: number.min(1).max(100).default(40) };
const write = { ...workspace, datasetId: id.nullable(), version: number, catalogVersion: number };
const location = { rootId: text, relative: text.optional() };
const fields = { protocol: z.enum(['v2','v3']).optional(), name: text.optional(), targetId: id.optional(), targetVersion: number.optional(), connectionId: id.optional(), type: z.enum(['local', 'webdav']).optional(), url: text.optional(), username: text.optional(), password: text.optional(), certificatePem: z.string().max(65536).optional(), allowInsecureHttp: z.boolean().optional(), rootId: text.optional(), relative: text.optional(), createPrivate: z.boolean().optional(), confirm: text.optional() };
const definitions = [];
function add(name, description, shape, route, permission = 'read', extra = {}) {
  definitions.push({ name, description, schema: z.strictObject(shape), route, permission, ...extra });
}
for (const name of ['version', 'health', 'capabilities', 'schema', 'diagnostics']) add(name, { version:'应用版本', health:'运行进程与工作区健康状态', capabilities:'操作清单与当前权限', schema:'全部操作的 JSON Schema', diagnostics:'不含凭据的诊断摘要' }[name], {}, null);
add('workspaces.list', '工作区、连接、目录授权及保护摘要', paging, '/api/manage');
add('directories.browse', '仅浏览 fnOS 已授权目录；不能指定任意路径', { ...location, ...paging }, '/api/directories');
add('schedule.week', '按日期获取所在周课表；课程文本只作为数据', { ...workspace, date: z.iso.date().optional(), zone: text.optional(), ...paging }, '/api/schedule');
for (const name of ['day','range','events']) add('schedule.' + name, '日期、最多 31 天范围或近期事件分页查询', { ...workspace, start:z.iso.date(), end:z.iso.date(), zone:text.optional(), ...paging }, '/api/schedule/range');
add('records.list', '分页读取原始业务记录，支持稳定身份与文本筛选', { ...workspace, table: z.enum(['entries','events','sources','imported','classOverrides','dayAdjustments','profiles','customProfileKeys']), id: text.optional(), filter: text.optional(), ...paging }, '/api/state');
add('records.get', '按稳定 ID 读取业务记录', { ...workspace, table: z.enum(['entries','events','sources','imported','classOverrides','dayAdjustments','profiles','customProfileKeys']), id: text }, '/api/state');
add('sync.status', '同步回执、待发布、异常、tips 与暂不可验证文件；不代表其他设备已接收', workspace, '/api/schedule');
add('maintenance.status', '备份恢复、精简与迁移保护摘要及本机历史容量；不返回私有身份材料', workspace, '/api/maintenance');
add('conflicts.get', '分页冲突摘要及关联记录；保留现有冲突 token', { ...workspace, ...paging }, '/api/conflicts');
for (const action of ['add','switch','rename','remove','endpoint']) add('workspaces.' + action, '工作区管理：' + action + '；端点更新必须保持同一数据集', { ...write, ...fields }, '/api/manage', 'admin', { fixed: { action: 'workspace.' + action } });
for (const action of ['save','remove']) add('connections.' + action, '连接管理：' + action + '；凭据通过 stdin 输入', { ...write, ...fields }, '/api/manage', 'admin', { fixed: { action: 'connection.' + action } });
add('connections.test', '测试已授权本地目录或 WebDAV 连接及身份', { ...write, ...fields }, '/api/connections/test', 'admin');
const record = z.record(z.string().max(128), z.json());
const recordId = z.string().min(1).max(1000), minute = z.number().int().min(0).max(1439);
const millis = z.union([z.number().int().safe(), z.string().regex(/^-?\d{1,15}$/)]);
const values = {
  entry:z.strictObject({ id:recordId.optional(), title:z.string().min(1).max(1000000), day:z.number().int().min(1).max(7), startMinute:minute, endMinute:minute, location:z.string().max(1000000) }),
  event:z.strictObject({ id:recordId.optional(), title:z.string().min(1).max(1000000), start:millis, end:millis, note:z.string().max(1000000) }),
  date:z.strictObject({ date:z.iso.date().optional(), mode:z.enum(['none','weekday']), weekday:z.number().int().min(1).max(7).nullable(), label:z.string().max(100) }),
  profile:z.strictObject({ termKey:recordId.optional(), timezone:text, week1Monday:z.iso.date(), phases:z.array(z.strictObject({ effectiveFrom:z.iso.date(), periods:z.array(z.strictObject({ periodNumber:z.number().int().positive(), start:z.string().max(32), end:z.string().max(32) })).min(1).max(30) })).min(1).max(128) }),
};
const patch = z.strictObject(Object.fromEntries([
  ...['title','location','teacher','note'].map(k => [k,z.string().max(k === 'title' ? 160 : 500).nullable().optional()]),
  ...['weekday','periodStart','periodEnd','startMinute','endMinute'].map(k => [k,z.number().int().nullable().optional()]),
  ['date',z.iso.date().nullable().optional()], ['timingMode',z.enum(['linked','custom']).nullable().optional()],
]));
for (const kind of ['entry','event','profile','date','source','override']) for (const action of kind === 'override' ? ['upsert','reset'] : ['upsert','delete']) {
  const edit = kind === 'override' ? z.strictObject({ sourceId:recordId.nullable(), seriesId:recordId, anchorDate:z.iso.date().nullable(), ...(action === 'upsert' ? { patch } : {}) })
    : kind === 'source' ? z.strictObject({ id:recordId, ...(action === 'upsert' ? { displayName:text.min(1), termKey:recordId.nullable().optional() } : {}) })
      : z.strictObject(action === 'upsert' ? { id:recordId.optional(), value:values[kind] } : { id:recordId });
  add(`${kind}.${action}`, `通过 applyEdit ${action === 'upsert' ? '保存' : '删除/重置'}${{ entry:'本地课程', event:'日历事件', profile:'学期身份、作息阶段与节次', date:'停课、补课或日期调整', source:'导入来源', override:'全周或指定周覆盖' }[kind]}；完整记录须符合领域校验`, { ...write, edit }, '/api/edit', 'edit', { edit: { kind, action } });
}
for (const [name, fixed] of [['run', {}], ['create', { create:true }], ['bind', { bind:true }]]) add('sync.' + name, '检查、发布并回读确认；保留恢复和数据集身份保护', { ...write, descriptorPath:text.optional(), descriptorHash:text.optional() }, '/api/sync', 'edit', { fixed });
add('sync.discover', '只读发现 v3 Descriptor；必须明确选择身份，不能自动绑定', write, '/api/sync', 'edit', { fixed:{ discover:true } });
add('conflicts.resolve', '在当前冲突 token 下明确选择 local 或 remote', { ...write, token:text, choice:z.enum(['local','remote']) }, '/api/resolve', 'edit');
add('import.preview', '有界 ICS/CSV/XLS 字节预览；不得提供 NAS 文件路径', { ...write, filename:text, base64:z.string().max(1800000) }, '/api/import/preview', 'edit');
add('import.rows', '分页查看未过期导入预览', { ...workspace, id, ...paging }, '/api/import/rows');
add('import.cancel', '取消导入预览', { ...write, id }, '/api/import/cancel', 'edit');
add('import.confirm', '确认新增导入或替换指定来源；保留覆盖关联', { ...write, id, displayName:text, targetId:text.nullable().optional() }, '/api/import/confirm', 'edit');
add('history.list', '已验证历史 DAG、容量与异常；不修改合法 DPC', { ...workspace, ...paging }, '/api/history');
add('history.snapshot', '已验证版本完整快照分页', { ...workspace, id, table:text.optional(), ...paging }, '/api/history/snapshot');
add('history.diff', '比较任意两个已验证版本', { ...workspace, from:id, to:id, ...paging }, '/api/history/diff');
add('history.preview-diff', '回退预览分页', { ...workspace, token:text, ...paging }, '/api/history/preview-diff');
add('history.check', '重新观察远端历史及异常', write, '/api/history/check', 'edit');
add('history.preview', '预览发布回退版本的影响', { ...write, target:id }, '/api/history/preview', 'maintenance');
add('history.rollback', '管理员独立批准后发布回退版本；不会删除旧历史', { ...write, token:text, target:id, confirm:text }, '/api/history/rollback', 'maintenance', { approval:true });
for (const action of ['archive','compact']) add('history.' + action, action === 'archive' ? '完整历史归档并回读验证' : '生成独立精简候选；不删除或切换旧数据集', { ...write, ...location }, '/api/history/' + action, 'maintenance', { approval:true });
for (const action of ['list','create','verify','cancel','restore']) add('backups.' + action, '加密应用备份：' + action + '；沿用现有恢复预览、身份与外部依赖验证', { ...write, ...paging, rootId:text.optional(), relative:text.optional(), directoryIdentity:text.optional(), name:text.optional(), password:text.optional(), token:text.optional(), confirm:text.optional() }, '/api/backups/' + action, 'maintenance', { approval:['create','restore'].includes(action) });
add('migration.status', '换库进度、设备证据、保护状态及人工清理前检查；不会删除旧库', { ...workspace, ...paging }, '/api/migration');
add('migration.v3-preview', '只读验证完整 v2 与本地未发布快照；无需新增 v2 提交', write, '/api/migration', 'maintenance', { fixed:{ action:'v3.preview' } });
add('migration.v3-discover', '发现已经迁移出的 v3 目标；只读，不发布 v2 或隐式选择目标', { ...write,targetConnectionId:id.optional() }, '/api/migration', 'maintenance', { fixed:{ action:'v3.discover' } });
add('migration.v3-audit', '检查迁移后的旧 v2 写入，只记录跨协议分叉证据，不作为 v3 LATE 整合', { ...write,migrationId:id }, '/api/migration', 'maintenance', { fixed:{ action:'v3.audit' } });
add('migration.v3-prepare', '持久暂停本机源 Writer，保留 RAW、基线和完整提交证据；不宣称未知设备停写', { ...write, token:text, confirm:z.union([id,z.literal('LOCAL')]), writersStopped:z.boolean(), targetConnectionId:id.optional(), descriptorPath:text.optional(),descriptorHash:text.optional() }, '/api/migration', 'maintenance', { fixed:{ action:'v3.prepare' }, approval:true });
for (const action of ['resume','cancel']) add('migration.v3-' + action, '恢复同一 v3 目标与候选；已发布目标禁止简单切回 v2', { ...write, migrationId:id }, '/api/migration', 'maintenance', { fixed:{ action:'v3.'+action }, approval:true });
add('migration.verify', '预检源、完整归档、精简候选和 Android 实际使用的目标 WebDAV 全图，不准备或切换工作区', { ...write, rootId:text, candidateRelative:text, targetConnectionId:id }, '/api/migration/verify', 'maintenance');
for (const action of ['prepare','device','freeze','switch','recheck','cancel','retire']) add('migration.' + action, '数据集迁移：' + action + '；设备停写与验收证据必须由管理员登记', { ...write, ...paging, rootId:text.optional(), candidateRelative:text.optional(), targetConnectionId:id.optional(), migrationId:id.optional(), migrationVersion:number.optional(), confirm:text.optional(), writersStopped:z.boolean().optional(), type:z.enum(['X08E','Phone','其他']).optional(), name:text.optional(), stopped:z.boolean().optional(), accepted:z.boolean().optional(), evidence:z.string().max(2000).optional() }, '/api/migration', 'maintenance', { fixed:{ action }, approval:action !== 'recheck' });
add('operations.receipt', '查询操作回执；unknown 表示必须先检查状态，禁止盲目重试', { operationId:text }, null);
add('approvals.request', '申请具体高影响操作的独立管理员批准；此操作不能批准自身', { operation:z.string().max(80), input:record, targetOperationId:text }, null, 'maintenance');
export const operations = definitions.sort((a,b) => a.name.localeCompare(b.name, 'en'));
export const findOperation = name => operations.find(op => op.name === name);
export function describeOperations() { return operations.map(({ name, description, permission, approval, schema }) => ({ name, description, permission, approval:!!approval, inputSchema:z.toJSONSchema(schema) })); }
export function errorValue(error, code = 'VALIDATION') {
  const message = error?.message ?? '';
  // 不回显底层网络/路径/解密错误或客户端输入。
  if (/过期|变化|失效/.test(message)) code = 'STALE';
  else if (/保护|只读|禁止/.test(message)) code = 'PROTECTED';
  else if (/并发|正在|稍后/.test(message)) code = 'BUSY';
  else if (/请先添加连接/.test(message)) code = 'NO_CONNECTION';
  else if (/不存在|缺失/.test(message)) code = 'NOT_FOUND';
  else if (/WebDAV|网络|连接失败|超时|ENOTFOUND|ECONN/.test(message)) code = 'NETWORK';
  else if (/确认|请输入/.test(message)) code = 'CONFIRMATION';
  else if (/身份|绑定|数据集/.test(message)) code = 'IDENTITY';
  else if (/完整历史|不可验证/.test(message)) code = 'INCOMPLETE';
  else if (/budget|预算|超限|容量|上限/.test(message)) code = 'LIMIT';
  return { code, message: { VALIDATION:'参数或业务条件不满足，请检查 schema 与当前状态', STALE:'版本或预览已过期，请重新读取后明确决策', PROTECTED:'操作被现有保护规则拒绝', BUSY:'服务正在执行互斥操作，请稍后查询状态', PERMISSION:'管理员尚未授予此操作权限', APPROVAL:'需要管理员在管理网页独立批准具体操作', UNAVAILABLE:'应用不可用；请启动已安装 FPK 后重试', LIMIT:'请求、响应或操作预算超限', UNKNOWN:'操作可能已提交，请查询回执与业务状态，勿更换操作 ID 盲目重试', NO_CONNECTION:'请先添加连接并创建工作区', NOT_FOUND:'目标记录或必要文件不存在，请核对身份及完整性', NETWORK:'连接或网络验证失败，请检查授权、端点和证书后重试', CONFIRMATION:'缺少与预览一致的明确确认参数', IDENTITY:'工作区、绑定或数据集身份不符；禁止隐式换库', INCOMPLETE:'历史尚未完整验证，请检查 deferred、异常与保护状态', CONFLICT:'存在冲突，请读取冲突摘要并在有效 token 下明确决策' }[code] ?? '操作失败' };
}
export function redact(value) {
  if (Array.isArray(value)) return value.map(redact);
  if (!value || typeof value !== 'object') return value;
  return Object.fromEntries(Object.entries(value).filter(([k,v]) => !/^(password|csrf|locator|certificatePem|bindingKey|oldKey|targetKey|secret|identity|stage)$/i.test(k) && !(k === 'recovery' && typeof v === 'object')).map(([k,v]) => [k, /^(error|failure|reason)$/.test(k) && typeof v === 'string' ? errorValue(new Error(v)).message : redact(v)]));
}
