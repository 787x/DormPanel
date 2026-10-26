# PR60 合并前审查修复

第二轮收尾见 [pr60-review-round2.md](pr60-review-round2.md)：本机读取错误的实际写保护、成功重载与过期确认安全拒绝。本页保留前轮记录。

修复基线为 PR60 的 `c8705508c42d21900afc005d4d47a1c46b55cb15`，继续更新原 PR60。此前完整 UX 验证见 [pr60-shared-schedule-ux.md](pr60-shared-schedule-ux.md)，本次只运行受影响的定向检查。

## 修复内容

1. **本地读取状态优先于旧成功记录。** `ScheduleSource.loadState` 区分尚未加载、正常加载和错误；此前已成功加载而 `reload()` 失败时，仍保留旧数据，但展示立即进入错误状态。展示层不再输出本机已保存、服务器检查成功或本机课表仍可用的健康结论，业务动作停止；既有恢复入口仍可达。成功重新加载后恢复正常映射。
2. **检查结果对应实际访问位置。** 新增轻量的展示证据位置摘要，包含规范化服务器地址、共享父目录、用户名和证书信任身份，不包含密码。成功时间和机器结果码仅在当前位置摘要匹配时用于日常展示；v3 使用其活动端点的独立账号和目录。前台及后台记录实际读取目标，跨位置的迟到结果不能证明新位置已验证。独立保存连接后立即失效展示证据；原 `last_result`、`last_success` 等诊断字段保留。升级前未标记位置的旧成功记录只供历史诊断，须重新检查才成为当前证据。
3. **确认锁定动作，执行前重新校验。** 创建、加入和迁移确认捕获动作、协议模式、数据集身份及访问位置；执行时在既有同步运行锁内读取实际状态。状态或位置已变化则拒绝原确认，不转成其他动作。尤其旧版创建不再在活动 v3 下调用普通同步。通过展示校验后，原绑定回读、迁移捕获、冲突、证书及端点事务继续决定是否允许提交。
4. **加入反馈分级。** 先只读列举共享父目录，按本轮观察到的目录发现 v3 候选；没有新版候选时才检查已有 v2 数据。普通加入区分新版候选、已验证旧版、尚未发现可加入课表、内容验证失败、网络失败和其他连接检查错误。新版验证失败时安全停止，不静默改选旧版。没有候选只说明本轮未发现，不证明目录全局为空，也不创建数据集。旧版预览明确标为“已识别旧版共享课表”。

未改变协议字节、Room schema、Writer、已有绑定、业务合并、目录格式、证书信任或端点切换规则；未修改 fnOS 页面、正式版本号或发布状态。

## 定向验证（2026-10-26）

| 检查 | 实际结果与范围 |
| --- | --- |
| JVM | 22 项通过：`SharedSchedulePresentationTest` 8、`ScheduleTest` 14。覆盖初次加载/加载失败/恢复、旧健康证据抑制、未加载不显示健康结论、动作/协议/数据集/位置确认矩阵及账号/目录/证书位置身份 |
| X08E 独立 core 测试包 | `SharedScheduleReviewAndroidTest` 6 项通过。随机 Room 和偏好、MockWebServer；实际注入已加载后的读取失败并恢复，实际读取 A/B 两位置后检查证据失效与恢复，过期 v2/v3 创建、v2/v3 加入及已准备 v3 迁移确认均不额外发请求或改 checkpoint |
| 加入只读反馈 | 上述 core 用例覆盖合法 v3、合法 v2、两种协议并存时 v3 优先、空位置、损坏对象及网络断连；核对候选/失败回调和机器状态，全部远端请求仅 GET/PROPFIND，课程、绑定、Writer 不因发现操作改变 |
| Phone 隔离 `.testbed` | `PhoneSharedScheduleReviewUiTest` 1 项通过：正式 UI 中两个创建确认框保持打开，切换实际活动协议后再确认，均拒绝并保持 checkpoint 和请求数量不变。复用 `PhoneSharedScheduleInspectionTest` 3 项通过：普通旧版加入、绑定后只读检查、并发绑定、关闭后的迟到预览及忙碌按钮禁用 |
| 构建 | `schedule-core` 测试 APK、X08E/Phone Debug、Phone `.testbed` 与测试 APK、X08E 测试 APK构建通过 |

主要命令：

```powershell
.\gradlew.bat :schedule-core:testDebugUnitTest --tests '*SharedSchedulePresentationTest' --tests '*ScheduleTest'
.\gradlew.bat :schedule-core:assembleDebugAndroidTest :app:assembleDebug :phone-app:assembleDebug
.\gradlew.bat :phone-app:assembleX08eTest :phone-app:assembleX08eTestAndroidTest :app:assembleX08eTestAndroidTest '-Pdormpanel.testBuildType=x08eTest'
# core 独立测试包：SharedScheduleReviewAndroidTest
# Phone .testbed：PhoneSharedScheduleReviewUiTest,PhoneSharedScheduleInspectionTest
```

测试将损坏数据注入随机独立数据库，未打开日用数据库、账号或 WebDAV 数据集。测试 v2 夹具补全真实 Depth-1 父目录的 collection 信息，复用原安全与只读断言。

## 剩余验证边界

- 没有重跑 PR60 全部 X08E/Phone 实机矩阵、fnOS 浏览器矩阵、三端 RC 或长期 soak；沿用基线证据，未把本轮定向测试写成重新完成全部验收。
- 后台 Job 的位置证据接线已构建核对，未做 JobScheduler 端到端执行；测试命名空间仍禁止调度日用任务。
- 真实设备上的新确认框测试只覆盖创建；加入和迁移的过期确认通过真实 Room/控制器和只读 MockWebDAV 验证，未操作真实旧课表或安装日用 APK/FPK。
- 不自动合并或发布，等待原 PR60 合并审查。
