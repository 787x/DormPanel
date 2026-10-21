import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm, readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Store } from '../src/store.mjs';
import { decode } from '../src/protocol.mjs';
import { loadGraph } from '../src/graph.mjs';
test('重建缓存重新校验原始字节，保留 int64 与异常清单', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'dp-state-')); t.after(() => rm(dir, { recursive: true, force: true }));
  const bytes = await readFile(new URL('../../schedule-core/src/test/resources/sync-v2/complex-root.dpc', import.meta.url)), c = decode(bytes);
  const g = await loadGraph({ list: async () => [c.commitId], read: async () => bytes });
  const s = await new Store(dir).init(); await s.save('graph.json', g); const restored = await (await new Store(dir).init()).restore();
  assert.equal(restored.state.imported[0].start, c.state.imported[0].start); assert.equal(s.identity.writerId, (await new Store(dir).init()).identity.writerId);
  g.hashes[c.commitId] = '0'.repeat(64); await s.save('graph.json', g); await assert.rejects(s.restore(), /本地缓存身份/);
});
