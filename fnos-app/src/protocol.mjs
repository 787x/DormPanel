import { createHash } from 'node:crypto';
import { Temporal } from '@js-temporal/polyfill';

export const MAX = 16 * 1024 * 1024;
export function ensure(ok, message = '非法协议内容') { if (!ok) throw new Error(message); }
export const hash = bytes => createHash('sha256').update(bytes).digest();
export const uuid = value => ensure(typeof value === 'string' && /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/.test(value), '非法 UUID');
export function date(value) { const d = Temporal.PlainDate.from(value); ensure(d.toString() === value, '非规范日期'); return d; }
export function zone(value) { Temporal.Instant.fromEpochMilliseconds(0).toZonedDateTimeISO(value); return value; }
export const termKey = s => s.termKey ?? (s.filename.toLowerCase().endsWith('.csv') ? /^湖北大学 ([0-9]{4}-[0-9]{4}-[0-9])$/.exec(s.calendarName ?? '')?.[1] ?? null : null);
export const overrideKey = (source, series, anchor) => `${source === null ? 'L' : `I:${source.length}:${source}`}:${series.length}:${series}:${anchor ?? 'all'}`;

const fields = s => s.split(' ').map(f => f.split(':'));
const period = fields('periodNumber:i start:t end:t');
const phase = [...fields('effectiveFrom:t'), ['periods', [30, period, 'periodNumber']]];
export const schemas = [
  ['events', 10000, 'id', fields('id:t title:t start:l end:l note:t')],
  ['entries', 10000, 'id', fields('id:t title:t day:i startMinute:i endMinute:i location:t')],
  ['sources', 16, 'id', fields('id:t displayName:t filename:t kind:t locator:n calendarName:n sha256:t importedAt:l timezones:t occurrenceCount:i firstStart:l lastEnd:l termKey:n')],
  ['imported', 50000, 'id', fields('id:t sourceId:t seriesId:t uid:n originalStart:l title:t start:l end:l timezone:t location:t description:t periodLabel:n')],
  ['dayAdjustments', 1000, 'date', fields('date:t mode:t weekday:j label:t')],
  ['classOverrides', 5000, 'key', fields('key:t sourceId:n seriesId:t anchorDate:n title:n weekday:j date:n periodStart:j periodEnd:j timingMode:n startMinute:j endMinute:j location:n teacher:n note:n')],
  ['profiles', 128, 'termKey', [...fields('termKey:t timezone:t week1Monday:t'), ['phases', [128, phase, 'effectiveFrom']]]],
  ['customProfileKeys', 128, null, null]
];

