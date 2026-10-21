import { readFile, mkdir, writeFile, rename, chmod } from 'node:fs/promises';
import { join } from 'node:path';
import { binding } from './webdav.mjs';
import { ensure } from './protocol.mjs';
process.umask(0o077);
try {
  const dir = process.env.TRIM_PKGETC; ensure(dir, '缺少配置目录');
  // 0.3 系统配置只承载目录授权，不消费任何旧 WebDAV 向导字段。
  if (process.argv.includes('--configure')) process.exit(0);
  const path = join(dir, 'webdav.json'); let old = null;
  // 多工作区启用后，旧向导只为兼容升级保留，不再维护第二套凭据源。
  if (process.env.TRIM_PKGVAR) {
    let catalog = null;
    try { catalog = await readFile(join(process.env.TRIM_PKGVAR, 'catalog.json'), 'utf8'); }
    catch (e) { if (e.code !== 'ENOENT') throw e; }
    if (catalog) {
      // fnOS 的目录授权同样触发 config_callback，必须允许其完成。
      // 连接配置已由页面接管；陈旧 wizard 环境不能改写任何连接。
      process.exit(0);
    }
  }
  try { old = JSON.parse(await readFile(path, 'utf8')); } catch (e) { if (e.code !== 'ENOENT') throw e; }
  // fnOS 升级会重放安装时的 wizard 环境；不能覆盖管理员在应用内更新的绑定/凭据。
  // 系统可能先调用已安装版本的无参数 callback。已有配置默认只校验、保留；
  // 已有配置默认只验证保留；0.3 的连接修改由页面独立持久化。
  const upgrading = process.argv.includes('--upgrade') || (!!old && !process.argv.includes('--configure'));
  if (upgrading && old) { binding(old); ensure(old.username && old.password, '已有凭据无效'); }
  const url = upgrading ? null : process.env.wizard_webdav_url?.trim();
  if (url) {
    const c = { ...old, url, username: process.env.wizard_webdav_username || old?.username || '', password: process.env.wizard_webdav_password || old?.password || '' };
    binding(c); ensure(c.username && c.password, '请填写 WebDAV 凭据');
    ensure(!old || binding(old).key === binding(c).key, '本版不允许更换已配置的绑定地址');
    await mkdir(dir, { recursive: true, mode: 0o700 }); await writeFile(path + '.tmp', JSON.stringify(c), { mode: 0o600 }); await rename(path + '.tmp', path); await chmod(path, 0o600);
  }
} catch {
  if (process.env.TRIM_TEMP_LOGFILE) await writeFile(process.env.TRIM_TEMP_LOGFILE, 'WebDAV 配置无效：检查 HTTPS 目录、凭据及既有绑定地址。');
  process.exitCode = 1;
}
