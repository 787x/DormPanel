# PR66 Phone 今日课程桌面小部件验收

基线：`861fc869a7eeb646e6d63cb490a1453c1001c415`（PR65 合并后的 main）。本 PR 保持 Draft；以下记录不代表全部 Done Criteria 已通过。

## 实现

- Phone 独有两个原生 AppWidgetProvider / RemoteViews 入口；MIUI 选择器实际识别为紧凑 2×2、宽版 4×2。支持横纵调整，按 Launcher 返回 dp 和系统字号选择 1–2 / 1–3 门课程。最小高度 140dp；横屏 API 28 Launcher 的实际格数可能不同。
- 默认固定深色低亮度背景；系统浅色时也保持深色。复用 Phone 的 sourceId / seriesId 配色索引，名称、真实起止时间、地点和宽版可选教师。状态标注“HH:mm 更新时上课中”，不把延迟刷新当成实时证据。
- 基于正式 Store 的异步 loadPresentation 和 ScheduleProjection.week 读取有效课程，按显示日相交区间包含跨午夜课程，过滤已经结束的课程。未使用 Activity 选中日期；仅在导入来源唯一关联到有效学期配置时展示教学周。
- 不创建独立数据库、Writer、网络任务；没有修改 schema、协议、版本号。读取恢复保护中的 v3 Store 时拒绝展示缓存帧。首次使用、全天无课、已经结束、加载、读取失败/超时分开显示。
- 本地成功提交、批量/导入共用写边界、v1/v2 接收事务、v3 同步/冲突处理及学期保存发出无数据通知。消费者重新读取正式 Store；同步即使在已接收本地状态后网络失败，也会要求重读，不把网络结果视作所有设备收到。
- 主线程协调刷新代次、合并在途请求；7 秒读取超时、8 秒广播完成上限，迟到回调按读取序号和刷新代次丢弃。课程边界/午夜使用非精确 AlarmManager，无精确闹钟权限，系统 widget 更新和 30 分钟上限作兜底；移除全部实例后取消专属 alarm。
- 标题和主体使用显式、immutable/update PendingIntent，URI 和 requestCode 按 widget ID 隔离，冷启动、已有 Activity 和重建均进入系统当前日期。
- Android 9–11 静态 PNG 预览；新版 previewLayout 使用对应尺寸的无个人信息示例图。

## 实际执行

| 检查 | 结果及边界 |
| --- | --- |
| Phone Debug 构建、JVM、Lint | `:phone-app:assembleDebug :phone-app:testDebugUnitTest :phone-app:lintDebug` 成功；17 项 JVM，0 failures / errors。Lint 无阻断错误，包含仓库既有告警。 |
| 隔离包构建 | `:phone-app:assembleX08eTest :phone-app:assembleX08eTestAndroidTest :phone-app:testX08eTestUnitTest`，参数 `-Pdormpanel.testBuildType=x08eTest -Pdormpanel.testSuffix=.pr66testbed`，成功。没有覆盖日用包或已有 .testbed。 |
| 新增 JVM 4 项 | 真实结束边界、稳定排序及重叠、指定周自定义时间覆盖/停课、跨午夜/系统时区、重命名后的稳定颜色。复用既有投影，未改变导入/合并算法。 |
| MIX 2S / API 33 / MIUI Home | 两个入口实际添加、同时展示课程、默认字号和 1.25 字号、系统深浅模式。两项 instrumentation 通过：隔离正式 Store 写入/读取、冷启动/已有 Activity/重建导航。MIUI 初始 instrumentation 后台拉起需要测试专用 shell fallback；生产 widget 点击由 Launcher 发出。 |
| API 28 标准 Launcher / 1280×800 模拟器 | 两个入口实际添加，宽版旧实例低高度显示一门，纵向扩大后显示两门；紧凑显示两门。两个 instrumentation 测试通过。模拟器使用 Phone APK，不是给 X08E 实机注册 widget。 |
| 进程结束后更新 | API 28 执行 am kill 后 pidof 无进程；Launcher 调整宽版高度后出现新 PID 4888，重新读取两门本机课程并显示新的更新时间。无 Activity 常驻。 |
| 模拟器重启 | API 28 reboot 后 sys.boot_completed=1，Launcher 恢复两个实例，并出现新的“更新时上课中”观察时间；截图 api28-after-reboot.png。没有重启日用实机。 |
| 既有 UI 定向回归 | 另建全新 .pr66regression.testbed 包。PR65 roomBatchAndCourseUi、提交未确认/恢复读取 2 项通过；PR64 日期边界/设置/重建与课程触控横划 2 项通过；v3 既有记录批量发布 1 项通过。共 5 项，不是完整历史 UI 矩阵，也不等于同步后的 Launcher 联动实测。 |
| Launcher 点击 | MIUI 实际点击紧凑标题进入 Phone；instrumentation 另外断言冷启动、既有历史选中日期、Activity 重建后的 today。未实现课程详情点击。 |
| 空/失败状态 | MIUI 两个实际实例分别显示首次使用、今天无课、课程已结束，以及通过测试 Store 注入的读取失败；截图检查失败时没有继续展示旧课程。注入仅发生在隔离测试包。 |
| 更新观察 | 4 次样本从测试命令开始到 Launcher XML 确认新文字为 3.80–4.10 秒，包含进程/测试启动和 UI dump 开销；见 update-observations.json。不是从事务提交点精确计时，不是厂商实时性保证。 |
| 安全边界 | 新增 widget fixture 操作要求包名以 .pr66testbed 结尾；既有回归测试使用独立 .pr66regression.testbed 包及原有隔离规则。没有访问日用 Room、WebDAV 凭据/目录；没有向连接的 X08E 实机安装。测试后恢复 MIX 2S 原系统字号 1.25 和夜间模式 yes。 |

