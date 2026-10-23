#!/usr/bin/env node
import { open, realpath } from 'node:fs/promises';
import { constants } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { randomUUID } from 'node:crypto';
import { callOperation } from './agent-client.mjs';
import { operations, describeOperations, findOperation, VERSION, BUDGET, errorValue } from './operations.mjs';
export const exitCode = result => result.ok ? 0 : ({ VALIDATION:2, CONFIRMATION:2, STALE:3, PERMISSION:4, APPROVAL:4, PROTECTED:5, IDENTITY:5, BUSY:6, LIMIT:7, UNAVAILABLE:8, NETWORK:8, UNKNOWN:9, CONFLICT:10, INCOMPLETE:10 }[result.error?.code] ?? 1);
async function readInput(path) {
  const chunks = []; let size = 0;
  if (!path) {
    if (process.stdin.isTTY) return {};
    for await (const chunk of process.stdin) { size += chunk.length; if (size > BUDGET) throw Error(); chunks.push(chunk); }
  } else {
    const file = await open(path, constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK);
    try {
      const stat = await file.stat(); if (!stat.isFile() || stat.size > BUDGET) throw Error();
      for await (const chunk of file.createReadStream({ autoClose:false })) { size += chunk.length; if (size > BUDGET) throw Error(); chunks.push(chunk); }
    } finally { await file.close(); }
  }
  return size ? JSON.parse(new TextDecoder('utf-8', { fatal:true }).decode(Buffer.concat(chunks))) : {};
}
export async function main(args = process.argv.slice(2)) {
  let json = false, operationId, approvalId, file, upload;
  const words = [], flags = {};
  try {
    for (let i = 0; i < args.length; i++) {
      const a = args[i];
      if (a === '--json') json = true;
      else if (a === '--help' || a === '-h') words.splice(0, words.length, 'help');
      else if (a.startsWith('--')) {
        const v = args[++i]; if (!v || v.startsWith('--')) throw Error();
        if (a === '--input') file = v;
        else if (a === '--file') upload = v;
        else if (a === '--operation-id') operationId = v;
        else if (a === '--approval-id') approvalId = v;
        else if (['--workspace-id','--dataset-id','--version','--catalog-version','--offset','--limit','--date','--zone','--table','--id','--filter','--confirm'].includes(a)) {
          const key = a.slice(2).replace(/-([a-z])/g, (_, c) => c.toUpperCase());
          flags[key] = ['version','catalogVersion','offset','limit'].includes(key) ? Number(v) : v === 'null' ? null : v;
        } else throw Error();
      } else words.push(a);
    }
    const operation = words.join('.') || 'help';
    if (operation === 'help') {
      const help = { version:VERSION, usage:'dormpanel-cli <领域> <操作> [--json] [--input FILE] [--file CSV/ICS]；JSON 默认从 stdin 读取', flags:'--workspace-id --dataset-id --version --catalog-version --offset --limit --id --table --filter --confirm --operation-id --approval-id', operationId:'写入自动生成并回显操作 ID。SSH 重试必须保存并复用同一 ID 和完全相同参数；退出码 9 后先查 operations receipt。', exitCodes:{ success:0, validation:2, stale:3, permissionOrApproval:4, protected:5, busy:6, limit:7, unavailable:8, unknown:9 }, operations:operations.map(({ name, description }) => ({ name, description })) };
      process.stdout.write(json ? JSON.stringify({ ok:true, data:help }) + '\n' : help.usage + '\n' + help.flags + '\n' + help.operationId + '\n' + help.operations.map(o => o.name.replaceAll('.', ' ') + ' — ' + o.description).join('\n') + '\n'); return 0;
    }
    if (operation === 'version' || operation === 'schema') {
      const result = { ok:true, data:operation === 'version' ? { version:VERSION } : describeOperations() };
      process.stdout.write(JSON.stringify(result, null, json ? 0 : 2) + '\n'); return 0;
    }
    const op = findOperation(operation); if (!op) throw Error();
    const input = { ...await readInput(file), ...flags };
    if (operation === 'operations.receipt' && operationId) { input.operationId ??= operationId; operationId = undefined; }
    if (upload) {
      if (operation !== 'import.preview') throw Error();
      const file = await open(upload, constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK);
      try {
        const stat = await file.stat(); if (!stat.isFile() || stat.size > 1024 * 1024) throw Error();
        const bytes = await file.readFile(); if (bytes.length > 1024 * 1024) throw Error();
        input.base64 = bytes.toString('base64'); input.filename = upload.split(/[\\/]/).at(-1);
      } finally { await file.close(); }
    }
    op.schema.parse(input);
    if (op.permission !== 'read') operationId ??= `${Date.now()}-${randomUUID()}`;
    // 即使 SSH 在结果前中断，调用者可从 stderr 保存 ID；不含业务正文或凭据。
    if (operationId) process.stderr.write('operationId=' + operationId + '\n');
    const result = await callOperation(operation, input, { operationId, approvalId });
    if (operationId) result.operationId ??= operationId;
    process.stdout.write(JSON.stringify(result, null, json ? 0 : 2) + '\n'); return exitCode(result);
  } catch {
    const result = { ok:false, error:errorValue(null) };
    process.stdout.write(JSON.stringify(result) + '\n'); return 2;
  }
}
if (process.argv[1] && await realpath(process.argv[1]).catch(() => null) === fileURLToPath(import.meta.url)) process.exitCode = await main();
