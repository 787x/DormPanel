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

## 合并前审查修复（2026-10-29）

- Node previewCourseSchedules/saveCourseSchedules 必须传 `target={sourceId,ownerSeries,deleteIds}`，slots 仅为该范围的 upsert，未提交的时段保留。删除必须列明 ID 并属于该目标；ID 不能借更新转移课程归属。不再提供含混的全数据集覆盖语义。预览和保存复用同一候选函数，保存仍校验 revision 和正式权限/事务。
- Kotlin 新编辑接口使用 CourseColorEdit.Unchanged 或 Set(rgb)；Set(null) 才表示明确恢复自动。旧 rgb 接口仅为显式颜色操作的兼容包装，不把 null 当作“不修改”。Phone 根据草稿与原模式比较，没有颜色变化就保留原记录；纯无改动不提交。Node 无 color 对象表示不改色，`{sourceId,seriesId,rgb:null}` 明确恢复自动。
- 来源替换保留仍存在的 sourceId/seriesId 关联；缺失或无法匹配时拒绝整个事务，并列出受影响安排和可执行处理方法。来源删除有引用时拒绝，不提供隐式级联。Phone 提供“移除此课程的全部共享安排规则（恢复原始基线）”的独立草稿确认与原子保存路径；移除所有关联规则后才能操作来源。Node 使用明确 deleteIds 做同样处理。保留新增安排且删除其来源目前不支持，不自动更换身份。规则移除与随后来源操作是两个明确的用户动作，各步均保持引用完整，不宣称为组合级联事务。
- 本地课程不再默认 20 周。新增本地安排从空周集合开始，用户必须输入范围；单双周按钮必须有明确上限。将无限重复原课程有限替换时，额外勾选确认“范围外不再上课”，否则拒绝接受草稿。导入课程仍由真实实例与有效学期计算周集合。已存在有限规则保持其原周集合，线格式没有变化。

## 第二轮审查：多系列原子课程编辑与父课程删除

Node 新入口 `previewCourseEdit(edit, expectedRevision)` / `saveCourseEdit(edit, expectedRevision)`：

```js
{
  target: {sourceId, series: ['A', 'B', 'C']},
  overrides: [{seriesId:'A', action:'upsert', patch:{timingMode:'custom', startMinute:600, endMinute:660}}],
  slots: [/* 显式新增或修改的完整时段 */],
  deleteIds: [/* 明确删除的时段 ID */],
  common: {teacher:'统一教师', note:'统一备注'},
  colors: [{seriesId:'A', rgb:0x123456}]
}
```

除 target 外其他字段可省略，target 必须明确非空、去重的系列集合。导入系列必须有相同来源及 PR65 原始逻辑身份；人工显示覆盖不参与分组，歧义系列不能扩展为多系列。手动条目必须逐 ID 显式选择。只改选定系列的全周覆盖，指定周覆盖保留。公共字段只允许名称、教师、备注，并作用于选中系列及其安排；未提交的时段保留，删除只按 deleteIds。colors 缺省不改色，rgb:null 明确恢复自动；同一逻辑颜色身份不得有竞争操作。原单系列入口委托此候选/事务，不维护另一套保存语义。

预览及保存都验证同一候选；保存重新在正式 tx 内校验当前身份、能力、完整业务状态与预算。成功返回完整 state 与 businessHash，revision 只前进一次；不提供绕过权限、恢复或冲突保护的入口。

Phone 以 `sourceId==null && ownerSeries==entryId` 检查手动父课程依赖，包含停用安排。详情及普通编辑入口不再允许直接删除被引用父课程，提示先进入课程管理移除规则并保存。正式 Room Writer 在删除事务中再次检查当前依赖；ScheduleSource 删除不再乐观移除内存行，成功后重读，拒绝/失败后重读真实状态，业务拒绝不标成数据库损坏。结果不确定时保留恢复保护，已提交但重读失败则明确提示不得重复删除。无依赖的旧格式课程仍可删除。

全周时间覆盖采用显式完整范围：自定义时间必须提供 custom 模式及有效起止分钟，节次必须提供 linked 模式、完整有序节次并能由来源作息解析；跨午夜新增安排仍使用 course.schedule 的已有语义。导入课程共享颜色针对逻辑课程，因此同一逻辑身份只能给出一次颜色操作；手动多 ID 可以逐 ID 明确给色。

## 第三轮审查：部分覆盖与实际日期阶段校验

`courseEditCandidate` 的 `overrides[].patch` 是部分更新：未提供字段保留已有值；显式非 null 设置；显式 null 清除该字段覆盖并恢复原始继承。清空最后一个覆盖字段会移除空记录；reset 明确移除整条全周覆盖。旧 `applyEdit` 仍是完整替换，契约未改变。

时间字段必须由请求本身表达完整变更，不能借旧字段补足不完整请求。custom 必须显式给出合法起止分钟，自动清除旧 periodStart/periodEnd；linked 必须显式给出完整有序节次，自动清除旧分钟。请求同时带互斥模式字段时拒绝。仅清除时间字段时，合并后的组合必须仍完整，或者所有时间覆盖字段均为 null（完整恢复原始时间）。其他名称、地点、教师、备注独立保留。

组合顺序是明确的业务优先级：先执行系列独立修改/reset，再对所有选中系列施加 common 中明确提供的公共字段；同字段以 common 为准，reset 后 common 也可以重新建立指定字段覆盖。未提供公共字段不修改。共享安排没有 null 继承模式，因此 common 含 null 且会作用于共享安排时明确拒绝；可通过 overrides 清除原系列覆盖，安排字段则使用明确字符串，不伪造继承或把 null 猜成空字符串。

linked 全周覆盖复用投影的 linkedPeriodTimes，仅检查实际生效实例日期：原始导入系列使用原实例学术日期与来源时区，若移动星期则校验对应目标日期；替换安排按已启用的 week1/weeks/weekday 投影实际范围。原始实例被替换抑制后，不把未上课的中间周加入校验。未知有限范围或无可靠来源作息时拒绝，不取今天或任意默认周代表未来。独立新增安排继续使用 Registry 自身的有效周校验；日期调整和指定周投影顺序保持不变。

对照测试发现 Kotlin ClassEditSession 也残留全阶段检查，已作为必要直接依赖改为相同的实际日期检查，原实例多星期判断使用学术时区。没有改变线格式、覆盖字段定义或投影顺序。

## 最终审查：继承 linked 的星期移动

新课程 API 的日期/时间候选检查不再仅选 `timingMode == linked`。对每个实际参与投影的基线实例，采用与 patch 完全相同的判定：显式 linked，或覆盖模式为 null 且该实例本身 linked；节次端点分别从覆盖值或实例 periodLabel 继承。自定义覆盖不进入 linked 阶段检查，模式为 null 的自定义实例也不误判为 linked。

移动星期时，用实例学术周的目标星期日期选阶段。原实例被替换抑制后只校验实际启用的有限替换日期，不校验未上课的中间周；无可靠有限范围继续明确拒绝。预览和事务保存共用该检查，不能依赖读取时的 patch 失败回退来接纳无效新覆盖；旧 projectWeek 读取回退及指定周/日期调整顺序未变。

Kotlin 原有继承 linked 的缺节次拒绝仍有效。对照另发现 linked 区间跨显示时区午夜时，ClassEditSession 会误用显示分钟判结束早于开始；现在 linked 仍由 linkedPeriodTimes 的实际 Instant 正向区间证明合法，自定义时间原有限制保持不变。没有更改协议或课程记录字段。
