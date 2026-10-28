# Sync v3 通用共享状态（DPV3/4、DPXS/1、DPXR/1）

本格式显式启用，沿用现有 Epoch/DAG、不可变对象、共同基线、Checkpoint、Transition、LATE、缓存、Pending 和精确回读回执。DPSS/1、DPC2、DPV3/3 的既有字节解释完全不变。

## 隔离与版本

新数据集使用 `dormpanel-sync-v3-shared/`，旧数据集继续使用 `dormpanel-sync-v3/`。两者相对路径规则相同，但访问器必须显式选择格式，读取/发布时核对对象版本；不可将既有绑定直接改为新目录。Descriptor、Genesis、Commit、Transition 都使用 DPV3 版本 **4**。其他外层字段、引用形状、长度预算和 SHA-256 校验规则沿用 DPV3/3；同一 descriptor 下的历史必须版本一致。

Android 私有 Journal 的帧版本升级为 3；Node 独立 Journal 为 3，正式 Workspace 帧为 2。旧客户端打开新帧时拒绝版本，不能在本机静默重写扩展。新客户端仍读取旧帧；扩展帧禁止降级。私有 SQLite 元数据布局和缓存布局未更换。

## 完整 RAW（DPXS/1）

所有整数为大端有符号 I32，长度必须非负并在分配前核对剩余输入。顺序如下：

| 字段 | 编码 |
|---|---|
| magic | ASCII `DPXS` |
| version | I32 = 1 |
| legacyLength | I32 |
| legacy | 原 DPV3/3 的八集合 RAW，字节及领域校验不变 |
| recordsLength | I32 |
| records | 下述 DPXR/1 |

RAW 总预算仍为 16 MiB，扩展块不额外提升对象或同步轮次预算。必须消费精确输入并通过重新编码精确相等验证。DPV3/4 的所有非空状态都用 DPXS/1，包括扩展集合为空的状态。DPV3/3 不接受 DPXS。

业务哈希继续使用 `SHA256(ASCII DPBS || I32(1) || canonical RAW)`，仅将 sources.importedAt 归零；扩展原始规范字节进入哈希。旧状态的哈希不变。空扩展格式与旧八集合状态的哈希不同；业务合并允许空扩展的无损升级，但不能直接转换活动绑定。

## 通用记录块（DPXR/1）

顺序：ASCII `DPXR`、I32(1)、I32(count)、各记录。记录按 typeId、recordId 两级 ASCII 字节升序排列。

| 字段 | 编码与限制 |
|---|---|
| typeId | I32 长度 + ASCII；`[a-z][a-z0-9]*(?:[.-][a-z0-9]+)+`，最多 96 字节 |
| recordId | I32 长度 + ASCII；`[A-Za-z0-9][A-Za-z0-9._:-]{0,199}` |
| schemaVersion | I32，1..2147483647 |
| required | I32，0 或 1 |
| dependencyCount | I32，0..16 |
| dependencies | 长度前缀 typeId，升序、不重复 |
| payload | I32 长度 + 原始字节，0..65536 字节 |

最多 4096 条，整个 DPXR 块最多 1048576 字节。重复 `(typeId, recordId)`、非法文本、版本、标记、非规范顺序和尾随字节全部拒绝。空 payload 不等于删除；类型决定空值是否有意义。删除以记录缺失表示，依赖已验证共同基线进行三方合并，不用时间戳裁决。

payload 无通用 JSON 语义。Kotlin 使用字节列表值对象；Node 在持久 Workspace 中使用小写偶数长度 hex，在类型边界转换为 Buffer。这样 JSON/structuredClone/SQLite 压缩不会改变 payload。类型的 codec 自行定义规范字节，不能绕过 Registry 校验写入。

## 类型接入、能力与合并

产品注册装配集中在 Kotlin `SharedTypes.kt` 与 Node `shared-types.mjs`，新增普通类型只在此注册。Kotlin 实现 `SharedTypeAdapter`，Node 提供普通适配器对象。声明 typeId、schemaVersion、maxPayload、可选 maxRecords（默认 4096）、validate；适配器可提供自己的有类型 encode/decode/投影函数。`relatedKeys` 声明与既有业务记录的关联组件；Node 可用 prepareRelations 一次建立关系索引，避免逐记录全表扫描。

