import { readFile } from 'node:fs/promises';
import { LocalGrants } from '../../src/local.mjs';
import { Budget, V3LocalStore, objectPath } from '../../src/v3-store.mjs';
const grants = new LocalGrants([{ id: 'test', path: process.argv[2] }]);
const d = await grants.directory('test'); const config = { rootId: 'test', relative: '', directoryIdentity: d.stamp }; await d.fd.close();
const raw = await readFile(new URL('../../../schedule-core/src/test/resources/sync-v3/genesis-empty.dpv', import.meta.url));
const store = new V3LocalStore(config, grants, new Budget(), async (stage, phase) => {
  if (phase !== process.argv[3]) return;
  process.on('message', () => {}); // Keep the IPC handle alive until the parent sends SIGKILL.
  process.send({ stage, phase }); await new Promise(() => {});
});
const { decode } = await import('../../src/v3-protocol.mjs');
await store.createForIsolatedTest(objectPath(decode(raw)), raw, async () => {});
