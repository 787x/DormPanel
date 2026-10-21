import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm, chmod, symlink, stat, mkdir } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import net from 'node:net';
const helper = fileURLToPath(new URL('../scripts/socket-access.sh', import.meta.url));
const linux = process.platform === 'linux';
const aclAvailable = linux && spawnSync('getfacl', ['--version']).status === 0 && spawnSync('setfacl', ['--version']).status === 0 && spawnSync('id', ['www-data']).status === 0;
const env = dir => ({ ...process.env, TRIM_APPDEST: dir });

test('Linux实际权限：撤销遗留www-data ACL，target 0700及Socket 0600', { skip: !aclAvailable }, async t => {
  const dir = await mkdtemp(join(tmpdir(), 'dp-acl-')); t.after(() => rm(dir, { recursive: true, force: true }));
  const server = net.createServer(); await new Promise(r => server.listen(join(dir, 'app.sock'), r)); t.after(() => new Promise(r => server.close(r)));
  await chmod(join(dir, 'app.sock'), 0o600);
  for (const path of [dir, join(dir, 'app.sock')]) assert.equal(spawnSync('setfacl', ['-m', 'u:www-data:rwx,m::rwx', path]).status, 0);
  for (const action of ['directory', 'socket']) { const result = spawnSync('/bin/sh', [helper, action], { env: env(dir), encoding: 'utf8' }); assert.equal(result.status, 0, result.stderr); }
  const parent = spawnSync('getfacl', ['-cp', dir], { encoding: 'utf8' }).stdout;
  const socket = spawnSync('getfacl', ['-cp', join(dir, 'app.sock')], { encoding: 'utf8' }).stdout;
  assert.doesNotMatch(parent, /user:www-data:/); assert.match(parent, /group::---/); assert.match(parent, /other::---/);
  assert.doesNotMatch(socket, /user:www-data:/); assert.match(socket, /group::---/); assert.match(socket, /other::---/);
  assert.equal((await stat(dir)).mode & 0o777, 0o700); assert.equal((await stat(join(dir, 'app.sock'))).mode & 0o777, 0o600);
  // 这是ACL的真实执行测试；其他UID/同UID进程的连接证据由NAS审计另行记录。
});
test('无法设置私有权限时失败，不回退共享组或全员权限', { skip: !linux }, async t => {
  const dir = await mkdtemp(join(tmpdir(), 'dp-acl-fail-')); t.after(() => rm(dir, { recursive: true, force: true }));
  const result = spawnSync('/bin/sh', [helper, 'directory'], { env: { ...env(dir), PATH: '/nonexistent' }, encoding: 'utf8' });
  assert.notEqual(result.status, 0); assert.match(result.stderr, /chmod/);
});
test('缺少ACL工具时chmod仍屏蔽遗留named-user权限', { skip: !aclAvailable }, async t => {
  const dir = await mkdtemp(join(tmpdir(), 'dp-acl-mask-')); t.after(() => rm(dir, { recursive: true, force: true }));
  const bin = join(dir, 'bin'); await mkdir(bin); await symlink('/bin/chmod', join(bin, 'chmod'));
  const server = net.createServer(); await new Promise(r => server.listen(join(dir, 'app.sock'), r)); t.after(() => new Promise(r => server.close(r)));
  for (const path of [dir, join(dir, 'app.sock')]) assert.equal(spawnSync('setfacl', ['-m', 'u:www-data:rwx,m::rwx', path]).status, 0);
  for (const action of ['directory', 'socket']) assert.equal(spawnSync('/bin/sh', [helper, action], { env: { ...env(dir), PATH: bin } }).status, 0);
  for (const path of [dir, join(dir, 'app.sock')]) assert.match(spawnSync('getfacl', ['-cp', path], { encoding: 'utf8' }).stdout, /mask::---/);
  assert.equal((await stat(dir)).mode & 0o777, 0o700); assert.equal((await stat(join(dir, 'app.sock'))).mode & 0o777, 0o600);
});
test('拒绝把符号链接当作待授权Socket', { skip: !aclAvailable }, async t => {
  const dir = await mkdtemp(join(tmpdir(), 'dp-acl-link-')); t.after(() => rm(dir, { recursive: true, force: true }));
  const server = net.createServer(); await new Promise(r => server.listen(join(dir, 'real.sock'), r)); t.after(() => new Promise(r => server.close(r)));
  await symlink(join(dir, 'real.sock'), join(dir, 'app.sock'));
  assert.notEqual(spawnSync('/bin/sh', [helper, 'socket'], { env: env(dir) }).status, 0);
});
