# Phone 课程通知：P0 与真机验收

仅影响 `phone-app`。不修改 `schedule-core` 数据模型、Sync v2/v3、X08E 端或签名/版本号。

## P0 行为
- 默认关闭。手机端「管理 → 课程通知」可切换：关闭、全天当前/下一节、仅课前/上课中；提前 5/15/30 分钟；锁屏隐藏详细信息。
- `CourseNoticePlanner` 按共享课程投影的实际 `Instant` 计算，覆盖调课、重叠、跨日期、下一次切换边界。
- `CourseNotificationCoordinator` 每次从正式 Store 异步读取，沿用小组件的超时/过期结果防护，但有独立于桌面组件数量的 Alarm 与 Notification ID。
- 使用系统 Chronometer 显示开始/结束倒计时；无需每秒轮询、永久前台服务、网络服务或精确闹钟权限。
- 课表提交、应用进程启动、正常系统重启、系统时间/时区/日期变动及 APK 替换时尝试刷新。
- 如果通知权限未授予/通知被系统禁用，不能强行展示；本地闹钟可能受 Doze 与 OEM 策略延迟。用户主动关闭时取消通知与闹钟。
- Store 读取失败与超时仅在已知有效期内保留通知；到期撤下，恢复读取后重新发布，不把读取失败当作空课表。

## 构建及自动测试（需在具备 Android SDK/Gradle 依赖的开发机执行）
```bash
./gradlew :phone-app:assembleDebug :phone-app:testDebugUnitTest
```
主要测试：`CourseNoticePlannerTest`。覆盖默认关闭、课前窗口、当前上课、重叠、课程覆盖、日界与无课。

## 真机验收（必须手动记录证据，不以代码审查代替）
1. Android 13+ 首次开启通知、拒绝权限、系统手动关闭通知，观察设置与退化行为。
2. 无任何桌面小组件时，课前/开始/结束/课后均正确更新；点击通知能打开今日课表。
3. 设备锁屏和息屏下测试标题脱敏、系统倒计时、折叠与取消行为；不承诺常驻锁屏最顶部。
4. 测试 Doze、息屏超过一小时、重启、时间和时区调整、应用升级；记录计划触发时间和真实观察时间。
5. 调课/停课、跨午夜课程与跨周、课程重叠、Store 读取失败恢复期间不修改已保存课表。
6. HyperOS 同时检查通知渠道设置、后台本地通知权限与电池策略；无法仅凭普通通知 API 保证准点。

## P1 及后续范围
Android 原生 Live Updates 只用于明确主动开启的实时跟踪尝试，系统可以拒绝提升。小米焦点通知/超级岛（P2）需要另行申请业务准入；P1 不写入未授权 `miui.focus.param` 假装上岛。

**验收范围：已有 MIX 2S 通知对象测试通过，尚未完成锁屏视觉及完整生命周期设备验收。** 下面按轮次保留历史验证记录。

## PR76 补充验证（2026-10-30）
- 修复设置单选按钮独立 ID 和初次回显；设置内容可滚动。设置变化立即撤下旧通知，防止旧公开详情残留。
- 权限拒绝保留配置，不循环申请；返回应用重新检查权限、总开关和渠道。不可展示时取消通知与闹钟，不读取 Store；用户主动保存开启配置时才申请权限。
- 初轮失败读取保留原通知（后续增加有效期限制），5 分钟非精确闹钟重试；复用协调器的 7 秒读取超时、8 秒广播完成期限和最多两个未返回读取限制。迟到/重复结果无发布权。
- nextWake 检查全部重叠起止/提前边界，但仅在实际选中课程或阶段变化时唤醒；本地午夜重新投影。不承诺非精确闹钟准点。
- `:phone-app:assembleDebug :phone-app:testDebugUnitTest`：通过。新增三档提前窗口精确边界、重叠切换/无效边界、倒序输入稳定性、导入跨午夜/跨周及 UTC、关闭再开启拒绝旧结果测试；现有协调器故障/超时/恢复测试全部运行。
- 独立 `.pr76testbed` 包构建及 Android 9 模拟器 instrumentation：`CourseNotificationTest` 1 项通过，验证设置持久化、同 ID 更新、ongoing/onlyAlertOnce、系统 Chronometer 起止时间、PRIVATE/publicVersion 脱敏及 OFF 取消。未改动日用 Store。
- MIX 2S（API 33）隔离包安装成功，instrumentation 返回 `Process crashed`，日志显示进程被 SIGKILL；未取得成功验收结果，未据此断言具体厂商限制原因。
- **仍未验证**：Android 13 权限弹窗与拒绝的交互、系统渠道关闭、通知点击与设置大字体触控、真实锁屏视觉脱敏/倒计时、HyperOS 后台延迟、Doze、重启和原位升级恢复。模拟器通知对象断言不代替这些验收。PR 保持 Draft。

