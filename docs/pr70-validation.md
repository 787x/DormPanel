# PR70 定向验证记录

基线：最新 origin/main c85c29e，包含 PR69 合并 ac2f45f。仅使用隔离 Room、临时 Node Workspace、Fake WebDAV 和独立 Android testbed；没有改日用绑定、安装正式 APK/FPK、改版本号或创建 Release。

## 实际结果（2026-10-28 至 2026-10-29）

| 检查 | 结果 |
|---|---|
| JVM SharedCourseScheduleTest / CourseBatchTest / SharedRecordsTest / SyncV3Test | 33 PASS |
| Phone JVM 全部单测 | 36 PASS |
| Node shared-course-schedule、shared-state、projection、shared-records、shared-review、v3-workspace-transaction | 38 PASS，0 FAIL / SKIP |
| Phone 与 X08E assembleDebug | PASS |
| Phone 独立 testbed SharedCourseManagementTest | 主集合 4 PASS，141.186 秒；旋转恢复另 1 PASS，6.048 秒 |
| X08E 独立 testbed sharedSchedulesAndColorsThreeLayouts | 1 PASS，1280×800 三布局 |
| Phone Room → Node 正式 Workspace → Phone Room | PASS，完整业务哈希一致 |

Phone 实机另以禁止写入的 Fake Store 验证 Activity 重建后已接受的离散周草稿与公共教师字段保留；初次测试的窗口焦点匹配失败已修正并复跑通过。

Phone 实机包含：多字段覆盖、共享 RGB 和安排在一次正式 Writer 保存；过期 capture 拒绝且原状态不变；深浅色编辑与颜色选择；新增安排精确输入 1/3/5 周、保留草稿、最终预览、确认保存；Widget RemoteViews 读取共享安排与共享颜色，停用后投影消失；Node 修改后的完整状态经正式 Room Writer 提交并核对哈希。大字体为测试设备当前配置，保存按钮在可滚动内容之外。

X08E 实机以合成导入来源、有效作息及扩展安排测试经典、实际时间、节次三布局；断言新增实例地点、日期与共享 RGB，并生成截图。没有为 X08E 增加复杂管理界面。

双端新增测试覆盖固定 payload 字节与完整业务哈希、严格周集合、未知注册表 required 写保护、无关 ID 合并、同 ID 删除/编辑冲突、非连续周、原时段替换、指定周锚点保留、复制不继承原例外、停用和跨午夜。Node 正式 Workspace 另覆盖只读预览、无效父身份拒绝、颜色/安排同事务、过期 revision 拒绝、重开哈希保持。旧投影、Registry、迁移与事务恢复测试继续通过。

## 状态互操作证据

`SharedCourseManagementTest` 将实际 Room Writer 保存后的完整 DPV3/4 对象导出到 testbed 外部文件目录。`fnos-app/scripts/verify-pr70-room.mjs` 在新建临时 Workspace 中接收，以正式事务修改颜色为 8844cc、地点和备注，再导出。`nodeFixtureCommitsThroughRoomWriter` 经正式 Room Writer 保存并比较完整业务哈希。

- Phone 原始业务哈希：`d9b1f4010f5f822022b0154a6e3fa7feefc9632cc4f44be8cbff6391a6a620bc`
- Node 修改后及 Phone 回灌哈希：`0be10f7e674715fc413cf526b8066d77be15622311fa0246647afc42dbff1845`

此项证明真实 Room/Workspace 与协议对象互操作，**不是新增的 NAS HTTPS 网络同步验收**。本轮没有连接真实日用 WebDAV 目录；没有升级 NAS FPK。传输、Epoch/DAG 和回执机制未改写。

## 单独记录的基线失败

扩大 JVM 过滤器到 SyncV3* 时，61 项中出现 1 FAIL、1 SKIP：`SyncV3EngineTest.publishes1024AndRotatesWithoutGlobalCommitLimit` 在第 201 行缓存读取断言期望 0、实际 1。将未修改 HEAD/main 导出到 build/pr70-baseline 后单独运行同一方法，也在同一行以同一断言失败。这不是 PASS，也未将其改为宽松断言。本 PR 的直接定向 33 项仍全部通过；没有继续改 Epoch/DAG 或做四小时 soak。相关原始 XML 位于两个隔离构建目录的 test-results 下。

## 兼容与验证边界

旧 v2/v3 保留本机颜色和原有可支持的覆盖编辑；新增安排与共享色仅允许已显式启用 DPV3/4。迁入旧色需逐门确认，不清理旧偏好，不提供未经验证的活动绑定切换。目标回读、已发布和所有设备接收仍是不同证据。

未保存草稿的进程死亡恢复不属于已提交事务恢复；旋转保留已接受的课程草稿，尚未“保留草稿”的嵌套时段输入不持久化。没有验收真实 Launcher 上两种已放置 Widget 的点击/重启，本轮 Widget 证据是共用投影及 RemoteViews；既有点击、刷新和时间边界单测通过。没有做大量真实课程的长时间 UI 压力验收。

截图来自本轮隔离设备运行；最后的迁移预览说明、未知版本提示与保存预览标签调整已做编译/单测复验，未重拍所有截图。

