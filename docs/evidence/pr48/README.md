# PR48 手机版整周课表验证记录

实现默认七列周视图、统一纵向时间轴、日期标题进入日视图、周次导航与原有详情/编辑入口。独立显示模型只消费 ScheduleProjection.week 的结果，跨日、跨周边界裁切后仍持有原始实例。最短显示高度 48dp；发生实际或触控范围重叠时聚合展示，点击列出全部课程。日期、视图与滚动位置在重建及正常重开时保留。

没有修改投影、数据库、同步协议、课程编辑算法、X08E 或 FPK。Phone 版本为 0.1.5 / versionCode 7，applicationId 不变。

## 已执行

- Phone Release、隔离测试包及 AndroidTest 构建通过，Release lint 通过，见 build.txt。
- WeekLayoutTest 3 项全部通过：跨年、周边界、跨午夜裁切、稳定排序、短课程触控碰撞、空周。schedule-core 单元测试任务通过（使用既有缓存结果）。
- MIX 2S 独立 testbed 包专项 UI 测试通过，见 week-ui.txt：竖屏七列、横屏七列、课程详情、旋转、Activity recreate、日视图切换。测试数据存于独立随机数据库；未更新正式包或操作日用数据库。
- portrait.png / landscape.png 为有课程数据的真实设备截图。窄列文本会换行/截断，完整内容在无障碍描述和详情中提供。

## 未完成的验收

- 整套 PhoneTimetableTest 未通过。多轮运行出现 fixture provider 不可见（No content provider），以及旧测试受持久选择日期影响。已增加测试前偏好清理、显式日视图、滚动点击和导入对话框等待；完整套件仍需在可靠 instrumentation 环境重新执行。regression.txt 是失败过程记录，不是通过证据。没有宣称导入、双向同步、换库保护回归全部通过。
- 尚未实测深色模式、大字体、360/430dp 多尺寸、真实导入数据的跨午夜/重叠交互，以及离线/同步状态变化；跨日和重叠当前有显示模型单元测试。
- 已补齐正式签名候选：`artifacts/pr48-candidate/DormPanel-Phone-0.1.5.apk`，SHA-256：`0dd9254260c31df79d3b4ce643b1bfdfcfc7bc44eb5cca81132517ebb7c13f36`。apksigner 验证通过，证书 SHA-256 与批准身份 `32d68b5c6ad0bfd1b87aa0d54514ef71c33838e7643eb038488af7ca2add17f7` 一致；applicationId 不变，minSdk 28，versionCode 7。见 signature.txt。
- MIX 2S 正式包已通过 `adb install -r` 从 0.1.4（6）原位升级到 0.1.5（7），未卸载或清除数据，冷启动成功。见 upgrade.txt。正式包竖横屏有课程完整交互及升级前后业务数据逐项比对仍待完成，不能以安装成功代替该项验收。
- 未执行 X08E/FPK 无关回归及 soak。PR 保持草稿，不合并、不打 tag、不发布。