class Reader {
  constructor(b) { this.b = b; this.p = 0; }
  take(n) { ensure(Number.isInteger(n) && n >= 0 && this.p + n <= this.b.length, '截断或长度越界'); const b = this.b.subarray(this.p, this.p + n); this.p += n; return b; }
  i() { return this.take(4).readInt32BE(); }
  l() { return this.take(8).readBigInt64BE(); }
  t() { const n = this.i(); ensure(n <= 1000000); return new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(this.take(n)); }
  value(type) {
    if (Array.isArray(type)) return this.rows(...type);
    if (type === 'n' || type === 'j') { const tag = this.take(1)[0]; ensure(tag <= 1, '非法 nullable tag'); return tag ? this[type === 'n' ? 't' : 'i']() : null; }
    return this[type]();
  }
  rows(max, schema, key) {
    const n = this.i(); ensure(n >= 0 && n <= max, '集合数量越界');
    const rows = Array.from({ length: n }, () => schema ? Object.fromEntries(schema.map(([f, t]) => [f, this.value(t)])) : this.t());
    const keys = rows.map(r => key ? r[key] : r);
    ensure(keys.every((k, i) => i === 0 || keys[i - 1] < k), '重复身份或非规范顺序'); return rows;
  }
  done() { ensure(this.p === this.b.length, '未知尾随字段'); }
}
class Writer {
  parts = []; length = 0;
  add(b) { this.length += b.length; ensure(this.length <= MAX + 8192, '编码大小越界'); this.parts.push(b); }
  i(v) { const b = Buffer.alloc(4); b.writeInt32BE(v); this.add(b); }
  l(v) { const b = Buffer.alloc(8); b.writeBigInt64BE(BigInt(v)); this.add(b); }
  t(v) { ensure(typeof v === 'string' && v.isWellFormed(), '非法文本'); const b = Buffer.from(v); ensure(b.length <= 1000000); this.i(b.length); this.add(b); }
  value(t, v) {
    if (Array.isArray(t)) return this.rows(v, t[1], t[2]);
    if (t === 'n' || t === 'j') { this.add(Buffer.from([v == null ? 0 : 1])); if (v != null) this[t === 'n' ? 't' : 'i'](v); }
    else this[t](v);
  }
  rows(rows, schema, key) {
    const sorted = [...rows].sort((a, b) => { a = key ? a[key] : a; b = key ? b[key] : b; return a < b ? -1 : a > b ? 1 : 0; });
    this.i(sorted.length); for (const r of sorted) { if (schema) for (const [f, t] of schema) this.value(t, r[f]); else this.t(r); }
  }
  bytes() { return Buffer.concat(this.parts); }
}
export function stateBytes(state) { const w = new Writer(); for (const [name, , key, schema] of schemas) w.rows(state[name], schema, key); return w.bytes(); }
export function encode(commit) {
  validate(commit.state); [commit.datasetId, commit.commitId, commit.writerId, ...commit.parents].forEach(uuid);
  ensure(commit.parents.length <= 64 && new Set(commit.parents).size === commit.parents.length && !commit.parents.includes(commit.commitId));
  const p = new Writer(); p.t(commit.datasetId); p.t(commit.commitId); p.value('n', null); p.l(1); p.l(1); p.add(stateBytes(commit.state));
  const payload = p.bytes(); const s = new Writer(); s.add(Buffer.from('DPSS')); s.i(1); s.i(payload.length); s.add(payload); s.add(hash(payload));
  const snapshot = s.bytes(); ensure(snapshot.length <= MAX);
  const w = new Writer(); w.add(Buffer.from('DPC2')); w.i(2); for (const id of [commit.datasetId, commit.commitId, commit.writerId]) w.add(Buffer.from(id));
  w.i(commit.parents.length); for (const id of [...commit.parents].sort()) w.add(Buffer.from(id)); w.i(snapshot.length); w.add(snapshot);
  const body = w.bytes(); return Buffer.concat([body, hash(body)]);
}
export function decode(bytes, filenameId) {
  ensure(bytes.length >= 200 && bytes.length <= MAX + 8192, '提交大小越界');
  const body = bytes.subarray(0, -32); ensure(hash(body).equals(bytes.subarray(-32)), '提交校验失败');
  const r = new Reader(body); ensure(r.take(4).toString() === 'DPC2' && r.i() === 2, '不支持的提交协议');
  const id = () => { const s = r.take(36).toString('ascii'); uuid(s); return s; };
  const datasetId = id(), commitId = id(), writerId = id(); ensure(!filenameId || commitId === filenameId, '文件身份不符');
  const n = r.i(); ensure(n >= 0 && n <= 64); const parents = Array.from({ length: n }, id);
  ensure(parents.every((p, i) => p !== commitId && (!i || parents[i - 1] < p)), '父引用无效');
  const size = r.i(); ensure(size <= MAX); const s = new Reader(r.take(size)); r.done();
  ensure(s.take(4).toString() === 'DPSS' && s.i() === 1, '不支持的快照版本');
  const payload = s.take(s.i()); ensure(hash(payload).equals(s.take(32)), '快照校验失败'); s.done();
  const p = new Reader(payload); ensure(p.t() === datasetId && p.t() === commitId && p.value('n') === null && p.l() === 1n && p.l() === 1n, '内外身份或固定字段不符');
  const state = Object.fromEntries(schemas.map(([name, max, key, schema]) => [name, p.rows(max, schema, key)])); p.done();
  validate(state); const c = { datasetId, commitId, writerId, parents, state };
  ensure(encode(c).equals(bytes), '非规范编码'); return c;
}