## 截图

![Phone 整门课管理](screenshots/pr70/phone-editor.png)
![Phone 浅色管理](screenshots/pr70/phone-editor-light.png)
![Phone 颜色选择](screenshots/pr70/phone-color.png)
![Phone 保存预览](screenshots/pr70/phone-preview.png)
![X08E 经典](screenshots/pr70/x08e-classic.png)
![X08E 实际时间](screenshots/pr70/x08e-time.png)
![X08E 节次](screenshots/pr70/x08e-periods.png)

## 合并前四项审查修复（2026-10-29）

基线 9ea8c83。实际复验：核心 JVM 25 PASS（SharedCourseScheduleTest 5、CourseBatchTest 14、SharedRecordsTest 6）；Node 定向 35 PASS；Phone / X08E Debug 构建 PASS。Phone 独立 testbed 的有限周确认及旋转草稿测试 1 PASS（8.762 秒）。首轮发现错误提示位于滚动区域外，已修复为自动滚动到错误并复跑通过。

- 局部更新：正式 Workspace 先保存 B，再预览/保存 A；B 原记录保持相等，预览与提交业务哈希相等。过期删除拒绝，显式删除 A 后 B 仍在，重开哈希一致。所有调用已传明确 target 和 deleteIds。
- 来源：相同 sourceId/seriesId 的替换候选保留两种关联规则；系列消失/身份改变及带关联规则的删除被拒绝，原哈希不变。注入事务异常不提交，过期 revision 拒绝；明确删除两条关联规则后，来源删除成功，重开无悬空引用。Kotlin 同样验证存续、缺失与显式清除规则后的引用完整性。Android 扩展格式来源入口增加事务内 expectedHash 校验，旧格式入口不增加该限制。
- 颜色：只改教师且颜色 Unchanged 时扩展记录完全不变，与远端独立改色正常合并；已有手动色保留，Set(null) 明确产生 AUTO。Phone 无实际变更不提交，Node 缺省 color 不改色。
- 无限重复：新增本地安排从空集合开始；实机断言原无限重复替换没有 20 周默认，未勾选影响确认时拒绝；确认后两条离散周草稿均在 Activity 重建后保留，Fake Store 禁止任何提前写入。

保留的产品边界：不支持在保留来源依赖安排的同时删除来源，也不提供“来源+规则自动级联删除”组合操作。用户须先在独立课程草稿中明确移除关联规则、预览保存，再删除/替换来源；取消或任一步失败时，已存状态仍保持引用完整。没有按名称、文件名或新导入顺序猜测新系列身份。此安全边界属于本轮交付，而非隐式丢弃安排。

本轮没有新增 Android 来源替换/删除故障注入实机验收、NAS 真 WebDAV 或 X08E 截图复拍；来源事务失败/重开专项为 Node 正式 SQLite Workspace，Android 的新增来源 hash 入口经编译验证。此前未修改 main 已复现的 1024 增长缓存断言失败继续保留原记录，未重跑或放宽断言。

四项修复在上述明确产品边界下可进入人工 Review；仍保持 Draft，不自动合并、部署或发布。

## 第二轮 P1 修复验证（2026-10-29，基线 3a68bd6）

- Node 隔离正式 SQLite Workspace：同来源原始同名 A/B/C 一次提交 A 自定义时间、B 地点、C 教师、公共备注、新增/显式删除安排及颜色，revision 仅增加一次，返回哈希等于预览。反向时间、错误课程归属、过期 revision 和注入 COMMIT 前异常均拒绝整次操作，原哈希不变。保留同课程未删除时段、其他课程及 opaque 未知记录，重开后状态一致。原单系列接口委托新入口。
- JVM ParentDeletionTest：删除排队后加入停用依赖，执行时拒绝，显示未乐观移除，重读后与持久状态一致且 error=false；启用/停用依赖均受保护；清除依赖后删除成功；旧格式无引用删除成功。
- `test-vectors/pr70-multi` 保存 Node 实际事务后的完整规范对象、业务哈希及两个周的最终投影。Kotlin 实际解码并逐项核对时间、地点、教师、备注及 RGB，与 Node 完全一致。固定输入没有网络、日用数据或协议格式变化。
- Phone 独立 testbed 删除专项 2 PASS（49.649 秒）：普通父课程可删除；启用/停用引用父课程的详情不显示普通删除按钮，直接业务删除拒绝后显示和存储一致；真实 Room Writer 拒绝引用父课程且哈希不变，明确移除依赖后成功删除。
- 最终 JVM 定向 21 PASS（ParentDeletionTest 2、SharedCourseScheduleTest 5、CourseBatchTest 14），Phone/X08E Debug 构建与隔离测试 APK 构建 PASS。Node 定向集合 36 PASS；写入失败专项另包含在新增多系列事务测试内。

额外尝试了完整课程管理 UI 测试，其菜单/弹窗匹配在测试宿主未稳定完成，多次失败未计为 PASS；本轮以独立父课程 UI 与真实 Room 专项作为直接证据，没有通过删除断言制造通过。没有重跑 X08E 设备显示、NAS 网络、1024 增长或 soak；此前 main 的缓存读取断言失败记录不变。