### MIX 2S 重试与杀进程诊断（2026-10-30）
- 设备：MIX 2S Emerald Edition，API 33，系统显示版本 `V14.0.23.10.8.DEV`（MIUI）；不能据此宣称 HyperOS 验收。
- 使用新鲜隔离包状态复现失败：通知入队后，logcat 记录 `POST_NOTIFICATIONS` 权限变更，`am_kill` 原因为 `PermissionHelper`，随后 SIGKILL；包权限变为 `granted=false`。本次明确是系统权限变更导致测试进程终止，未观察到应用 Java 崩溃。渠道首次初始化与撤权的时间关联不能证明 MIUI 内部具体策略。
- 保留已创建渠道，仅移除隔离包的 `course_notice_settings.xml` 测试配置并重新授予通知权限后，`CourseNotificationTest` 连续两次通过（各 1 项）：一次 root 启动，一次普通 shell 启动。无需修改系统省电策略；未触及日用包或日用 Store。
- 真机通过范围仅为 notification 对象、设置持久化及 OFF 取消等该测试中的断言；没有将其扩大为锁屏视觉、通知点击、权限弹窗、重启恢复或 HyperOS 后台验收。保持 Draft。

## 本轮三项定向修复（2026-10-30）
- 新增透明背景、单色日历轮廓 `ic_notification_course`，只替换课程通知及脱敏 publicVersion 的 smallIcon；原应用/Launcher/小组件图标不变。
- 删除渠道 `lockscreenVisibility` 赋值，不删除或重建渠道；保留通知自身 PRIVATE/PUBLIC、脱敏 publicVersion、用户隐私选项及系统总开关/渠道检查。
- 成功计算后、发布前同步保存 `nextWake` 时间戳，不保存课程副本。该边界代表当前课程/展示阶段变化或本地午夜重新投影。请求开始及失败/7 秒超时恢复时检查有效期，已过期或无有效期记录的旧通知撤下；读取失败不修改 Store。未知期限可覆盖从旧版本升级留下的系统通知。
- 失败重试取 5 分钟与尚未到达的有效期边界中的较早者，避免覆盖已知下课/开课切换；过期后按 5 分钟重试。通知同时设置系统 `timeoutAfter`，进程退出或刷新广播延迟时仍可撤下旧状态。不增加闹钟或轮询；系统实际取消时间仍可能延迟，不保证严格准点。
- 验证：`:phone-app:assembleDebug :phone-app:testDebugUnitTest --tests '*CourseNotice*' --tests '*WidgetRefreshCoordinatorTest'` 通过（26 项：Planner 8、Validity 4、协调器 14）。新增 Fake Store/时钟测试覆盖期限前失败、精确到期失败、跨界超时、成功恢复、迟到结果、关闭交错及重建后读取持久期限/未知期限。
- 隔离包及测试 APK 构建通过；Android 9 模拟器 `CourseNotificationTest` 1 项通过，断言普通/脱敏通知均引用新图标及携带 timeout，并实际观察无刷新时系统撤下通知、重新发布恢复、OFF 取消。最初固定等待 2.5 秒不足，改为最长 15 秒轮询后通过，不以系统 timeout 作为严格准点保证。
- 历史 67 项 JVM 与 MIX 2S 对象测试记录保留，本轮未重复完整设备矩阵。新图标的真实状态栏视觉、锁屏视觉及其他上文列出的生命周期设备项目仍未实测；本轮不要求 HyperOS/长时间 Doze。三项定向修复完成，可进入代码审查；此状态不代表完整设备验收完成。
