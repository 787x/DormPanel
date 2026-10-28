# PR68：Phone 课程配色验收记录

## 实现与边界

基线为 main `3867cbb`（已合并 PR66、PR67）。

- 导入课按 `CourseBatch.candidates()` 的**来源 ID + 原始逻辑名称**分组；原始名称不一致的系列按来源 ID + 系列 ID 隔离。本地课程按 entry ID 独立分色。覆盖改名不改颜色身份。键使用长度编码，私有配置只保存 SHA-256 摘要、色值、自动色位和最近观察日，不保存业务正文或凭据。
- 16 色自动分配保留当前身份已有色位，新身份选占用最少的色位；超过容量复用。手动色独立覆盖，恢复自动保留原自动色位；允许用户主动选择相同颜色。
- 每个已读取 ScheduleState 构建只读身份映射，绘制卡片不扫描导入实例。Phone 日/周、管理列表及两种 Widget 共用解析器。管理列表按**实际可用宽度和字体比例**选择单/双列。
- 预设色、HSV 触控、色相滑条、十六进制输入和当前课程预览；选色对整门课生效，不受公共字段参与时段勾选影响。选色弹窗纳入编辑器生命周期管理。
- 显示偏好使用 Phone 私有 SharedPreferences，不改 Room schema、ClassPatch、v2/v3 RAW、fnOS 或 HA 接口。取消不保存；只改颜色不执行业务批量提交。保存后主动请求原 Widget 更新协调器，RemoteViews 使用 Android 9 支持的 `setBackgroundColor`，没有动态 Bitmap、网络同步或新增轮询。
- 混合修改先提交业务再保存颜色。失败、结果待确认或回读失败时保留颜色草稿，不报告全部成功。业务已提交而回读失败时，提供**保留草稿重试读取**，恢复后仅重试颜色，不重复提交业务。
- 配置记录通常最多保留 10000 个身份；合法超大数据集按活跃数量扩展至 RAW 允许的最多 60000 个身份（10000 本地条目 + 50000 导入实例），不活跃记录保留至多 30 天并受剩余预算限制。较旧 Widget 读取不会立即删除新课程的设置。失效身份不能匹配新 UUID 来源；课程读取失败时不从颜色缓存制造课程数据。
- X08E 自动颜色实现和经典默认风格未改。手动色不跨端同步，跨端显示偏好协议留作独立任务。Widget 保留 PR66 的深色表面和浅色文字，在系统深浅色下使用同一逻辑强调色。

## 实际执行结果

设备：MIX 2S（210b601e）；仅安装 `.pr68testbed` 与对照用 `.pr68before`，未升级日用包。前后截图使用同一 ICS fixture（11 个系列、9 门逻辑课程，数学三个系列），分别导入两个独立包；来源 UUID 由各自导入生成。

| 检查 | 结果与证据 |
| --- | --- |
| Phone JVM | 31 个测试通过；包含三系列同组、跨来源同名、原始名称歧义、覆盖改名、手动条目独立身份、长度编码、16 色内避撞、17 门复用及新增保留已有色位。`artifacts/pr68/final-build.log` |
| Phone 隔离构建 | `assembleX08eTest`、`assembleX08eTestAndroidTest` 通过。未改版本号。 |
| 业务失败、只读、导入替换、回读恢复 | `CourseColorAcceptanceTest` 四项一轮通过：业务失败保留草稿后成功重试；只读仍可选本机颜色而业务写入次数为 0；读取失败管理列表显示错误；真实 CSV 导入替换保持颜色、覆盖改名和同组删时段保持身份、删除来源再导入新来源不继承旧手动色；业务已提交而回读失败，恢复后业务写入次数仍为 1。`artifacts/pr68/acceptance-final4.log` |
| 结果待确认 | 注入 `ScheduleCommitUnconfirmed`，显示待确认提示且保留手动色草稿，未保存颜色也未出现业务覆盖。`artifacts/pr68/unconfirmed.log` |
| 深浅色与跨视图 | `CourseColorVisualTest` 两种主题通过：核对三个数学 CourseCard 为同一色，并核对卡片背景与统一解析器一致；切换周次、日视图、彩色管理列表、预设/自定义十六进制、取消整课编辑、保存、恢复自动；280dp 窗口和 1.6 倍字体单列截图。`artifacts/pr68/visual-light-final.log`、`artifacts/pr68/visual-dark2.log` |
| 实际 Launcher | 紧凑与宽版由 `com.miui.home` host 1024 承载。仅调用颜色保存，分别核对两个实际竖条区域变为手动色和自动色，并确认 Room 课程状态未变化。系统深浅色各运行通过；截图像素带有噪声，区域均值允许 15 通道误差，精确色值另由 RemoteViews apply 测试核对。`artifacts/pr68/launcher-final.log`、`artifacts/pr68/launcher-light.log`、`artifacts/pr68/launcher-dark.log`，host 证据 `artifacts/pr68/appwidget.txt` |
| 强停/新进程 | `am force-stop` 隔离包后重新运行 instrumentation，从实际 Room 和私有配置读取；手动色及原自动色位保持，三个系列投影同色。`artifacts/pr68/restart-final.log` |
| RemoteViews / 读取失败 | 实际 apply 后核对精确 ARGB；读取失败视图课程容器为空。`artifacts/pr68/visual-light-final.log`（包含此方法） |
| 修改前对照 | main `3867cbb` 独立 worktree 构建，原始算法截图可见数学周三与周一/二不同色；对照测试通过。`artifacts/pr68/imported-before.log` |
| X08E | `:app:assembleDebug` 通过；`X08eWeekGeometryTest` 6 项和 `X08eWeekDisplayTest` 3 项通过。`artifacts/pr68/followup-build.log`、`artifacts/pr68/x08e-regression.log`。未安装 X08E APK，不将其描述为实机显示验收。 |
| 差异检查 | `git diff --check` 通过。 |

MIUI 测试中曾出现窗口焦点和底层日视图同名按钮误匹配；验收测试已等待可读/有焦点状态，并把管理卡片查询限定到 Dialog。失败运行日志保留，未将其计为 PASS；上述表格指向修正后的通过记录。

## 截图与交付

原始截图在 `artifacts/pr68/evidence/`，`index.html` 为本地截图索引，`sha256.json` 记录原图摘要。涵盖修改前/后周视图、深浅色周/日视图、课程管理、整课编辑、自定义颜色、窄宽大字体、两种 Launcher Widget 的自动/手动效果。没有使用生成图替代截图。完整证据包为 `artifacts/pr68/pr68-evidence.zip`（本机交付，不把大体积截图写入 Git 历史）。

## 验证范围

本次未重复 PR65 全部批量网络故障矩阵、PR66 全部超时恢复矩阵、Sync v3 RC 或长时 soak；相关协议和协调器未修改，Phone JVM 中原有协调器测试仍通过。X08E 本次只做构建和相关纯逻辑回归，未部署实机。没有合并、tag 或 Release。
