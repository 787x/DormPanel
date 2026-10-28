# PR70 课程安排与共享颜色

## 业务模型：course.schedule/1

通过 SharedTypes 的 Registry 注册，不改变 DPV3/4、DPXS/1、DPXR/1。每条记录是一个稳定时段，recordId 创建时生成一次，复制使用新 ID；不得与原课程、导入实例或同来源原系列身份碰撞。required 必须为 true，dependencies 必须为空；不支持该类型的客户端禁止业务写入。记录级三方合并沿用 Registry：不同 ID 独立修改可以合并，同 ID 的不同修改和删除对修改产生冲突。关联键为手动课程 class:ownerSeries 或导入 source:sourceId。

字段按以下顺序编码。整数均为大端 I32；字符串为 I32 UTF-8 字节数及字节，不使用 JSON；解码后重编码精确相等，拒绝尾随字节、错误 UTF-8、非规范布尔及周次顺序。

| 字段 | 约束 |
|---|---|
| sourceId | 空字符串表示本地；否则必须为有效业务身份 |
| ownerSeries | 必须存在的原始课程/系列身份，与名称无关 |
| replacesSeries | 空表示独立新增；否则必须等于 ownerSeries |
| week1 | 第 1 周周一，YYYY-MM-DD，年份 0001–9997 |
| weekCount、weeks | 0–104 项；1–104，严格递增，不连续周与单双周均为显式集合 |
| weekday | 1–7 |
| startMinute、endMinute | 0–1439，不能相等；结束早于开始表示次日结束 |
| periodStart、periodEnd | 均为 0 表示自定义时间；或 1–30 有序范围 |
| enabled | 0/1 |
| title、location、teacher、note | UTF-8 字符串，标题非空 |

每个字符串最多 4096 字节，整个 payload 最多 16384 字节。沿用 DPXR 最多 4096 条、块最多 1 MiB、完整 RAW 最多 16 MiB 的总预算。

`replacesSeries` 抑制原系列基线，不改写原始导入实例。相同来源同一系列最多一条替换记录；停用替换记录仍抑制原系列。删除独立新增记录则移除此安排；隐藏整门课生成/停用所有相关替换规则，不删除来源或手动课程身份。多星期原系列不能安全对应一个旧指定周锚点时拒绝替换。

投影顺序：原基线 → 共享安排替换/新增 → 原全周覆盖 → 日期调整 → 指定周例外。替换保留原始星期锚点，修改星期后旧指定周例外仍作用于原实例身份；复制使用新 ID，不继承原指定周例外。日期调整复制采用既有目标日锚点规则。按节次使用来源的有效学期作息与阶段；无法解析时拒绝写入。自定义时间使用来源的学期/原始时区，本地课程使用展示时区；跨午夜保留真实结束日期。

Registry 增加可选 validateState 钩子，完整编码/回读及正式 Workspace 编辑使用该钩子核验父课程存在、替换唯一性和所选有效周作息；默认不增加其他类型限制。来源消失时拒绝悬空写入，来源并发变化沿用关联冲突规则。

## 正式接口与颜色

Kotlin CourseBatch.withColor 生成候选，RoomScheduleStore.saveCourseWithColor 在现有 V3Engine.edit 事务内重新验证完整业务哈希、来源、覆盖、扩展及写入资格，将公共字段覆盖、安排和颜色一次提交。提交结果不确定时使用 ScheduleCommitUnconfirmed，不重复提交。ScheduleSource 的成功回执区分本机已提交与刷新失败，发布/回读继续使用既有同步状态。

Node Workspace.courseSchedules、previewCourseSchedules、saveCourseSchedules 提供 PR71 可复用核心查询、预览与受保护事务。revision、权限及现有 mutationGuard 继续生效；无新网络写入口。

DPV3/4 Phone 使用 SharedCourseColors.display；没有共享颜色采用确定性 RGB，不读取旧偏好覆盖共享状态。旧格式保留 PR68 本机颜色。Phone 日/周视图和两种 Widget 使用同一 Snapshot；X08E 最终投影带共享 RGB，按深浅主题压低亮度，单色模式不变。

本机颜色迁入入口只在扩展格式出现，预览既有/本机设置与自动颜色变化，差异课程必须明确选择保留共享、覆盖或跳过。确认经 expectedHash 与正式事务；取消或失败不清理旧偏好。未做活动绑定的自动升级/切换，也没有旧新数据集持续桥接。

## 编辑边界

整门课编辑保留显式勾选的公共字段语义。新安排编辑支持周集合、单双周、星期、时间/节次、教室、教师、备注，复制和停用；保存前列出有限教学周范围内的最终新增/取消/修改实例与真实时间，指定周例外按最终投影预览。超过 100 项的明细显示前 100 项及全范围计数。

已接受的课程草稿通过 Activity 非配置实例保留旋转恢复；过期仍重新校验，失败保留页面草稿。系统杀进程前未保存的编辑不是已持久化课程修改；嵌套时段编辑尚未“保留草稿”的输入仍需重新输入。X08E 对新安排提供只读详情，完整编辑在 Phone。

固定向量位于 test-vectors/course-schedule；验证记录和截图见 pr70-validation.md。
