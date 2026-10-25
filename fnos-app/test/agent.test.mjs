import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm, readFile, chmod, symlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import http from 'node:http';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { Client } from '@modelcontextprotocol/client';
import { StdioClientTransport } from '@modelcontextprotocol/client/stdio';
import { createApp, PREFIX } from '../src/server.mjs';
import { callOperation } from '../src/agent-client.mjs';
import { AgentService } from '../src/agent-service.mjs';
import { operations, describeOperations } from '../src/operations.mjs';
const operationId = () => `${Date.now()}-${randomUUID()}`;
async function fixture(t, options = {}) {
  const dir = await mkdtemp(join(tmpdir(), 'dp-agent-'));
  const exports = await mkdtemp(join(tmpdir(), 'dp-agent-exports-'));
  const app = await createApp({ dataDir:dir, configPath:join(dir, 'webdav.json'), authorizedRoots:[{ id:'test', name:'独立验收目录', path:exports }], ...options });
  const socket = name => process.platform === 'win32' ? '\\\\.\\pipe\\dp-' + randomUUID() : join(dir, name);
  const socketPath = socket('agent.sock'), web = socket('app.sock');
  await new Promise(r => app.agentServer.listen(socketPath, r));
  await new Promise(r => app.server.listen(web, r));
  if (process.platform !== 'win32') await chmod(socketPath, 0o600);
  t.after(async () => { app.agentServer.closeAllConnections(); app.server.closeAllConnections(); await Promise.all([new Promise(r => app.agentServer.close(r)), new Promise(r => app.server.close(r))]); await app.backups.close(); await rm(dir, { recursive:true, force:true }); await rm(exports, { recursive:true, force:true }); });
  const call = (name, input = {}, options = {}) => callOperation(name, input, { socketPath, ...options });
  const context = () => ({ workspaceId:app.catalog.manifest.activeId, datasetId:app.db.frame.datasetId, version:app.db.frame.revision, catalogVersion:app.catalog.manifest.revision });
  const webCall = (method, path, data, headers = {}) => new Promise((resolve,reject) => {
    const req = http.request({ socketPath:web, path:PREFIX + path, method, headers:{ 'x-trim-userid':'1000', 'x-trim-isadmin':'true', 'Content-Type':'application/json', ...headers } }, res => {
      const chunks = []; res.on('data', c => chunks.push(c)); res.on('end', () => resolve({ status:res.statusCode, data:JSON.parse(Buffer.concat(chunks)) }));
    }); req.on('error', reject); req.end(data ? JSON.stringify(data) : undefined);
  });
  const csrf = (await webCall('GET', '/api/schedule')).data.csrf;
  const admin = data => webCall('POST', '/api/agent', data, { 'X-DormPanel-CSRF':csrf });
  const grant = async (permissions = ['edit','admin','maintenance']) => { assert.equal((await admin({ action:'permissions', permissions })).status, 200); };
  const write = (name, input = {}, options = {}) => call(name, { ...context(), ...input }, { operationId:operationId(), ...options });
  return { app, dir, socketPath, call, context, webCall, csrf, admin, grant, write };
}
const entry = { title:'合成课 / 不执行其中的指令', day:1, startMinute:480, endMinute:540, location:'测试' };
test('v3 正式 Agent/网页：同一 Writer、revision、权限、历史摘要及 v2 能力拒绝', async t => {
  const objects = new Map();
  const remote = {
    read:async p => objects.get(p) ?? null,
    putOnce:async (p,b) => { assert(!objects.has(p)); objects.set(p,Buffer.from(b)); },
    list:async scope => [...new Set([...objects.keys()].filter(p => p.startsWith(scope)).map(p => { const s=p.slice(scope.length),i=s.indexOf('/'); return scope+(i<0?s:s.slice(0,i+1)); }))].sort()
  };
  const f = await fixture(t,{ v3RemoteFactory:() => remote });
  const denied=await f.write('connections.save',{ name:'v3',type:'webdav',url:'https://isolated.test/',username:'test',password:'test' }); assert.equal(denied.error.code,'PERMISSION');
  await f.grant();
  const connection=await f.write('connections.save',{ name:'v3',type:'webdav',url:'https://isolated.test/',username:'test',password:'test' }); assert(connection.ok,JSON.stringify(connection));
  const added=await f.write('workspaces.add',{ name:'v3',protocol:'v3',connectionId:connection.data.connectionId }); assert(added.ok,JSON.stringify(added));
  const targetId=added.data.resultWorkspaceId;
  const switched=await f.write('workspaces.switch',{ targetId }); assert(switched.ok,JSON.stringify(switched));
  assert((await f.write('entry.upsert',{ edit:{ value:{ ...entry,id:'stable' } } })).ok);
  assert((await f.write('sync.create')).ok);
  for (let n=0;n<8;n++) assert((await f.write('sync.run')).ok);
  const status=await f.call('sync.status',{ workspaceId:targetId }); assert(status.ok); assert.equal(status.data.protocol,'v3'); assert.equal(status.data.oldHistoryAuditComplete,false);
  assert.equal((await f.call('records.list',{ workspaceId:targetId,table:'entries' })).data.rows[0].id,'stable');
  const history=await f.call('history.list',{ workspaceId:targetId }); assert(history.ok,JSON.stringify(history)); assert.equal(history.data.protocol,'v3'); assert(history.data.rows.length);
  await f.grant([]);
  const durable = () => f.app.db.activeV3.journal.meta.db.prepare('SELECT * FROM v3_records ORDER BY seq').all();
  const beforeDiscovery=durable(), revision=f.app.db.frame.revision;
  const discovered=await f.call('sync.discover',{workspaceId:targetId});assert(discovered.ok,JSON.stringify(discovered));assert.equal(discovered.data.candidates.length,1);
  assert((await f.call('migration.v3-discover',{workspaceId:targetId})).ok);
  assert.deepEqual(durable(),beforeDiscovery);assert.equal(f.app.db.frame.revision,revision);
  await f.grant();
  const editContext=f.context();
  const webpage=await f.webCall('POST','/api/edit',{ ...editContext,operation:{ kind:'entry',action:'upsert',value:{ ...entry,id:'stable',title:'网页修改' } } },{ 'X-DormPanel-CSRF':f.csrf }); assert.equal(webpage.status,200);
  const stale=await f.call('entry.upsert',{ ...editContext,edit:{ value:{ ...entry,id:'stable' } } },{ operationId:operationId() }); assert.equal(stale.error.code,'STALE');
  const wrong=await f.webCall('POST','/api/history/compact',f.context(),{ 'X-DormPanel-CSRF':f.csrf }); assert.notEqual(wrong.status,200); assert.match(wrong.data.error,/不支持/);
  assert.equal(f.app.db.activeV3.journal.value.writerId,f.app.store.identity.writerId);
});
function cli(socketPath, args, input = '', entryPath = 'src/cli.mjs') {
  return new Promise(resolve => {
    const p = spawn(process.execPath, [entryPath, ...args], { cwd:new URL('../', import.meta.url), env:{ ...process.env, DORMPANEL_AGENT_SOCKET:socketPath }, stdio:['pipe','pipe','pipe'] });
    let stdout = '', stderr = ''; p.stdout.on('data', c => stdout += c); p.stderr.on('data', c => stderr += c);
    p.on('exit', code => resolve({ code, stdout, stderr })); p.stdin.end(input);
  });
}
test('CLI help/schema/version、JSON、退出码、参数和分页边界', async t => {
  const f = await fixture(t);
  for (const name of ['help','version','schema']) { const r = await cli(f.socketPath, [name,'--json']); assert.equal(r.code, 0); assert(JSON.parse(r.stdout).ok); }
  assert.equal((await cli(f.socketPath, ['unknown','--json'])).code, 2);
  assert.equal((await cli(f.socketPath, ['records','list','--json'], JSON.stringify({ workspaceId:f.context().workspaceId, table:'entries', limit:101 }))).code, 2);
  assert.equal((await cli(f.socketPath, ['entry','upsert','--json'], JSON.stringify({ ...f.context(), edit:{ value:entry } }))).code, 4);
  const unavailable = await cli(join(f.dir,'missing.sock'), ['health','--json']); assert.equal(unavailable.code, 8);
  const health = await cli(f.socketPath, ['health','--json']); assert.equal(JSON.parse(health.stdout).data.running, true);
  assert(!health.stderr);
});
test('服务端默认只读、CSRF 隔离、伪造 header/身份和无权限写入不改变数据', async t => {
  const f = await fixture(t), before = JSON.stringify(f.app.db.frame);
  assert.equal((await f.write('entry.upsert', { edit:{ value:entry } })).error.code, 'PERMISSION');
  assert.equal((await f.webCall('POST','/api/agent',{ action:'permissions', permissions:['edit'] })).status, 403);
  assert.equal((await f.call('/api/agent', { action:'approve' })).ok, false);
  assert.equal(JSON.stringify(f.app.db.frame), before);
  await f.grant(['edit']);
  assert.equal((await f.write('workspaces.rename', { targetId:f.context().workspaceId, targetVersion:0, name:'不能改' })).error.code, 'PERMISSION');
  assert.equal((await f.write('entry.upsert', { edit:{ value:entry } })).ok, true);
  await f.grant([]);
  assert.equal((await f.write('entry.upsert', { edit:{ value:entry } })).error.code, 'PERMISSION');
});
test('网页与 Agent 共用 applyEdit；并发过期请求拒绝，重复 ID 与重启回执不重复新增', async t => {
  const f = await fixture(t); await f.grant();
  const input = { ...f.context(), edit:{ value:entry } }, id = operationId();
  const result = await f.call('entry.upsert', input, { operationId:id }); assert(result.ok);
  assert.deepEqual(await f.call('entry.upsert', input, { operationId:id }), result);
  assert.equal((await f.call('entry.upsert', { ...input, edit:{ value:{ ...entry, title:'不同输入' } } }, { operationId:id })).error.code, 'STALE');
  assert.equal((await f.webCall('POST','/api/edit',{ ...input, operation:{ kind:'entry',action:'upsert',value:entry } }, { 'X-DormPanel-CSRF':f.csrf })).status, 409);
  const fresh = f.context();
  assert.equal((await f.webCall('POST','/api/edit',{ ...fresh, operation:{ kind:'entry',action:'upsert',value:{ ...entry,title:'网页' } } }, { 'X-DormPanel-CSRF':f.csrf })).status, 200);
  assert.equal((await f.call('entry.upsert', { ...fresh, edit:{ value:entry } }, { operationId:operationId() })).error.code, 'STALE');
  const rows = (await f.call('records.list',{ workspaceId:fresh.workspaceId, table:'entries', limit:1 })).data;
  assert.equal(rows.total, 2); assert.equal(rows.rows.length,1); assert.equal(rows.next,1);
  const reopened = await AgentService.open({ store:f.app.store, catalog:() => f.app.catalog, dispatch:() => { throw Error('不能重执行'); } });
  assert.deepEqual(await reopened.execute({ operation:'entry.upsert', input, operationId:id }, 'mcp'), result);
  assert.equal(f.app.db.frame.local.entries.length,2);
});
test('已落盘意图但响应未知或进程中断时不重新执行业务', async t => {
  const f = await fixture(t); await f.grant();
  const input = { ...f.context(), edit:{ value:entry } }, id = operationId();
  const digest = f.app.agents.digest({ operation:'entry.upsert',input,operationId:id });
  f.app.agents.state.receipts.push({ operationId:id,digest,time:Date.now() }); await f.app.agents.save();
  assert.equal((await f.call('entry.upsert',input,{ operationId:id })).error.code, 'UNKNOWN');
  assert.equal(f.app.db.frame.local.entries.length,0);
  assert.equal((await f.call('operations.receipt',{ operationId:id })).error.code,'UNKNOWN');
});
test('恢复/回退/精简/迁移必须独立管理员批准，批准绑定参数且不能由两次工具调用伪造', async t => {
  const f = await fixture(t); await f.grant();
  const input = { ...f.context(),rootId:'test' }, targetId = operationId();
  assert.equal((await f.call('history.compact',input,{ operationId:targetId })).error.code,'APPROVAL');
  const request = await f.write('approvals.request',{ operation:'history.compact',input,targetOperationId:targetId });
  // 申请工具的 schema 不接受业务 context。
  assert.equal(request.ok,false);
  const approval = await f.call('approvals.request',{ operation:'history.compact',input,targetOperationId:targetId },{ operationId:operationId() }); assert(approval.ok);
  const approvalId = approval.data.approvalId;
  assert.equal((await f.call('history.compact',input,{ operationId:targetId,approvalId })).error.code,'APPROVAL');
  assert.equal((await f.admin({ action:'approve',id:approvalId })).status,200);
  assert.equal((await f.call('history.compact',{ ...input,relative:'different' },{ operationId:targetId,approvalId })).error.code,'APPROVAL');
  // 原领域路径在未绑定工作区明确拒绝；不能把批准当作业务成功。
  const result = await f.call('history.compact',input,{ operationId:targetId,approvalId }); assert.equal(result.ok,false); assert.notEqual(result.error.code,'APPROVAL');
  assert(f.app.agents.state.approvals.find(p => p.id === approvalId).consumed);
  assert.equal((await f.call('backups.restore',{ ...f.context(),token:'fake',confirm:'恢复应用数据' },{ operationId:operationId() })).error.code,'APPROVAL');
});
test('课程/覆盖/来源/学期/日期/事件/导入共用领域校验，读取与输出不泄露凭据', async t => {
  const f = await fixture(t); await f.grant();
  assert((await f.write('entry.upsert',{ edit:{ id:'course-1',value:entry } })).ok);
  assert((await f.write('override.upsert',{ edit:{ sourceId:null,seriesId:'course-1',anchorDate:null,patch:{ title:'全周' } } })).ok);
  assert((await f.write('override.reset',{ edit:{ sourceId:null,seriesId:'course-1',anchorDate:null } })).ok);
  assert((await f.write('profile.upsert',{ edit:{ id:'test-term',value:{ termKey:'test-term',timezone:'Asia/Shanghai',week1Monday:'2026-10-19',phases:[{ effectiveFrom:'2026-10-19',periods:[{ periodNumber:1,start:'08:00',end:'09:00' }] }] } } })).ok);
  assert((await f.write('date.upsert',{ edit:{ value:{ date:'2026-10-20',mode:'none',weekday:null,label:'合成停课' } } })).ok);
  assert((await f.write('event.upsert',{ edit:{ id:'event-1',value:{ title:'合成',start:1792368000000,end:1792371600000,note:'' } } })).ok);
  const bytes = Buffer.from('BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:synthetic-49\r\nDTSTART:20261019T000000Z\r\nDTEND:20261019T010000Z\r\nSUMMARY:合成导入\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n');
  const preview = await f.write('import.preview',{ filename:'test.ics',base64:bytes.toString('base64') }); assert(preview.ok,JSON.stringify(preview));
  assert.equal((await f.call('import.rows',{ workspaceId:f.context().workspaceId,id:preview.data.id,limit:1 })).data.rows.length,1);
  assert((await f.write('import.confirm',{ id:preview.data.id,displayName:'合成来源' })).ok);
  const sources = (await f.call('records.list',{ workspaceId:f.context().workspaceId,table:'sources' })).data.rows;
  assert((await f.write('source.upsert',{ edit:{ id:sources[0].id,displayName:'重命名',termKey:'test-term' } })).ok);
  assert((await f.write('source.delete',{ edit:{ id:sources[0].id } })).ok);
  assert((await f.write('event.delete',{ edit:{ id:'event-1' } })).ok);
  assert((await f.write('entry.delete',{ edit:{ id:'course-1' } })).ok);
  assert((await f.write('profile.delete',{ edit:{ id:'test-term' } })).ok);
  assert((await f.write('date.delete',{ edit:{ id:'2026-10-20' } })).ok);
  const audit = JSON.stringify(f.app.agents.adminSummary().audit); assert(!audit.includes('合成')); assert(!audit.includes('base64'));
  const result = await f.call('sync.status',{ workspaceId:f.context().workspaceId }); assert(!JSON.stringify(result).includes('csrf'));
});
for (const mode of ['auto','legacy']) test('官方 MCP SDK stdio ' + mode + ' 发现 Tools/Resources/Prompts 并与 CLI 一致', async t => {
  const f = await fixture(t);
  let entryPath = 'src/mcp.mjs';
  if (process.platform === 'linux') {
    const link = join(f.dir,'linked-src'); await symlink(fileURLToPath(new URL('../src',import.meta.url)),link,'dir');
    entryPath = join(link,'mcp.mjs');
    const linkedCli = await cli(f.socketPath,['health','--json'],'',join(link,'cli.mjs'));
    assert.equal(JSON.parse(linkedCli.stdout).data.running,true);
  }
  const client = new Client({ name:'pr49-client',version:'1.0.0' }, { versionNegotiation:mode === 'legacy' ? { mode:'legacy' } : { mode:'auto' } });
  const transport = new StdioClientTransport({ command:process.execPath,args:[entryPath],cwd:new URL('../',import.meta.url).pathname.replace(/^\/(\w:)/,'$1'),env:{ ...process.env,DORMPANEL_AGENT_SOCKET:f.socketPath },stderr:'pipe',maxBufferSize:2*1024*1024 });
  t.after(() => client.close()); await client.connect(transport);
  assert.equal(client.getNegotiatedProtocolVersion(),mode === 'auto' ? '2026-07-28' : '2025-11-25');
  const tools = await client.listTools(); assert.equal(tools.tools.length,operations.length);
  const names = tools.tools.map(t => t.name); assert.deepEqual(names,[...names].sort((a,b) => a.localeCompare(b,'en')));
  const resources = await client.listResources(); assert(resources.resources.length >= 3);
  const templates = await client.listResourceTemplates(); assert(templates.resourceTemplates.length >= 6);
  const prompts = await client.listPrompts(); assert.equal(prompts.prompts.length,6);
  const resource = await client.readResource({ uri:'dormpanel://workspaces' }); assert(JSON.parse(resource.contents[0].text).ok);
  const input = { workspaceId:f.context().workspaceId,table:'entries' };
  const result = await client.callTool({ name:'records_list',arguments:input });
  assert.deepEqual(result.structuredContent,await f.call('records.list',input));
  assert.equal((await client.callTool({ name:'entry_upsert',arguments:{ ...f.context(),edit:{ value:entry },operationId:operationId() } })).isError,true);
  await f.grant(['edit']);
  const writeId = operationId();
  assert.equal((await client.callTool({ name:'entry_upsert',arguments:{ ...f.context(),edit:{ value:entry },operationId:writeId } })).isError,false);
  assert.equal((await client.callTool({ name:'operations_receipt',arguments:{ operationId:writeId } })).structuredContent.operationId,writeId);
  const fromCli = await cli(f.socketPath,['records','list','--json'],JSON.stringify(input));
  assert.deepEqual(JSON.parse(fromCli.stdout),(await client.callTool({ name:'records_list',arguments:input })).structuredContent);
});
test('操作 schema 完整确定，拒绝任意 SQL/shell/路径与错误工作区或数据集', async t => {
  const f = await fixture(t); await f.grant();
  assert(operations.length > 60); assert.equal(describeOperations().length,operations.length);
  for (const operation of ['shell','sql','javascript','/api/manage']) assert.equal((await f.call(operation,{})).ok,false);
  assert.equal((await f.write('entry.upsert',{ datasetId:randomUUID(),edit:{ value:entry } })).error.code,'STALE');
  assert.equal((await f.call('directories.browse',{ rootId:'test',relative:'../../etc' })).ok,false);
  assert.equal((await f.write('entry.upsert',{ edit:{ value:{ ...entry,day:9 } } })).ok,false);
  assert.equal((await f.call('schedule.range',{ workspaceId:f.context().workspaceId,start:'2026-01-01',end:'2026-12-31' })).ok,false);
  assert((await f.call('schedule.range',{ workspaceId:f.context().workspaceId,start:'2026-10-19',end:'2026-10-25' })).ok);
  // 退役工作区保护由 Catalog 的原 guard 生效。
  f.app.catalog.migrations.push({ sourceId:f.context().workspaceId,phase:'retired' });
  assert.equal((await f.write('entry.upsert',{ edit:{ value:entry } })).error.code,'PROTECTED');
  assert(!JSON.stringify(await f.call('diagnostics')).includes('password'));
  assert.equal((await readFile(join(f.dir,'agent-control.json'),'utf8')).includes('合成课'),false);
});
const linux = process.platform === 'linux' ? test : test.skip;
async function approved(f, operation, fields = {}) {
  const input = { ...f.context(), ...fields }, id = operationId();
  const requested = await f.call('approvals.request',{ operation,input,targetOperationId:id },{ operationId:operationId() });
  assert(requested.ok,JSON.stringify(requested));
  assert.equal((await f.admin({ action:'approve',id:requested.data.approvalId })).status,200);
  return f.call(operation,input,{ operationId:id,approvalId:requested.data.approvalId });
}
test('CLI/MCP 本地快照创建 v3 与迁移恢复必须独立批准，批准后复用同一业务 Workspace',async t=>{
  const rows=new Map(), objects=new Map();let v2Puts=0;
  const source={key:'https://isolated.test/dormpanel-sync-v2/',list:async()=>[...rows.keys()],read:async id=>rows.get(id)??null,put:async(id,b)=>{v2Puts++;rows.set(id,b);}};
  const remote={read:async p=>objects.get(p)??null,putOnce:async(p,b)=>{assert(!objects.has(p));objects.set(p,Buffer.from(b));},list:async scope=>[...new Set([...objects.keys()].filter(p=>p.startsWith(scope)).map(p=>{const s=p.slice(scope.length),i=s.indexOf('/');return scope+(i<0?s:s.slice(0,i+1));}))].sort()};
  const f=await fixture(t,{remoteFactory:()=>source,v3RemoteFactory:()=>remote});await f.grant();
  const original=f.context().workspaceId;
  const connection=await f.write('connections.save',{name:'local capture',type:'webdav',url:'https://isolated.test/',username:'test',password:'test'});assert(connection.ok);
  assert((await f.write('workspaces.endpoint',{targetId:original,targetVersion:f.context().version,connectionId:connection.data.connectionId})).ok);
  assert((await f.write('entry.upsert',{edit:{value:{...entry,id:'local-stable'}}})).ok);
  const preview=await f.write('migration.v3-preview');assert(preview.ok,JSON.stringify(preview));assert.equal(preview.data.sourceProtocol,'LOCAL');
  const input={token:preview.data.token,confirm:'LOCAL',writersStopped:true};
  assert.equal((await f.write('migration.v3-prepare',input)).error.code,'APPROVAL');assert.equal(objects.size,0);
  const prepared=await approved(f,'migration.v3-prepare',input);assert(prepared.ok,JSON.stringify(prepared));
  assert.equal((await f.write('migration.v3-resume',{migrationId:prepared.data.id})).error.code,'APPROVAL');
  const active=await approved(f,'migration.v3-resume',{migrationId:prepared.data.id});assert(active.ok,JSON.stringify(active));assert.equal(active.data.phase,'ACTIVE');assert.equal(v2Puts,0);
  const current=f.context();assert.notEqual(current.workspaceId,original);
  assert.equal((await f.call('records.list',{workspaceId:current.workspaceId,table:'entries'})).data.rows[0].id,'local-stable');
  const history=await f.call('history.list',{workspaceId:current.workspaceId});assert(history.ok);assert.equal(history.data.protocol,'v3');
});
linux('Agent 实际本地连接、工作区、共享数据集、同步回执、历史快照/比较/回退/归档/精简闭环', async t => {
  const f = await fixture(t); await f.grant();
  const connection = await f.write('connections.save',{ name:'合成连接',type:'local',rootId:'private',createPrivate:true }); assert(connection.ok,JSON.stringify(connection));
  const added = await f.write('workspaces.add',{ connectionId:connection.data.connectionId,name:'Agent 合成验收' }); assert(added.ok);
  const id = added.data.resultWorkspaceId; assert(id && id !== f.context().workspaceId);
  assert((await f.write('workspaces.switch',{ targetId:id })).ok);
  assert((await f.write('connections.test',{ connectionId:connection.data.connectionId })).ok);
  assert((await f.write('sync.create')).ok);
  const root = (await f.call('history.list',{ workspaceId:id })).data.rows[0].id;
  assert((await f.write('entry.upsert',{ edit:{ id:'synthetic',value:entry } })).ok);
  const published = await f.write('sync.run'); assert(published.ok); assert(published.data.receipt); assert.equal(published.data.pending,false);
  const h = (await f.call('history.list',{ workspaceId:id,limit:1 })).data; assert.equal(h.total,2); assert.equal(h.rows.length,1);
  assert((await f.call('history.snapshot',{ workspaceId:id,id:h.rows[0].id,table:'entries',limit:1 })).ok);
  const diff = await f.call('history.diff',{ workspaceId:id,from:root,to:h.rows[0].id }); assert.equal(diff.data.total,1);
  const preview = await f.write('history.preview',{ target:root }); assert(preview.ok,JSON.stringify(preview));
  assert.equal((await f.write('history.rollback',{ target:root,token:preview.data.token,confirm:root })).error.code,'APPROVAL');
  const rollback = await approved(f,'history.rollback',{ target:root,token:preview.data.token,confirm:root }); assert(rollback.ok,JSON.stringify(rollback));
  assert.equal(f.app.db.frame.local.entries.length,0);
  const archive = await approved(f,'history.archive',{ rootId:'test' }); assert(archive.ok,JSON.stringify(archive));
  const compact = await approved(f,'history.compact',{ rootId:'test' }); assert(compact.ok,JSON.stringify(compact));
  assert(compact.data.candidate); assert(compact.data.resultDatasetId !== f.context().datasetId);
  assert.equal((await f.call('history.list',{ workspaceId:id })).data.total,3);
});
linux('Agent 加密备份回读、预览、取消和独立批准恢复；Catalog 重开、权限及幂等账本不被恢复覆盖', async t => {
  const f = await fixture(t); await f.grant();
  assert((await f.write('entry.upsert',{ edit:{ id:'original',value:entry } })).ok);
  const password = 'pr49-secret-not-in-results', created = await approved(f,'backups.create',{ rootId:'test',password }); assert(created.ok,JSON.stringify(created));
  assert(!JSON.stringify(created).includes(password));
  const list = await f.write('backups.list',{ rootId:'test',limit:1 }); assert.equal(list.data.backups.rows.length,1);
  let preview = await f.write('backups.verify',{ rootId:'test',name:created.data.name,password }); assert(preview.ok,JSON.stringify(preview));
  assert((await f.write('backups.cancel',{ token:preview.data.token })).ok);
  assert((await f.write('entry.upsert',{ edit:{ id:'newer',value:{ ...entry,title:'备份后新增' } } })).ok);
  preview = await f.write('backups.verify',{ rootId:'test',name:created.data.name,password }); assert(preview.ok);
  const old = f.app.catalog;
  const restored = await approved(f,'backups.restore',{ token:preview.data.token,confirm:'恢复应用数据' }); assert(restored.ok,JSON.stringify(restored));
  assert.notEqual(f.app.catalog,old); assert(old.invalidated); assert.equal(f.app.db.frame.local.entries.length,1);
  assert((await f.write('entry.upsert',{ edit:{ id:'after',value:entry } })).ok);
  assert((await f.call('operations.receipt',{ operationId:restored.operationId })).ok);
  const audit = await readFile(join(f.dir,'agent-control.json'),'utf8'); assert(!audit.includes(password)); assert(!audit.includes('备份后新增'));
});


