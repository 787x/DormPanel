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
- 本机有原签名密钥文件，但未配置签名密码。仅生成本地未签名构建 artifacts/pr48-candidate/DormPanel-Phone-0.1.5-unsigned.apk，不能原位升级安装，不应当作正式候选。SHA-256：9caf9178f0a7b674b5c697ca781de08b769f135eb2b2f298ae252426d8b2d8d9。正式签名及 MIX 2S 日用包原位升级验收待完成，未改用调试签名冒充正式候选。
- 未执行 X08E/FPK 无关回归及 soak。PR 保持草稿，不合并、不打 tag、不发布。
