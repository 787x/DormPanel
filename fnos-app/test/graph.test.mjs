import test from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { decode, encode, hash } from '../src/protocol.mjs';
import { loadGraph } from '../src/graph.mjs';
import { readFile } from 'node:fs/promises';
const get = async name => readFile(new URL(`../../schedule-core/src/test/resources/sync-v2/${name}.dpc`, import.meta.url));
async function remote(names) { const rows = await Promise.all(names.map(async n => { const b = await get(n); return [decode(b).commitId, b]; })); return adapter(new Map(rows)); }
function adapter(rows, listed = [...rows.keys()]) { return { list: async () => listed, read: async id => rows.get(id) ?? null }; }
test('多父 DAG、共同祖先与 tips', async () => { const g = await loadGraph(await remote(['complex-root', 'left', 'right', 'multi-parent'])); assert.equal(g.tips.length, 1); assert.equal(g.status, 'verified'); assert.equal(Object.keys(g.raw).length, 4); });
test('不同内容的多 Writer 分支不选任意最终结果', async () => { const g = await loadGraph(await remote(['complex-root', 'left', 'right'])); assert.equal(g.status, 'branches'); assert.equal(g.tips.length, 2); assert.equal(g.baseline.length, 1); assert.equal(g.state, null); });
test('缺父、不同数据集、多根、已知内容改变/缺失', async () => {
  await assert.rejects(loadGraph(await remote(['left'])), /祖先缺失/);
  await assert.rejects(loadGraph(await remote(['empty', 'complex-root'])), /dataset|根/);
  const b = await get('complex-root'), c = decode(b), other = { ...c, commitId: randomUUID() };
  await assert.rejects(loadGraph(adapter(new Map([[c.commitId, b], [other.commitId, encode(other)]]))), /多个无关根/);
  await assert.rejects(loadGraph(adapter(new Map()), { hashes: { [c.commitId]: hash(b).toString('hex') }, datasetId: c.datasetId }), /缺失/);
  await assert.rejects(loadGraph(adapter(new Map([[c.commitId, encode({ ...c, writerId: randomUUID() })]])), { hashes: { [c.commitId]: hash(b).toString('hex') } }), /改变/);
});
test('暂不可验证孤立文件持久化回查、不显示全部完成', async () => {
  const r = await remote(['complex-root']); const id = randomUUID(), ids = await r.list();
  const read = r.read; r.list = async () => [...ids, id]; r.read = async k => k === id ? Buffer.alloc(250) : read(k);
  let checkpoint; const g = await loadGraph(r, {}, async d => checkpoint = d); assert(checkpoint.includes(id)); assert.equal(g.status, 'incomplete'); assert(g.deferred.includes(id));
  r.list = async () => ids; let revisited = false; r.read = async k => { if (k === id) revisited = true; return read(k); };
  await loadGraph(r, g); assert(revisited);
});
test('完整封装未知语义不能隔离后采用', async () => { const c = decode(await get('complex-root')), b = encode(c); b.writeInt32BE(9, 4); hash(b.subarray(0, -32)).copy(b, b.length - 32); await assert.rejects(loadGraph(adapter(new Map([[c.commitId, b]]))), /不支持/); });
test('有环、等价 tips、目录遗漏但父闭包可读取', async () => {
  const root = decode(await get('complex-root')), a = { ...root, commitId: randomUUID(), parents: [root.commitId] }, b = { ...root, commitId: randomUUID(), parents: [root.commitId] };
  const rows = new Map([root, a, b].map(c => [c.commitId, encode(c)]));
  const g = await loadGraph(adapter(rows, [a.commitId, b.commitId])); assert.equal(g.status, 'verified'); assert.equal(g.tips.length, 2); assert.equal(Object.keys(g.raw).length, 3);
  a.parents = [b.commitId]; b.parents = [a.commitId]; await assert.rejects(loadGraph(adapter(new Map([a, b].map(c => [c.commitId, encode(c)])))), /有环/);
});
test('失败轮次中已验证身份哈希仍持久化', async () => {
  const c = decode(await get('complex-root')), b = encode(c); let last;
  await loadGraph(adapter(new Map([[c.commitId, b]])), {}, async (_, metadata) => { if (metadata?.hashes[c.commitId]) last = metadata; });
  assert.equal(last.hashes[c.commitId], hash(b).toString('hex'));
});
test('深祖先链不误用64个隔离路径上限', async () => {
  const root = decode(await get('empty')), rows = new Map([[root.commitId, encode(root)]]); let parent = root.commitId;
  for (let i = 0; i < 90; i++) { const c = { ...root, commitId: randomUUID(), parents: [parent] }; rows.set(c.commitId, encode(c)); parent = c.commitId; }
  const g = await loadGraph(adapter(rows, [parent])); assert.equal(Object.keys(g.raw).length, 91); assert.equal(g.status, 'verified');
});
