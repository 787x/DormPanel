# Android v0.1.11 / fnOS 0.8.2 发布记录

源码提交：`e44e4a07061191b2febe2816ebdb5c005da3e973`（PR75 main 上仅增加版本与发布说明）。两个 Tag：`v0.1.11`、`fpk-v0.8.2`，均指向此提交；后续记录提交不改变冻结资产来源。

- Android：[v0.1.11](https://github.com/787x/DormPanel/releases/tag/v0.1.11)，Stable / Latest。
- fnOS：[fpk-v0.8.2](https://github.com/787x/DormPanel/releases/tag/fpk-v0.8.2)，Pre-release。
- HA 保持 0.1.2，没有构建、上传或发布 HA ZIP。

| 文件 | 字节 | SHA-256 |
|---|---:|---|
| DormPanel-Phone-0.1.10.apk | 10794955 | `6dcc95bed06a569ae176204d73495a4b361561e697ea439db7943df009da6fb8` |
| DormPanel-X08E-0.1.11.apk | 16897772 | `94cf2580f882692562abeef1c04f706c890db75b01ae510551c23c09651fa68c` |
| dormpanel-schedule-manager-0.8.2.fpk | 4648167 | `3228f56b2d9471b0e39b4988ee1efdd5a05bb54f82b06003b880877952b03785` |

## 实际检查

- `build-release.ps1 -AndroidOnly -SkipTests`：两款正式 Release APK 门禁通过，包名保持 com.dormpanel.app / com.dormpanel.timetable；版本分别 0.1.11/14、0.1.10/12，SDK 28/37，非 debuggable。批准证书 SHA-256：`32d68b5c6ad0bfd1b87aa0d54514ef71c33838e7643eb038488af7ca2add17f7`，与本地已冻结 v0.1.10 两款旧包一致；versionCode 递增，满足身份层面的原位升级条件。未声称实机安装通过。
- APK ZIP CRC 完整；aapt2 最终资源表存在 compact/wide/recent/week 四套 Widget XML；最终 Manifest 保留四个 Provider 及受 BIND_REMOTEVIEWS 保护的 CollectionService。Phone CreateDocument(text/calendar) 与无额外存储权限、X08E 新旧卡片注册通过源码静态核对；新卡片布局在 Views 代码中创建，未将静态核对报告为触控实测。
- NAS `/tmp/dormpanel-release082-f1dc9791-f924-46aa-9b45-6b9327e116c2` 官方 fnpack 构建成功；最终 FPK 在 NAS 及本地 verify-package 均通过，54 个 src/web/package 文件逐字节一致，Node 22 入口、管理员私有 Socket、目录授权、升级配置及无开发浏览器依赖门禁通过。备份白名单保留 0.8.1 并追加 0.8.2。
- 初次 Windows backup.test 执行因 Linux 平台条件得到 36 SKIP，不能算通过。随后使用现有 `build/pr69-linux-node` 在 WSL 独立 /tmp 目录只执行受版本更新影响的“多工作区、候选/capture/attempt/abandoned、真实冲突、凭据证书和私有历史精确恢复”：1 PASS、0 FAIL、0 SKIP，确认新备份 appVersion 为 0.8.2。未重复全套恢复矩阵。
- 复用 PR71、PR72、PR73 缓存/教学周最终记录、PR74 review-fixes、PR75 ICS 证据，链接见两份发布说明。历史 Kotlin 缓存断言及 Node 增长/worker 基线失败保持披露。
- 未安装或升级日用设备、NAS 应用；未卸载、清数据、恢复真实备份、重置 Writer、改变连接或迁移日用数据集。未完成本轮正式候选启动/原位安装与官方应用中心 0.8.1→0.8.2 升级验收，不能将历史隔离测试计为本轮安装 PASS。

## 本地冻结位置

- `D:\Projects\DormPanel\artifacts\release\android-v0.1.11-candidate1`
- `D:\Projects\DormPanel\artifacts\release\fpk-v0.8.2-candidate1`
- `D:\Projects\DormPanel\artifacts\release\joint-0111-082-freeze.json`

构建日志：`build/release-0111-build.log`、FPK 候选目录 `fpk-build.log`；WSL 定向结果：`build/release082-wsl-backup.log`。

## GitHub 最终核对

两份 Release 均已公开（非 Draft）：Android Stable，`/releases/latest` 返回 `v0.1.11`；fnOS `prerelease=true`。五个附件均为 uploaded，GitHub 资产 API 的 size 与 SHA-256 digest 全部等于本地冻结文件（含两份 SHA256SUMS.txt）。按用户要求未重新下载二进制；服务端 digest 比对不冒称下载后逐字节比较。

公开下载 URL 的无正文 HEAD 探测受本机网络影响，五项均 curl 56 / HTTP 000，未能验证直接下载链路；不计为 HTTP 可下载 PASS。GitHub API 可正常读取已公开 Release 及 uploaded 资产，大小和服务端 digest 已一致。没有下载或修改远端资产。
