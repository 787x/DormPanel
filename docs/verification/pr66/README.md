# PR66 Phone 今日课程桌面小部件验收

基线：`861fc869a7eeb646e6d63cb490a1453c1001c415`（PR65 合并后的 main）。原始验收未覆盖全部兼容性条件；本轮刷新竞态定向修复的结果见文末，历史结果与当前修复结果分别记录。

## 实现

- Phone 独有两个原生 AppWidgetProvider / RemoteViews 入口；MIUI 选择器实际识别为紧凑 2×2、宽版 4×2。支持横纵调整，按 Launcher 返回 dp 和系统字号选择 1–2 / 1–3 门课程。最小高度 140dp；横屏 API 28 Launcher 的实际格数可能不同。
- 默认固定深色低亮度背景；系统浅色时也保持深色。复用 Phone 的 sourceId / seriesId 配色索引，名称、真实起止时间、地点和宽版可选教师。本轮按参考图改为名称、地点/教师及独立真实时间层级，不重复显示“上课中”实时状态标签。
- 基于正式 Store 的异步 loadPresentation 和 ScheduleProjection.week 读取有效课程，按显示日相交区间包含跨午夜课程，过滤已经结束的课程。未使用 Activity 选中日期；仅在导入来源唯一关联到有效学期配置时展示教学周。
- 不创建独立数据库、Writer、网络任务；没有修改 schema、协议、版本号。读取恢复保护中的 v3 Store 时拒绝展示缓存帧。首次使用、全天无课、已经结束、加载、读取失败/超时分开显示。
- 本地成功提交、批量/导入共用写边界、v1/v2 接收事务、v3 同步/冲突处理及学期保存发出无数据通知。消费者重新读取正式 Store；同步即使在已接收本地状态后网络失败，也会要求重读，不把网络结果视作所有设备收到。
- 主线程协调刷新代次、合并在途请求；7 秒读取超时、8 秒广播完成上限；本轮改为独立的物理读取名额、发布权和等待方生命周期，迟到回调不能操作新代次。课程边界/午夜使用非精确 AlarmManager，无精确闹钟权限，系统 widget 更新和 30 分钟上限作兜底；移除全部实例后取消专属 alarm。
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
- [MIUI 1.25 字号（本轮重新取得，含两个小部件）](miui-large-font-dark.jpg)
- [紧凑裁切](miui-compact.png) / [宽版裁切](miui-wide.png)
- [全天无课](miui-noCourses.jpg) / [已结束](miui-ended.jpg)
- [首次使用](miui-firstUse.jpg) / [注入读取失败](miui-readFailure.jpg)
- [API 28 两个入口](api28-launcher.png) / [API 28 扩大宽版后](api28-launcher-resized.png) / [API 28 重启后](api28-after-reboot.png)

## 尚未验收，不记为 PASS

- 真实设备重启、午夜及课程边界自然触发、真实 Doze/厂商长期后台延迟、系统时区变更广播后的最终时延。
- MIUI 手动拖动改变尺寸、删除全部 widget 后 alarm 取消的系统观察；本轮实际尺寸调整在标准 Launcher 完成。
- 实际 XLS/CSV/ICS 导入替换、批量管理、v2/v3 远端接收提交后，逐条观察 Launcher；目前接入成功事务边界，但不可将代码检查当作上述联动实测。
- widget 的 v3 恢复保护与真实 Room 故障联动专项测试；刷新竞态已由下述确定性状态机测试及 Android 接入测试覆盖，不能等同真实 Room 卡死/恢复验收。
- PR64/65 其余 Android UI 组合；已执行的 5 项定向回归及既有 13 项 Phone JVM 均通过。
- Android 12+ previewLayout 的实际选择器兼容矩阵；其他 MIUI/HyperOS 版本、字体大于 1.25 和其他 Launcher。

没有合并、tag、Release、正式版本号变更或日用设备升级。上述兼容性与长期后台限制继续保留，不以本轮竞态修复测试替代。


## 刷新竞态与加载闪烁定向修复（2026-10-28）

审查基线 `b9d0d2b024811b53343656c396253b7eb09be918`。

- 提取单线程纯逻辑 WidgetRefreshCoordinator。每轮读取的发布权、尚未返回的物理读取名额、刷新代次及广播等待方分别管理。超时仅撤销旧读取发布权，不能假装取消了 Room 已排队的任务。
- Room 串行执行约束下最多允许两个尚未回调的任务：原阻塞读取和一个恢复探测。超时前已有新请求时可立即发起一次有界探测；两名额都占用时不再排队。任一过期回调释放名额后，在没有较新读取运行且仍有请求待处理时，启动最新读取。
- 没有新请求的普通超时不立即重试；失败/超时安排 5 分钟非精确恢复 alarm。长期未返回的两项任务不会因 alarm 增长队列；条件恢复并返回回调后继续处理最新请求。进程重建依靠同一 alarm / 系统 widget 更新重新初始化，不引入常驻服务。
- 过期及重复回调不能发布、完成新等待方、清除新读取状态或重排新 alarm。每个等待方独立、最多 8 秒且只完成一次。删除全部实例会取消 alarm；物理名额仍保留到旧任务实际返回，避免删除/重加实例绕过数量上限。
- 不再在后台读取前发送加载 RemoteViews。第一次添加由 existing initialLayout 显示加载提示；进程重建、尺寸变更和普通后台刷新沿用各实例在 Launcher 内的原画面，成功后直接替换，真实失败/超时则明确替换为错误。没有跨实例内容缓存。
- 竞态修复没有修改 Room、ScheduleProjection、提交信号、课程模型、同步协议或导航。后续展示日选择仅在 Phone 只读投影上增加，见下文。

### 本轮实际验证

