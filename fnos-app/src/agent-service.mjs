import http from 'node:http';
import { randomBytes, createHmac, randomUUID } from 'node:crypto';
import { operations, describeOperations, findOperation, VERSION, BUDGET, errorValue, redact } from './operations.mjs';
const DAY = 86400000;
const canonical = value => JSON.stringify(value && typeof value === 'object' ? Array.isArray(value) ? value.map(v => JSON.parse(canonical(v))) : Object.fromEntries(Object.keys(value).sort().map(k => [k, JSON.parse(canonical(value[k]))])) : value);
const fail = code => { throw Object.assign(new Error(code), { agentCode:code }); };
export class AgentService {
  static async open({ store, catalog, dispatch, approvalSummary = () => ({}) }) {
    const self = new AgentService();
    Object.assign(self, { store, catalog, dispatch, approvalSummary, active:0, queue:Promise.resolve() });
    self.state = await store.read('agent-control.json') ?? { schema:1, key:randomBytes(32).toString('hex'), permissions:['read'], receipts:[], approvals:[], audit:[] };
    await self.save(); return self;
  }
  save() { return this.store.save('agent-control.json', this.state); }
  digest(value) { return createHmac('sha256', this.state.key).update(canonical(value)).digest('hex'); }
  async admin(input, user) {
    // 只由原管理 Socket + 网关管理员认证 + CSRF 路由调用，Agent IPC 没有此操作。
    const run = this.queue.then(async () => {
      if (input.action === 'permissions') {
        if (!Array.isArray(input.permissions) || input.permissions.some(p => !['read','edit','admin','maintenance'].includes(p))) fail('VALIDATION');
        this.state.permissions = [...new Set(['read', ...input.permissions])];
      } else if (input.action === 'approve') {
        const p = this.state.approvals.find(p => p.id === input.id && p.expires > Date.now() && !p.consumed);
        if (!p) fail('STALE');
        p.approvedBy = user; p.approvedAt = Date.now();
      } else fail('VALIDATION');
      this.audit('web', null, 'agent.' + input.action, 'ok', user);
      await this.save(); return this.adminSummary();
    });
    this.queue = run.catch(() => {}); return run;
  }
  adminSummary() { return { permissions:this.state.permissions, approvals:this.state.approvals.filter(p => p.expires > Date.now()).map(({ digest, ...p }) => p), audit:this.state.audit }; }
  audit(interfaceType, workspaceId, operation, result, identity = 'package-os', operationId = undefined) {
    this.state.audit.push({ at:new Date().toISOString(), interfaceType, workspaceId, operation, result, identity, operationId });
    this.state.audit = this.state.audit.slice(-256);
  }
  async execute(request, interfaceType) {
    if (++this.active > 8) { this.active--; return { ok:false, error:errorValue(null, 'LIMIT') }; }
    let intentSaved = false;
    try {
      const op = findOperation(request.operation);
      if (!op) fail('VALIDATION');
      const input = op.schema.parse(request.input ?? {});
      if (!this.state.permissions.includes(op.permission)) fail('PERMISSION');
      if (op.permission === 'read') return await this.perform(op, input);
      const run = this.queue.then(async () => {
        // 权限在排队完成后重新检查，降级立即生效。
        if (!this.state.permissions.includes(op.permission)) fail('PERMISSION');
        const now = Date.now(), operationId = request.operationId;
        if (typeof operationId !== 'string' || !/^\d{13}-[0-9a-f-]{36}$/.test(operationId)) fail('VALIDATION');
        const time = Number(operationId.slice(0,13));
        if (time > now + 60000 || time < now - DAY) fail('STALE');
        this.state.receipts = this.state.receipts.filter(r => r.time >= now - DAY);
        this.state.approvals = this.state.approvals.filter(p => p.expires > now);
        const digest = this.digest({ operation:op.name, input, operationId });
        const old = this.state.receipts.find(r => r.operationId === operationId);
        if (old) {
          if (old.digest !== digest) fail('STALE');
          return old.result ?? { ok:false, operationId, error:errorValue(null, 'UNKNOWN') };
        }
        if (op.name === 'approvals.request') {
          const target = findOperation(input.operation);
          if (!target?.approval || !this.state.permissions.includes(target.permission)) fail('PERMISSION');
          const targetInput = target.schema.parse(input.input);
          if (this.state.approvals.length >= 32) fail('LIMIT');
          if (!/^\d{13}-[0-9a-f-]{36}$/.test(input.targetOperationId)) fail('VALIDATION');
          const approval = { id:randomUUID(), operation:target.name, operationId:input.targetOperationId, digest:this.digest({ operation:target.name, input:targetInput, operationId:input.targetOperationId }), summary:{ parameters:redact(Object.fromEntries(Object.entries(targetInput).filter(([k]) => !['password','token','base64'].includes(k)))), preview:redact(await this.approvalSummary(target.name, targetInput)) }, expires:now + 10 * 60000 };
          if (Buffer.byteLength(JSON.stringify(approval)) > 16384) fail('LIMIT');
          this.state.approvals.push(approval); await this.save();
          return { ok:true, data:{ approvalId:approval.id, expires:approval.expires, next:'请管理员在 fnOS 管理网页核对操作摘要并批准；重试执行时带 approvalId' } };
        }
        const c = this.catalog();
        if (c.maintenance || c.invalidated) fail('BUSY');
        const db = await c.load(input.workspaceId);
        if (db.frame.datasetId !== input.datasetId || db.frame.revision !== input.version || c.manifest.revision !== input.catalogVersion) fail('STALE');
        if (op.approval) {
          const p = this.state.approvals.find(p => p.id === request.approvalId && p.digest === digest && p.approvedBy && !p.consumed && p.expires > now);
          if (!p) fail('APPROVAL');
          p.consumed = true;
        }
        if (this.state.receipts.length >= 512) fail('LIMIT');
        const receipt = { operationId, digest, time:now };
        this.state.receipts.push(receipt);
        // 先保存意图：崩溃后为 unknown，不重新执行可能已提交的业务操作。
        await this.save(); intentSaved = true;
        let result;
        try { result = await this.perform(op, input); }
        catch (e) { result = { ok:false, error:errorValue(e, e.agentCode) }; }
        result.operationId = operationId;
        receipt.result = result;
        // 回执持久化上限；大预览通过读取工具重新取得。
        if (Buffer.byteLength(JSON.stringify(result)) > 8192) receipt.result = { ok:result.ok, operationId, ...(result.error ? { error:result.error } : {}), data:{ ...Object.fromEntries(Object.entries(result.data ?? {}).filter(([k]) => ['version','catalogVersion','datasetId','workspaceId','currentWorkspaceId','resultWorkspaceId','resultDatasetId','id','token','dirty','pending','receipt','status','archive','candidate','name'].includes(k))), resultAvailable:true, next:'请通过只读操作重新查询分页结果' } };
        this.audit(interfaceType, input.workspaceId, op.name, result.ok ? 'ok' : result.error.code, 'package-os', operationId);
        await this.save(); return result;
      });
      this.queue = run.catch(() => {}); return await run;
    } catch (e) {
      const error = errorValue(e, intentSaved ? 'UNKNOWN' : e.agentCode);
      const op = findOperation(request?.operation);
      if (op && op.permission !== 'read') {
        const audit = this.queue.then(async () => {
          this.audit(interfaceType, /^[0-9a-f-]{36}$/.test(request.input?.workspaceId ?? '') ? request.input.workspaceId : null, op.name, error.code);
          await this.save();
        });
        this.queue = audit.catch(() => {}); await this.queue;
      }
      return { ok:false, ...(intentSaved ? { operationId:request.operationId } : {}), error };
    }
    finally { this.active--; }
  }
  async perform(op, input) {
    const c = this.catalog();
    if (op.name === 'version') return { ok:true, data:{ version:VERSION, node:process.versions.node } };
    if (op.name === 'capabilities' || op.name === 'schema') return { ok:true, data:{ version:VERSION, permissions:this.state.permissions, operations:describeOperations(), limits:{ requestBytes:BUDGET, responseBytes:BUDGET, page:100, concurrent:8, writesPerDay:512 }, highImpactApproval:'web-admin-csrf', transport:'owner-only-unix-socket', otherDevicesReceived:'unknown' } };
    if (op.name === 'health' || op.name === 'diagnostics') return { ok:true, data:redact({ version:VERSION, running:true, pid:process.pid, writerId:c.store.identity.writerId, maintenance:!!c.maintenance, invalidated:!!c.invalidated, ...await c.summary() }) };
    if (op.name === 'operations.receipt') {
      const r = this.state.receipts.find(r => r.operationId === input.operationId);
      return r?.result ?? { ok:false, error:errorValue(null, r ? 'UNKNOWN' : 'STALE') };
    }
    const method = op.permission === 'read' ? 'GET' : 'POST';
    let data = { ...input, ...op.fixed };
    if (op.edit) { data.operation = { ...input.edit, ...op.edit }; delete data.edit; }
    const result = await this.dispatch(method, op.route, data);
    if (result.status !== 200) return { ok:false, error:errorValue(new Error(result.data.error)) };
    data = result.data;
    const page = rows => {
      const offset = input.offset ?? 0, limit = input.limit ?? 40;
      return { rows:rows.slice(offset, offset + limit), total:rows.length, offset, next:offset + limit < rows.length ? offset + limit : null };
    };
    if (op.name.startsWith('records.')) {
      const key = { profiles:'termKey', dayAdjustments:'date', classOverrides:'key' }[input.table] ?? 'id';
      let rows = data.local[input.table];
      if (input.id) rows = rows.filter(r => (typeof r === 'string' ? r : r[key]) === input.id);
      if (input.filter) rows = rows.filter(r => JSON.stringify(r).includes(input.filter));
      data = { workspaceId:input.workspaceId, version:data.version, ...page(rows) };
    } else if (op.name === 'sync.status') {
      data = Object.fromEntries(Object.entries(data).filter(([k]) => !['courses','events','sources','profiles','csrf'].includes(k)));
    } else if (op.name === 'schedule.week') {
      const { courses, events, profiles, sources, ...status } = data;
      data = { ...status, courses:page(courses), events:page(events), profiles:page(profiles), sources:page(sources) };
    } else if (op.name === 'conflicts.get') {
      const { remote, conflicts, details, branches, ...status } = data;
      data = { ...status, conflicts:page(conflicts), details:page(details), branches:page(branches) };
    } else if (op.name === 'workspaces.list') {
      data = { ...data, workspaces:page(data.workspaces), connections:page(data.connections) };
    } else if (['schedule.day','schedule.range','schedule.events'].includes(op.name)) data = { ...data, rows:undefined, ...page(op.name === 'schedule.events' ? data.rows.filter(r => r.recordType === 'event') : data.rows) };
    else if (op.name === 'directories.browse') data = page(data.directories);
    else if (op.name === 'history.list') { const { rows, ...rest } = data; data = { ...rest, ...page(rows) }; }
    else if (op.name === 'migration.status') data = { ...data, records:page(data.records) };
    else if (op.name === 'backups.list') data = { ...data, backups:page(data.backups) };
    else if (op.name.startsWith('history.') && Array.isArray(data.rows) && input.limit && data.rows.length > input.limit) { data = { ...data, rows:data.rows.slice(0,input.limit), next:(input.offset ?? 0) + input.limit }; }
    if (method === 'POST') {
      const current = this.catalog();
      const db = current.manifest.workspaces.some(w => w.id === input.workspaceId) ? await current.load(input.workspaceId) : null;
      data = { ...data, ...(data.workspaceId ? { resultWorkspaceId:data.workspaceId } : {}), ...(data.datasetId ? { resultDatasetId:data.datasetId } : {}), catalogVersion:current.manifest.revision, currentWorkspaceId:current.manifest.activeId, workspaceId:input.workspaceId, version:db?.frame.revision ?? null, datasetId:db?.frame.datasetId ?? null, localSaved:!!db, dirty:db?.dirty() ?? null, pending:db ? !!db.frame.pending : null, receipt:db?.frame.receipt ?? null, status:db?.frame.graph?.status ?? 'unchecked', conflict:!!db?.frame.conflict, error:db?.frame.error ? errorValue(new Error(db.frame.error)) : null, deferred:db?.frame.checkpoint.deferred ?? [], otherDevicesReceived:'unknown' };
      if (op.name.startsWith('sync.') && (data.error || data.conflict || data.deferred.length)) return { ok:false, data:redact(data), error:data.error ?? errorValue(null, data.conflict ? 'CONFLICT' : 'INCOMPLETE') };
    }
    return { ok:true, data:redact(data) };
  }
  createServer() {
    const server = http.createServer(async (req, res) => {
      let result;
      try {
        if (req.socket.remoteAddress != null || req.method !== 'POST' || req.url !== '/operations' || !['cli','mcp'].includes(req.headers['x-dormpanel-interface']) || req.headers['content-type'] !== 'application/json' || req.headers['content-encoding']) fail('PERMISSION');
        if (Number(req.headers['content-length'] ?? 0) > BUDGET) fail('LIMIT');
        const chunks = []; let bytes = 0;
        for await (const chunk of req) { bytes += chunk.length; if (bytes > BUDGET) fail('LIMIT'); chunks.push(chunk); }
        result = await this.execute(JSON.parse(new TextDecoder('utf-8', { fatal:true }).decode(Buffer.concat(chunks))), req.headers['x-dormpanel-interface']);
        if (Buffer.byteLength(JSON.stringify(result)) > BUDGET) result = { ok:false, error:errorValue(null, 'LIMIT') };
      } catch (e) { result = { ok:false, error:errorValue(e, e.agentCode) }; }
      res.writeHead(200, { 'Content-Type':'application/json', 'Cache-Control':'no-store' }); res.end(JSON.stringify(result));
    });
    server.requestTimeout = 65000; server.headersTimeout = 10000; server.maxConnections = 16;
    return server;
  }
}
