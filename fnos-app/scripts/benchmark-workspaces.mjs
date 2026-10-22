// 用法：node scripts/benchmark-workspaces.mjs prepare <fixture-dir>
//       node scripts/benchmark-workspaces.mjs measure <fixture-dir> <baseline-src-dir>
// 每个样本使用独立 Node 进程；相同目录、热文件缓存，不宣称冷盘性能。
import { mkdir, readFile, stat, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { randomUUID } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { Store, json } from '../src/store.mjs';
import { Workspace } from '../src/workspace.mjs';
import { emptyState } from '../src/merge.mjs';
import { encode, hash } from '../src/protocol.mjs';
import { loadGraph } from '../src/graph.mjs';
const [mode, directory, baseline, countArg, sizeArg] = process.argv.slice(2);
const counts = [1, 8, 32, 64];
if (mode === 'prepare') {
  for (const large of [false, true]) for (const count of counts) {
    const dir = join(directory, `${large ? 'large' : 'small'}-${count}`), store = await new Store(dir).init(), rows = [];
    for (let i = 0; i < count; i++) {
      const id = randomUUID(), child = new Store(join(dir, 'workspaces', id)); child.identity = store.identity;
      const db = await Workspace.open(child), state = emptyState();
      for (let n = 0; n < (large ? 40 : 4); n++) state.entries.push({ id: 'course-' + n, title: '合成课程 ' + n, day: n % 7 + 1, startMinute: 480, endMinute: 530, location: '测试教室' });
      const datasetId = randomUUID(), commits = new Map(); let parents = [];
      for (let n = 0; n < (large ? 128 : 2); n++) {
        state.entries[0].title = '课程历史 ' + n;
        const commitId = randomUUID(), bytes = encode({ datasetId, commitId, writerId: store.identity.writerId, parents, state });
        commits.set(commitId, bytes); parents = [commitId];
      }
      const graph = await loadGraph({ list: async () => [...commits.keys()], read: async id => commits.get(id) });
      await db.tx(f => { Object.assign(f, { local: graph.state, baseline: graph.state, datasetId, graph, frontier: graph.tips, checkpoint: { datasetId, hashes: graph.hashes, deferred: [] } }); });
      rows.push({ id, name: '合成工作区 ' + i, connectionId: null });
    }
    await store.save('catalog.json', { schema: 1, revision: 0, activeId: rows[0].id, workspaces: rows, connections: [] });
    console.log(json({ prepared: dir, count, large }));
  }
} else if (mode === 'sample') {
  const { Store: SampleStore } = await import(pathToFileURL(join(resolve(baseline), 'store.mjs'))), { Catalog } = await import(pathToFileURL(join(resolve(baseline), 'catalog.mjs')));
  let reads = 0, bytes = 0, workspaceReads = 0;
  const read = SampleStore.prototype.read;
  SampleStore.prototype.read = async function(name) {
    reads++; if (name === 'workspace.json') workspaceReads++;
    try { bytes += (await stat(join(this.dir, name))).size; } catch { /* 不存在的迁移输入 */ }
    return read.call(this, name);
  };
  const ioBefore = await readFile('/proc/self/io', 'utf8'), start = performance.now();
  const store = await new SampleStore(directory).init(), catalog = await Catalog.open({ store, configPath: join(directory, 'unused.json') });
  const startupMs = performance.now() - start, startupRss = process.memoryUsage().rss, loadedAtStart = catalog.workspaces.size;
  await catalog.summary(); const listReads = workspaceReads, startupReads = reads, startupReadBytes = bytes;
  const ioAfter = await readFile('/proc/self/io', 'utf8');
  const targetId = catalog.manifest.workspaces.at(-1).id, switchStart = performance.now();
  await catalog.manage({ action: 'workspace.switch', catalogVersion: catalog.manifest.revision, targetId }); catalog.prune?.();
  const switchMs = performance.now() - switchStart;
  const cpu = process.cpuUsage(), idleStart = performance.now(); await new Promise(r => setTimeout(r, 1000));
  const idleCpu = process.cpuUsage(cpu), idleMs = performance.now() - idleStart;
  const physical = s => Number(/read_bytes: (\d+)/.exec(s)[1]);
  console.log(json({ count: Number(countArg), size: sizeArg, startupMs, startupRss, loadedAtStart, applicationReads: startupReads, applicationReadBytes: startupReadBytes,
    workspaceReadsThroughList: listReads, physicalReadBytes: physical(ioAfter) - physical(ioBefore), switchMs, loadedAfterPrune: catalog.workspaces.size,
    idleCpuPercent: (idleCpu.user + idleCpu.system) / 1000 / idleMs * 100, idleRss: process.memoryUsage().rss }));
} else if (mode === 'measure') {
  const results = [];
  for (const size of ['small', 'large']) for (const count of counts) for (const [label, src] of [['before', baseline], ['after', resolve('src')]]) for (let repeat = 0; repeat < 3; repeat++) {
    const dir = join(directory, `${size}-${count}`), manifest = JSON.parse(await readFile(join(dir, 'catalog.json'), 'utf8'));
    manifest.activeId = manifest.workspaces[0].id; await writeFile(join(dir, 'catalog.json'), json(manifest));
    const run = spawnSync(process.execPath, ['--max-old-space-size=192', process.argv[1], 'sample', dir, src, String(count), size], { encoding: 'utf8', timeout: 120000 });
    if (run.status !== 0) throw Error(run.stderr || run.error?.message);
    const row = { label, repeat, ...JSON.parse(run.stdout) }; results.push(row); console.log(json(row));
  }
  await writeFile(join(directory, 'results.json'), json({ node: process.version, platform: process.platform, arch: process.arch, memoryLimitMiB: 192, cache: 'warm', results }));
} else throw Error('需要 prepare / measure / sample 参数');
