import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { decode, encode, hash, validate, overrideKey } from '../src/protocol.mjs';
export const vector = async name => readFile(new URL(`../../schedule-core/src/test/resources/sync-v2/${name}.dpc`, import.meta.url));
export const fixture = async () => decode(await vector('complex-root'));
for (const name of ['empty', 'complex-root', 'left', 'right', 'multi-parent']) test(`固定向量 ${name} 逐字节兼容`, async () => { const b = await vector(name); assert.deepEqual(encode(decode(b)), b); });
test('拒绝坏校验、截断、未知协议、文件名不符和非规范父序', async () => {
  assert.throws(() => decode(Buffer.alloc(100)));
  assert.throws(() => decode(Buffer.alloc(250)));
  const b = await vector('complex-root'); assert.throws(() => decode(b.subarray(0, -1)));
  assert.throws(() => decode(b, '11111111-1111-4111-8111-111111111111'));
  const unknown = Buffer.from(b); unknown.writeInt32BE(3, 4); hash(unknown.subarray(0, -32)).copy(unknown, unknown.length - 32); assert.throws(() => decode(unknown), /不支持/);
});
test('坏校验固定向量', async () => { const b = await vector('bad-checksum'); assert.throws(() => decode(b)); });
function reseal(b) { const start = 124 + b.readInt32BE(116) * 36; hash(b.subarray(start + 12, -64)).copy(b, b.length - 64); hash(b.subarray(0, -32)).copy(b, b.length - 32); return b; }
test('有效双层 SHA 不能掩盖领域错误、UTF-8 错误、nullable tag 或尾随字段', async () => {
  const original = await vector('complex-root');
  const domain = Buffer.from(original), title = domain.indexOf(Buffer.from('日历')); domain.writeBigInt64BE(domain.readBigInt64BE(title + 6), title + 14); assert.throws(() => decode(reseal(domain)), /时间范围/);
  const utf = Buffer.from(original); utf[title] = 0xc0; assert.throws(() => decode(reseal(utf)));
  const tag = Buffer.from(original); tag[124 + 12 + 80] = 2; assert.throws(() => decode(reseal(tag)), /tag/);
  const multi = Buffer.from(await vector('multi-parent')); const a = Buffer.from(multi.subarray(120, 156)); multi.copy(multi, 120, 156, 192); a.copy(multi, 156); hash(multi.subarray(0, -32)).copy(multi, multi.length - 32); assert.throws(() => decode(multi), /父引用/);
  const trail = Buffer.concat([original.subarray(0, -32), Buffer.from([0]), Buffer.alloc(32)]); hash(trail.subarray(0, -32)).copy(trail, trail.length - 32); assert.throws(() => decode(trail), /尾随/);
});
const mutations = [
  v => v.events[0].title = ' ', v => v.events[0].end = v.events[0].start,
  v => v.entries[0].day = 0, v => v.entries[0].endMinute = 1440,
  v => v.sources[0].sha256 = 'A'.repeat(64), v => v.sources[0].occurrenceCount++, v => v.sources[0].firstStart++,
  v => v.sources[0].locator = 'https://a:b@host/file', v => v.sources[0].termKey = 'missing',
  v => v.imported[0].sourceId = 'missing', v => v.imported[0].timezone = 'Not/AZone', v => v.imported[0].seriesId = '',
  v => v.imported[0].description = 'a'.repeat(4000001),
  v => v.dayAdjustments[0].weekday = 3, v => v.dayAdjustments[0].mode = 'unknown', v => v.dayAdjustments[0].date = '2026-02-30',
  v => v.classOverrides[0].key = 'bad', v => v.classOverrides[0].date = '2026-10-12',
  v => v.classOverrides[0].timingMode = 'auto', v => v.classOverrides[0].periodStart = 31,
  v => v.classOverrides[0].location = 'a'.repeat(501),
  v => v.profiles[0].week1Monday = '2026-09-08', v => v.profiles[0].phases = [],
  v => v.profiles[0].phases[0].periods[0].start = '08:05:00', v => v.profiles[0].phases[0].periods[0].end = '23:59:01',
  v => v.customProfileKeys.push('missing'), v => v.events.push(v.events[0])
];
for (const [i, mutate] of mutations.entries()) test(`完整领域校验 ${i + 1}`, async () => { const c = await fixture(); mutate(c.state); assert.throws(() => validate(c.state)); });
test('UTF-16 key、空字符串与稀疏覆盖、完整 int64 保真', async () => {
  const c = await fixture(); c.state.classOverrides[0].periodEnd = null; c.state.imported[0].originalStart = -9223372036854775808n;
  assert.equal(decode(encode(c)).state.imported[0].originalStart, -9223372036854775808n);
  assert.equal(overrideKey('😀', '课', null), 'I:2:😀:1:课:all');
});
