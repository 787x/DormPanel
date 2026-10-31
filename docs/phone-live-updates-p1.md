# Phone P1：Android Live Updates 主动课程跟踪

本 PR 基于已合并的 [P0 课程通知 PR #76](https://github.com/787x/DormPanel/pull/76)（`main` 最终 P0，merge commit `ea15455`）开发。P0 的全部行为（默认关闭、当前/下一节/课前倒计时、重叠与跨午夜边界、独立 AlarmManager 调度、正式 Store 读取与失败恢复、`timeoutAfter` 有效期、透明单色图标、锁屏隐私 `publicVersion`、权限拒绝保留配置）原样保留，未在本 PR 中回退。

## 实验设计
- 手机端「管理 → 课程通知」对话框内提供「跟踪当前课程（实验）」入口：**只能**手动选择正在进行、已由本地课表成功加载的课程；不会为将来日历事件或全天课表自动申请提升，也不会根据课表自动开启跟踪。
- 采用已保存课程的 `sourceId + seriesId + start + end` 身份，用户操作后持久化一次跟踪会话；课程内容（标题/教室）修改但身份不变时继续跟踪并显示新内容；课程取消、删除、起止时间变化、来源被替换或实例不再存在时，跟踪会话失效并结束。
- 本节实际结束时自动停止；应用进程重建后，尚未结束的跟踪会话可恢复，但会重新验证课程与通知设置。
- 普通课程通知为 OFF 时不能在后台偷偷发布跟踪通知：此时不显示跟踪、不申请提升，且应用内开始跟踪会被引导先开启普通通知，而不是暗中修改用户设置。用户关闭课程通知会同时结束进行中的跟踪会话。
- 通知权限被拒绝或系统关闭通知时安全退化：不崩溃、不循环申请权限、不擅自清除原有通知配置。

## 与 P0 调度/有效期的关系
- P1 不建立第二套竞争性课程通知调度器。普通通知与主动跟踪共用 `CourseNotificationCoordinator`、正式 `ScheduleStores`、`AlarmManager`（同一 PendingIntent ID 21001）与通知 ID 21001。
- `CourseNoticePlanner` 在最终 P0 投影逻辑上叠加跟踪选择：跟踪会话有效时该课程优先于普通选中（包括与其他同时进行课程的重叠场景）；普通模式完全保持 P0 语义。
- 跟踪会话必须遵守 P0 的持久化通知有效期（`CourseNoticeValidity`）与 `timeoutAfter`：Store 读取失败时可暂时保留仍有效的跟踪通知，但过期后不得继续显示“正在跟踪”，也不会继续保留无效的本地闹钟计划（诊断中计划时点会被清除）。
- 停止跟踪、课程提交、闹钟广播与 Store 回调交错时，过期结果不能重新激活已停止的跟踪；协调器沿用小组件已有的超时/过期结果防护，不增加轮询。
- 恢复失败沿用 P0 已有的 5 分钟有限重试，不增加永久前台服务、精确闹钟特殊权限或新数据源。

## 会话级停止归属
- 每次明确开始跟踪都生成独立会话 token 并与会话一起持久化；重新跟踪同一课程也是新会话。
- 课程内容（标题/教室）修改不产生新 token；课程实例身份 `sourceId + seriesId + start + end` 继续用于验证课程是否变化，不代替会话身份。
- 通知「结束跟踪」与 `deleteIntent` 使用**每会话唯一**的 PendingIntent：data URI 携带会话 token，`FLAG_UPDATE_CURRENT` 不会把旧会话操作改写为新会话。
- Receiver 只在校验 token 与当前会话一致时才执行停止；旧会话、已结束或无效 token 的操作被安全忽略，不取消当前会话的通知、闹钟或下次刷新计划。
- 校验与清除在同一同步步骤内完成，新会话不会被迟到的旧停止操作误删；旧版本的“无 token”遗留会话在读取时安全迁移出新 token，遗留操作无法删除新会话。
- 应用内主动结束跟踪仍为无条件入口。

## Android 16+（API 36）Live Updates 接入
- 只用公开 Android / AndroidX API：`NotificationCompat.Builder.setRequestPromotedOngoing(true)`、`NotificationManager.canPostPromotedNotifications()`、`POST_PROMOTED_NOTIFICATIONS` 权限声明。
- 只有明确处于有效主动跟踪状态的通知才请求 promoted ongoing；用户结束跟踪后，后续普通通知不再携带提升请求。
- 按官方要求设置 `ongoing`、标题与系统 Chronometer 样式；不自定义 RemoteViews 伪装 Live Updates。
- 系统不支持、不允许或未提升时，使用标准普通通知正常工作。`setRequestPromotedOngoing(true)` 调用成功**不等于**实际获得系统提升；诊断明确区分“提升资格（canPostPromotedNotifications）”“提升请求（已调用 promoted setter）”与“实际系统展示（需人工确认）”。
- API 28–35：不调用不支持的 API，主动跟踪以普通通知呈现，仍提供可靠的「结束跟踪」操作。
- 课程进度并非 Android 官方列举的典型 Live Updates 用例；此功能明确标记为实验性，不保证系统认定为适合提升的活动，也不为强制提升而规避系统限制。

## 入口与状态反馈
- 「跟踪当前课程（实验）」和「通知能力诊断」不再独立占用一级管理菜单，整合进「管理 → 课程通知」对话框：
  - 显示当前是否正在跟踪、跟踪的课程标题与预计结束时间；
  - 一个按钮切换「开始跟踪 / 结束跟踪」；
  - 「查看通知能力诊断」作为低频高级入口。
- 用户可随时从应用内「课程通知」或跟踪通知中的「结束跟踪」操作结束跟踪。

## 通知能力诊断
「课程通知 → 查看通知能力诊断」异步（不阻塞课表 UI/Store 读写）输出：
- Android API 版本、普通通知运行时权限、系统通知总开关、课程通知渠道状态。
- Android Live Updates 系统支持情况（API 36+）与 `canPostPromotedNotifications()` 资格。
- 当前是否存在有效跟踪会话、最近一次提升请求时间点。
- 最后一次计划唤醒（仅在闹钟/调度真正提交后记录，取消或关闭时清除）、最近闹钟广播收到时间、最近 `notify()` 正常返回时间、最近一次失败及时间。
- Xiaomi 焦点协议版本与用户开关为**只读、尽力查询**，明确区分协议支持、用户开关与厂商业务准入；不把查询结果解释为已获得小米超级岛授权。不实现 `miui.focus.param`、MiPush 或私有岛通知模板。
- 不记录课程私密内容、WebDAV 凭据或敏感数据；避免持续日志、频繁轮询或无限累积诊断状态。查询失败/API 不可用显示“未知/不可用”，不误报为已授权。
- 诊断在独立一次性后台线程执行并通过单次运行门控：快速重复点击不会并发查询或堆叠弹窗；结果对话框关闭后才允许再次查询；Activity 销毁、查询或展示异常不会永久锁住入口。

## 真机验收矩阵（未来参考，不作为本 PR 验收门槛）
以下设备矩阵在具备时执行并记录构建 commit、机型、Android API、系统版本、权限设置、截图/录屏、预计/实际通知变化时点：

| 设备 | 系统目标 | 说明 |
| --- | --- | --- |
| HyperOS 2 设备 | Android 14/15 + HyperOS 2 | 普通通知、跟踪结束、诊断对照 |
| HyperOS 3 设备 | Android 15/16 + HyperOS 3 | 普通通知、跟踪、API 36 提升可用性 |

1. 从 DormPanel「课程通知」开启普通通知：无桌面小组件时课前 15 分钟、开课、下课分别在锁屏/解锁状态观察。
2. 关闭系统「应用通知」、设置课程渠道关闭、启用/关闭锁屏通知，验证不强行恢复。
3. 同一节课中开始跟踪：查看状态栏/锁屏/通知抽屉；Android 16+ 在诊断中检查 Live Updates 资格，对比普通通知与提升通知。
4. 在跟踪通知中点击「结束跟踪」以及从 App「课程通知」结束；确认恢复为用户设置的普通通知行为，不重复新建提升通知。
5. 熄屏 Doze 超过一小时、关闭桌面小组件、重启、时区/时间修改、原位升级，分别记录闹钟触发/通知 API 返回与真正可见时间。强行停止应用后不承诺持续工作。
6. **断网**：关闭 Wi-Fi/移动数据后验证课程通知与主动跟踪继续使用本地课表，不应因断网失败；断网不等于本地 Store 故障。
7. **Store 故障**：通过隔离测试、Fake Store 或受控失败注入验证读取异常诊断、通知有效期与恢复机制，不在正常日用包上制造故障。
8. 检查小米「后台发送本地通知」权限（若 ROM 暴露）、电池管理和焦点通知开关；区分闹钟根本未到、广播未达、Store 读取失败、`notify()` 返回与 SystemUI 实际过滤。
9. 导入课程、调课/停课、已结束课程、跨日课程和课程重叠，确认不会重复推进错误的跟踪会话。
10. 对照「通知能力诊断」与实机录屏，记录未验证/失败，不要写成 PASS。

## 参考调试命令（只读）
```bash
adb shell settings get system notification_focus_protocol
adb shell dumpsys notification --noredact | grep -i -A 8 com.dormpanel.timetable
adb shell dumpsys alarm | grep -i -A 4 com.dormpanel.timetable
adb logcat -d -v time | grep -i DormPanelCourseNotice
```
这些命令的输出随 ROM/ADB 权限变化，不可将 grep 无结果直接认定 App 没有通知。
推荐使用 Android Studio Logcat 结合手机诊断的 UTC 时间点比对。

## 开发机验证（本次实际执行）
```bash
./gradlew :phone-app:assembleDebug
./gradlew :phone-app:testDebugUnitTest
```
- `assembleDebug`：通过。
- `testDebugUnitTest`：通过（89 项）。覆盖 `CourseNoticePlannerTest`（含新增跟踪优先、身份稳定、内容修改、时间变化、OFF 不发布、会话结束/取消、跨午夜边界、会话 token 传播）、`CourseNoticeTrackingLifecycleTest`（跟踪生命周期与有效期）、`CourseLiveTrackingTest`（含会话级停止归属、旧/缺失 token 不能停止）、`CourseNoticeValidityTest`、`WidgetRefreshCoordinatorTest` 与既有 P0 规划/有效期测试。
- 隔离环境设备验证（MIX 2S / API 33，测试包 `com.dormpanel.timetable.testbed`）：`CourseTrackingSessionTest` 2 项通过，验证真实 PendingIntent 身份（不同会话 token 生成不同 PendingIntent，`FLAG_UPDATE_CURRENT` 不改写旧操作）、会话隔离停止（旧 A 停止不影响 B）、同课重启为新会话、遗留无 token 会话迁移且不受未知操作影响。
- 低版本兼容：MIX 2S（API 33 / MIUI 14）已连接，作为低版本退化确认环境；不在本 PR 中把 Android 9 或 MIUI 测试矩阵作为验收门槛。

## 尚未验证
- Android 16 / API 36 实机或 AOSP 模拟器上的真实提升（状态栏 chip、锁屏、通知抽屉）与 `canPostPromotedNotifications` 授权交互：**未执行**。
- HyperOS 2 / HyperOS 3 真机矩阵：**未执行**，仅保留为未来参考。
- 长时间 Doze、后台数小时驻留、小米后台本地通知白名单：**未执行**。
- 锁屏视觉脱敏与真实状态栏视觉：未在本 PR 中重新验证。

官方边界：
- [Google Android Live Updates](https://developer.android.com/develop/ui/views/notifications/live-update)——课程日历事件并非典型用例，用户主动跟踪也不保证提升；
- [小米后台发送本地通知](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1626)——可能阻止后台本地通知；
- [小米超级岛开发指南](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2131)——私有业务场景须另行接入与授权。