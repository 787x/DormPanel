import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, cp, symlink, readFile, writeFile, readdir, stat, rm, chmod, rename, lstat } from 'node:fs/promises';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { tmpdir } from 'node:os';
import { spawn, spawnSync } from 'node:child_process';
import http from 'node:http';

const linux = process.platform === 'linux' ? test : test.skip;
const root = fileURLToPath(new URL('../', import.meta.url));
const delay = ms => new Promise(r => setTimeout(r, ms));
async function until(check) {
  const end = Date.now() + 15000;
  while (!(await check())) { assert(Date.now() < end, '启动生命周期等待超时'); await delay(20); }
}
async function fixture(t, gate = false) {
  const dir = await mkdtemp(join(tmpdir(), 'pr42-start-')), app = join(dir, 'app'), data = join(dir, 'var'), etc = join(dir, 'etc');
  const launches = new Set();
  await mkdir(join(app, 'server'), { recursive: true }); await mkdir(data); await mkdir(etc);
  await cp(join(root, 'src'), join(app, 'server/src'), { recursive: true });
  await cp(join(root, 'web'), join(app, 'server/web'), { recursive: true });
  await symlink(join(root, 'node_modules'), join(app, 'server/node_modules'), 'dir');
  const serverPath = join(app, 'server/src/server.mjs'), source = await readFile(serverPath, 'utf8');
  await writeFile(serverPath, source.replace('  process.umask(0o077);', "  process.umask(0o077);\n  await writeFile(join(process.env.TRIM_PKGVAR, '.boot-' + process.pid), '', { mode: 0o600 });"));
  const script = join(dir, 'main.sh');
  // 只替换解释器位置，使同一生产脚本也能在非 fnOS Linux Node 环境测试。
  const shellQuote = s => "'" + s.replaceAll("'", "'\\''") + "'";
  await writeFile(script, (await readFile(join(root, 'scripts/main.sh'), 'utf8')).replace(/^NODE=.*$/m, 'NODE=' + shellQuote(process.execPath)));
  await cp(join(root, 'scripts/socket-access.sh'), join(app, 'server/access-real.sh'));
  await writeFile(join(app, 'server/socket-access.sh'), `#!/bin/sh
set -eu
if [ "$1" = directory ] && [ -f "$TRIM_PKGVAR/gate" ]; then
  touch "$TRIM_PKGVAR/entered"
  while [ ! -f "$TRIM_PKGVAR/release" ]; do sleep 0.02; done
fi
if [ "$1" = socket ] && [ -f "$TRIM_PKGVAR/fail-socket" ]; then exit 1; fi
exec sh "$TRIM_APPDEST/server/access-real.sh" "$1"
`);
  if (gate) await writeFile(join(data, 'gate'), '');
  const env = { ...process.env, TRIM_APPDEST: app, TRIM_PKGVAR: data, TRIM_PKGETC: etc, TRIM_TEMP_LOGFILE: join(dir, 'control.log'), TRIM_DATA_SHARE_PATHS: '', TRIM_DATA_ACCESSIBLE_PATHS: '' };
  const call = action => {
    const child = spawn('/bin/sh', [script, action], { env, stdio: ['ignore', 'pipe', 'pipe'] }); launches.add(child);
    return new Promise((resolveResult, reject) => {
      let error = ''; child.stderr.on('data', b => error += b); child.stdout.resume(); child.on('error', reject);
      child.on('close', code => { launches.delete(child); resolveResult({ code, error }); });
    });
  };
  const freeLock = () => spawnSync('flock', ['-n', join(data, '.writer.lock'), 'true']).status === 0;
  const boots = async () => (await readdir(data)).filter(n => /^\.boot-\d+$/.test(n));
  t.after(async () => {
    await writeFile(join(data, 'release'), ''); await rm(join(data, 'fail-socket'), { force: true });
    for (const child of launches) child.kill('SIGTERM');
    for (const name of await boots()) {
      const pid = Number(name.slice(6));
      try {
        const cmd = (await readFile(`/proc/${pid}/cmdline`)).toString().split('\0');
        if (cmd.includes(serverPath)) process.kill(pid, 'SIGKILL');
      } catch (e) { if (!['ENOENT', 'ESRCH'].includes(e.code)) throw e; }
    }
    await until(freeLock); await rm(dir, { recursive: true, force: true });
  });
  const request = () => new Promise((resolveResult, reject) => {
    const req = http.get({ socketPath: join(app, 'app.sock'), path: '/app/dormpanel-schedule-manager/api/schedule', headers: { 'X-Trim-Userid': '1000', 'X-Trim-Isadmin': 'true' } }, res => {
      let text = ''; res.on('data', b => text += b); res.on('end', () => resolveResult({ code: res.statusCode, body: JSON.parse(text) }));
    }); req.on('error', reject);
  });
  return { dir, app, data, script, call, freeLock, boots, request };
}
linux('两个实际 start 竞争：失败方不删除运行文件，获胜 Socket 可访问且只有一个 Writer', async t => {
  const f = await fixture(t, true);
  await writeFile(join(f.data, 'app.pid'), '99999999\n'); await writeFile(join(f.data, 'socket.ready'), 'stale-ready'); await writeFile(join(f.app, 'app.sock'), 'stale-socket');
  const winning = f.call('start'); await until(() => stat(join(f.data, 'entered')).then(() => true, () => false));
  const losing = await f.call('start'); assert.equal(losing.code, 1);
  assert.equal(await readFile(join(f.data, 'app.pid'), 'utf8'), '99999999\n');
  assert.equal(await readFile(join(f.data, 'socket.ready'), 'utf8'), 'stale-ready');
  assert.equal(await readFile(join(f.app, 'app.sock'), 'utf8'), 'stale-socket');
  await writeFile(join(f.data, 'release'), ''); assert.equal((await winning).code, 0);
  const pid = Number(await readFile(join(f.data, 'app.pid'), 'utf8')), ready = await readFile(join(f.data, 'socket.ready'), 'utf8'), inode = (await stat(join(f.app, 'app.sock'))).ino;
  assert.equal((await f.boots()).length, 1); assert.equal((await f.request()).code, 200); assert.equal(f.freeLock(), false);
  assert.equal(ready, String(pid)); assert.equal((await stat(f.app)).mode & 0o777, 0o700); assert.equal((await stat(join(f.app, 'app.sock'))).mode & 0o777, 0o600);
  const log = await readFile(join(f.data, 'app.log'));
  assert.equal((await f.call('start')).code, 0); assert.equal((await f.request()).code, 200);
  assert.equal(Number(await readFile(join(f.data, 'app.pid'), 'utf8')), pid); assert.equal(await readFile(join(f.data, 'socket.ready'), 'utf8'), ready);
  assert.equal((await stat(join(f.app, 'app.sock'))).ino, inode); assert.deepEqual(await readFile(join(f.data, 'app.log')), log);
  await chmod(f.app, 0o755); await chmod(join(f.app, 'app.sock'), 0o666);
  assert.equal((await f.call('start')).code, 0);
  assert.equal((await stat(f.app)).mode & 0o777, 0o700); assert.equal((await stat(join(f.app, 'app.sock'))).mode & 0o777, 0o600);
  await rename(join(f.app, 'app.sock'), join(f.app, 'saved.sock')); await symlink(join(f.app, 'saved.sock'), join(f.app, 'app.sock'));
  assert.equal((await f.call('start')).code, 1); assert((await lstat(join(f.app, 'app.sock'))).isSymbolicLink());
  assert.equal(Number(await readFile(join(f.data, 'app.pid'), 'utf8')), pid);
  await rm(join(f.app, 'app.sock')); await rename(join(f.app, 'saved.sock'), join(f.app, 'app.sock')); assert.equal((await f.request()).code, 200);
  assert.equal((await f.call('stop')).code, 0); assert.equal((await f.call('status')).code, 3);
});
linux('SIGKILL 后残留真实 Socket/PID/ready 可重启，身份保留；正常 stop/start 不回归', async t => {
  const f = await fixture(t); assert.equal((await f.call('start')).code, 0);
  const oldPid = Number(await readFile(join(f.data, 'app.pid'), 'utf8')), identity = await readFile(join(f.data, 'identity.json'));
  process.kill(oldPid, 'SIGKILL'); await until(f.freeLock); assert((await stat(join(f.app, 'app.sock'))).isSocket());
  assert.equal((await f.call('start')).code, 0); const nextPid = Number(await readFile(join(f.data, 'app.pid'), 'utf8')); assert.notEqual(nextPid, oldPid);
  assert.equal((await f.request()).code, 200); assert.deepEqual(await readFile(join(f.data, 'identity.json')), identity); assert.equal((await f.boots()).length, 2);
  assert.equal((await f.call('stop')).code, 0); assert.equal((await f.call('start')).code, 0); assert.equal((await f.request()).code, 200);
  assert.deepEqual(await readFile(join(f.data, 'identity.json')), identity);
});
linux('无关旧 PID 不被误杀；Socket 权限校验失败只清理自己且可重试', async t => {
  const f = await fixture(t); await writeFile(join(f.data, 'app.pid'), String(process.pid) + '\n');
  await writeFile(join(f.data, 'socket.ready'), 'stale'); await writeFile(join(f.app, 'app.sock'), 'stale'); await writeFile(join(f.data, 'fail-socket'), '');
  assert.equal((await f.call('start')).code, 1); await until(f.freeLock);
  for (const path of [join(f.data, 'app.pid'), join(f.data, 'socket.ready'), join(f.app, 'app.sock')]) await assert.rejects(stat(path), { code: 'ENOENT' });
  await rm(join(f.data, 'fail-socket')); assert.equal((await f.call('start')).code, 0); assert.equal((await f.request()).code, 200);
  assert.equal((await f.call('stop')).code, 0);
});
