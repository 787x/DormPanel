import http from 'node:http';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { BUDGET, errorValue } from './operations.mjs';
export const defaultSocket = () => process.env.DORMPANEL_AGENT_SOCKET ?? join(dirname(fileURLToPath(import.meta.url)), '../../agent.sock');
export function callOperation(operation, input = {}, { operationId, approvalId, socketPath = defaultSocket(), interfaceType = 'cli', timeout = 60000 } = {}) {
  return new Promise(resolve => {
    let connected = false;
    const bytes = JSON.stringify({ operation, input, operationId, approvalId });
    if (Buffer.byteLength(bytes) > BUDGET) { resolve({ ok:false, error:errorValue(null, 'LIMIT') }); return; }
    const req = http.request({ socketPath, path:'/operations', method:'POST', headers:{ 'Content-Type':'application/json', 'Content-Length':Buffer.byteLength(bytes), 'X-Dormpanel-Interface':interfaceType } }, res => {
      let size = 0; const chunks = [];
      res.on('data', chunk => { size += chunk.length; if (size > BUDGET) req.destroy(); else chunks.push(chunk); });
      res.on('error', () => resolve({ ok:false, operationId, error:errorValue(null, operationId ? 'UNKNOWN' : 'UNAVAILABLE') }));
      res.on('end', () => {
        try { resolve(JSON.parse(Buffer.concat(chunks).toString('utf8'))); }
        catch { resolve({ ok:false, operationId, error:errorValue(null, operationId ? 'UNKNOWN' : 'UNAVAILABLE') }); }
      });
    });
    req.setTimeout(timeout, () => req.destroy());
    req.on('socket', socket => { if (socket.connecting) socket.once('connect', () => { connected = true; }); else connected = true; });
    req.on('error', () => resolve({ ok:false, operationId, error:errorValue(null, operationId && connected ? 'UNKNOWN' : 'UNAVAILABLE') }));
    req.end(bytes);
  });
}
