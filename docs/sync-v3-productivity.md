# Sync v3 Todo 与备忘

共享 Todo 和备忘以 DPV3/4 的正式 Writer 为权威来源。X08E、Phone 和 fnOS 使用同一组共享记录，随完整业务状态参与业务哈希、不可变对象、Checkpoint、缓存、回执与应用备份。外层对象和通用记录格式沿用 [DPV3/4、DPXS/1、DPXR/1](sync-v3-shared-state.md)。

## 类型规范

三种类型均为 schemaVersion 1，能力声明如下。业务身份为 `(typeId, recordId)`。recordId 是小写、带连字符的 36 字节 UUID 字符串，禁止全零。新建操作在提交前分配 UUID，整次重试保留原 ID。

| typeId | required | dependencies | payload 字段顺序 | 单集合上限 | payload 上限 |
| --- | --- | --- | --- | ---: | ---: |
| `todo.list` | true | `[]` | 名称 | 128 清单 | 260 B |
| `todo.task` | true | `["todo.list"]` | 清单 UUID ASCII 36 B、顺序 I32、完成标志 U8、正文 | 2048 任务 | 4141 B |
| `memo.text` | false | `[]` | 标题、正文 | 256 备忘 | 33032 B |

- 文本字段为大端 I32 字节数及严格 UTF-8。名称和标题最多 256 B，任务正文最多 4096 B，备忘正文最多 32768 B。名称、标题及任务正文至少包含一个 ASCII 空白集合（U+0009–000D、U+0020）之外的字符。备忘可用空正文。所有字段禁止 NUL 及未配对代理项，保留 CR、LF、BOM、前后空白及 Unicode 原始形式。
- 任务顺序为 0 至 2147483647。每个清单按顺序值及任务 ID 升序排列，允许两个设备并发分配相同顺序值。清单和备忘按 ID 返回。完成标志只能为 0 或 1。
- 任务必须引用同一状态中的 schema 1 清单。删除由记录缺失表达。编辑 API 删除清单时须提供精确的全部子任务 ID，空清单用空集合。出现未知任务 schema 时拒绝整张清单删除。
- DPXR 上限为 4096 条、1 MiB，DPXS RAW 上限 16 MiB。写入和导入预检包含其他类型与课表已有的预算占用，整次失败时保留原数据。固定字节与业务哈希见 [跨语言向量](../test-vectors/sync-v3-productivity/vector.json)。

## 合并和兼容

同一清单下的不同任务和不同备忘各为独立记录。相同修改合并。对同记录的不同修改或删除与修改保留三方内容。任务通过 Registry 声明所属清单为关联键。清单变化与并发子任务变化走关联决策，避免产生孤儿；清单改名与另一端子任务编辑也可能要求人工选择。

符合 DPV3/4 规范且缺少 Todo 适配器的旧 Writer 仍可读取数据集、访问原有课表和原样保留未知记录。含 Todo 必需能力时，旧 Writer 的 Registry 会拒绝本地业务写入，发布检查也会拒绝任何仍含 Todo 的旧 Registry 合并候选，包括含孤儿任务的候选。继续编辑含 Todo 的数据集需要升级 Writer。数据集只有备忘等独立可选记录时，旧 Writer 可以继续编辑课表并透传原字节。DPV3/3 和 v2 的共享效率写入口拒绝写入。

PR87 审查草稿 `a6b8cb3` 的可选 Todo 字节仅用于早期隔离测试。schema 1 使用本页唯一的能力声明，修复版拒绝早期可选 Todo 字节。早期测试目录和本地绑定可以保留用于核对，重新验收需创建全新的隔离 DPV3/4 目标并重新生成测试数据。已写入的不可变对象保持原样。

冲突视图返回现有冲突 token、版本以及带类型和业务 ID 的 base/local/remote 记录。删除侧用 null，双方文本包含原始换行。UI 可直接比较字段，备忘可做逐行差异显示。选择仍交正式版本化冲突入口 `resolveV3` / `activeV3.resolve`。

## PR88/89 接口

