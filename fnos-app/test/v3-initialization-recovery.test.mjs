import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { V3Journal } from '../src/v3-transaction.mjs';
import { V3Engine } from '../src/v3-engine.mjs';
import { decode } from '../src/v3-protocol.mjs';

for (const phase of ['before-commit', 'after-commit']) test(`create: ${phase} 首次绑定中断，不产生无候选绑定或第二个数据集`, async t => {
  const directory = await mkdtemp(join(tmpdir(), 'pr55-create-')), writer = randomUUID();
  let journal = new V3Journal(directory); await journal.open();
  const objects = new Map(); let puts = 0, expected;
  const remote = { read: async p => objects.get(p) ?? null,
    putOnce: async (p, bytes) => { puts++; assert(!objects.has(p)); objects.set(p, bytes); } };
  journal.meta.fault = p => {
    const frame = journal.meta.get('state', 'frame');
    if (p === phase && frame?.binding) {
      expected = frame.binding.datasetId;
      assert.equal(frame.initialization.length, 2);
      assert.equal(decode(Buffer.from(frame.initialization[0], 'base64')).datasetId, expected);
      assert.equal(decode(Buffer.from(frame.initialization[1], 'base64')).datasetId, expected);
      throw Error('injected first binding');
    }
  };
  await assert.rejects(new V3Engine(journal, writer).initialize(remote), /injected/);
  assert.equal(puts, 0); await journal.close(); journal = new V3Journal(directory); await journal.open();
  t.after(() => journal.close());
  if (phase === 'before-commit') assert.equal(journal.value.binding, undefined);
  else { assert.equal(journal.value.binding.datasetId, expected); assert.equal(journal.value.initialization.length, 2); }
  const binding = await new V3Engine(journal, writer).initialize(remote);
  if (phase === 'after-commit') assert.equal(binding.datasetId, expected);
  assert.equal(new Set([...objects.values()].map(b => decode(b).datasetId)).size, 1);
  assert.equal(puts, 2); assert.equal(journal.value.initialization.length, 0);
});