Registry 的 replace/remove 负责规范校验和受保护的类型修改；新增类型只修改注册装配与该类型实现，不修改 RAW、传输、历史或回执。默认记录级三方合并：相同修改合并、单端变化合并、双端不同修改/删除对修改返回冲突。关联父记录变化与子记录修改组合为一个冲突决定。保留现有来源、学期、日期等组件规则。

`required=false` 且无未知依赖的独立可选记录可原字节透传；这不代表客户端理解其业务。未知类型/版本不得进行业务编辑或删除。required 类型不受支持、依赖能力未知或依赖出现不支持版本时，保守阻止整个状态的业务写入。dependencies 是能力声明，业务实例关系由 relatedKeys 与 validate 检查，不能用可选标记隐藏对已有业务语义的依赖。

例：Node 注册 `{typeId:'example.note',schemaVersion:1,maxPayload:32,validate(record, records){ /* 规范 payload 与引用检查 */ }}` 到 Registry，并传给 V3Engine 第三个参数。Kotlin 创建相同的 SharedTypeAdapter，传入 SharedTypeRegistry，再传给 V3Engine。测试中的 test.note 使用这一方式写入、发布并由旧注册表客户端透传；核心没有 test.note 分支。

## course.color/1

类型校验器要求记录 ID 为 64 位小写 hex。记录 ID 为 PR68 逻辑颜色键 UTF-8 的 SHA-256 小写 hex。键沿用 UTF-16 code-unit 长度前缀：本地为 local + entryId；普通导入为 import + sourceId + 原始课程标题；原始同系列标题不一致时为 ambiguous + sourceId + seriesId。遵循 CourseBatch 分组，人工标题覆盖不改变身份，不同来源同名不合并。

payload 为 `00`（自动）或 `01 RR GG BB`（手动 RGB）。自动 RGB 为 recordId ASCII 的 SHA-256 前三个字节，无设备分配缓存。恢复自动会保存 `00`，也是可合并的真实修改。颜色为弱课程关联，允许保留已删除课程的颜色；课程删除与并发改色仍产生关联冲突，不能悄悄选边。

受保护接口：Kotlin V3Engine.setCourseColor、RoomScheduleStore.setSharedCourseColor/putSharedRecords（业务哈希乐观检查）；Node V3Engine.setCourseColor、Workspace.setSharedCourseColor（revision 与正式事务/权限保护）。完整 Phone/FPK 管理 UI 留给 PR70/71。

Phone SharedColorMigration.preview 只读取 PR68 已确认设置（包含不确定 SharedPreferences 内存值的隔离），展示原 RGB 与共享自动 RGB，手动值转 RGB、自动值转确定性自动模式。confirm 经正式 Store 原子保存；不清理旧偏好，因此取消、版本过期或失败不丢原配置。

## 存储、恢复与迁移

扩展与八集合一起进入正式 Room/Workspace 控制帧，同一 SQLite 事务保存本地状态、同步 capture、基线和回执。Android DAO 编辑重建状态时显式保留 sharedRecords。Node 正式 Workspace 的普通编辑也执行未知必需能力和未知可选记录只读保护。历史原对象、快照缓存、Checkpoint/LATE、回执重放使用完整状态 codec；历史比较和分页支持通用 sharedRecords。SQLite 备份/恢复包含相同控制帧，不新建 sidecar 或每类型表。旧 DPSS 导出明确拒绝扩展状态，避免截断式导出。

`previewSharedUpgrade` 接受当前已验证的旧 DPV3/3 精确 capture，在**独立、未绑定目标 Store**持久化预览、源 capture/hash、目标 descriptor/Genesis 精确字节与新 datasetId。预览包含八集合计数和扩展计数。取消仅标记预览；确认重新检查源 capture，持久化绑定与精确候选后复用既有发布恢复。失败可继续原候选，不能覆盖不确定对象。目标回读确认状态为 TARGET_READBACK_CONFIRMED，**不报告原绑定已切换或旧 Writer 已停写**。

源数据集、历史、绑定和未发布编辑均留在源 Store。源 Writer 之后的发布不会自动进入新数据集，也不能当作其已停写；需要再次审查/迁移。不得把本接口用于不经确认的日用升级。正式设备绑定没有自动迁移入口。

新建隔离业务 Store 可通过 RoomScheduleStore.prepareV3(..., sharedFormat=true) 或 fnOS activeV3.initializeSharedDataset(expectedRevision) 显式启用。新建目标后需显式选择该目标；现有数据集不会自动升级。Todo、Memo、偏好、附件等类型均未启用同步。

