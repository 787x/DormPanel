# PR68：Phone 本机课程配色（Draft 验收记录）

## 已实现

- 导入课颜色身份复用 `CourseBatch.candidates()` 的来源 ID + 原始名称分组；原始名称歧义系列使用来源 ID + 系列 ID 隔离；手动课使用 entry ID。长度编码避免拼接碰撞，持久层只存 SHA-256 键摘要与颜色整数。
- 16 色自动分配保留已有有效记录，新身份使用当前分配中占用最少的色位，容量耗尽后复用；不会按修改后的标题重排。手动色独立覆盖，恢复自动时保留原自动色位。
- 每个已读取 ScheduleState 建立只读系列到逻辑键映射；卡片绘制不扫描导入记录。Phone 周/日、管理列表、紧凑/宽版 Widget 复用解析器。
- 管理卡片支持单/两列与次要元数据。整课编辑提供预设色、HSV 触控区域、色相滑条、十六进制输入、即时预览和恢复自动。
- 颜色仅在 Phone 私有 SharedPreferences；不修改 Room、RAW、协议或原始记录。取消不保存；只改颜色不调用批量课程保存。
- 混合修改先保存业务，再保存颜色；失败或结果待确认时编辑器保留颜色草稿；业务已成功而颜色失败时可单独重试颜色。
- 颜色保存后主动请求已有 Widget 更新协调器。竖条使用 Android View 支持的 `setBackgroundColor`，不生成 Bitmap。
- 本机配置最多 10000 个身份；不活跃身份保留至多 30 天并按最近观察时间受上限约束，避免较旧 Widget 读取立即清除新课程偏好。读取失败不借颜色缓存恢复课程数据。异常大集合会明确拒绝配置，而非无限增长。
- X08E 实现和经典默认风格未修改；手动色不跨端同步。跨端显示偏好需另行设计协议兼容。

## 实际验证

基线：main `3867cbb`（PR67），包含 PR66。未修改版本号，未安装日用包，未发布 Release。

- `:phone-app:testX08eTestUnitTest`：31 个测试通过（含新增身份与分配测试，以及现有布局、课程展示、Widget 协调器回归）。
- `:phone-app:assembleX08eTest`、`:phone-app:assembleX08eTestAndroidTest`：构建通过，使用 `.pr68testbed` 隔离包。
- MIX 2S（序列号 210b601e）`CourseColorsTest.isolatedPersistenceAndPickerCancellation`：浅色和深色各通过一次。验证配置重读、改名保持、恢复自动、选择取消、确认保存、只改颜色不产生 ClassOverride。
- MIX 2S `remoteViewsUseSavedLogicalAccentAndRenderReadErrors`：通过。实际 apply RemoteViews 后核对手动颜色竖条；失败视图无课程行。此检查不等同于实际 Launcher 两种尺寸更新验收。
- `git diff --check`：通过。

本地证据：`artifacts/pr68-build.log`、`artifacts/pr68-light.log`、`artifacts/pr68-dark.log`、`artifacts/pr68-remote.log`。截图在 `artifacts/pr68/screenshots/`，包含当前实现同一本地九课程 fixture 的周视图、管理列表、整课编辑、选色器深浅色。

## 尚未完成的验收（保持 Draft）

- 同一导入课程三系列的实际周视图对照截图；同数据修改前截图。
- MIUI Launcher 上实际放置紧凑/宽版 Widget、修改颜色后主动更新与效果截图。
- 自定义色输入、进程结束后重启、实际导入替换、较大字体/窄屏、只读状态与混合业务保存失败的完整 UI 流程。
- X08E 定向构建/实机回归；本 PR 未改其颜色行为。

没有将这些未执行场景标成 PASS，也未重复 Sync v3 RC、完整批量网络矩阵或长时 soak。
