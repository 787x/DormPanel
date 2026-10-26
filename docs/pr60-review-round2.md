# PR60 第二轮审查收尾

随后补充的页面手动重读入口及定向验证见 [pr60-local-read-retry.md](pr60-local-read-retry.md)，本页保留此前写保护与安全拒绝记录。

基线：`e870af012fb25c83246c058f5854c9b95cfb0c84`。继续更新原 PR60；[前轮修复](pr60-review-fixes.md)及[初始 UX 验证](pr60-shared-schedule-ux.md)保留，不重复全套矩阵。

## 本机读取错误与业务写保护

- `ScheduleSource.readOnly` 纳入本机错误状态，`editable` 同时要求已加载、非重载中且没有本机错误/迁移停写保护。此前成功加载而后读取失败时保留 `ready` 和缓存，但保存事件/课程、删除、日期调整、课程覆盖及学期刷新等入口均在乐观修改内存前停止。冲突本身不新增只读限制，既有迁移保护不撤销。
- 导入确认、删除导入来源和快照写入遇到本机错误时返回 `ScheduleLocalReadFailed`，不误称为迁移停写，异步调用方可以正确结束等待并显示原因。
- 初次加载、`reload()`、`applySnapshot()` 后重新读取成功均清除 `error`；失败不清空此前课程。快照应用的回调继续反映实际应用结果，即使随后读取成功，也不把应用失败报成完成。
- X08E 的事件/课程新增、导入和接收入口不可编辑；两端编辑器、导入确认及学期设置复用写保护和原因提示。Phone 在错误状态使用缓存的学期配置，避免为展示旧课表再次访问不可读的目录并崩溃。管理与恢复入口仍可访问。

## 过期确认与真实失败分开

使用 `SharedConfirmationRejected` 表示在实际状态复核中被安全拒绝的动作。控制器仅展示“原操作未执行，状态已变化，请重新打开操作”，不调用同步完成回执、不覆盖原机器检查结果/成功时间/历史诊断，也不因此调度失败重试。

提示使用独立展示字段，不解析中文日志。真实网络和内容验证异常仍走原失败分支，不被当成过期确认隐藏。当前位置没有可验证活动端点时仍保持“待检查”，不会为了显示旧成功证据而提升未完成绑定的可信度。

不修改 Sync v2/v3 协议、Room schema、Writer、绑定、合并或端点/证书保护，不修改正式版本和 fnOS 页面。

## 本轮实际验证（2026-10-26）

| 检查 | 结果与范围 |
| --- | --- |
| JVM 定向检查 | 28 项通过：读取写保护 3、展示/确认状态 9、迁移 UI 写保护 2、本地课表 14。覆盖所有业务写入拒绝、导入/删除/快照回调、无乐观幽灵数据、读取恢复、快照重载成功/失败与真实应用结果分离 |
| X08E 独立 core 测试包 | 按受影响方法分次运行 4 项通过：真实 Room 读取失败后拒绝课程新增/编辑/删除并保留原行，恢复后实际落盘；过期的 v2/v3 创建与加入不发额外请求、不改 checkpoint、不覆盖检查结果及成功时间；复用真实验证失败/网络断连对照 |
| X08E `.testbed` UI | `X08eReadFailureUiTest` 1 项通过，1280×800 的课程页显示不可编辑原因，事件新增、课程新增、导入、接收按钮禁用；实际课程写入被拒绝，恢复后按钮与持久化写入恢复 |
| Phone `.testbed` UI | 按方法运行 `PhoneSharedScheduleReviewUiTest` 2 项通过：保留两个创建确认框并切换协议后提示未执行；读取失败后隐藏新增/日期调整入口并显示原因，旧数据不变，恢复后新增入口及实际落盘恢复 |
| 构建与静态差异检查 | 定向测试、core 测试 APK、两端 Debug/隔离测试构建通过，`git diff --check` 通过 |

UI 注入仅损坏随机数据库的合成 checkpoint，修复时恢复原 checkpoint，不删除课程。测试过程中发现的 Phone 学期目录重复读取崩溃已修复并通过同一用例复核。

主要命令：

```powershell
.\gradlew.bat :schedule-core:testDebugUnitTest --tests '*ScheduleReadFailureGuardTest' --tests '*SharedSchedulePresentationTest' --tests '*ScheduleMigrationUiGuardTest' --tests '*ScheduleTest'
# core：SharedScheduleReviewAndroidTest 的 reloadFailure...、staleV2Creation...、verifiedJoinConfirmations...、joinFindsV3... 四个方法
# X08E .testbed：X08eReadFailureUiTest
# Phone .testbed：PhoneSharedScheduleReviewUiTest 的两个方法
```

只安装独立测试包，未操作日用账号、数据库和 WebDAV。未重跑全套设备/协议矩阵、fnOS 检查或长期 soak；导入确认的所有故障组合由 Source 回归和入口保护覆盖，未逐一重跑两端全部弹窗。未合并、升级日用应用或发布。
