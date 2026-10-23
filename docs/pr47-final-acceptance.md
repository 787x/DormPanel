# PR47 合并前定向验收

日期：2026-10-23。基于 `7063b1804c17a5de02177b39f6d99ed8a98fcc65`，在同一个 PR47 内完成必要修复。最终候选为 **Android 0.1.4、FPK 0.6.1**；FPK 升版已获管理员同意，以便通过 fnOS 正常升级路径复核修复包。没有合并、tag 或 Release。

## 结论

**READY TO MERGE。** 本次要求的正式应用原位升级、正式 UI 隔离换库、实际重启恢复、写入保护、安全拒绝及最终候选复核已完成，发现的两处 UI 缺陷已修复。PR 继续保持草稿，不自动合并或发布；本结论的网络与未执行边界见文末。

## 实际执行与数据保护

| 检查 | 结果及证据 |
| --- | --- |
| 正式 Android 原位升级 | X08E `com.dormpanel.app` / API 28、MIX 2S Phone `com.dormpanel.timetable` / API 33；升级前完整备份 APK、Room、设置及文件并读回。连续使用 `adb install -r`，没有卸载、清空数据库或改变签名。首次升级后、启动前的 32 / 20 个文件哈希与备份一致；最终安装 APK 又从设备取回，与最终候选逐字节一致。[原位数据核验](evidence/pr47/final/upgrade-preservation.json)、[安装核验](evidence/pr47/final/installed-final.json) |
| 正式应用 UI 换库 | 两端在保留原 Room 业务记录和 Writer 的隔离工作副本上，通过正式共享设置输入目标目录 / datasetId、保存预览、保持暂停、确认切换，并在管理员放行后完成本机验收。逐字段等价：来源 1、课程实例 131、学期 1，其余业务集合为空。旧 5 提交 → 新 1 个根。[X08E 持久日志及完成记录](evidence/pr47/final/x08e-local-migration.json)、[Phone 完成](evidence/pr47/final/phone-completed.png) |
| 中断与重启 | Phone 在 prepared 阶段实际 force-stop / 重开，在 committed 阶段实际 `adb reboot` 后重开；X08E 在 prepared 阶段实际 `adb reboot` 后继续。保护和日志保留，重开后使用目标目录与目标基线。两端最终 `sys.boot_completed=1`。[Phone 重启后 committed](evidence/pr47/final/phone-committed-reboot.png) |
| 保护期间普通入口 | Phone 新增课程入口隐藏；已打开编辑器的保存检查和后端事务均共用保护。实际尝试普通同步、选择 ICS 导入及学期保存，均未改变业务数据。Room 回读确认六类业务表原值一致，无幽灵课程或持久存储错误。[提交后数据核验](evidence/pr47/final/audit-committed.txt) |
| 目标暂不可达 | 主动断开隔离连接，或临时移动第二个隔离候选目录后确认；拒绝完成并保留 prepared / 原绑定，恢复目录后仍可从入口继续。该操作只影响新建测试目录，无删除。 |
| 源新增合法提交、预览过期 | 首次迁移完成后，另建第二个等价单根候选。Phone 保存第二次迁移预览，再向其源数据集追加并回读一条合法同状态子提交；确认被拒绝，提示“旧库前沿变化，请同步并重新归档准备候选”。保留原绑定，随后通过 UI 取消准备并正常同步。[拒绝页面](evidence/pr47/final/phone-stale-refused.png) |
| 正常写入恢复 | Phone 正式 UI 新增 `PR47_UI_AFTER`，FPK 网关接收，再通过网关新增 / 发布 `PR47_UI_FPK_AFTER`。X08E、Phone 正常同步后，Room 均含这条课程和事件，原导入集合与稳定身份仍一致。[两端接收核验](evidence/pr47/final/audit-completed.txt) |
| 已安装 FPK 正常升级 | 在应用中心“手动安装 → 从 NAS 添加”执行 **0.4.4 → 0.6.0 → 0.6.1**，实际观察升级过程。两次均预先完整备份 var / etc 并读回；0.6.1 升级前已有 7 个工作区、7 个连接，升级后均一致，20 个受保护文件字节不变，身份与凭据一致。[首次升级](evidence/pr47/final/fpk-upgrade-verification.txt)、[最终包升级](evidence/pr47/final/fpk-upgrade-061-verification.txt) |
| 正式 FPK 网关迁移 | 真实管理员网关页面完成归档 / 候选校验、设备登记、停写、独立工作区切换及退役开放；原工作区仍能查看 5 个提交。Android 登记依据实际本机 committed 摘要，明确注明管理员登记和 USB 测试传输。最终 0.6.1 又以仅 FPK 的合成 2 提交源完成归档、准备、停写及切换：页面自动选中新工作区并显示 1 根和 switched，不需刷新；随后实际安全取消，保留两库和证据。[0.6.1 切换](evidence/pr47/final/fpk-061-switch.png) |
| FPK 旧工作区只读 | 正式 UI 尝试新增事件并保存，后端返回“换库保护禁止课程编辑、发布与冲突决策”，表单可关闭；普通同步同样拒绝。没有产生旧库提交。[实际拒绝](evidence/pr47/final/fpk-old-write-refused.txt) |
| 原 FPK 数据和入口 | 原有 5 个工作区完整 workspace.json、连接行和安装 Writer 在全部主要测试后仍逐字节一致；升级后历史、备份与恢复页正常显示，测试结束切回原默认工作区。未对真实实例执行备份恢复覆盖。[原状态核验](evidence/pr47/final/fpk-original-preserved.txt)、[备份入口](evidence/pr47/final/fpk-backup-061.png) |
| 日用数据恢复 | 完整保留隔离测试最终状态后，将两端原日用目录恢复原位；X08E 32 个、Phone 22 个保留文件哈希一致，分别 3 / 2 个 SQLite 数据库 integrity_check 通过；原课程业务表及 Writer 与最初备份一致。正式签名候选继续安装。恢复后原课表可见，X08E Todo 入口、课表卡片、HA 连接与传感器显示正常；Todo / Memo 的 productivity 数据库原文件保留，未新增或编辑真实备忘。[恢复核验](evidence/pr47/final/restoration-verification.txt) |