test('XLS Agent 预览、取消、确认和 CSV 来源替换保留身份', async t => {
 const f=await fixture(t);await f.grant();
 const profile={termKey:'2026-2027-1',timezone:'Asia/Shanghai',week1Monday:'2026-08-31',phases:[{effectiveFrom:'2026-08-31',periods:Array.from({length:11},(_,i)=>({periodNumber:i+1,start:`${String(8+i).padStart(2,'0')}:00`,end:`${String(8+i).padStart(2,'0')}:45`}))}]};
 assert((await f.write('profile.upsert',{edit:{id:profile.termKey,value:profile}})).ok);
 const preview=async ext=>f.write('import.preview',{filename:'oracle.'+ext,base64:(await readFile(new URL('../../schedule-core/src/test/resources/schedule/hubei_2026-2027-1.'+ext,import.meta.url))).toString('base64')});
 const cliResult=await cli(f.socketPath,['import','preview','--json','--file',fileURLToPath(new URL('../../schedule-core/src/test/resources/schedule/hubei_2026-2027-1.xls',import.meta.url))],JSON.stringify(f.context()));
 assert.equal(cliResult.code,0,cliResult.stdout);const cliPreview=JSON.parse(cliResult.stdout);assert.equal(cliPreview.data.count,131);assert((await f.write('import.cancel',{id:cliPreview.data.id})).ok);
 const client=new Client({name:'pr50-xls',version:'1.0.0'});
 const transport=new StdioClientTransport({command:process.execPath,args:['src/mcp.mjs'],cwd:new URL('../',import.meta.url).pathname.replace(/^\/(\w:)/,'$1'),env:{...process.env,DORMPANEL_AGENT_SOCKET:f.socketPath},stderr:'pipe',maxBufferSize:2*1024*1024});
 t.after(()=>client.close());await client.connect(transport);
 const mcp=await client.callTool({name:'import_preview',arguments:{...f.context(),filename:'mcp.xls',base64:(await readFile(new URL('../../schedule-core/src/test/resources/schedule/hubei_2026-2027-1.xls',import.meta.url))).toString('base64'),operationId:operationId()}});
 assert.equal(mcp.isError,false);assert.equal(mcp.structuredContent.data.count,131);assert((await f.write('import.cancel',{id:mcp.structuredContent.data.id})).ok);
 let p=await preview('xls');assert(p.ok,JSON.stringify(p));assert.equal(p.data.count,131);
 assert((await f.write('import.cancel',{id:p.data.id})).ok);
 assert.equal((await f.write('import.confirm',{id:p.data.id,displayName:'已取消'})).ok,false);
 p=await preview('csv');assert(p.ok);assert((await f.write('import.confirm',{id:p.data.id,displayName:'CSV'})).ok);
 const before=structuredClone(f.app.db.frame.local);
 p=await preview('xls');assert(p.ok);const rows=await f.call('import.rows',{workspaceId:f.context().workspaceId,id:p.data.id,limit:100});assert(rows.ok);
 assert((await f.write('import.confirm',{id:p.data.id,displayName:'XLS',targetId:before.sources[0].id})).ok);
 assert.equal(f.app.db.frame.local.sources[0].id,before.sources[0].id);
 assert.deepEqual(f.app.db.frame.local.imported,before.imported);
});
