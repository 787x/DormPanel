import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, readFile, readdir } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { V3Journal } from '../src/v3-transaction.mjs';
import { V3Discovery } from '../src/v3-history.mjs';
import { datasetScope, epochScope, Budget } from '../src/v3-store.mjs';
import { randomUUID } from 'node:crypto';
import { emptyState } from '../src/merge.mjs';
import { V3Engine } from '../src/v3-engine.mjs';
import { encode, sha, ZERO, ZERO_HASH } from '../src/v3-protocol.mjs';

test('pages: 超过旧 scopes/observed 边界，冷打开与单项更新不读写累计历史', async () => {
  const root = await mkdtemp(join(tmpdir(), 'v3-pages-')); let j = await new V3Journal(root).open();
  for (let page = 0; page < 1100; page++) j.meta.atomic(() => { for (let n = 0; n < 64; n++) { const k = String(page * 64 + n), row = { path: k, padding: 'x'.repeat(768) }; j.meta.put('scope', k, row); j.meta.put('observed', k, row); } });
  assert.equal(j.meta.count('scope'), 70400); await j.close();
  j = await new V3Journal(root).open(); const before = { ...j.meta.metrics }; let cursor = 0, total = 0;
  while (true) { const rows = j.meta.page('observed', cursor, 64); if (!rows.length) break; cursor = rows.at(-1).seq; total += rows.length; }
  assert.equal(total, 70400); const scanned = { ...j.meta.metrics };
  await j.transaction(s => { s.revision = 1; });
  assert(j.meta.metrics.reads - scanned.reads < 4); assert(j.meta.metrics.writes - scanned.writes < 4);
  assert(before.reads <= 2); await j.close();
});
test('atomic: 索引与 receipt 同事务，抛错两者均回滚', async () => {
  const root = await mkdtemp(join(tmpdir(), 'v3-atomic-')), j = await new V3Journal(root).open();
  await assert.rejects(j.transaction(s => { j.meta.put('node', 'candidate', { hash: 'original' }); s.receipt = 'candidate'; throw Error('before commit'); }));
  assert.equal(j.meta.get('node', 'candidate'), null); assert.equal(j.value.receipt, undefined);
  await j.transaction(s => { j.meta.put('node', 'candidate', { hash: 'original' }); s.receipt = 'candidate'; });
  await j.close(); const reopened = await new V3Journal(root).open(); assert.equal(reopened.value.receipt, 'candidate'); assert.equal(reopened.meta.get('node', 'candidate').hash, 'original'); await reopened.close();
});
test('owner: 第二个拥有者失败且不能修改获胜者状态；关闭后可恢复', async () => {
  const root = await mkdtemp(join(tmpdir(), 'v3-owner-')), a = await new V3Journal(root).open();
  await a.transaction(s => { s.local = 'saved'; });
  await assert.rejects(new V3Journal(root).open(), /writer|locked/);
  assert.equal(a.value.local, 'saved'); await a.close();
  const b = await new V3Journal(root).open(); assert.equal(b.value.local, 'saved'); await b.close();
});
test('owner: 旧 Draft 与生产 v2 私有目录拒绝隐式接管且原文件不变', async () => {
  for (const name of ['v3-engine-v1.json', 'workspace.json', 'schedule.db']) {
    const root = await mkdtemp(join(tmpdir(), 'v3-refuse-')); await writeFile(join(root, name), 'preserved');
    await assert.rejects(new V3Journal(root).open(), /INCOMPATIBLE|production/);
    assert.equal(await readFile(join(root, name), 'utf8'), 'preserved'); assert.deepEqual(await readdir(root), [name]);
  }
});
test('fair: 单请求轮次中断与持续新任务不会饿死旧前缀巡检', async () => {
  const root = await mkdtemp(join(tmpdir(), 'v3-fair-')), dataset = randomUUID(), old = epochScope(dataset, randomUUID()); let j = await new V3Journal(root).open(), budget;
  const visits = [], remote = { list: async scope => { budget.charge(); visits.push(scope); return []; } };
  j.meta.atomic(() => { for (const path of [datasetScope(dataset), old]) j.meta.put('scope', path, { path }); });
  for (let n = 0; n < 30; n++) {
    budget = new Budget({ requests: 1 }); j.meta.budget = budget;
    j.meta.atomic(() => { for (let k = 0; k < 5; k++) { const path = epochScope(dataset, randomUUID()); j.meta.put('scope', path, { path }); j.meta.put('scan', path, { path }); } });
    await assert.rejects(new V3Discovery(j, remote, dataset).scan(2), /budget/); assert.equal(j.meta.count('error'), 0);
    if (n % 5 === 0) { await j.close(); j = await new V3Journal(root).open(); }
  }
  assert(visits.filter(p => p === old).length >= 2); assert(j.meta.page('scan', 0, 1).length); await j.close();
});
test('snapshots: 大量控制字符不放大控制 Journal，精确快照引用与事务同提交', async () => {
  const root = await mkdtemp(join(tmpdir(), 'v3-snapshots-')), j = await new V3Journal(root).open();
  const note = '\0'.repeat(1000000), state = { ...emptyState(), events: Array.from({ length: 9 }, (_, n) => ({ id: String(n), title: 'event', start: 0n, end: 1000n, note })) };
  await j.transaction(s => { s.local = state; s.baseline = state; s.capture = state; });
  assert.equal(j.meta.count('snapshot'), 1); assert(j.meta.db.prepare("SELECT length(v) AS n FROM v3_records WHERE ns='state'").get().n < 4096);
  const writes = j.meta.metrics.writes; await j.transaction(s => { s.phase = 'control-only'; }); assert.equal(j.meta.metrics.writes - writes, 1);
  await j.close(); const reopened = await new V3Journal(root).open(); assert.equal(reopened.value.local.events[0].note, note); assert.equal(reopened.value.baseline.events[8].end, 1000n); await reopened.close();
});
test('local: 未绑定编辑与过期网络预算解耦，绑定不静默清空本地状态', async () => {
  const root = await mkdtemp(join(tmpdir(), 'v3-local-')), j = await new V3Journal(root).open(), e = new V3Engine(j, randomUUID());
  const state = { ...emptyState(), entries: [{ id: 'saved', title: 'saved', day: 1, startMinute: 480, endMinute: 540, location: '' }] }; await e.edit(state);
  const expired = new Budget({ millis: 0 }); j.meta.budget = expired; j.meta.verifiedSnapshots.clear(); await e.edit(state); assert.equal(j.meta.budget, expired); assert.equal(j.value.local.entries[0].id, 'saved');
  const d = { type: 1, datasetId: randomUUID(), objectId: randomUUID(), writerId: randomUUID(), epochId: ZERO, epochNumber: 0, descriptorHash: ZERO_HASH, refs: [], state: null };
  await assert.rejects(e.bind(d, sha(encode(d)), emptyState()), /preserve/); assert.equal(j.value.binding, undefined);
  await e.bind(d, sha(encode(d))); assert.equal(j.value.local.entries[0].id, 'saved'); assert(j.value.unbasedLocal); await j.close();
});
