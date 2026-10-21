import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { decode, overrideKey } from '../src/protocol.mjs';
import { projectWeek } from '../src/projection.mjs';
const fixture = async () => decode(await readFile(new URL('../../schedule-core/src/test/resources/sync-v2/complex-root.dpc', import.meta.url))).state;
test('全周 → 日期调课 → 指定周，保持原始 anchor', async () => {
  const s = await fixture(); s.imported = []; s.sources = []; s.classOverrides = [];
  s.entries[0].day = 1;
  s.classOverrides.push({ key: overrideKey(null, 'local', null), sourceId: null, seriesId: 'local', anchorDate: null, weekday: 2, title: '全周标题' });
  s.dayAdjustments = [{ date: '2026-10-14', mode: 'weekday', weekday: 2, label: '复制周二' }];
  s.classOverrides.push({ key: overrideKey(null, 'local', '2026-10-14'), sourceId: null, seriesId: 'local', anchorDate: '2026-10-14', date: '2026-10-15', location: '指定周教室' });
  const rows = projectWeek(s, '2026-10-12'); assert.equal(rows.length, 2); assert.equal(rows[1].date, '2026-10-15'); assert.equal(rows[1].anchorDate, '2026-10-14'); assert.equal(rows[1].title, '全周标题'); assert.equal(rows[1].location, '指定周教室'); assert.equal(rows[1].copiedFrom, '2026-10-13');
});
test('不复制来源日停课；无效覆盖回退；目标日停课过滤', async () => {
  const s = await fixture(); s.imported = []; s.sources = []; s.entries[0].day = 1;
  s.classOverrides = [{ sourceId: null, seriesId: 'local', anchorDate: null, key: overrideKey(null, 'local', null), startMinute: 900 }];
  s.dayAdjustments = [{ date: '2026-10-12', mode: 'none' }, { date: '2026-10-14', mode: 'weekday', weekday: 1 }];
  const rows = projectWeek(s, '2026-10-12'); assert.equal(rows.length, 1); assert.equal(rows[0].date, '2026-10-14'); assert.equal(rows[0].startMinute, 600);
});
test('学期阶段按调课目标日期与 profile 时区联动', async () => {
  const s = await fixture(); s.entries = [];
  // 原向量实例在 2026-10-12 周一，复制至周三时改用新阶段。
  s.profiles[0].phases.push({ effectiveFrom: '2026-10-14', periods: [{ periodNumber: 1, start: '09:00', end: '09:45' }] });
  const rows = projectWeek(s, '2026-10-12'); assert.equal(rows.length, 2); assert.equal(rows[0].startMinute, 485); assert.equal(rows[1].startMinute, 540);
});
test('DST 缺口与重叠采用 Java atZone 等价规则', async () => {
  const s = await fixture(); s.sources = []; s.imported = []; s.classOverrides = []; s.dayAdjustments = [];
  s.entries = [{ id: 'dst', title: 'DST', day: 7, startMinute: 150, endMinute: 240, location: '' }];
  const spring = projectWeek(s, '2026-03-02', 'America/New_York'); assert.equal(new Date(spring[0].start).toISOString(), '2026-03-08T07:30:00.000Z');
  s.entries[0].startMinute = 90; const autumn = projectWeek(s, '2026-10-26', 'America/New_York'); assert.equal(new Date(autumn[0].start).toISOString(), '2026-11-01T05:30:00.000Z');
});
test('跨日实例保留真实结束时间与相邻学术周', async () => {
  const s = await fixture(); s.entries = []; s.classOverrides = []; s.dayAdjustments = [];
  const r = s.imported[0]; r.start = BigInt(Date.parse('2026-10-11T15:30:00Z')); r.end = BigInt(Date.parse('2026-10-11T17:00:00Z'));
  const rows = projectWeek(s, '2026-10-12'); assert.equal(rows.length, 1); assert.equal(rows[0].date, '2026-10-11'); assert.equal(rows[0].end, Number(r.end));
});