- Android 从宿主取得当前 `RoomScheduleStore`。用 `loadProductivity` 读取清单、任务、备忘、版本、写保护原因及冲突。向 `editProductivity(version, edit, callback)` 提交 `ProductivityEdit`，包括明确的 `SetTaskCompleted(id, completed)`。新建任务提供所属清单、位置以及目标完成状态。版本包含 datasetId、本地 revision 和当前完整业务哈希。回调成功表示本机事务已保存。视图的 `pending` 反映整个数据集与正式基线的差异。
- Android 观察者在主线程调用 `observeProductivity` 并在生命周期结束时关闭句柄。订阅按 Writer 事件重读，包含远端应用和冲突决定。仅效率记录变化时保留课表投影。共享控制器通过独立的 reload 通知重读 pending、revision、冲突和写保护快照，呈现变化才通知状态 UI。该通知沿用现有同步调度，提交仍由既有 Job 发布。
- fnOS 调用已通过管理权限和 Workspace 准入检查的 `workspace.productivity()` 读取视图。把视图的 `{ datasetId, revision }` 交给 `workspace.saveProductivity(edit, version, validateContext)`。宿主提供同步事务内的上下文校验以复核当前工作区、访问权限或保存范围。操作名为 `create`、`renameList`、`deleteList`、`editTask`、`setTaskCompleted`、`deleteTask`、`renameMemo`、`editMemo`、`deleteMemo`。`create` 的 `value` 含 `typeId`、`id` 和上述领域字段。任务通过 `listId` 过滤。修改后可用活动 Workspace 的既有同步能力单独发布。
- 读取或编辑移到当前 datasetId 时，应重新取版本和内容。编辑中的网络错误与本地保存结果分别呈现。遇到本地提交不确定，重开 Writer 后以同一身份重读；遇到上传回读不确定，沿用已持久化候选和正式恢复流程。

## X08E 旧数据导入

用 `LegacyProductivityImport.scan(context, callback)` 在旧 Store 的串行队列上只读扫描旧库。每个 owner 按 Todo 与 Memo 各生成可选预览项。Todo 保持旧 `position, id` 顺序和完成状态，Memo 保持原文本。Timer 专属 owner 可以被扫描，但其计时字段与空内容没有共享记录。超出单项限制的预览项返回错误，不载入超大的正文。扫描期间若其他连接提交，则要求重新扫描。

`preview(scan)` 是纯转换，调用者可传名称。导入身份由版本化命名空间、旧 owner ID、类型和旧任务 ID 稳定导出，名称、内容、时钟与 Writer 无关。对相同来源及内容再次预检使用相同记录；如共享端已有同身份的不同内容，导入报冲突并保留双方。

候选关联原始数据库路径和所选 owner／类型的 SHA-256 内容指纹。Todo 指纹包含 owner 是否存在、任务 ID、正文、完成状态及原始 position；Memo 指纹包含完整正文。未选中的 owner 或同 owner 的另一类内容在预览后变化时可独立保留。

`check(store, version, preview, selected, callback)` 预检所选内容、全量 RAW 预算、目标版本及源指纹。取消时丢弃候选。确认期间，应用内旧 Store 的串行队列先完成已入队的写入，再等待共享事务结束；共享事务实际保存前复核目标版本与源指纹。源内容变化或 owner 被删除时要求重新扫描。重复确认过期候选会失败。重新预检同一批相同内容后确认，记录身份保持稳定。

### PR88 绑定前检查

1. `confirm(store, checked, callback)` 成功返回 `Confirmed`，表示候选已提交到本机正式共享 Store。保留凭证，旧内容和卡片绑定继续可读。
2. 持久化绑定前，用当前活动的 `RoomScheduleStore` 调用 `validateBinding(store, confirmed, callback)`。它在旧 Store 串行队列上重读所选源指纹，再取得本机 Writer 的有效快照。检查活动 datasetId、已导入记录的原始规范内容，以及选中清单的任务成员。其他课程、清单和备忘的修改无需匹配导入时的版本。
3. `Verified(version)` 只表示此次读取时，所选源和本机共享目标与确认记录一致。PR88 应在实际持久化绑定的入口重新调用校验，通过后用当前数据集身份及记录 ID 保存卡片绑定。应用内协调旧写入和绑定操作的准入，避免把较早的校验结果用于晚到操作。卡片绑定持久化成功才表示切换已完成。

`TargetChanged` 返回变化的记录键，包括选中清单下的新任务。保留原绑定，重新读取共享目标后可展示差异，并让用户明确选择连接现有内容。`TargetMissing` 返回缺失记录键。`DatasetChanged` 表示活动数据集与确认目标不一致，即使记录 ID 和内容碰巧相同也要停止。`SourceChanged` 要求重新扫描，`SourceUnavailable` 或 `TargetUnavailable` 表示本次读取得不到有效证据，包括共享目标读取失败、格式不支持、初始化未完成、冲突或恢复保护。上述分支均保留原绑定和两端内容。

旧库、共享库和卡片绑定各自持久化。外部 SQLite 连接、同步接收或外部 Writer 可以在最后一次检查后提交。校验只针对本机已接收并可读的权威状态，无需触发 WebDAV。用户批准连接已变化内容时，PR88 应针对用户看到的最新目标及相同数据集重新检查，避免以旧导入凭证覆盖新内容。

