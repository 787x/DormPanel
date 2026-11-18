import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import http from 'node:http';
import { createApp, PREFIX } from '../src/server.mjs';

export async function productivityFixture(t, { url, format = 4, proxy = false, transport } = {}) {
  const directory = await mkdtemp(join(tmpdir(), 'dp-productivity-'));
  const objects = new Map();
  const memory = transport ?? {
    read:async path => objects.get(path) ?? null,
    putOnce:async (path, bytes) => {
      if (objects.has(path)) throw Error('immutable fixture path already exists');
      objects.set(path, Buffer.from(bytes));
    },
    list:async prefix => [...new Set([...objects.keys()].filter(path => path.startsWith(prefix)).map(path => {
      const tail = path.slice(prefix.length), slash = tail.indexOf('/');
      return slash < 0 ? path : prefix + tail.slice(0, slash + 1);
    }))].sort(),
  };
  const app = await createApp({ dataDir:directory, configPath:join(directory, 'config.json'),
    v3RemoteFactory:url ? undefined : () => memory });
  const socket = name => process.platform === 'win32' ? '\\\\.\\pipe\\pr89-' + randomUUID() : join(directory, name);
  const web = socket('web'), agent = socket('agent');
  const listen = (server, ...args) => new Promise(resolve => server.listen(...args, resolve));
  const close = server => new Promise(resolve => { server.closeAllConnections(); server.close(resolve); });
  await listen(app.server, web);
  await listen(app.agentServer, agent);
  const headers = { 'x-trim-userid':'1000', 'x-trim-isadmin':'true' };
  const frontend = proxy && http.createServer((req, res) => {
    const upstream = http.request({ socketPath:web, path:req.url, method:req.method, headers:{ ...req.headers, ...headers } }, response => {
      res.writeHead(response.statusCode, response.headers); response.pipe(res);
    });
    upstream.on('error', () => res.destroy());
    req.pipe(upstream);
  });
  if (frontend) await listen(frontend, 0, '127.0.0.1');
  t.after(async () => {
    if (frontend) await close(frontend);
    await Promise.all([close(app.server), close(app.agentServer)]);
    await app.backups.close();
    await app.catalog.close();
    await rm(directory, { recursive:true, force:true });
  });
  const request = (method, path, input, extra = {}) => new Promise((resolve, reject) => {
    const req = http.request({ socketPath:web, path:PREFIX + path, method,
      headers:{ ...headers, 'Content-Type':'application/json', ...extra } }, res => {
      const chunks = [];
      res.on('data', chunk => chunks.push(chunk));
      res.on('end', () => {
        const bytes = Buffer.concat(chunks);
        resolve({ status:res.statusCode, data:JSON.parse(bytes), bytes:bytes.length });
      });
    });
    req.on('error', reject);
    req.end(input && JSON.stringify(input));
  });
  const manage = input => app.catalog.manage({ catalogVersion:app.catalog.manifest.revision, ...input });
  const createWorkspace = async (name, protocol = 'v3') => {
    const connection = { type:'webdav', url:url ?? 'https://isolated.test/' + randomUUID() + '/',
      username:'fixture', password:'fixture', allowInsecureHttp:!!url };
    const { connectionId } = await manage({ action:'connection.save', name, ...connection });
    const { workspaceId } = await manage({ action:'workspace.add', connectionId, name, protocol });
    await manage({ action:'workspace.switch', workspaceId:app.catalog.manifest.activeId,
      version:app.db.frame.revision, targetId:workspaceId });
    return workspaceId;
  };
  if (format !== 2) {
    await createWorkspace('隔离共享课表');
    if (!url && format === 4) await app.db.activeV3.initializeSharedDataset(app.db.frame.revision);
    if (!url && format === 3) await app.catalog.network(app.catalog.manifest.activeId, engine => engine.synchronize({ create:true }));
  }
  const { data:{ csrf } } = await request('GET', '/api/schedule');
  const post = (path, input, extra) => request('POST', path, input, { 'X-DormPanel-CSRF':csrf, ...extra });
  const ctx = () => ({ workspaceId:app.catalog.manifest.activeId, datasetId:app.db.frame.datasetId,
    version:app.db.frame.revision, catalogVersion:app.catalog.manifest.revision });
  const read = (view, params = {}, workspaceId = ctx().workspaceId) =>
    request('GET', '/api/productivity?' + new URLSearchParams({ workspaceId, view, ...params }));
  const edit = (action, input, scope = ctx()) => post('/api/productivity/edit', { ...scope, action, ...input });
  async function sync() { return post('/api/sync', ctx()); }
  async function settle() {
    for (let n = 0; n < 10; n++) {
      const result = await sync();
      if (result.status !== 200) throw Error(JSON.stringify(result.data));
      const status = (await read('todo.lists')).data;
      if (status.conflict || !status.dirty && !status.pending && status.availability === 'ready') return status;
    }
    throw Error('共享效率数据未收敛');
  }
  async function joinDataset() {
    const { candidates } = await app.catalog.discover(ctx().workspaceId);
    return post('/api/sync', { ...ctx(), ...candidates[0], bind:true });
  }
  return { app, agent, request, post, ctx, read, edit, createWorkspace, sync, settle, joinDataset, transport:memory,
    url:frontend && `http://127.0.0.1:${frontend.address().port}${PREFIX}/` };
}
