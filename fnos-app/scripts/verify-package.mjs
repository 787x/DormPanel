// 在官方 fnpack 构建后检查最终 FPK，而非仅检查生成目录。
import assert from 'node:assert/strict';
import { readFile, readdir } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import { resolve } from 'node:path';
import { createHash } from 'node:crypto';
const root = new URL('../', import.meta.url), path = resolve(process.argv[2] ?? 'dormpanel-schedule-manager.fpk');
function tar(args, input) {
  const result = spawnSync('tar', args, { input, maxBuffer: 32 * 1024 * 1024 });
  assert.equal(result.status, 0, result.stderr.toString()); return result.stdout;
}
const files = tar(['-tf', path]).toString().split(/\r?\n/);
assert(!files.some(p => /^wizard\/install(?:\/|$)/.test(p)), '安装包不能残留 WebDAV 安装向导');
assert(files.includes('wizard/config'), '必须保留 fnOS 配置与目录授权入口');
assert.deepEqual(JSON.parse(tar(['-xOf', path, 'config/resource'])), { 'data-share': { shares: [{ name: 'dormpanel-schedule-manager/schedules' }] } });
const pkg = JSON.parse(await readFile(new URL('package.json', root)));
assert.match(tar(['-xOf', path, 'manifest']).toString(), new RegExp('version\\s*=\\s*' + pkg.version.replaceAll('.', '\\.')));
const app = tar(['-xOf', path, 'app.tgz']), appFiles = tar(['-tzf', '-'], app).toString();
assert(!/server\/node_modules\/(?:playwright|playwright-core)(?:\/|\n)/.test(appFiles), '不得包含开发期浏览器依赖');
let checked = 0;
for (const folder of ['src', 'web']) for (const file of await readdir(new URL(folder + '/', root))) {
  assert.deepEqual(tar(['-xzOf', '-', `server/${folder}/${file}`], app), await readFile(new URL(folder + '/' + file, root)), file); checked++;
}
for (const file of ['package.json', 'package-lock.json']) {
  assert.deepEqual(tar(['-xzOf', '-', 'server/' + file], app), await readFile(new URL(file, root)), file); checked++;
}
for (const hook of ['install_callback', 'config_callback', 'upgrade_callback']) assert.match(tar(['-xOf', path, 'cmd/' + hook]).toString(), /src\/configure\.mjs/);
console.log(JSON.stringify({ version: pkg.version, noInstallWizard: true, folderAuthorizationPreserved: true, sourceFilesVerified: checked, sha256: createHash('sha256').update(await readFile(path)).digest('hex') }, null, 2));