function time(s) {
  ensure(/^\d{2}:\d{2}(?::\d{2}(?:\.\d{3}(?:\d{3})?(?:\d{3})?)?)?$/.test(s), '非法节次时间');
  const t = Temporal.PlainTime.from(s);
  let canonical = `${String(t.hour).padStart(2, '0')}:${String(t.minute).padStart(2, '0')}`;
  const nano = t.millisecond * 1000000 + t.microsecond * 1000 + t.nanosecond;
  if (t.second || nano) canonical += `:${String(t.second).padStart(2, '0')}`;
  if (nano) canonical += '.' + String(nano).padStart(9, '0').slice(0, nano % 1000000 === 0 ? 3 : nano % 1000 === 0 ? 6 : 9);
  ensure(s === canonical, '非规范节次时间'); return t;
}
export function validate(v) {
  const nonblank = x => typeof x === 'string' && /[^\u0009-\u000d\u001c-\u0020\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000]/u.test(x);
  const range = (n, a, b) => Number.isInteger(n) && n >= a && n <= b;
  const optional = (n, a, b) => n == null || range(n, a, b);
  const span = r => ensure(BigInt(r.end) > BigInt(r.start) && BigInt(r.start) >= -62135510400000n && BigInt(r.end) <= 253402214400000n, '课程时间范围非法');
  for (const [name, max, key] of schemas) {
    ensure(Array.isArray(v[name]) && v[name].length <= max, '集合超限');
    const keys = v[name].map(r => key ? r[key] : r); ensure(keys.every(nonblank) && new Set(keys).size === keys.length, '空或重复身份');
  }
  for (const r of v.events) { ensure(nonblank(r.title)); span(r); }
  for (const r of v.entries) ensure(nonblank(r.title) && range(r.day, 1, 7) && range(r.startMinute, 0, 1439) && range(r.endMinute, 1, 1439) && r.endMinute > r.startMinute, '本地课程无效');
  const profiles = new Map(v.profiles.map(p => [p.termKey, p]));
  for (const p of v.profiles) {
    zone(p.timezone); ensure(date(p.week1Monday).dayOfWeek === 1 && p.phases.length >= 1 && p.phases.length <= 128);
    ensure(new Set(p.phases.map(x => x.effectiveFrom)).size === p.phases.length && p.phases.some(x => date(x.effectiveFrom).until(date(p.week1Monday)).sign >= 0), '学期阶段无效');
    for (const ph of p.phases) {
      date(ph.effectiveFrom); ensure(ph.periods.length >= 1 && ph.periods.length <= 30 && new Set(ph.periods.map(x => x.periodNumber)).size === ph.periods.length);
      for (const t of ph.periods) ensure(range(t.periodNumber, 1, 2147483647) && Temporal.PlainTime.compare(time(t.start), '05:00') >= 0 && Temporal.PlainTime.compare(time(t.end), '23:59') <= 0 && Temporal.PlainTime.compare(time(t.start), time(t.end)) < 0, '节次无效');
    }
  }
  ensure(v.customProfileKeys.every(k => profiles.has(k)), '自定义 catalog 悬空');
  const sources = new Map(v.sources.map(s => [s.id, s]));
  for (const s of v.sources) {
    const rows = v.imported.filter(r => r.sourceId === s.id); const starts = rows.map(r => BigInt(r.start)), ends = rows.map(r => BigInt(r.end));
    ensure(s.id.length <= 160 && /^[a-f0-9]{64}$/.test(s.sha256) && rows.length > 0 && rows.length === s.occurrenceCount && starts.reduce((a, b) => a < b ? a : b) === BigInt(s.firstStart) && ends.reduce((a, b) => a > b ? a : b) === BigInt(s.lastEnd), '来源摘要不符');
    ensure(termKey(s) === null || profiles.has(termKey(s)), '来源学期悬空');
    ensure(s.locator == null || !/https?:\/\/[^/]*@/i.test(s.locator), '来源含凭据');
  }
  let text = 0;
  for (const r of v.imported) {
    ensure(sources.has(r.sourceId) && r.seriesId.length >= 1 && r.seriesId.length <= 200 && nonblank(r.title)); span(r); zone(r.timezone);
    text += ['id', 'sourceId', 'seriesId', 'uid', 'title', 'timezone', 'location', 'description', 'periodLabel'].reduce((n, f) => n + (r[f]?.length ?? 0), 0);
  }
  ensure(text <= 4000000, '实例文本超限'); text = 0;
  for (const d of v.dayAdjustments) ensure((d.mode === 'none' || d.mode === 'weekday' && range(d.weekday, 1, 7) && d.weekday !== date(d.date).dayOfWeek) && (date(d.date), d.label.length <= 100), '调课日期无效');
  for (const e of v.classOverrides) {
    ensure(e.seriesId.length >= 1 && e.seriesId.length <= 200 && (e.sourceId === null ? v.entries.some(r => r.id === e.seriesId) : sources.has(e.sourceId)), '覆盖身份悬空');
    ensure(e.key === overrideKey(e.sourceId, e.seriesId, e.anchorDate), '覆盖 key 无效');
    if (e.anchorDate != null) date(e.anchorDate); if (e.date != null) date(e.date);
    ensure((e.title == null || nonblank(e.title) && e.title.length <= 160) && optional(e.weekday, 1, 7) && (e.anchorDate === null ? e.date == null : e.weekday == null) && optional(e.periodStart, 1, 30) && optional(e.periodEnd, 1, 30) && [null, 'linked', 'custom'].includes(e.timingMode ?? null) && optional(e.startMinute, 0, 1439) && optional(e.endMinute, 1, 1439) && ['location', 'teacher', 'note'].every(f => e[f] == null || e[f].length <= 500), '覆盖字段无效');
    text += ['key', 'sourceId', 'seriesId', 'anchorDate', 'title', 'date', 'timingMode', 'location', 'teacher', 'note'].reduce((n, f) => n + (e[f]?.length ?? 0), 0);
  }
  ensure(text <= 2000000, '覆盖文本超限');
}