新接口不提供跨来源同名合并、歧义系列扩组或隐式删除；手动多 ID 必须明确选择。来源生命周期的上轮安全边界、显式颜色意图和有限教学周确认均保持不变。上述本轮范围可进入人工 Review；继续保持 Draft，不自动合并、打 tag 或发布。完整课程管理 UI 测试宿主的窗口稳定性仍作为验收限制保留。

## 第三轮覆盖/阶段校验修复（基线 677ed90）

实际运行：Node 受影响集合 **41 PASS、0 FAIL/SKIP**；Kotlin ClassEditSessionTest 3、CourseBatchTest 14、PeriodPhaseReviewTest 1，共 **18 PASS**；Phone/X08E assembleDebug PASS。

Node 新回归验证：保留已有名称/地点/教师/备注，仅改时间；仅改教师保留时间和地点；teacher:null 只清除教师；reset 清整条；公共字段优先于独立同字段且保留其他字段；空 patch/空公共清除保持业务哈希；自定义/节次模式切换清理互斥字段，部分时间/矛盾时间/不完整清除拒绝；旧 applyEdit 完整替换仍清除未给字段。正式 SQLite Workspace 另验证原字段持久保留、预览/保存哈希相同、非法组合附带颜色不产生半提交、过期拒绝与重开一致。前轮多系列失败/COMMIT 故障、引用保护和未知记录测试继续通过。

阶段回归验证：所有实际课程位于首阶段时，不相关未来阶段缺节次仍可编辑；实际涉及阶段缺节次则拒绝；非连续第 1/3 周分别使用 08:00 和 10:00，第 2 周缺节次不影响；有限替换加入第 2 周后拒绝；无可靠作息/有限范围拒绝。纽约 2026-09-13 周日 23:30 原实例在中国已是周一，校验仍使用纽约学术日期，选择原周日阶段。

`test-vectors/pr70-phases` 保存原状态、合法覆盖状态、实际阶段缺节次的非法候选基线，以及 Node 最终哈希/投影。Kotlin 使用同一文件实测接受合法候选、拒绝缺节次候选，最终哈希和中国时区投影逐项一致，并独立验证纽约/中国日界线。初次对照发现 Kotlin 也有全阶段限制，新增测试先失败，修复共享核心后通过；不是将静态推断报告为一致。

本轮没有安装或升级任何设备，没有重跑 UI、NAS、X08E 设备显示、增长或 soak。修改共享核心只涉及课程时间合法性，已用上述日期 oracle 和相关 JVM 测试覆盖；未新增用户交互。前轮完整管理 UI 窗口稳定性限制和 main 已复现的增长缓存断言失败均继续保留。本轮 P1/P2 范围满足进入人工 Review 的条件，仍保持原 Draft PR，不自动合并或发布。

## 最终继承 linked 移动修复（基线 e226980）

复现测试先证明：仅给 weekday:4、原课程从周一 9–10 节继承时间模式时，旧候选校验没有抛错；修复后目标周四缺节次的预览和正式 Workspace 保存均拒绝。

最终实际结果：Node 受影响集合 **45 PASS、0 FAIL/SKIP**；Kotlin ClassEditSessionTest 3、CourseBatchTest 14、PeriodPhaseReviewTest 1、InheritedMoveReviewTest 1，共 **19 PASS**；Phone/X08E assembleDebug PASS。

- 只移动星期、保留教师和地点覆盖；目标周四有效时实际移动到周四并使用新阶段 11:00–12:40（纽约时区），不是周一旧时间。目标缺节次时拒绝。显式 linked 仍拒绝，自定义覆盖及原始自定义实例不误受节次限制。
- 多个周只有后一个目标缺节次时整次拒绝；有限替换只启用第 1 周时允许，加入第 3 周后拒绝，被抑制的原实例不参与校验。另验证同名同系列但不同来源/时区的课程按各自作息校验，不误用另一来源阶段。
- 正式 SQLite Workspace 中，同时提交另一系列教师、公共备注、新安排和颜色；目标日期非法时 revision 和整个 frame 完全不变，原课程仍在周一。修正目标作息后预览/提交哈希相等，教师、地点、原始导入、其他课程与未知记录保留，重开一致。
- `test-vectors/pr70-inherited-move` 是本问题专用的小型向量，不重新生成无关向量。Kotlin 对同一输入接受合法移动、拒绝缺节次移动；最终哈希和中国时区投影与 Node 相等。此向量还复现 Kotlin 原有显示分钟校验误拒绝合法跨午夜 linked 区间的问题，修复一条条件后通过，仍保留实际时间区间的严格校验。

本轮未安装设备或改变安装状态，未重复 Phone/X08E UI、Widget、NAS、增长或 soak。必要的 Kotlin 直接依赖改动经同字节向量与相关 JVM、构建验证；没有新增 UI 交互。前轮 UI 测试宿主窗口稳定性限制、main 已复现的增长缓存断言失败继续保留原记录。本轮范围达到进入人工 Review 的条件，PR 状态仍保持 Draft，不自动合并或发布。
