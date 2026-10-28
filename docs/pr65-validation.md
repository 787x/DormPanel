# PR65 验证记录

基线：6832986d825e8ad62348f5cbe18b8b065fff13d2。

Phone 课程管理支持原始来源与名称候选分组、系列去重、时段选择、本地条目显式加入、公共字段勾选、复用单系列编辑器的时段草稿、变更预览与持久完成回执。Room 批量接口使用已有 write / v3 engine.edit 入口，在事务中重新检查课表基线、身份、活动版本、记录合法性与覆盖预算。

## 已执行验证（2026-10-28）

- Phone Debug 与隔离 x08eTest 构建通过；CourseBatchTest 5 项通过；git diff --check 通过。
- MIX 2S（210b601e）仅安装更新 `com.dormpanel.timetable.testbed` 及测试 APK，没有升级日用包。
- CourseManagementTest.roomBatchAndCourseUi：通过。真实 Room 两系列提交、过期基线拒绝、失效系列整批拒绝、第二条写入 SQLite trigger 故障整批回滚、管理入口、公共名称预览确认、关闭数据库后重开恢复。
- CourseManagementTest.activeV3BatchPublishesExistingRecords：通过。先建立隔离活动 v3，再调用正式批量 API，保存后执行同步检查，验证不可变发布对象包含两个覆盖。未将此项称为自动后台发布时序验收。
- CourseManagementTest.lightCourseUi：通过。浅色管理入口、编辑与确认保存；同时保留深色截图。测试机原有 font_scale 为 1.25，未改变系统设置。
- TimetableSwipeTest.nativeTouchAnimationAndDateContentStayTogether：通过，原生左右横划、日/周切换、日期与内容一致。
- Android 实测导出的 v2 DPC 和活动 v3 DPV 对象，通过 SSH 在 fnOS 测试机 192.168.3.153 的已安装隔离应用解码器读取，均得到 2 条覆盖和一致名称。仅上传到随机 `/tmp/dormpanel-pr65-*`，未修改原有工作区或共享绑定。
- fnOS 侧边浏览器确认隔离应用可访问。此次跨端结果是实际对象读取兼容性，不能替代三端网络收敛验收。

实机发现并修复：批量事务原先错误使用要求同步目录的 fullState；改用正式 readState，未绑定时补齐实际学期配置，保留活动 sync 身份比较。公共信息取排除单周例外的 all-weeks 草稿，避免把例外值误用作统一默认值；允许将已有覆盖恢复为继承原始值。

## 实际截图

![课程管理列表](images/pr65/list.png)
![课程编辑](images/pr65/editor.png)
![保存预览](images/pr65/preview.png)

## 未完成与限制

保持 Draft，尚未满足全部验收条件：
- 尚无 Phone → 真实 WebDAV → X08E / fnOS 的三端完整网络同步操作验收。
- 活动 v3 批量写入的断电/回执故障与迁移保护、只读、导入替换专项仍待补充；Room 已验证第二条写入故障回滚与重开持久恢复。
- UI 取消、独立时间/节次编辑、多来源歧义、多星期系列与湖北大学 CSV/XLS 实例组合仍需更完整的实机覆盖；当前操作实测使用针对性 ICS 形态合成数据。
- 日期列表直接列出实际日期，长学期数据的显示密度仍需优化；预览目前统一说明例外优先，字段级最终投影提示仍需完善。
- 保守并发检测比较完整课表，其他课程变更也可能要求重新加载。
- 初次全量 schedule-core JVM 测试与另一 Gradle 测试任务重叠，因 in-progress-results-generic.bin 丢失失败；随后串行定向测试通过，未报告全量通过。