隔离准备没有删除课程再首次绑定：原目录先完整保留，工作目录是含全部 Room 记录的副本。只在证明源 DPC 原始字节完全相同后，为测试副本配置同数据集隔离端点；后续换 dataset 的基线、witness、目录和恢复档案由正式 UI 的迁移事务建立。测试结束恢复的是预先保留的日用目录，未把隔离新库覆盖回日用库。

**本轮正式 Android UI 网络路径：** 管理员批准的 HTTP 5005，仅限 NAS 隔离目录；设备经临时 USB TCP 转发访问同一 NAS。FPK 使用内网 HTTPS 5006 和现有独立证书信任配置。两种地址读取的是同一份 NAS 文件，Android 有实际完整图读回证据；没有把 FPK 本地可读当作手机可达。日用 HTTPS 配置已恢复，测试转发已移除。此前独立测试 APK 的直接公网 HTTPS / 三端实机核心同步证据仍见[原验收记录](pr47-dataset-migration-verification.md)，与本轮正式 UI 证据分别记录。

## 必要修复及回归

1. Android：原后端拒绝了迁移保护中的写入，但乐观 UI 仍可能暂留未持久化课程并进入存储错误状态。现在提前检查持久保护；排队写入遇到保护竞态时重新读取已接受数据，不把预期拒绝当作数据库损坏。导入 / 删除拒绝必定回调，课程和学期编辑显示可理解的拒绝原因。
2. FPK：后端切换活动工作区后，页面仍携带旧工作区身份请求历史。现在重新读取活动工作区及历史，真实网关和浏览器回归均确认无需刷新即可继续。
3. FPK 0.6.1 备份元数据与包版本一致，继续接受旧 0.6.0 及此前支持版本的备份。

