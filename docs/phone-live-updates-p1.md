# Phone P1：Android Live Updates 与 HyperOS 真机诊断

本 PR 叠加 [P0 课程通知 PR #76](https://github.com/787x/DormPanel/pull/76)，先合并 P0。

## 实验设计
- 手机端「管理 → 跟踪当前课程（实验）」：**只能**手动选择正在进行、已由本地课表成功加载的课程；不会为将来日历事件或全天课表自动申请提升。
- 采用已保存课程的 `sourceId + seriesId + start + end` 身份，用户操作后持久化一次跟踪，到本节实际结束时间自动过期；课程更改后身份不匹配会结束跟踪。
- Android 16+ 对这一单独通知调用 `NotificationCompat.Builder.setRequestPromotedOngoing(true)`，使用系统标准通知和 Chronometer。若没有授权、系统不支持或 OEM 不认可，**可能仅显示普通通知**。
- 提供通知内「结束跟踪」操作和被用户删除时的结束回调。低版本 Android 只使用普通通知。
- `POST_PROMOTED_NOTIFICATIONS` 为 Manifest 声明；`POST_NOTIFICATIONS` 仍需要 Android 13+ 用户授予。
- 没有 `miui.focus.param`、`MiPush` 或平台未授权的焦点/超级岛模板。其正式接入属于 P2。

## 手机内诊断
「管理 → 通知能力诊断」异步读取：Android API、通知权限/总开关、课程通知渠道、Live Updates 提升授权、小米焦点协议版本、焦点通知用户开关，以及本地闹钟/通知调用时点。
- 若 API 36+，可跳转 Android 实时更新授权页面；普通通知设置也可以打开。
- 小米 `notification_focus_protocol`=2/3 只能说明支持相应协议，**不等于本应用获得厂商业务审核**。
- 小米后台本地通知权限默认拒绝，日程提醒等可能可申请白名单；没有可信的公开接口查询实际白名单，诊断不能声称已通过。
- `notify()` 返回成功是应用调用证据，不等于 SystemUI 在锁屏/岛上实际展示。

## 真机验收表（待执行；不能用代码审查代替）
请以 **HyperOS 2 与 HyperOS 3 各一台** 为目标（具备时），记录构建 commit、机型、Android API、HyperOS 版本、权限设置、截图/录屏、预计/实际通知变化时点。
1. 从 DormPanel「课程通知」开启普通通知：**无桌面小组件**时，课前 15 分钟、开课、下课分别在锁屏/解锁状态观察。
2. 关闭系统「应用通知」、设置课程渠道为关闭、启用/关闭锁屏通知，验证不强行恢复。
3. 同一节课中「跟踪当前课程（实验）」：查看状态栏/锁屏/通知抽屉；Android 16+ 在诊断中检查 Live Updates 授权，尝试正常通知与提升通知对比。
4. 在跟踪通知中点击「结束跟踪」，以及从 App 的管理菜单结束；确认恢复为用户设置的普通通知行为，不重复新建提升通知。
5. 熄屏 Doze 超过一小时、断网、关闭桌面小组件、手机正常重启、时区/系统时间修改、原位升级，分别记录闹钟触发/通知 API 返回与真正可见时间。**强行停止应用后不承诺持续工作。**
6. 在系统设置中检查小米「后台发送本地通知」权限（若 ROM 暴露）、电池管理和焦点通知开关；如后台通知未出现，区分闹钟根本未到、广播未达、Store 读取失败、`notify()` 调用返回与 SystemUI 实际过滤。
7. 导入课程、调课/停课、已结束课程、跨日课程和课程重叠，确认不会重复推进错误的跟踪会话。
8. 对照 `管理 → 通知能力诊断` 与实机录屏，记录 **未验证/失败**，不要写成 PASS。

## 参考调试命令（只读）
```bash
adb shell settings get system notification_focus_protocol
adb shell dumpsys notification --noredact | grep -i -A 8 com.dormpanel.timetable
adb shell dumpsys alarm | grep -i -A 4 com.dormpanel.timetable
adb logcat -d -v time | grep -i DormPanelCourseNotice
```
这些命令的输出随 ROM/ADB 权限变化，不可将 grep 无结果直接认定 App 没有通知。
推荐使用 Android Studio Logcat 结合手机诊断的 UTC 时间点比对。

## 开发机验证（未在当前运行环境执行）
```bash
./gradlew :phone-app:assembleDebug :phone-app:testDebugUnitTest
```
P1 单元测试 `CourseLiveTrackingTest`：活动窗口、身份稳定及调课后的失效。P0 见 [phone-course-notifications.md](phone-course-notifications.md)。

官方边界：
- [Google Android Live Updates](https://developer.android.com/develop/ui/views/notifications/live-update)——课程日历事件并非典型用例，用户主动跟踪也不保证提升；
- [小米后台发送本地通知](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1626)——可能阻止后台本地通知；
- [小米超级岛开发指南](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2131)——私有业务场景须另行接入与授权。

**验收状态：当前没有 Android SDK/ADB/Xiaomi 实机，尚未完成构建和物理显示验证。**
