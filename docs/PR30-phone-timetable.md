# PR30 — 独立手机课程表

## 模块与兼容性

- `:schedule-core` 是 Android Library：原数据模型、投影、ICS/湖北大学 CSV 解析、导入限制与事务、Room 存储及 v1→v2→v3 migration、学期配置存储和时间解析。Kotlin 包名仍为 `com.dormpanel.app.schedule`。
- 两端编辑器共用 `ClassEditSession` 计算继承基线、校验和稀疏补丁；学期表单共用 `TermProfileForm`。UI 各自实现，手机不依赖横屏页面。
- `:app` 仍是 `com.dormpanel.app`，版本号、签名配置、横屏配置、数据库名称 `schedule.db`、Room schema、SharedPreferences 键名及 Keystore 均未改变。HA/WebDAV/LAN 和 X08E UI 留在原应用模块。
- `:phone-app` 安装身份为 `com.dormpanel.timetable`，minSdk 28，原生 Kotlin/Views，系统 DayNight 主题。无网络权限、存储权限、服务、闹钟或后台同步。数据库和学期配置由 Android 应用沙箱隔离，可与 X08E App 共存。
- Room/KSP 生成课程表实现的位置转入 library；原应用仍为自身 Dashboard/Productivity 数据库运行 KSP。只将已有消费者需要的复制字节/摘要、CSV 解码与导入校验 API 公开；预览构造器和来源绑定方法仍为模块内可见。

## 手机操作

首页显示学期教学周、上/下周、今天和周一至周日日期条，下方只列所选日课程。课程行显示名称、设备本地时间、节次、地点和教师；详情保留来源时区、源说明、备注与编辑状态。长内容可滚动，无持续动画或轮询。

- `＋ 课程`：新增本地每周课程。点击课程进入详情后可修改、删除；导入课程可选择 This week / All weeks，并分别重置。编辑器沿用既有语义：未改变的字段继承源值，空地点/教师/备注表示显式清空；支持关联节次及自定义时间。
- `日期调整`：正常、无课、使用同一教学周另一星期课表；重置恢复正常。指定周编辑仍以原始 anchorDate 标识，可在该周内移课。
- `管理 → 导入`：系统 `ACTION_OPEN_DOCUMENT` 选择文件，限额读取和解析在线程池执行。预览展示格式、系列/实例数、日期范围、时区、学期阶段、警告和前八个实例，必须明确确认后才写课程数据库。
- `管理 → 导入来源`：显式选择已有来源进行替换，保留其 sourceId 及可匹配修改；删除确认后清理来源及关联修改。不自动按文件名/课程名合并。
- `管理 → 学期、教学周与节次`：选择用于周次显示的学期，查看、添加、编辑和删除配置（内置项删除自定义覆盖后恢复预设）。配置包含时区、第一周周一、任意多个生效日期阶段及节次时间。未知学期 CSV 会先要求配置，成功解析后仍须确认导入。

文件错误、超限或配置不完整不提交课程数据。主动保存的学期配置独立持久化；取消后续导入不会撤销该配置。CSV 底层实例沿用原语义，修改学期配置后需替换导入以重建底层时间，关联节次编辑则立即重投影。未选择学期的 ICS 显示所选周的周一日期；选择/配置学期后显示教学周，避免猜测第一周。

页面选中日期在配置重建时恢复；未保存编辑草稿和未确认预览在 Activity 销毁后取消。所有已提交课程、来源、编辑、日期调整及学期配置持久化。存储异常保留可见警告，不声称保存/云同步成功。

## 验证（2026-10-20）

构建和 JVM 测试：

```powershell
./gradlew.bat :app:assembleDebug :phone-app:assembleDebug
./gradlew.bat :app:testDebugUnitTest :schedule-core:testDebugUnitTest :phone-app:testDebugUnitTest
./gradlew.bat :app:lintDebug :schedule-core:lintDebug :phone-app:lintDebug
```

