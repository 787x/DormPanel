# DormPanel Schedule Manager 0.4.1

日期：2026-10-22（Asia/Shanghai）。基线为 PR42 合并提交 `76dc937e8fc5d36f2d2ff104c2f3bbc75219edf5`。本次没有修改 Android、Home Assistant、同步协议、运行依赖、包用户、应用 ID、数据路径或升级机制。

## 版本和旧备份

FPK manifest 生成器、Node package/lock 和 Web 页面统一为 0.4.1。新归档记录 0.4.1；读取仅允许格式版本 1 的 0.4.0、0.4.1 归档。DPBK0001、scrypt、AES-256-GCM、文件长度/校验、writerId 和 installationId 约束以及恢复事务保持原样，未知应用/格式版本仍拒绝。

新增固定旧密文夹具，由原 0.4.0 源码实际创建，覆盖四工作区、候选、冲突、凭据证书和私有历史。0.4.1 直接认证、恢复并逐字节比较，分别改变两项安装身份均拒绝。夹具来源和哈希见 [说明](../fnos-app/test/fixtures/backup-0.4.0/README.md)。

## 本轮实际验证

- Windows Node 24：139 项，93 通过、46 个 Linux 项跳过、0 失败；fnOS 官方 Node 22.18.0、包用户：139 项，138 通过、1 个非 Linux 项跳过、0 失败。
- `npm run check`、`npm run build`、官方 `fnpack build` 通过，依赖审计 0 漏洞。包内 20 份源码/Web/package 文件、内外启动脚本、Socket 权限脚本和升级钩子与工作区逐字节一致；manifest 版本、应用 ID、Node 22 依赖和 package 用户核对通过。
- 升级前正常 stop 后完整保护 var/etc：25 个普通文件；重新读取 tar.gz，核对全部成员集合、文件大小和 SHA-256。包含 identity、catalog、全部五工作区完整帧、私有 local-data 提交、连接/凭据/证书配置及兼容文件。备份保留在 NAS 私有 0700 安全目录，归档和清单为 0600；敏感原始清单不提交仓库。
- 通过侧边浏览器的 fnOS 应用中心“手动安装→从电脑上传”，对现有 0.4.0 执行正常更新。应用中心和安装后的 package/Web 均为 0.4.1，无卸载重装、无数据清理。
- 升级后以及正常 stop/start 后，writerId、installationId、catalog、五工作区及私有历史不变；22 个持久化文件大小、SHA-256、inode 和 mtime 全部不变，排除日志/PID/ready/锁等运行文件及派生摘要。五工作区课程数为 2/2/1/2/1，最后一个保留原冲突；原 pending 等字段由完整帧字节比较覆盖（本轮原五工作区没有 pending 对象）。
- 唯一有效生产 Writer、0600 Socket 和真实管理员页面正常；页面仍显示原有待发布修改。安装实例通过管理员 Socket 创建/验证包含五工作区、22 文件的 0.4.1 加密备份，取消预览后私有明文暂存清理完成；其后持久化文件再核对无变化。
- PR42 已留存、SHA-256 为 `f3fabb53683a71c7d71927d06855e19b155720bf2c8f8e6ce3fa7c2285bd57b5` 的真实旧 0.4.0 归档（16,995 字节），本轮使用**实际安装的 0.4.1 源码**在新建隔离副本中认证并恢复：10 文件、四工作区、pending/capture/冲突及身份均保留，发布隔离存在。恢复文件逐个核对大小和 SHA-256；不覆盖原五工作区，不进行外部写入。

本轮未重做完整三端同步、四小时 soak、大规模性能基准或全部恢复故障注入。PR42 原有结果只作为开发基线，不计作本次执行结果。

## 最终包

文件：`dormpanel-schedule-manager-0.4.1.fpk`；大小：1,756,494 字节。

SHA-256：`3cd85d71288b4fb68f3c2c74b37a60e8441a26896086c60e0706bee97f5c8e38`。

配套 `.fpk.sha256` 使用本次最终包重新计算；不沿用任何 0.4.0 候选哈希。计划发布 tag `fpk-v0.4.1`，Pre-release=true、Draft=false、make_latest=false；Android `v0.1.2` 保持 Latest。0.4.0 仅为内部候选，不创建其 Release。

## 功能和限制

本版本发布 PR42 的页面内加密备份、安全恢复事务、恢复后发布隔离及工作区按需加载，并包含预览到期/取消/失败清理和并发启动/stop 的生命周期加固。安全设计详见 [PR42](pr42-fpk-backup-recovery.md) 和 [最终加固](pr42-final-hardening.md)。

外部 WebDAV/NAS 提交历史不默认包含在备份中，本轮不修改或删除这些历史；跨安装恢复不支持，writerId 和 installationId 必须相同。口令不保存，遗失无法解密。已验证环境仅为 fnOS 1.2.0800 x86_64 / 官方 Node 22.18.0；manifest 的 platform=all 不是 ARM 实测声明。ARM、真实掉电、磁盘满、介质损坏及其他 fnOS 版本未验证；持续权限故障可能留下私有暂存并等待重试。
