# PR53：Sync v3 基础层验收记录

基线：2026-10-24 fetch 后的 `main` / `8625606dee15bd0e2499723b6a499beb5f39385d`。本 PR 只新增基础层，不启用生产 v3 绑定、发布或同步。

## 实际实现

- [协议](sync-v3-protocol.md)：四类 DPV3/3 RAW 对象、规范业务哈希、完整路径/身份约束、引用角色、跨代因果契约、并发初始化/换代与普通 WebDAV 的发现限制。
- Kotlin / Node codec：生成、严格解码/重编码、独立路径和引用身份校验；复用既有 DPSS 领域模型及规则。业务哈希剔除封装身份，并按 `ScheduleMerge.same()` 忽略 source.importedAt；完整快照仍保留该字段。
- 隔离 ObjectStore：WebDAV 与 fnOS 官方 LocalGrants 下的本地目录，严格 list/read、仅测试可用的独立 create、意图回调和精确回读；跨 Epoch 共享有限预算，PUT 无透明重放。
- 独立 `sync-v3-cache-v1`：内容寻址原始 blob、先持久化的不可变路径预期哈希 receipt、经原始字节重建的元数据索引、命中/缺失读取和四类分离证据。索引只保留元数据，不常驻所有业务快照。Android 使用独立可恢复文件日志，不修改 Room schema 或业务行；FPK 不修改 workspace.json / v2 缓存。
- 独立 Python oracle 与共享固定向量：7 个有效对象、19 个错误对象及 manifest，共 27 个固定文件。包含 checkpoint、多父和 Bridge 的**引用编码形状**，没有实现或声称历史图已通过验证。

## 真实执行结果

| 检查 | 结果 |
| --- | --- |
| `python tools/sync_v3_vectors.py` | 27 个固定文件逐字节一致；Kotlin/Node 同时核对完整对象哈希、长度、BusinessStateHash 和重编码 |
| `python tools/sync_v2_vectors.py` | 6 个既有文件通过；v2 向量未改 |
| `:schedule-core:testDebugUnitTest` | 160 项通过；覆盖 v3 有效/非法对象、引用身份、缓存崩溃重建/缺失/冲突/预算、WebDAV 精确回读与 PUT 不重放，以及既有课程/v2 回归 |
| `:app:testDebugUnitTest` / `:phone-app:testDebugUnitTest` | 200 / 3 项通过 |
| Windows `npm test` | 226 项：160 通过、66 个 Linux 专用项跳过，无失败；最终 v3 专项 34 项中 33 通过、1 个 Linux 项跳过 |
| NAS 官方 Node 22.18.0、FPK 包用户 `node --test` | 226 项：225 通过、1 个非 Linux 项跳过，无失败。所有源码、依赖及测试数据位于随机 `/tmp/dormpanel-pr53-*`，未更换已安装包的 node_modules |
| 最终缓存/路径修订后的 NAS `node --test test/v3.test.mjs test/local.test.mjs` | 43 项：42 通过、1 个非 Linux 项跳过，无失败 |
| 实际测试 WebDAV | `verify-v3-isolated.mjs` 在测试服务器 `dormpanel/pr53-b351ed1e-6edc-42ee-825f-872ecade3855/` 下创建、按代列举并精确回读 7 个对象；缓存重开 7 个对象，命中时远端读取 0 次。使用测试服务器，未访问日用服务器的课表目录 |
| X08E / Android 9 与 Phone / Android 13 | 直接 AndroidJUnitRunner 各 **20 项通过**：新缓存测试 + ScheduleDatasetMigrationRoomTest + AppendOnlyRoomSyncTest + ScheduleV2BindingRoomTest。最终缓存修订后，两台设备又各运行新缓存测试 1 项通过 |
| Android 构建 | X08E App / Phone 的 Debug、原签名配置 Release，以及 schedule-core 测试 APK 构建通过；未改版本/签名/applicationId |
| FPK 构建 | npm check/build、官方 fnpack、最终 FPK 内 31 份 src/web/package 字节核对通过；版本保持 0.7.2，无新增依赖、无生产 Playwright/MCP client |

设备测试只安装独立 `com.dormpanel.schedule.core.test` 测试 APK，使用随机私有目录和随机测试数据库；未安装本次生产 APK，未重装/清除日用应用。缓存测试覆盖 fsync 后故障注入和重新打开，不是物理断电测试。

原始本地日志与未发布构建位于忽略提交的 `artifacts/pr53/`。提交的摘要、WebDAV 和包核验结果见 [evidence/pr53](evidence/pr53/verification.json)。凭据、密钥及真实业务数据不进入源码或证据。

## 失败、边界和后续工作

- `:schedule-core:connectedDebugAndroidTest` 两次汇总任务返回非零，但 XML 中两台设备的新缓存用例均为 0 failures；未确定 Gradle/UTP 汇总失败原因。因此没有把该任务记为 PASS。改为直接安装同一独立测试 APK、调用 AndroidJUnitRunner，输出 `OK (20 tests)`；最终缓存修订后输出 `OK (1 test)`。
- 首次 NAS 全测因安装包只有生产依赖而缺 MCP client，随后在随机临时目录独立安装 lockfile 开发依赖；包用户 npm 默认 home 不可写，改用该临时目录专属 npm cache 后通过。未改变包用户权限边界或生产依赖。
- 第一次真实 WebDAV 探测发生网络错误，未确认成功；随后使用新随机测试目录完成验证，没有重放不确定对象路径。初次包核验误选了工作目录中既有 FPK，重新运行官方 fnpack 并显式核对本次产物后通过。
- 未执行真实掉电、FPK 安装/升级、生产 v3 发布、跨代图/换代/离线重放/迟到整合、v2→v3 迁移或物理 GC。本 PR 没有新 Room migration；既有 Room 迁移/同步恢复按上表回归。
- 底层 create 仍需后续生产协调器处理 attempted 日志及本地 staging 中断残留，不能直接当成安全发布入口。
- 缓存单一拥有者/写队列是后续集成前提，不能多进程竞争同一私有缓存目录。启动恢复有 4096 个目录项及共享字节/时间限制，超过时安全停止；更大的缓存需后续实现持久游标分批恢复。普通 WebDAV 不提供停写或全局目录完整性证明。
- PR54/55/56 继续实现并验收图验证、持久同步/换代事务、离线与迟到分支、迁移和 UI。对象 hash 正确、缓存命中或本轮远端观察都不能被报告成“所有设备同步完成”。