## PR87 定向验证记录

初版验证日期 2026-11-17，基线 `011224c`。下表记录审查修复前的草稿验证。

| 检查 | 实测结果 |
| --- | --- |
| Kotlin 定向测试 | 19 项通过，覆盖固定字节、业务哈希、任务顺序、孤儿拒绝、关联删除、同记录冲突、回执恢复与既有本地效率行为。 |
| Node 定向测试 | 35 项通过。另在 fnOS 测试机的 Node 22.23.5 上以临时 SQLite Workspace 执行本类型的 3 项用例，通过回滚、重开、回读恢复及备份重开。 |
| Android 构建和导入 | X08E testbed APK 与 instrumentation APK 构建通过，Phone Debug Kotlin 编译通过。API 28 模拟器导入与本地效率持久化 3 项测试通过。使用独立旧库和共享库，检查两位 owner、同位置顺序、原始换行、Timer 共存、超长拒绝、取消、选择确认、重复确认和旧库逐行一致。 |
| 隔离 WebDAV | Android API 28 模拟器的正式 Room Writer 在本地创建清单、任务、备忘，测试本地提交回滚，并在真实 PUT 成功后中断回读。重开 Room 后恢复原候选及回执。主机 Node 正式 Workspace 从同一个 WebDAV 目录接收，修改任务正文和完成状态及多行备忘，发布并回读。新的 Android instrumentation 进程接收修改，观察者收到通知，课程镜像没有重写。两端最终业务哈希均为 `9be92828392c72e089d912068a08f1cbef3031ceed941a29d29846b4dc5beecf`。 |

WebDAV 往返使用隔离的 WsgiDAV 目录、合成凭据、API 28 testbed 和独立 Node Workspace。Android 测试使用 `ProductivityWebDavAndroidTest` 的 `seed`、`receive` 阶段，中间运行 `fnos-app/scripts/verify-productivity-webdav.mjs` 并传入 seed JSON。fnOS 实机验证范围为隔离 Workspace 的本地事务和恢复。日用应用数据、绑定和正式 fnOS 工作区均未纳入验证，未进行正式 UI 或 FPK 安装验收。

额外选入的 `publishes1024AndRotatesWithoutGlobalCommitLimit` 在第 32 次发布后的检查失败，快照缓存读取期望 0、实测 1。在 `main` 的独立检出仅运行原用例至第 32 次发布，复现相同断言。本 PR 的定向验收不包含历史增长全量测试。

## PR87 审查修复验证

- Kotlin 定向测试 20 项、Node 定向测试 22 项通过。两端旧 Registry 实际读取含 Todo 的数据集并拒绝本地课表编辑，清单删除及任务新增的分岔没有产生新的 PUT。新版保存关联冲突。仅含独立备忘的旧 Writer 仍可发布课表编辑。
- API 28 模拟器的旧库导入与持久化 3 项通过。覆盖预览后的新增、修改、完成、删除、排序值改变、备忘正文变更、owner 删除、排队写入、未选内容变化、目标版本过期及绑定前重新核验。X08E testbed APK 构建与 Phone Debug Kotlin 编译通过。
- 使用新的隔离 DPV3/4 目录完成 Android Room 与主机 Node 的真实 WebDAV 往返。两端业务哈希均为 `977a1c8a46aea033eeaa3f26e11bbe0eba04455607fea40cc3579de9053bcfa7`。两阶段测试包含回读中断后重开、任务和备忘跨端更新及效率订阅。
- 独立的 API 28 控制器测试通过。真实 WebDAV 上的两台隔离 Writer 编辑同一备忘，状态订阅收到待发布、冲突及解决后回执，课表完整刷新计数为 0。
- 审查修复使用主机 Node 和 API 28 模拟器。实体 X08E、Phone 及 fnOS 实机 FPK 未重测。历史增长用例保留初版的基线记录。

### 绑定检查补充验证

X08E testbed APK 和测试 APK 构建通过。API 28 模拟器执行 `LegacyProductivityImportAndroidTest` 与 `ProductivityPersistenceTest`，共 3 项通过。隔离 Room 测试覆盖原样通过、目标任务正文及完成状态变化、备忘正文变化、所选清单新增任务、目标删除、同 ID 同内容的不同 datasetId、无关记录变化、源变化、两端读取异常和旧库逐行一致。测试仅用隔离 WebDAV 初始化两个目标，其余场景通过正式 Room 业务接口修改及读取本机状态。
