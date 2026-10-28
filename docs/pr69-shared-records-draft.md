# PR69：通用共享状态实现与隔离验证

基于 PR68 合并提交 `9ef5154ef6819a9e2f2bc4d6f7389cb6237f970d`，实现 DPV3/4、DPXS/1 和 DPXR/1。协议与开发接口见 [通用共享状态规范](sync-v3-shared-state.md)。PR 保持 Draft，供架构与兼容性审查；没有修改正式版本号或迁移日用数据。

## 实际完成

- 通用记录、规范排序、版本与预算、类型装配、独立 payload 校验和课程颜色业务接口；Kotlin/Node 同字节固定向量与完整业务哈希。
- 默认三方合并、删除/并发冲突、关联组件、未知可选透传及只读保护、未知必需/依赖能力写保护。
- 完整状态接入 v3 对象、捕获、回执、重启、Checkpoint、历史索引和缓存；Room/Workspace 同事务保存，普通 DAO 编辑保留扩展。
- 正式 Workspace revision/权限检查，通用历史分页/比较，SQLite 备份校验与恢复；不新增可写课程副本或网络 sidecar。
- 新目录与对象版本、本地帧版本拒绝保护；显式隔离迁移预览/取消/确认/失败恢复，源与目标不自动切换。
- PR68 Phone 手动与自动颜色只读迁移预览、哈希过期保护及正式 Store 确认接口；取消/失败保留旧偏好。
- 第二类型 test.note 通过注册进入真实引擎，旧注册表修改无关课程后精确保留字节；无需在 RAW 或网络核心增加该类型分支。

## 实际执行的验证（2026-10-28）

| 验证 | 结果 |
|---|---|
| Node 针对性集合：shared-records、shared-state、v3 固定向量、Workspace 事务、恢复、迁移、初始化恢复 | 81 项，76 PASS、0 FAIL、5 因 Windows 平台跳过 |
| JVM SharedRecordsTest、SyncV3Test、三个扩展引擎测试、初始化恢复 | 18 项 PASS |
| Phone CourseColorIdentityTest、SharedColorMigrationTest | 4 项 PASS |
| Phone 与 app assembleDebug；schedule-core assembleDebugAndroidTest | PASS |
| Phone 实机独立测试包：Room + HTTPS + 失败回滚 + 重启恢复 | PASS（1 项） |
| X08E 实机独立测试包：同上 | PASS（1 项） |
| 完整 DPV3/4 固定对象、扩展块字节、颜色与示例类型、业务哈希 | Kotlin/Node 相同；见 test-vectors/sync-v3-shared 与双端单测 |
| SQLite 快照、备份验证、重开恢复颜色与未知记录 | Node 专项 PASS |
| 迁移取消与发布响应丢失、Pending 回执期间新编辑、Checkpoint 透传 | 双端/Node 专项 PASS（以对应测试为准） |

本轮没有运行 1024 增长或四小时 soak。补充验收已在 WSL Ubuntu 的 ext4 临时目录，以及 fnOS 测试机的独立 /tmp 目录完成：两端各 **139 PASS、0 FAIL、0 SKIP**，使用 Node 22.18.0。fnOS 以 `dormpanel-schedule-manager` 应用 UID 运行，包含 Linux fd/link、安全目录、SIGKILL、完整加密备份恢复与维护测试。没有替换已安装 FPK 或日用服务。

Catalog 失败已修复：原测试未提供新端点密码，却期望越过凭据保护直接触发 v3 端点变更限制。现在分别断言“缺新凭据时拒绝”和“提供新凭据后仍拒绝不支持的 v3 连接变更”，两道保护均覆盖；没有修改或放宽生产鉴权。该测试在 WSL 与原生 fnOS 均通过。

可复现 Linux 入口：`tools/verify_pr69_linux.py`（SSH 参数由 stdin 输入；使用安装环境的隔离应用依赖与 Node 22）。原始本地日志为 `build/pr69-nas-linux.log`、`build/pr69-wsl-linux.log`。

