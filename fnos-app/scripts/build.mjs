import { cp, mkdir, readFile, writeFile, chmod, rm } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
const root = new URL('../', import.meta.url), build = new URL('../build/dormpanel-schedule-manager/', import.meta.url);
await mkdir(build, { recursive: true });
for (const folder of ['cmd', 'config', 'wizard', 'app/ui/images', 'app/server']) await mkdir(new URL(folder + '/', build), { recursive: true });
for (const name of ['src', 'web']) await cp(new URL(name, root), new URL('app/server/' + name, build), { recursive: true });
for (const name of ['package.json', 'package-lock.json']) await cp(new URL(name, root), new URL('app/server/' + name, build));
// npm ci 的运行依赖是纯 JS；不打包开发机二进制、缓存或开发依赖。
if (!process.env.npm_execpath) throw Error('请使用 npm run build');
const installed = spawnSync(process.execPath, [process.env.npm_execpath, 'ci', '--omit=dev', '--ignore-scripts', '--prefix', new URL('app/server/', build).pathname.replace(/^\/(\w:)/, '$1')], { stdio: 'inherit' });
if (installed.status !== 0) throw Error('依赖安装失败');
await writeFile(new URL('manifest', build), `appname=dormpanel-schedule-manager
version=0.6.1
display_name=DormPanel Schedule Manager
desc=多工作区、本地与 WebDAV 数据源、Sync v2 课表编辑与发布
platform=all
source=thirdparty
maintainer=787x
distributor=787x
desktop_uidir=ui
desktop_applaunchname=dormpanel-schedule-manager.main
install_dep_apps=nodejs_v22
os_min_version=1.1.3100
ctl_stop=true
`);
await writeFile(new URL('config/privilege', build), JSON.stringify({ defaults: { 'run-as': 'package' }, username: 'dormpanel-schedule-manager', groupname: 'dormpanel-schedule-manager' }));
await writeFile(new URL('config/resource', build), JSON.stringify({ 'data-share': { shares: [{ name: 'dormpanel-schedule-manager/schedules' }] } }));
await writeFile(new URL('app/ui/config', build), JSON.stringify({ '.url': { 'dormpanel-schedule-manager.main': { title: 'DormPanel Schedule Manager', icon: 'images/icon_{0}.png', type: 'iframe', protocol: '', gatewayPrefix: '/app/dormpanel-schedule-manager', gatewaySocket: 'app.sock', url: '/app/dormpanel-schedule-manager/', allUsers: false, control: { accessPerm: 'readonly' } } } }));
const main = await readFile(new URL('main.sh', import.meta.url));
await writeFile(new URL('cmd/main', build), main); await chmod(new URL('cmd/main', build), 0o755);
await writeFile(new URL('app/server/control.sh', build), main); await chmod(new URL('app/server/control.sh', build), 0o755);
await cp(new URL('socket-access.sh', import.meta.url), new URL('app/server/socket-access.sh', build));
await chmod(new URL('app/server/socket-access.sh', build), 0o755);
for (const hook of ['install_init', 'install_callback', 'upgrade_init', 'upgrade_callback', 'uninstall_init', 'uninstall_callback', 'config_init', 'config_callback']) {
  const content = hook === 'upgrade_init' || hook === 'uninstall_init' ? '#!/bin/sh\n"$TRIM_APPDEST/server/control.sh" stop\nexit $?\n' : ['install_callback', 'upgrade_callback', 'config_callback'].includes(hook) ? '#!/bin/sh\numask 077\nmkdir -p "$TRIM_PKGETC" "$TRIM_PKGVAR"\nchmod 700 "$TRIM_PKGETC" "$TRIM_PKGVAR"\n/var/apps/nodejs_v22/target/bin/node "$TRIM_APPDEST/server/src/configure.mjs"'+(hook === 'upgrade_callback' ? ' --upgrade' : hook === 'config_callback' ? ' --configure' : '')+'\nexit $?\n' : '#!/bin/sh\nexit 0\n';
  await writeFile(new URL('cmd/' + hook, build), content); await chmod(new URL('cmd/' + hook, build), 0o755);
}
// 增量构建也必须移除旧安装表单，保留官方配置/目录授权入口。
await rm(new URL('wizard/install', build), { force: true });
await writeFile(new URL('wizard/config', build), JSON.stringify([{ stepTitle: '数据源管理', items: [{ type: 'tips', helpText: '请打开 DormPanel 页面中的数据源管理添加或修改连接；NAS 本地文件夹授权请使用应用设置的访问权限。系统配置回调不会覆盖已保存的工作区或凭据。' }] }]));
// 仓库内自绘 PNG 图标，无外部图片、字体或构建时下载。
for (const [source, targets] of [['icon64.png', ['ICON.PNG', 'app/ui/images/icon_64.png']], ['icon256.png', ['ICON_256.PNG', 'app/ui/images/icon_256.png']]]) for (const target of targets) await cp(new URL(source, import.meta.url), new URL(target, build));
console.log('FPK 工程已生成：' + build.pathname);
