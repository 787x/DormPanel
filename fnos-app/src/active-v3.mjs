import { join } from 'node:path';
import { ensure } from './protocol.mjs';
import { same, emptyState } from './merge.mjs';
import { openV3Workspace } from './v3-workspace.mjs';
import { V3Engine } from './v3-engine.mjs';
import { Budget, V3WebDavStore, V3LocalStore, datasetScope, match } from './v3-store.mjs';
import { IndexedV3Cache } from './v3-index.mjs';
import { sha } from './v3-protocol.mjs';
import { DatabaseSync } from 'node:sqlite';
import { uuid } from './protocol.mjs';
import { V3Maintenance } from './v3-maintenance.mjs';

/** Catalog owns lifetime, permissions and endpoint configuration. The facade projects
 * the one SQLite Workspace into existing business services; it never persists a v2 frame. */
export async function openActiveV3(catalog, id, create = false) {
  let facade;
  const writable = () => {
    ensure(!catalog.maintenance && !catalog.invalidated, '正在备份恢复或工作区引用已失效');
    ensure(!facade || catalog.workspaces.get(id) === facade, '工作区引用已失效');
  };
  const adapter = await openV3Workspace(join(catalog.workspaceStore(id).dir, 'v3'), writable, catalog.store.identity,{requireExisting:!create});
  const { journal, workspace } = adapter;
  // Use the installation identity already owned by the host service, including after restart.
  const stored = journal.value.writerId;
  ensure(!stored || stored === catalog.store.identity.writerId, 'v3 安装 Writer 身份变化');
  const engine = new V3Engine(journal, catalog.store.identity.writerId);
  const readLocal = operation => {
    const previous = journal.meta.budget; journal.meta.budget = new Budget({ requests: 0, bytes: 2 * 1024 * 1024, millis: 5000 });
    try { return operation(); } finally { journal.meta.budget = previous; }
  };
  const frame = () => {
    const f = workspace.frame, s = journal.value;
    return { ...f, protocol: 'v3', datasetId: s.binding?.datasetId ?? null,
      baseline: s.baseline ?? null, pending: s.pending?.phase === 'RECEIPTED' ? null : s.pending,
      conflict: s.conflict ?? null, receipt: s.receipt ?? null,
      graph: { status: f.error ? 'error' : s.conflict ? 'conflict' : s.lastRound?.status ?? 'unchecked',
        tips: readLocal(() => journal.meta.page('frontier', 0, 65).map(r => r.key)), checkedAt: s.lastRound?.checkedAt ?? null },
      v3: s };
  };
  const runtime = {
    journal, engine,
    bindingIdentity() { const b=workspace.frame.v3?.binding; return b ? structuredClone(b) : null; },
    migrationHeader(id) { return readLocal(()=>journal.meta.get('hostMigrationHeader',id)); },
    history(input = {}) { return readLocal(() => new V3Maintenance(catalog,id).list(input)); },
    async synchronize(options = {}) {
      const budget = new Budget(), remote = remoteV3(catalog, id, budget);
      ensure(!options.discover, '只读发现必须使用 Catalog.discover，禁止进入编辑事务');
      const current = journal.value;
      if (options.bind && current.binding) ensure(options.descriptorPath === current.binding.descriptorPath && options.descriptorHash === current.binding.descriptorHash, '已有活动数据集，禁止隐式换库');
      if (!current.binding && options.bind) {
        ensure(same(current.local, emptyState()) && !current.pending && !current.conflict, '本地非空或有恢复状态，禁止覆盖绑定；请保留原工作区并新建空工作区加入');
        ensure(options.descriptorPath && /^[0-9a-f]{64}$/.test(options.descriptorHash ?? ''), '请选择已验证的 v3 Descriptor 身份');
        const bytes = await remote.read(options.descriptorPath);
        ensure(bytes && sha(bytes) === options.descriptorHash, 'v3 Descriptor 身份变化');
        const descriptor = match(options.descriptorPath, bytes); ensure(descriptor.type === 1, '不是 Descriptor');
        const identities = await catalog.identities();
        ensure(!catalog.manifest.workspaces.some(w => w.id !== id && identities.get(w.id) === descriptor.datasetId), '数据集已绑定其他工作区');
        // Existing courses are retained in their original workspace; joining starts empty.
        await journal.transaction(s => { s.hostIntent = 'JOIN'; }, true);
        await engine.bind(descriptor, options.descriptorHash);
      } else if (options.create) {
        if (!current.binding || current.initialization?.length) await engine.initialize(remote);
        else ensure(current.baselinePath, 'v3 创建需要恢复原候选');
      }
      ensure(journal.value.binding, '请选择创建或已验证数据集，禁止隐式绑定');
      let result;
      try { result = await engine.round(remote, new IndexedV3Cache(journal.directory, budget), budget, { discoverySteps: 32 }); }
      catch (error) {
        if (!/budget|initial discovery\/validation incomplete/.test(error.message)) throw error;
        result = { status: /budget/.test(error.message) ? 'BUDGET' : 'DISCOVERING', oldHistoryAuditComplete: false };
      }
      await journal.transaction(s => { s.lastRound = { ...result, checkedAt: Date.now(), oldHistoryAuditComplete: false }; }, true);
      return result;
    },
    async resolve(choice, token, version) {
      ensure(version === workspace.frame.revision && token === journal.value.conflict?.id, '冲突版本过期');
      await engine.resolve(token, choice);
    },
    async close() { await journal.close(); }
  };
  facade = new Proxy(workspace, {
    get(target, key) {
      if (key === 'frame') return frame();
      if (key === 'activeV3') return runtime;
      if (key === 'isV3InternalFrame') return adapter.isInternalFrame;
      if (key === 'dirty') return () => !same(journal.value.baseline ?? emptyState(), journal.value.local);
      if (key === 'conflictToken') return () => journal.value.conflict?.id ?? null;
      const value = Reflect.get(target, key); return typeof value === 'function' ? value.bind(target) : value;
    }
  });
  return facade;
}