## 真实 WebDAV 双向证据

仅访问用户授权 NAS 的新建 `dormpanel/pr69-isolated-<随机 UUID>/`，扩展对象位于其 `dormpanel-sync-v3-shared/`。未访问或改写任何既有同步目录。

1. Kotlin JVM Writer 发布课程 x、手动 RGB 123456 和未知可选 test.note/one（payload `opaque`）。
2. Node 正式 Workspace 接收，修改 RGB 为 abcdef，Kotlin 重启后接收；完整业务哈希一致：`6e86c39551ba11cf85948a6b54880c38dc55669417c5b85b57a4bc51a5fe2167`。
3. Android Room Writer 经同一 HTTPS 目录接收；保存前注入失败，确认颜色和帧未部分提交。正常保存并同步、重启后颜色与未知记录仍在。
4. 最新 Phone 构建发布 RGB 112233，Node 接收并核验，哈希均为 `af69c9d496b2238583c64fbcb08ab57ae7f6f256002e95da7af9425c9e1bc05a`。
5. 最新 X08E 构建接收后发布 RGB 445566，Node 接收并核验，哈希均为 `e644c2ba3268afaee3058d8b20af594b9f101a09d530ad5ed12ebe2fad6b0140`。

Android 直连首次失败；随后使用 ADB reverse 和本机 TCP 转发承载**原 HTTPS 流量**，保留连接级证书精确校验。该结果证明跨端协议与实际 Room/Workspace Writer 互操作，不证明设备直连网络环境已经修复。测试仅安装 `com.dormpanel.schedule.core.test`，没有安装/卸载正式 App 或修改其数据。

可复现入口为 `SharedStateWebDavTest`、`SharedStateRoomAndroidTest` 与 `fnos-app/scripts/verify-shared-state.mjs`。凭据由忽略的本地配置传入，不进入源码、向量或公共验证记录。

## 后续 UI 与迁移边界

完整 Phone/FPK 管理 UI 留给 PR70/71。现有日用绑定不自动升级，独立目标回读确认不等于已切换绑定、旧 Writer 停写或所有设备收到。旧源后续编辑仍留在源数据集，需要重新审查；本 PR 不宣称跨格式持续桥接。

扩展数据集的完整端点生命周期/目录迁移 UI 未做专项验收；不要据本记录在日用环境自动换端点或清理旧历史。Todo、Memo、偏好和附件同步没有启用。PR 不合并、不打 tag、不发布。

补充实际兼容检查：从原 main 导出的 PR68 Node Writer 尝试打开本次扩展 Workspace，明确拒绝；比较正式 workspace 控制行校验和，失败前后相同。未将“新版模拟旧版”的断言当作这项实测。

## 原生 fnOS 与 Kotlin 的 HTTPS 互操作补充

新增独立 WebDAV 数据集：Kotlin Writer 发布课程颜色 123456 与 opaque 未知记录；在 fnOS 原生 Node 22、应用 UID、独立 Workspace 上接收并改为 abcdef；Kotlin 重启接收，随后 fnOS 重开再确认。双方完整业务哈希均为 `6e86c39551ba11cf85948a6b54880c38dc55669417c5b85b57a4bc51a5fe2167`。该项使用测试机本地直连 HTTPS，不经 ADB 转发。此前 Android Phone/X08E Room 实测与本项合起来覆盖 Android、Kotlin、原生 fnOS 实现。

侧边浏览器已实际打开 fnOS 桌面与已有隔离应用，确认网页可达；未操作已有工作区的编辑或同步按钮，不将旧网页的可达性冒充 PR69 新 UI 验收。正式颜色管理 UI 仍按任务边界留给 PR70/71。

## f1689fe 后四项审查修复

已修复统一 Registry 接纳校验、Phone 迁移逐门显式决策、未来颜色版本展示降级及升级源 Writer 证据保护。详见共享状态规范末节；没有修改既有有效线格式。

