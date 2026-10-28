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

本轮没有运行 1024 增长或四小时 soak。Node 运行在本机 Windows 的 Node 24，复用 fnOS 正式 Workspace/Writer 实现；没有向 NAS 安装新 FPK 或替换日用服务。Linux fd/link、平台专有授权与完整加密 FPK 容器恢复未在本轮重跑，不能将本机 Node 结果报告为 fnOS 部署验收。

较广检查中 Catalog 的“非空加入不覆盖；备份、旧历史和连接切换有明确边界”存在已有断言失败：测试期待“v3 连接变更”，实际先触发“服务器、用户名或证书信任已变化，请重新输入密码”。已从 origin/main 导出隔离副本，实际复现同一失败；PR69 未修改该鉴权顺序或放宽测试。

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
