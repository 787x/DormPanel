import { writeFileSync, readFileSync, existsSync, openSync, fsyncSync, closeSync } from 'node:fs';
import { join } from 'node:path';
import { V3Journal, V3Publisher } from '../../src/v3-transaction.mjs';
const [root, target] = process.argv.slice(2), input = JSON.parse(readFileSync(join(root, 'input.json'), 'utf8'));
function stop(phase) { if (phase === target) { writeFileSync(join(root, 'boundary'), phase); Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0); } }
function durable(name, bytes) { const f = openSync(join(root, name), 'w', 0o600); try { writeFileSync(f, bytes); fsyncSync(f); } finally { closeSync(f); } }
const journal = await new V3Journal(root).open();
journal.meta.fault = phase => { if (phase === 'after-commit') stop(journal.meta.get('state', 'frame')?.pending?.phase); };
await journal.transaction(s => { s.local = { userEdit: 'saved' }; });
const remote = {
  async read() { if (existsSync(join(root, 'remote'))) { stop('READBACK_BEFORE'); const bytes = readFileSync(join(root, 'remote')); stop('READBACK_AFTER'); return bytes; } return null; },
  async putOnce(path, bytes) { durable('remote', bytes); durable('puts', '1'); stop('PUT_DONE'); if (target === 'UNCERTAIN') throw Error('lost response'); }
};
const publisher = new V3Publisher(journal, remote);
await publisher.stage(input.path, Buffer.from(input.bytes, 'base64'), { exactCapture: 'saved' });
await publisher.resume(); await publisher.receipt(s => { s.applied = (s.applied ?? 0) + 1; });