测试命令示例（ADB 必须明确指定隔离目标）：

```powershell
adb -s <device> shell am instrument -w -e class com.dormpanel.phone.TodayWidgetTest com.dormpanel.timetable.pr66testbed.test/androidx.test.runner.AndroidJUnitRunner
# fixtureMode 可为 firstUse / noCourses / ended / readFailure，仅运行 seedIsolatedLauncherFixture 方法
```

Android 结果见 `miui-instrumentation.txt`、`api28-instrumentation.txt`、`pr65-regression.txt`、`pr64-v3-regression.txt`，JVM 结果见同目录 TEST-*.xml。早期测试发现测试启动 Intent 自带 CLEAR_TASK、MIUI 后台拉起和模拟器 shell fallback 竞争；已修正隔离 harness，并以保留结果文件中的最终运行作为通过证据。

## 实际截图

以下为 Launcher 实际截图，数据均为明确标注的演示课程。全屏图为 JPEG 压缩导出，原始 PNG 留在本地忽略目录 artifacts/pr66；紧凑/宽版裁切图未重绘 UI。

- [MIUI 默认字号、系统深色、两个实例](miui-two-widgets-dark.jpg)
- [MIUI 系统浅色，widget 保持默认深色](miui-default-font-light.jpg)
- [MIUI 1.25 字号](miui-large-font-dark.jpg)
- [紧凑裁切](miui-compact.png) / [宽版裁切](miui-wide.png)
- [全天无课](miui-noCourses.jpg) / [已结束](miui-ended.jpg)
- [首次使用](miui-firstUse.jpg) / [注入读取失败](miui-readFailure.jpg)
- [API 28 两个入口](api28-launcher.png) / [API 28 扩大宽版后](api28-launcher-resized.png) / [API 28 重启后](api28-after-reboot.png)

## 尚未验收，不记为 PASS

- 真实设备重启、午夜及课程边界自然触发、真实 Doze/厂商长期后台延迟、系统时区变更广播后的最终时延。
- MIUI 手动拖动改变尺寸、删除全部 widget 后 alarm 取消的系统观察；本轮实际尺寸调整在标准 Launcher 完成。
- 实际 XLS/CSV/ICS 导入替换、批量管理、v2/v3 远端接收提交后，逐条观察 Launcher；目前接入成功事务边界，但不可将代码检查当作上述联动实测。
- v3 故障注入后的恢复保护、超时与过期回调竞争的专项 Android 测试；本轮读取失败用 fake Store 注入，不能等同真实 Room/恢复故障验收。
- PR64/65 其余 Android UI 组合；已执行的 5 项定向回归及既有 13 项 Phone JVM 均通过。
- Android 12+ previewLayout 的实际选择器兼容矩阵；其他 MIUI/HyperOS 版本、字体大于 1.25 和其他 Launcher。

没有合并、tag、Release、正式版本号变更或日用设备升级。完成上述剩余定向验收后再决定是否转 Ready for Review。