- `:app` 198 项、`:schedule-core` 87 项 JVM 测试通过，零失败/跳过。手机逻辑使用共享核心，手机模块无独立 JVM 测试。
- 原有七个课程表 JVM 测试文件和三个持久化 instrumentation 测试文件迁移到核心，断言未删减。另加三项共享编辑基线/继承与阶段表单回归测试。
- 湖北大学原夹具断言仍为 13 个课程系列、131 个实例，包含分阶段作息与稳定身份、覆盖优先级、替换保留编辑等回归。
- 三模块 Lint 无错误；仍有既有/同类国际化文本、KTX 和依赖版本提示，未关闭检查或建立忽略基线。
- API 28 X08E 模拟器（1280×800、160 dpi）：核心 12 项 Android 测试通过，验证 v1/v2 升级到 v3 后旧数据、事务回滚、磁盘重开、来源清理和学期配置；X08E 课程表 UI 9 项通过，覆盖页面/卡片、主题、编辑、导航、导入、删除、跨日 ICS、时区变化和重开恢复。
- 手机流程 6 项分别在 API 28 竖屏（同一 AVD 临时设为 1080×1920、420 dpi，完成后恢复）与 Pixel 10 Pro API 37 竖屏（1280×2856、480 dpi）通过。包括空表、本地新增/编辑/重置/重开、ICS、损坏文件、CSV 预览确认、缺少学期配置补填、指定周/全部周次编辑与重置、无课调整、替换保留修改及删除清理。
- Pixel 另执行 `PhoneRestartTest` 的 seed → `am force-stop` → verify，使用同一 UUID 隔离数据库与配置命名空间，跨进程核对本地课、131 条导入实例、sourceId、编辑、日期调整和自定义学期。未清除应用数据。
- Pixel 深色模式另跑 CSV 导入及重开测试并检查截图；全黑背景、课程和按钮可读。系统文件选择器返回值由测试 APK 文档 Provider 与 Espresso Intents 提供，验证真实 URI 读取/解析/预览/提交路径；未人工遍历各厂商文件管理器。
- 两应用已在 API 28 模拟器并存安装。无 X08E/手机实机验证，无长时间常驻、OEM 生命周期或厂商文件 Provider 验证；模拟器结果不能代替这些验证。
- `git diff --check` 通过。

Instrumentation 可复现入口（只连接一个设备，或用 `ANDROID_SERIAL` 选择设备）：

```powershell
./gradlew.bat :schedule-core:connectedDebugAndroidTest
./gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.dormpanel.app.ScheduleAndroidTest,com.dormpanel.app.ScheduleImportAndroidTest,com.dormpanel.app.ScheduleRestartAndroidTest'
./gradlew.bat :phone-app:connectedDebugAndroidTest
```

`PhoneRestartTest` 支持 runner 参数 `phonePhase=seed|verify` 和同一个 `phoneRun=<UUID>`；`ScheduleRestartAndroidTest` 保留原参数。所有测试使用隔离数据库，不操作正式用户数据。核心测试显式依赖 AndroidJUnitRunner；应核对实际执行数量，不能把零测试或 runner 启动失败当成通过。

## PR31 同步入口

同步层需要覆盖完整本地课程/日历事件、导入来源元数据与原始投影实例、日期调整、全部/指定周次编辑，以及数据库之外的所有自定义学期配置。仅传 `schedule.db` 会遗漏学期时间和阶段定义；仅传原始 ICS/CSV 会遗漏本地编辑和本地课程。UI 选中日期、主题和网络连接状态不属于课程原始模型。

保留现有 local entry id、sourceId、seriesId、原始实例身份和 anchorDate；编辑查询使用 sourceId + seriesId 等稳定键，不按显示课程名关联。独立设备重复导入同一文件会产生不同 sourceId，不能将文件名、hash、课程名当作可直接替换的全局身份。未来同步层须明确身份映射、原子导入/导出、删除传播与冲突策略，再讨论传输；PR30 不固定同步格式，不实现 WebDAV 上传、双向同步、冲突合并或后台任务。X08E 现有 WebDAV/HA 远端导入保持原有行为。
