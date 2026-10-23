import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { previewImport } from '../src/import.mjs';
const raw = readFileSync(new URL('../../schedule-core/src/test/resources/schedule/hubei_2026-2027-1.xls', import.meta.url));
test('BIFF8 公式、FILEPASS、越界单元格和字符串索引拒绝', () => {
  const cell = raw.indexOf(Buffer.from('fd000a00', 'hex'));
  assert(cell > 0);
  for (const mutate of [b => b.writeUInt16LE(6, cell), b => b.writeUInt16LE(0x2f, cell), b => b.writeUInt16LE(256, cell + 4), b => b.writeUInt16LE(64, cell + 6), b => b.writeUInt32LE(0x7fffffff, cell + 10)]) {
    const b = Buffer.from(raw); mutate(b);
    assert.throws(() => previewImport(b, 'bad.xls', { profiles: [] }));
  }
});