| 检查 | 结果 |
| --- | --- |
| Phone Debug 构建 / Lint / 全部 Phone JVM | `:phone-app:testDebugUnitTest :phone-app:assembleDebug :phone-app:lintDebug` 成功；29 项 JVM（原有 17 + 新增协调器 10 + 名称/明日展示 2），0 failures / errors。 |
| 10 项确定性协调器测试 | 可控时间、回调、实例存在性：超时前新请求、同一时刻两种队列顺序、超时后新请求、迟到/重复结果、旧结果不能回滚新日期/课程或改变新调度、连续请求合并、两个名额上限、阻塞恢复、同步抛错/异步失败恢复、移除/重新添加、等待方截止时间与完成恰好一次。无需墙钟睡眠。 |
| MIUI API 33 双实例 Android 接入 | 新测试 launcherBroadcastRefreshKeepsContentAndRecoversFromFailure，通过（1 项）。真实 AppWidget 广播与 RemoteViews，临时阻塞 Fake Store 读取时两个实例仍保持 A、没有加载提示；有新请求时不发布 B；C 成功后重复 B 不回滚；注入失败时两个实例显示错误，后续均恢复。finally 恢复正式 Store 并重新读取。结果见 refresh-fix-android.txt。 |
| 隔离构建安装 | 仅更新 .pr66testbed，参数与上文相同。没有安装日用 APK、修改字号或夜间设置；读取确认 font_scale=1.25、night=yes。 |
| 大字体证据修正 | 原 miui-large-font-dark.jpg 未含 widget，不能支持之前的视觉结论。本轮在同一 MIUI Launcher 页面重拍并覆盖该文件，检查两实例真实节点及截图：名称/地点允许省略，时间没有覆盖名称，未观察到越界；最新参考图排版不再显示实时状态标签，因此不将本图作为状态标签显示布局的证据。旧截图不再作为 widget 视觉证据。 |

Android 首次测试因重复访问无障碍树/等待 idle 耗时跨过真实 7 秒超时而失败；压缩重复查询、明确主线程请求顺序后，通过保留结果中的最终运行。生产超时时间未为测试延长。纯逻辑测试负责确定性超时竞争，Android 测试负责真实广播/双实例画面接入。

本轮未重复 API 28、全部 PR64/65、长期 Doze 或完整 Launcher 矩阵；它们的历史结果和上方未验证条件仍有效。删除全部实例及物理读取永久不返回场景通过状态机 fake 覆盖，未在真机破坏 Room/执行器或移除用户桌面实例。课程开始/结束、跨日和同步后的桌面联动没有新增实测保证。


## 参考图排版、名称与明日课程（同轮用户补充）

- 头部左侧是课表名称，右侧为日期、教学周（仅存在可靠配置时）与星期。紧凑版首门课程突出显示名称、地点和完整时间，第二门采用较小名称及时间/地点；宽版地点/教师在名称下方，右侧独立纵列显示起止时间。列宽分配和单行省略防止时间覆盖课程内容；圆角背景及圆角课程色条参考用户图片。
- CourseColors 统一课程身份色表：根据 sourceId / seriesId 选择稳定颜色，小部件使用强调色，Phone 卡片使用同一色表的既有主题底色。没有按顺序随机上色，也没有改变 Phone 卡片原有深浅底色值。不同课程可能落在同一有限调色板色项，不伪造用户自定义课程颜色字段。
- 每个实例可通过 Launcher 配置/重新配置入口设置课表名称。没有自定义时，单一导入来源使用正式 ImportSource.displayName；多个导入来源默认“合并课表”，纯本地默认“我的课表”。名称最多 80 字符，在小部件上合理省略。没有新增课程/同步数据库字段。
- 每个实例独立开关“今日课程结束后显示明日课程”，默认开启。只有今天确有课程、且其有效结束时间全部已过时才切到明日；正在上课和当天剩余课程仍优先展示。今天本来无课保留今天无课提示；明天无课显示“明天没有课程”，不继续搜索未来日期。开关关闭时保留“今天的课程已结束”。
- 明日画面标注“周X·明日”，安排当前时区下一次午夜刷新；过午夜按新的真实今天重新判断，不被人工选中日期影响。不改变点击进入 Phone 今天课表的原有约定。多个实例共用一次正式 Store 读取，按各自开关选择投影并调度最早边界。
- 新增名称与明日课程 JVM oracle；MIUI 广播接入测试进一步断言两个实例不同名称、一个关闭明日而另一个开启时，分别显示“今天的课程已结束”和明日课程/明日标记。真实读取仍由正式 Store 完成；竞态/展示 oracle 使用受控 Fake Store，仅限隔离包。
- 设置页 Android 定向测试在 MIUI API 33、标准 API 28 均通过，验证名称/开关保存与 Activity 重建；API 28 实际添加紧凑 widget 时弹出配置页并保存成功。MIUI instrumentation 启动确认由人工点击允许后测试通过（120.901 秒包含等待确认），不是生产功能需要新增权限。见 configuration-android.txt、configuration-api28.txt。
- 最新实际截图：[1.25 字号参考排版](miui-large-font-dark.jpg)、[两个实例的独立名称及明日开关](miui-tomorrow-switch.jpg)。前者在 1.25 字号下检查名称/地点省略、完整时间及列间分离；后者是受控演示课程与开关结果。静态 previewImage / previewLayout 的示例图也已同步为最新层级。此前其他截图仍作为原始版本历史证据，不代表本轮最新排版。

本次修复与新增展示规则的定向检查已通过；真实 Room 永久阻塞/恢复、长期 Doze、其余厂商 Launcher 和完整同步后桌面联动仍属于上述未验证条件，不将 Fake 测试扩充成真实故障或长期兼容保证。
