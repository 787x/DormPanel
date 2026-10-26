# DormPanel Sync v3 发布说明

发布于 2026-10-26（Asia/Shanghai）。[Android v0.1.9](https://github.com/787x/DormPanel/releases/tag/v0.1.9) 为 Stable / Latest；[fnOS fpk-v0.8.1](https://github.com/787x/DormPanel/releases/tag/fpk-v0.8.1) 为已公开的 Pre-release，不替换 Android Latest。产物直接复用 PR58 最终冻结 candidate-3，没有重新构建。PR58 的 **RC ACCEPTED** 保留为[历史验收事实](pr58-sync-v3-rc.md)，本页为实际发布说明。

| 组件 | 实际版本 | 正式附件 |
|---|---|---|
| X08E | 0.1.9 / code 12 | [DormPanel-X08E-0.1.9.apk](https://github.com/787x/DormPanel/releases/download/v0.1.9/DormPanel-X08E-0.1.9.apk) |
| Phone | 0.1.8 / code 10 | [DormPanel-Phone-0.1.8.apk](https://github.com/787x/DormPanel/releases/download/v0.1.9/DormPanel-Phone-0.1.8.apk) |
| HA Integration | 0.1.2，未变更 | [DormPanel-HA-0.1.2.zip](https://github.com/787x/DormPanel/releases/download/v0.1.9/DormPanel-HA-0.1.2.zip) |
| fnOS Schedule Manager | 0.8.1，Pre-release | [dormpanel-schedule-manager-0.8.1.fpk](https://github.com/787x/DormPanel/releases/download/fpk-v0.8.1/dormpanel-schedule-manager-0.8.1.fpk) |

GitHub tag v0.1.9 不改变 Phone 的独立版本 0.1.8。完整校验值：[Android / HA SHA256SUMS](https://github.com/787x/DormPanel/releases/download/v0.1.9/SHA256SUMS.txt)、[FPK SHA256SUMS](https://github.com/787x/DormPanel/releases/download/fpk-v0.8.1/SHA256SUMS.txt)，另有[仓库冻结清单](evidence/pr58/SHA256SUMS.txt)。GitHub 自动生成的 Source code 归档不能代替正式 APK / FPK。

## 功能说明

X08E、Phone 与可选 fnOS Writer 三端使用独立 DPV3/3 协议目录、不可变对象、分页因果历史、Epoch / Checkpoint、可恢复 Pending 与回执、长期离线和迟到分支处理。v3 通过 Epoch 避免 v2 的全数据集累计 256 提交限制，仍受每轮与分代预算约束；v2 限制并未解除。

课程按稳定业务身份进行三方合并；无法安全合并时保留本地内容并要求明确冲突决策，恢复待发布与回执后再继续，不按文件时间或最后写入者覆盖。

fnOS 增加 v3 分页历史、完整 RAW 比较/差异、新提交式回退、独立归档、私有安全整理及 DPBK0002 完整应用备份。安全访问端点变更先只读预检同一 Descriptor，确认后保留本机 Writer 和本地编辑；确认后的网页会重新读取管理版本。普通 WebDAV 远端自动 GC 仍不可用。

## X08E

沿用 `com.dormpanel.app` 和批准签名，minSdk 28、固定横屏使用。保留原 Room、导入、布局、效率数据、设置与 HA 身份。先备份并验证旧包签名、版本和发布文件哈希，再通过系统安装器或 `adb install -r DormPanel-X08E-0.1.9.apk` 原位升级。重开检查课程与共享入口；升级不会自动迁移日用 v2。

## Phone

沿用 `com.dormpanel.timetable` 和批准签名，minSdk 28。先备份、核对签名和发布文件哈希，再执行 `adb install -r DormPanel-Phone-0.1.8.apk` 或系统原位安装。重开核对原课程、导入来源、绑定和凭据。v3 首次加入/迁移须明确选择已经验证的同一 Descriptor。

## fnOS

先保留完整私有状态、连接配置、授权目录、安装身份与可恢复备份；升级期间停止其他可能竞争的维护操作。从 fnOS 官方应用中心手动上传较高版本的已验证 FPK，按官方流程升级并核对实际版本、运行源码和服务状态。CLI 返回“已安装”不能替代这些核对。

**已安装 0.8.0 时不能用同版本包完成官方网页升级；不能以 `appcenter-cli install-local` 绕过限制，该工具在 PR58 实测先卸载旧包。** 最终 0.8.1 已从 0.8.0 通过官方网页原位升级并核对持久身份和数据。不卸载、不清理数据、不复制安装 Writer；保留原 Writer、旧工作区、连接与凭据，升级本身不自动迁移工作区或开始远端发布。CLI/MCP 默认只读，高影响操作继续要求管理员权限及独立网页批准。

## 迁移、回退及恢复限制

- v2→v3 是显式迁移，安装升级不会自动迁移已有日用库。首台设备明确迁移并创建单一 v3 目标；后续 v2 设备选择已有 Descriptor、完成 RAW 等价预检后加入。存在冲突、不完整历史或未回执 PUT 时先恢复，不新建根来覆盖。
- 原 v2 目录与迁移证据保留。已接受 v3 编辑或发布后，不能通过旧 APK/FPK 简单切回 v2；先保留未发布数据、完整备份与外部历史，按既有恢复保护处理。
- Android 不以降 versionCode、卸载、清数据或重置 Keystore回退。旧二进制不保证能读取新的 v3 私有状态；不能把迁移前旧副本当作当前数据。
- DPBK0002 包含应用私有状态、凭据与私有本地历史；外部 WebDAV / 授权目录仍需原数据和访问条件。不是整个远端数据集的离线独立备份；恢复只适用同一安装身份，恢复后先隔离发布并核验依赖。
- 端点切换成功、对象精确回读、其他端实际观察、已知设备确认及服务端禁止写入是不同证据。未知旧 Writer 和旧分代迟到写入仍可能存在，不能自动删除旧历史。
- HA ZIP 保持 0.1.2，与当前源码核验一致；没有新的 HA 功能或版本，已部署同版本无需仅为本次 Sync v3 更新而重复部署。