| 定向验证 | 实际结果 |
| --- | --- |
| JVM | schedule-core **146 / 146**、app **198 / 198**，无失败。含两项新增保护 / 竞态回归。[统计](evidence/pr47/final/jvm-test-summary.json)、[构建日志](evidence/pr47/final/fix-tests.txt) |
| Android Release | 两款最终 APK 构建、aapt / apksigner 及批准证书检查通过；设备内 APK 再取回校验通过。[签名核验](evidence/pr47/final/release-verification.txt) |
| Windows Node | 177 项，114 通过、63 项 Linux 专项跳过、0 失败。[日志](evidence/pr47/final/fpk-node-061.txt) |
| fnOS 官方 Node 22、非 root 包用户 | 最终源码的备份 / 历史观察 **46 / 46**、迁移 **7 / 7**，无失败或跳过；包含备份恢复、拒绝状态、事务恢复、哈希 / 漏列和旧库新增提交。[最终 53 项日志](evidence/pr47/final/fnos-061-tests.txt) |
| 浏览器 | 21 / 21；新增切换响应后读取新活动工作区和历史的回归通过。[全套](evidence/pr47/final/fpk-browser-final.txt)、[定向](evidence/pr47/final/fpk-switch-ui-test.txt) |
| FPK 打包 | 0.6.1 静态检查、生产依赖审计、官方 fnpack 及 22 份 src / web / package 字节比对通过，无 Playwright 运行依赖；已安装实例的同 22 份文件也与最终源码一致。[包核验](evidence/pr47/final/package-061.txt)、[安装文件核验](evidence/pr47/final/installed-fpk-source.txt) |

## 最终候选 SHA-256

三份候选均因必要修复而改变哈希；Android 仍使用原 applicationId、版本号和批准签名。没有发布候选二进制。

| 本地文件 | SHA-256 |
| --- | --- |
| `artifacts/pr47/final/release-candidate/DormPanel-X08E-0.1.4.apk` | `5f792f3126e1586c84368c0caa598fefc7a2c31b52f1f7870d128b260dc326c4` |
| `artifacts/pr47/final/release-candidate/DormPanel-Phone-0.1.4.apk` | `d7c90487dcfbb01fa2373e5a8a62b86c196c597cad86744d6bbb8275a6ee4475` |
| `artifacts/pr47/final/dormpanel-schedule-manager-0.6.1.fpk` | `32c9ed1d208516461bac1e2ff3bd4d873878186103a47a984b6834bab82d1077` |

## 退役和验收边界

- **完成换库：** 本轮主隔离图已由两台正式 Android 和 FPK 验收，从单根继续同步；最终保留的 Android 日用库已恢复原位。
- **管理员确认设备迁移：** FPK 清单保存人工登记及所查看的本机证据，不代表自动发现或证明全部客户端永久停写。
- **旧库不再可写：** 已证明受保护 FPK 工作区后端拒绝写入；没有声称 NAS 存储 / WebDAV 服务端已经禁止所有其他客户端写旧目录。
- **物理空间回收：** **0 字节**。原旧库仍为 5 个 DPC、245,192 字节，所有原 SHA-256 不变；完整归档 5 个原始 DPC、两个 verified 标记和原精简单根仍保留。[旧库核验](evidence/pr47/final/old-immutability.json)、[归档核验](evidence/pr47/final/archive-final.txt)。没有自动 DELETE，未删除任何真实课程目录。

未执行本轮正式 UI 的直接 Wi-Fi / 公网 HTTPS 端到端验收；没有在正式日用库注入崩溃、制造冲突或回滚业务编辑。任意事务指令点的强制中断仍由此前独立 Room / 测试 APK 的四阶段证据覆盖，本轮正式应用覆盖上述实际重启与重开阶段。没有新增 16MiB 极限内存压力、四小时 soak、真实实例备份恢复覆盖、NAS 服务端停写或物理清理。Memo 未另外进行新增 / 保存操作；本轮验证其原持久数据完整，未把这称为 Memo 编辑 UI 全量验收。