/** Catalog-only identity read. A read-only SQLite connection verifies control-row checksums
 * without unpacking local/baseline RAW snapshots or creating another Workspace/Writer. */
export function readV3WorkspaceIdentity(directory,writerId) {
  const db=new DatabaseSync(join(directory,'v3-state-v2.sqlite'),{readOnly:true});
  try {
    const raw=(ns,key)=>{
      const row=db.prepare('SELECT v,sum FROM v3_records WHERE ns=? AND k=? AND length(v)<=?').get(ns,key,48*1024*1024);
      ensure(row && sha(Buffer.from(ns+'\0'+key+'\0'+row.v))===row.sum,'v3 控制记录缺失或校验失败');
      return JSON.parse(row.v);
    };
    ensure(raw('format','version').value===2,'v3 私有格式不兼容');
    ensure(raw('installation','identity').writerId===writerId,'v3 安装 Writer 身份变化');
    const frame=raw('workspace','workspace.json'); ensure(frame.version===1 && !frame.datasetId,'v3 工作区身份异常');
    const b=frame.v3?.binding; if(!b)return null;
    uuid(b.datasetId); ensure(frame.v3.writerId===writerId && /^[0-9a-f]{64}$/.test(b.descriptorHash) && b.descriptorPath.startsWith(datasetScope(b.datasetId)+'descriptor-'),'v3 绑定身份异常');
    return {datasetId:b.datasetId,descriptorHash:b.descriptorHash};
  } finally { db.close(); }
}

export function remoteV3(catalog, id, budget) {
  const row = catalog.row(id); ensure(row.connectionId, '请先添加连接并创建工作区');
  return remoteV3Connection(catalog,row.connectionId,budget);
}
export function remoteV3Connection(catalog,connectionId,budget) {
  const c = catalog.connection(connectionId);
  return catalog.v3RemoteFactory ? catalog.v3RemoteFactory(c, budget)
    : c.type === 'local' ? new V3LocalStore(c.config, catalog.grants, budget) : new V3WebDavStore(c.config, budget);
}

/** Bounded read-only identity discovery. No unique-candidate auto binding. */
export async function discoverV3(remote) {
  const candidates = [];
  for (const scope of await remote.list('')) {
    if (!/^dataset-[0-9a-f-]{36}\/$/.test(scope)) continue;
    const datasetId = scope.slice(8, -1); datasetScope(datasetId);
    for (const path of await remote.list(scope)) {
      if (!path.startsWith(scope + 'descriptor-')) continue;
      try {
        const bytes = await remote.read(path); ensure(bytes, 'Descriptor 缺失');
        const descriptor = match(path, bytes); ensure(descriptor.type === 1 && descriptor.datasetId === datasetId);
        candidates.push({ datasetId, descriptorPath: path, descriptorHash: sha(bytes), state: 'DESCRIPTOR_VERIFIED', historyVerified: false });
      } catch { candidates.push({ datasetId, descriptorPath: path, state: 'UNVERIFIABLE', historyVerified: false }); }
    }
  }
  return { protocol: 'v3', candidates, completeDirectoryView: false, otherWritersKnown: false };
}