## PR69 审查修复：接纳、颜色迁移与可信源

- DPV3/4 完整状态 codec 统一调用 SharedTypes Registry.validate；受支持类型的 payload/身份/资源校验失败时拒绝对象。历史回读、缓存快照和发布编码均复用此入口，合并另校验输入/输出。validate 与 writable 分离，未知独立可选记录仍可读取和保留。有效线格式及固定向量未改变。
- SharedColorMigration.Preview 增加 conflicts（共享/本地原记录、业务身份及课程标题）与 automaticVisualChanges。records 默认仅增加不存在的记录；相同记录无修改，差异保留共享。decide 必须收到每个冲突的 KEEP_SHARED / USE_LOCAL / SKIP，缺少决策则整次拒绝。confirm 将批准后的完整集合和 expectedHash 交正式 Store 单事务保存；旧偏好不清理。模式比较依据原记录，自动与手动不会因为显示 RGB 相同而视为相同设置。
- SharedCourseColors.display / Node display 返回 DEFAULT、AUTO、MANUAL 或 UNSUPPORTED 及展示 RGB。未来 schema 的备用 RGB 仅用于显示，原记录不改写；rgb 兼容入口委托 display。Registry.replace 也拒绝覆盖同身份的未知既存版本。
- 升级预览/确认现在必须提供当前源 V3Engine；不再接受只有 sourceCapture 的不受保护调用。校验源绑定、已验证 baseline 节点/hash、当前本地与基线业务相等、revision、已观察 settled/frontier 和无未决 Pending/冲突/初始化/换代/历史验证任务，保存 sourceEvidence。确认前及目标回读后重新核对；源出现未发布修改或预览过期时停止。重开目标继续同一候选与 datasetId。

这只证明当前源 Writer 已验证、已观察且无未发布修改的业务状态，不证明 WebDAV 全局历史完备或未知旧 Writer 已停写。宿主须持有正式源 Store 生命周期与权限边界；本 PR 不自动接线日用迁移。旧草稿预览没有 sourceEvidence 时拒绝确认，保留源和目标，需宿主重新审查，不能补造证据。

## Registry 生命周期与历史链路一致性

V3Engine 的注入 Registry 是本次运行的业务语义上下文；引擎创建 V3History 时传递同一实例。History.value、对象接纳及 merged 的所有输入/输出验证和多分支合并都使用该实例；外部传入的历史视图用于换代时先绑定引擎 Registry。历史索引的 hash/祖先证明仍可复用，但取出完整业务状态时必须重新按当前 Registry 校验，不以旧的索引证书代替领域校验。

Kotlin ScheduleV2Plan（v3 复用的冲突方案）与 Node mergePlan 增加带默认值的 Registry/adapters 参数，成对分支检测、远端方案、强制冲突与决策重放都传递该上下文。合并同 key 的冲突摘要时并集保留关联 keys，避免较短摘要丢掉历史层已发现的关联范围。

codec 的 encode/decode/stateBytes 接口可显式传 Registry（Node encode/decode 为 `{registry}` 选项）；缺省仍使用产品装配，旧调用保持兼容。业务哈希同样可带上下文，字节规则不变。重建引擎时必须重注入同样的产品/测试 Registry，并复验持久本地、基线、冲突与待确认状态；回执解码、发布和恢复使用引擎上下文。注册表不写入线格式，不由远端提供，也不把测试类型加到产品默认装配。

此前合法透传的未知记录在升级为已知类型后若校验失败，拒绝业务接纳并保留原材料；不能为继续同步而忽略失败。新增类型仍只需实现适配器与产品装配，不需更改 RAW、历史或传输核心。

## 课程颜色修改的 Registry 上下文

SharedCourseColors.change 增加可选 Registry 参数，默认仍为产品装配，旧的纯业务调用兼容。V3Engine.setCourseColor 显式传入引擎的 sharedRegistry；RoomScheduleStore.setSharedCourseColor 统一委托引擎入口，将 expectedHash 校验放在同一次 edit 事务内，并按同一 Registry 计算业务哈希。没有删减 writable、未知依赖或既存颜色 schema 的保护。

本次仅修复 Kotlin 业务辅助函数的上下文传递，不修改类型身份、颜色模式、线格式、历史、网络、回执、Phone 迁移决策或 fnOS 实现。
