// 开发测试入口：stdin JSON 指定测试 WebDAV 父目录；只创建随机子目录，不触碰绑定或旧协议。
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { readFile, mkdtemp } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { WebDav } from '../src/webdav.mjs';
import { V3WebDavStore, Budget, objectPath, datasetScope, epochScope } from '../src/v3-store.mjs';
import { decode, sha } from '../src/v3-protocol.mjs';
import { V3Cache } from '../src/v3-cache.mjs';
import { Store } from '../src/store.mjs';

let input = ''; for await (const b of process.stdin) { input += b; assert(input.length <= 20000); }
const config = JSON.parse(input); assert(config.url && config.username && config.password);
const parent = new URL(config.url); assert(parent.pathname.endsWith('/') && !parent.pathname.includes('dormpanel-sync-v2'));
const probe = new URL(`pr53-${randomUUID()}/`, parent);
const transport = new WebDav(config); transport.base = probe;
await transport.request('MKCOL', probe, 65536);
const budget = new Budget(), remote = new V3WebDavStore({ ...config, url: probe.href }, budget);
const privateRoot = await mkdtemp(join(tmpdir(), 'dp-v3-webdav-')), cache = new V3Cache(join(privateRoot, 'sync-v3-cache-v1'), budget);
const journal = new Store(join(privateRoot, 'recovery')); await cache.recover();
for (const name of ['descriptor', 'genesis-empty', 'commit-complex', 'commit-multi', 'checkpoint', 'transition', 'bridge']) {
  const raw = await readFile(new URL(`../../schedule-core/src/test/resources/sync-v3/${name}.dpv`, import.meta.url));
  const o = decode(raw), path = objectPath(o), scope = o.type === 1 ? datasetScope(o.datasetId) : epochScope(o.datasetId, o.epochId);
  await remote.prepareForIsolatedTest(scope); await cache.put(path, raw);
  await journal.save('intent.json', { version: 1, path, hash: sha(raw), attempted: false });
  await remote.createForIsolatedTest(path, raw, () => journal.save('intent.json', { version: 1, path, hash: sha(raw), attempted: true }));
  assert.deepEqual(await remote.read(path), raw); assert((await remote.list(scope)).includes(path));
}
const reopened = new V3Cache(join(privateRoot, 'sync-v3-cache-v1'), new Budget()); assert.equal((await reopened.recover()).length, 7);
let reads = 0; for (const entry of reopened.index()) await reopened.load(entry.path, { read: async () => { reads++; throw Error('cache miss'); } });
assert.equal(reads, 0);
assert.equal((await remote.list('')).length, 1);
console.log(JSON.stringify({ result: 'PASS', isolatedPath: probe.pathname, objects: 7, cacheReopened: 7, remoteReadsOnCacheHit: reads }));
