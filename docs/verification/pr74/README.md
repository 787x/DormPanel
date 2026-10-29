# PR74 X08E 课程首页卡片验收

基线：main `0724c440bdd109c63a0c3b833a8aa0b953dd618d`，包含 PR72 / PR73。日期：2026-10-29。

## 实现

- 独立 `schedule_today` / `schedule_recent` Provider，中文目录名称「今日课程」「近日课程」，默认 3×2 / 4×3，连续范围 2×1–8×6。旧 calendar / timetable Provider、尺寸和默认值保留。
- 复用 Dashboard 的添加、拖动、删除、碰撞拒绝、Resize 与 Room 持久化；没有新建布局引擎。
- 原生 View 按实际测量宽高调整密度；近日课程内容宽度达 470dp 时双栏独立滚动，窄版同一滚动容器分组；今日宽版横向排列课程字段。全部课程可滚动访问。
- Phone 已验证的按日区间投影和严格教学周证据核验提取到 schedule-core，Phone 同时复用。真实 occurrence.start/end、有效覆盖、跨午夜区间交集保持一致。
- 每个 ScheduleSource 缓存一组当天/明天投影，Resize、主题与多个实例复用；可见时复用 ScheduleDisplay 订阅和单个有效边界任务，旧卡片默认调度行为保留。
- 课程标记复用 X08E courseBackground，详情在点击时重新核对当前 occurrence 身份；头部进入对应日期课表。纵向滚动认领首页手势，横向移动取消子项点击并保留首页导航；编辑模式内部操作停用。

## 实际验证

主设备：ADB `28605/A0UH93770`，型号 X08E、Android 9 / API 28、1280×800。仅安装 `com.dormpanel.app.testbed` 和测试 APK，使用内存 Room 与合成课表，未升级日用包、未访问真实 WebDAV 或业务数据。

- X08E 隔离 APK 和 AndroidTest APK 构建成功。
- 定向 app JVM：48 测试，0 失败。覆盖 Dashboard 布局与状态持久化、X08E 几何与显示、按日真实时间投影、已结束过滤、覆盖/停课、跨午夜与时区、可选明日切换。
- Phone 共享逻辑回归：15 测试，0 失败（TodayCourses / CollectionProjection / CollectionBasis）。
- schedule-core 课程模型、覆盖与共享安排定向 JVM：36 测试，0 失败。
- 实机新卡片最终复跑：2/2 通过（69.491 秒），包含六种尺寸（2×1、2×3、4×2、4×4、6×3、8×6）、两种主题、连续改变尺寸、测量后头部高度、单双栏切换、纵向/横向触摸认领、双栏滚动互不影响、编辑模式停用和恢复、source.reload。
- 实机 Dashboard 集成测试包含目录注册、新旧卡片同时存在、今日卡片多实例、最大尺寸无空间时拒绝且其他卡片不变、删除邻卡后最大 Resize、缩小和移动，以及关闭 Activity 后重新从 Room 恢复相同位置尺寸。
- 旧 CoreDashboard 实机回归：首轮 12/13 通过；本地化断言修正后剩余一项单独复跑 1/1 通过（16.652 秒）。覆盖亮度/灯控、核心卡片尺寸、主题、四向导航、拖动/Resize 与持久化等；英文固定断言已兼容实际中文课表标签。

## 发现与修正

- Resize 的 onSizeChanged 中立即重建子视图可能留下未测量内容：改为下一次主线程布局重排，并增加实际高度断言。
- 横向滑动可触发子项点击：超过横向触摸阈值后向子项发 CANCEL，继续把横向流留给首页观察。
- 新测试第一次尝试从非原点位置扩大到 8×6，被既有边界规则正确拒绝；测试先移动到原点再验证最大尺寸，未修改布局引擎。
- 首次 app JVM 全量为 208/209，通过之外的失败是未修改的 `WebDavClientTest.lastModifiedFallbackAndTimeout`，断言要求异常消息含英文 `timed out`；单独复跑仍失败。定向业务测试不包含此网络异常文案断言，不将全量报告为通过。

## 截图与边界

`x08e/` 收录 24 张真实 X08E 系统截图（`-screen.png`），文件名区分 Provider、尺寸与主题，列表在截取前实际发生滚动。图中课程和日期为固定合成数据。模拟器早期用于开发检查，不作为实机验收证据。

没有执行真实账号同步、多端发布矩阵或长时间 soak；同步后刷新通过正式 ScheduleSource 订阅通道复用，没有改变协议/Writer。未做真实手指拖拽录屏和系统字体缩放矩阵；实机触摸验证使用 Android MotionEvent / Espresso。截图中的孤立卡片使用与首页相同的 Provider 和 DashboardCardView，完整首页注册与持久化另由集成测试验证。

保持 Draft，未合并、未创建 tag 或 Release。