本次实际验证：Node 定向 27 项通过（shared-records / shared-review / shared-state / v3-workspace-transaction）；随后新增重启场景的 shared-review/shared-state 13 项再次通过。Kotlin schedule-core 11 项与 Phone 迁移 4 项通过；Phone/app assembleDebug 通过。包含合法 SHA 非法颜色接纳拒绝且 local/baseline/Pending 不变、未来可选版本只读、逐门迁移决策、模式差异、源未发布编辑/过期证据拒绝和目标重开恢复。

本轮未重跑两套 139 项 Linux、实机、真实 WebDAV、长增长或 soak；已有平台单事务证据沿用，不将本轮 Fake/JVM 结果称为新增实机验收。迁移确认继续使用原有 putSharedRecords 的 expectedHash 检查和 Room 单事务，旧偏好无清理操作。本轮新增的是决策与接纳语义；不新增迁移 UI。

本轮修复可提交人工 Review，PR 仍保持 Draft，由维护者决定转 Ready。剩余边界是源证据仅覆盖该 Writer 已验证的观察范围，以及旧的无 sourceEvidence 草稿需重新审查；不能据此报告旧 Writer 停写或日用迁移完成。

## Registry 全链路一致性补充验收

本轮修复引擎到历史的 Registry 传递、历史 value/接纳/多分支 merged、冲突方案 mergePlan/ScheduleV2Plan、codec 显式上下文、重启复验和回执解码；外部换代历史也绑定引擎上下文。线格式、产品类型装配和课程颜色含义不变。

Kotlin/Node 均新增 test.note/1（非空 payload，并声明关联 class:x）的隔离用例：

- 合法外壳与 SHA 的空 payload 作为实际远端分支：已注册客户端拒绝，本地、基线、Pending 不变；未注册客户端可保留独立可选原字节；注册客户端重开仍拒绝异常分支。
- 基线同源的课程删除与关联 note 修改：直接合并及 V3History.merged 均报告关联冲突；冲突方案保留完整 keys，不扩大至无关 class:y。
- 无关课程修改自动合并；真实引擎保存冲突、重开并重新注入 Registry 后选择本地方案，双端最终业务哈希均为 `b4496b6ccdaed04e6bd25b6494ec72bd9a22f21018e844dafecfb21f92eac7de`。

实际执行：Node 67 项中 63 PASS、4 Linux 平台专项跳过；Kotlin schedule-core 40 PASS、Phone 迁移 4 PASS；Phone/app assembleDebug PASS。包括固定向量、未来颜色版本、未知透传、Checkpoint/LATE、Pending/Workspace 事务、备份恢复及 v2 合并回归。本轮未重复已有 Linux 139 项、真实 WebDAV/设备、1024 增长或 soak。当前修复可进入人工 Review，仍保持 Draft；应用重启必须由宿主重新装配 Registry，远端不携带可执行规则。

## 课程颜色修改 Registry 最终定向修复

已确认 c4c6ed1 的 SharedCourseColors.change 仍创建默认 Registry。现在辅助函数接受可选 Registry，V3Engine 传当前注入实例；Room 颜色入口复用引擎并保留事务内 expectedHash 检查。其余旧调用继续使用默认产品装配。

新增 colorEditUsesInjectedRegistryAndPreservesRequiredAndOpaqueRecords：真实隔离 V3Journal/Engine 初始状态包含课程、事件、已有颜色、自定义必需 test.note 和未知可选原始字节；手动修改、恢复自动均成功，其他业务数据完全相等，非颜色扩展的规范编码字节相同。默认 Registry 仍拒绝；未来颜色版本、未知依赖、过期哈希及 before-commit 故障均拒绝且不改原状态。

实际运行：schedule-core 定向 11 PASS（SharedRecordsTest 6、V3Engine 5），Phone 迁移 4 PASS；app/phone-app Kotlin Debug 编译通过。未重跑 Linux 139 项、实机、真实 WebDAV、增长或 soak；本次不涉及其实现。未发现新增实质性合并阻塞，满足转 Ready for Review 条件，仍须人工 Review 与后续 CI，不自动合并或发布。
