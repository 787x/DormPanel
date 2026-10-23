// 开发期官方 SDK 验证器，不进入 FPK。可直接传 ssh 命令及参数。
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { Client } from '@modelcontextprotocol/client';
import { StdioClientTransport } from '@modelcontextprotocol/client/stdio';
const [command, ...args] = process.argv.slice(2);
assert(command, '用法：node scripts/verify-agent-client.mjs ssh -T 管理员@NAS sudo -n -u dormpanel-schedule-manager /var/apps/dormpanel-schedule-manager/target/bin/dormpanel-mcp');
const client = new Client({ name:'dormpanel-acceptance', version:'0.7.1' }, { versionNegotiation:{ mode:process.env.MCP_PROTOCOL_MODE ?? 'auto' } });
const transport = new StdioClientTransport({ command, args, stderr:'pipe' });
let stderr = '';
transport.stderr?.on('data', b => { stderr += b; if (stderr.length > 8192) stderr = stderr.slice(-8192); });
try {
  await client.connect(transport);
  const tools = await client.listTools(), resources = await client.listResources(), templates = await client.listResourceTemplates(), prompts = await client.listPrompts();
  const version = (await client.callTool({ name:'version', arguments:{} })).structuredContent;
  assert(version.ok);
  const workspaces = await client.readResource({ uri:'dormpanel://workspaces' }); assert(JSON.parse(workspaces.contents[0].text).ok);
  let writeVerified = false;
  if (process.env.DORMPANEL_TEST_WORKSPACE_ID) {
    const workspaceId = process.env.DORMPANEL_TEST_WORKSPACE_ID;
    const context = async () => {
      const r = (await client.callTool({ name:'sync_status', arguments:{ workspaceId } })).structuredContent; assert(r.ok);
      const { datasetId,version,catalogVersion } = r.data; return { workspaceId,datasetId,version,catalogVersion };
    };
    const before = await context(), id = 'pr49-' + randomUUID(), operationId = `${Date.now()}-${randomUUID()}`;
    const input = { ...before, edit:{ id, value:{ title:'PR49 SSH 合成课程', day:1,startMinute:480,endMinute:540,location:'隔离测试' } }, operationId };
    const first = (await client.callTool({ name:'entry_upsert',arguments:input })).structuredContent; assert(first.ok);
    assert.deepEqual((await client.callTool({ name:'entry_upsert',arguments:input })).structuredContent,first);
    assert.equal((await client.callTool({ name:'entry_upsert',arguments:{ ...input,operationId:`${Date.now()}-${randomUUID()}` } })).structuredContent.error.code,'STALE');
    assert.equal((await client.callTool({ name:'operations_receipt',arguments:{ operationId } })).structuredContent.operationId,operationId);
    const rows = (await client.callTool({ name:'records_get',arguments:{ workspaceId,table:'entries',id } })).structuredContent; assert.equal(rows.data.total,1);
    assert((await client.callTool({ name:'entry_delete',arguments:{ ...await context(),edit:{ id },operationId:`${Date.now()}-${randomUUID()}` } })).structuredContent.ok);
    const synced = (await client.callTool({ name:'sync_run',arguments:{ ...await context(),operationId:`${Date.now()}-${randomUUID()}` } })).structuredContent; assert(synced.ok); assert(synced.data.receipt);
    writeVerified = true;
  }
  console.log(JSON.stringify({ ok:true, protocol:client.getNegotiatedProtocolVersion(), serverVersion:version.data.version, tools:tools.tools.length, resources:resources.resources.length, resourceTemplates:templates.resourceTemplates.length, prompts:prompts.prompts.length, writeVerified, stderrBytes:Buffer.byteLength(stderr) }));
} finally { await client.close(); }
