# PR60：本机读取失败后的手动重试

基线为 `c552a779626845fefda59f3cc9420309bc1632d6`，继续更新原 PR60。

## 用户操作与行为

- X08E 课程/日历页面、Phone 周/日课表页面在本机读取失败提示旁显示“重新读取本机课表”。Phone 的入口位于首次加载失败的提前返回之前，不要求已有缓存或 `ready=true`。
- 普通加载中、正常状态和仅网络异常不显示入口。重试期间显示“正在重新读取…已有数据保留。”，按钮禁用；再次失败恢复可点击，成功则隐藏提示与按钮。
- `ScheduleSource.retryLocalRead()` 仅在未关闭、有本机错误、且没有快照/读取正在进行时接收请求，复用 `reload()`。它不清空旧课程、不写入业务数据，不使用远端同步、创建/加入/迁移、存储重开或备份恢复代替重读。
- 成功后只清除读取错误。`editable` 继续由原迁移/恢复停写等保护决定，不因本次读取成功而解除已有保护。

## 同步与生命周期边界

手动读取开始和完成的通知都带有本机重试标记，完成后不再补发普通就绪事件。共享课表首次就绪监听、Phone 恢复监听以及 X08E WebDAV 文件导入监听不把该通知当成远端同步请求。正常启动和后续正常生命周期/业务操作的自动同步逻辑保持原入口；没有新增自动重试循环、定时器或动画。

页面不传入持有 View/Activity 的重读回调，只通过现有 Source 订阅刷新。离开 X08E 页面会停止订阅；Phone 销毁时解除订阅并关闭 Source，迟到结果不能再通知旧 UI。

不修改 Sync v2/v3 协议、Room schema、Writer、已有绑定、账号、检查结果或恢复日志。未修改正式版本和 fnOS 页面。

## 实际定向验证（2026-10-26）

| 检查 | 结果 |
| --- | --- |
| JVM | 10 项通过：`ScheduleLocalRetryTest` 4、`ScheduleMigrationUiGuardTest` 3、`ScheduleReadFailureGuardTest` 3。覆盖首次失败恢复、保留缓存后重复失败再成功、连续十次重复请求只新增一次在途读取、完成通知仍为本机来源、正常/初始加载不接受重试、关闭后的迟到结果不通知旧订阅者，以及原写保护 |
| X08E 1280×800 `.testbed` | `X08eReadFailureUiTest` 2 项通过：日历和课程模式均可点击重试；先失败、按钮恢复可用、修复后再次点击成功，原课程保留并恢复编辑；首次失败已有 v2 绑定时直接点击成功，无远端请求、checkpoint 或检查历史改写 |
| Phone `.testbed` | 按方法执行读取失败相关 2 项通过：周/日视图入口可达，失败可再次重试，成功后恢复编辑；首次读取失败也可直接重试已有绑定，保留原课程、checkpoint 和检查记录，不联系服务器 |
| 独立 core 测试包 | 新增 2 项通过：正常启动仍实际 GET 检查已绑定服务器；成功本机重读保留已有迁移停写、checkpoint、检查历史且没有额外远端请求 |
| 构建 | 两端 Debug/隔离测试构建、core 测试 APK 及 `git diff --check` 通过 |

主要命令：

```powershell
.\gradlew.bat :schedule-core:testDebugUnitTest --tests '*ScheduleLocalRetryTest' --tests '*ScheduleMigrationUiGuardTest' --tests '*ScheduleReadFailureGuardTest'
# X08E .testbed：X08eReadFailureUiTest
# Phone .testbed：PhoneSharedScheduleReviewUiTest 的 localReadFailure... 与 initialFailureRetry... 两个方法
# core：SharedScheduleReviewAndroidTest 的 normalStartupStillChecksTheBoundServer 与 successfulManualRetryKeepsMigrationPauseAndAllEvidence
```

全部使用 Fake Store、随机 Room/偏好命名空间及 MockWebServer，仅安装独立测试包。快速重复请求和迟到回调的精确时序用可延迟 Fake Store 验证；真实 Room 页面读取较快，不宣称已对所有设备存储延迟完成 UI 时序压力测试。沿用 PR60 已有协议、确认、证书及恢复验证，未重跑完整矩阵、RC 或长期 soak，未操作日用数据、合并或发布。
