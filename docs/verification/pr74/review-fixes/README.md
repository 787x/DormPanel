# PR74 审查修复验收

日期：2026-10-29。修复基线：`16ceb015e400ab9491fc725a2b4f4374c24e36e1`。直接更新原 PR74，无新 PR。

## 修改与语义

1. `ScheduleSession.openTimetableDate()` 同时定位 selectedDate、month、周一 weekStart，并进入周课表。MainActivity 的卡片日期导航复用该操作；日历 select() 原有语义保留。未改周视图左右切周和 Today。
2. 列表在越过触摸阈值时，按当前手指方向查询对应的 canScrollVertically：能向该方向滚动就认领整条流；不能则 CANCEL 子项、留给首页。流被列表认领后保持至结束，即使随后到达边缘也不切换。横向流取消子项点击，保留首页导航；编辑模式不消费内部触摸。
3. 仓库已有 RecyclerView 用于课程区，创建可见和少量缓存行，不创建全部课程 View，不截断数据。每张卡片保留渲染键；相同 state/实际宽高/交互/日期时区且未跨课程边界时不重建。applyAppearance 只更新已有节点，回收行重新附着时更新主题。数据或几何改变才重建列表，LayoutManager 状态用于保留位置。
4. 双栏各自的日期标题位于 RecyclerView 外侧，滚动互不影响。窄版保留分组行，并在课程时间行携带显示日期，滚入明天的中间仍可识别分组。跨午夜课程的该日期取显示日，不取原始发生日期。今日卡片移除内容区重复日期。
5. 每次渲染仅 capture 一次 now、zone、today、state、locale；投影、剩余筛选、教学周及课程状态共用该快照。缓存继续按 state 引用、日期和时区失效。ScheduleDisplay 沿用刚渲染的下一边界；即使调度计算时已经越过午夜，也会立即补刷，不重新从新日期跳过旧边界。没有新增轮询、服务或数据库读取器。

Provider key、尺寸策略、旧 timetable/calendar、布局引擎、Writer 和同步协议未改变。

## 实际执行结果

主设备：真实 X08E，ADB `28605/A0UH93770`、API 28、1280×800。仅安装 `.testbed` 和测试 APK，隔离 Room/合成课表；未覆盖日用应用或访问真实账号。

- X08E APK / AndroidTest APK 构建通过。
- app 定向 JVM：49/49 通过，包含新增日期操作、原 select() 语义、跨周跨年/跨月和已有课程投影、Dashboard、X08E 几何测试。
- `CourseCardReviewAndroidTest` 三项均在实机通过。实际 Activity 事件路径使用 sendPointerSync，而非仅对列表直接 dispatch。覆盖顶部/底部向内与向外、已认领流到达底部、左右独立滚动、空列表、横向导航、真实课程点击关闭详情、编辑模式、卡片头部日期与历史周跳转。断言最终实际可见页面类型和课表周范围文字。
- 真实渲染周范围验证：从旧周回到首页后点击卡片日期；周日 2026-12-27 的明天 12-28；同周跨年 2027-01-01；跨月 2027-02-01。并非只断言 selectedDate。
- 50/200 门当天课程（同时各有同数量明日课程）：单次 bind 完整 render 为 1；落地测量后的总 render 为 2（新增一次真实几何变化）。相同尺寸再次 bind、主题更新和未到边界的普通 refresh 不增加计数；双栏累计创建的 holder 均为 28，末尾课程可访问。新 state 刷新后保留末尾滚动位置。测量日志最后一轮首 bind 约 119ms（50，含冷启动）/14ms（200，已预热）；不把二者当作线性性能比较，也不设置毫秒阈值。
- 可控时钟：bind 的一次 instant() 读取后恰好跨午夜，头部仍与该次投影使用 10/28；下一轮一起切换。实际延迟任务检查课程结束和午夜自动刷新；午夜后的两天标题同时变成 10/30 与 10/31。更改可控时区至洛杉矶/上海后，统一重算显示日。拆除卡片后订阅数为 0，refresh 不增加渲染计数。
- 更新后的 `CourseListCardsAndroidTest` 两项通过：2×1 / 4×4 / 8×6、双主题、单双栏、范围尺寸、编辑恢复、目录多实例、拒绝无空间最大尺寸、Room 关闭后恢复。沿用初始证据，不重新机械验收 24 张图。
- 原 `CoreDashboardTest.dragResizeUsesProviderPoliciesForEveryCoreCard` 在实机通过。

原始构建/JVM/实机日志及 JVM XML 位于本目录。最后一轮只补跑变动断言对应方法，不重复已经通过且没有产品修改的全矩阵。

## 补充截图

以下是真实 X08E 系统截图，列表已滚动。其余尺寸继续参考原验收记录。

- [今日 2×1 深色：日期不重复](pr74-review-schedule_today-2x1-DARK-screen.png)
- [近日 4×4 深色：两栏日期固定](pr74-review-schedule_recent-4x4-DARK-screen.png)
- [近日 2×1 浅色：课程时间带分组日期](pr74-review-schedule_recent-2x1-LIGHT-screen.png)
- [今日 8×6 浅色](pr74-review-schedule_today-8x6-LIGHT-screen.png)

## 验证边界与评审状态

上述触摸由真实设备的 Android 输入事件执行；未录制手指操作视频，未覆盖所有第三方字体/无障碍配置。没有重跑长期 soak、真实账号同步或完整 Phone/fnOS 矩阵；这些通道未修改。初始记录中的 WebDavClientTest 英文异常文案失败原样保留，本轮未重复该无关测试。

本轮审查五项问题已修复并完成定向验证，满足 Ready for Review。此结论不表示已合并或已发布。
