# DormPanel Append-only Schedule Sync v2 协议

本规范定义跨平台文件与合并语义。Android、fnOS FPK、Go/Node.js/Python 编辑端地位相同；不存在设备优先级。协议不含 Kotlin/Room/Java 序列化对象。PR38 增加 fnOS Writer，实现及验收范围见 `docs/pr38-fnos-verification.md`，不改变本规范字段与语义。`tools/sync_v2_vectors.py` 是仅使用 Python 标准库的独立二进制编码/框架校验参考；完整领域校验以本文为准，不能只验证外层哈希就采纳课程。

## 存储与身份

用户选择的已有 WebDAV 共享目录下创建独立 `dormpanel-sync-v2/` collection。只接受直属文件 `commit-<commitId>.dpc`；不解释 URL 为命令，不下载 locator，不跟随跨源或路径改变的重定向。目录内其他文件、子目录、重复 href、不可读取的 propstat、身份不匹配均报错。v1 head、snapshot、探测文件留在父目录，v2 不修改它们。

datasetId 在创建数据集时生成并永久保留。commitId 每次新发布用安全随机 UUIDv4，writerId 每个独立安装/编辑器持久化一个安全随机 UUIDv4；重启不更换。接收端接受标准小写 UUID `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`（兼容既有身份，不靠 UUID 版本判胜负）。不得克隆 writer 存储到另一活动编辑端；不得重复使用提交路径。UUID 不是用户身份或授权凭证，服务器仍负责认证/目录权限。

课程、事件、sourceId、seriesId、实例 id 是不透明非空字符串，不能按名称、位置、导入文件名猜测同一记录，不能重生成既有身份。导入实例的 `originalStart` 和覆盖记录 `anchorDate` 保持原始身份，编辑移动日期不改变它们。`uid` 可为空，不能用它代替实例 id。

## 字节布局

所有整数为**大端、有符号、二补码**。I32 为 4 字节，I64 为 8 字节。长度/数量必须非负，在分配内存前校验。SHA-256 为 32 个原始字节。文件没有 BOM、换行、压缩或尾随数据。

外层顺序：

| 字段 | 编码 |
| --- | --- |
| magic | 4 字节 ASCII `DPC2`，十六进制 44 50 43 32 |
| protocolVersion | I32，必须为 2 |
| datasetId | 固定 36 字节小写 ASCII UUID |
| commitId | 固定 36 字节小写 ASCII UUID，必须等于文件名 |
| writerId | 固定 36 字节小写 ASCII UUID |
| parentCount | I32，0..64 |
| parents | parentCount 个固定 36 字节 UUID，严格升序、无重复、不得引用自身 |
| snapshotLength | I32，完整内层快照字节数，至多 16 MiB |
| snapshot | 以下定义的完整课程快照 |
| sha256 | 从 magic 开始到 snapshot 末尾（含全部元数据、长度和父引用）的 SHA-256 |

整个提交至多 16 MiB + 8192 字节；最小有效长度也由字段布局与内层空集合决定，接收端首先拒绝小于 200 字节的输入。

内层使用明确的便携 DPSS/1 数据编码，**不是 v1 远端协议**。其 parentRevision、generation、createdAt 是固定填充值，不承担 v2 因果语义，不显示成真实发布时间：

`ASCII DPSS | I32 1 | I32 payloadLength | payload | SHA256(payload)`。

payload 依次为：`T datasetId, T commitId, N parentRevision=null, I64 createdAt=1, I64 generation=1`，然后依次为下表的 8 个集合。内层 datasetId/commitId 必须与外层相同，固定值必须精确匹配。校验和、长度或任何字段不符均拒绝。

基本类型：

- `T`：I32 UTF-8 字节长度 + 严格 UTF-8 字节。禁止无效编码、未配对代理字符；每字段至多 1,000,000 UTF-8 字节。保留 Unicode 原文，不执行 NFC 或大小写归一化。空字符串合法时编码为长度 0。
- `N`：单字节 0 表示 null；单字节 1 后接 T 表示非 null。空字符串与 null 不同。其他 tag 非法。
- `J`：单字节 0 表示 null；单字节 1 后接 I32。数字没有浮点、NaN 或文本表示。
- `A(...)`：I32 记录数 + 按指定顺序编码的记录，无字段名或分隔符。
- 时间戳为 I64 Unix epoch 毫秒，可为负；不用于决定版本胜负。民用日期使用 ISO LocalDate 文本（通常 `YYYY-MM-DD`）；时区为 IANA ZoneId（如 `Asia/Shanghai`），接收端不支持时区必须拒绝应用。节次时间为 ISO LocalTime 的最短 Java/ISO 表示：`HH:mm`，非零秒追加 `:ss`，非零纳秒追加 3/6/9 位小数（使用能够完整表示值的最短一组）；不含偏移。实例 start/end 表示绝对时刻，profile 时间表示该时区墙上时间。
- 字符串排序使用 UTF-16 code unit 的词典序（不是语言地区排序）；Python 可用 `s.encode('utf-16-be')` 作 key。UUID ASCII 升序。数字用数值升序。所有集合必须符合规范排序，解码后重新编码必须与输入字节完全相同。

## 完整快照集合与字段顺序

下表中的每行都是一个 `A(...)`，并按照表中顺序出现在 payload 中。每条记录的字段顺序严格如下：

| 集合 / 上限 / 排序 | 一条记录的全部字段 |
| --- | --- |
| events / 10,000 / id | T id, T title, I64 start, I64 end, T note |
| entries / 10,000 / id | T id, T title, I32 day, I32 startMinute, I32 endMinute, T location |
| sources / 16 / id | T id, T displayName, T filename, T kind, N locator, N calendarName, T sha256, I64 importedAt, T timezones, I32 occurrenceCount, I64 firstStart, I64 lastEnd, N termKey |
| imported / 50,000 / id | T id, T sourceId, T seriesId, N uid, I64 originalStart, T title, I64 start, I64 end, T timezone, T location, T description, N periodLabel |
| dayAdjustments / 1,000 / date | T date, T mode, J weekday, T label |
| classOverrides / 5,000 / key | T key, N sourceId, T seriesId, N anchorDate, N title, J weekday, N date, J periodStart, J periodEnd, N timingMode, J startMinute, J endMinute, N location, N teacher, N note |
| profiles / 128 / termKey | T termKey, T timezone, T week1Monday, A(phases) |
| customProfileKeys / 128 / 字符串 | T termKey |

一个 phase：`T effectiveFrom, A(periods)`。每 profile 至多 128 phase，按 effectiveFrom 日期排序，不允许重复生效日。一个 period：`I32 periodNumber, T start, T end`，每 phase 至多 30，按 periodNumber 数值排序、无重复。

这些集合不是数据库表的转储：没有本机绑定状态、认证、HA token、文件路径或任务调度信息。`locator` 只是来源元数据，不能带 HTTP URL userinfo；不得被编辑端当成自动下载指令。source.sha256 是原导入内容摘要，不是提交校验。timezones 是来源的已有不透明展示元数据，实例 timezone 才是计算使用的 ZoneId。

### 领域校验与关联

所有集合身份无重复且非空。event/entry title 非空白；event/实例 end > start，范围为 start >= -62135510400000、end <= 253402214400000 毫秒。entry day=1..7（周一到周日），0 <= startMinute < endMinute <= 1439。source id 长度至多 160 个 UTF-16 单元；sha256 必须为 64 个小写十六进制字符；source 至少一个实例，occurrenceCount/firstStart/lastEnd 必须精确等于该来源实例数/min(start)/max(end)。非空 resolved termKey 必须存在于 catalog；未指定时沿用既有来源 term 解析约定（来源 termKey；若为空且 filename 忽略大小写以 `.csv` 结束，仅当 calendarName 完整匹配 `湖北大学 ([0-9]{4}-[0-9]{4}-[0-9])` 时取捕获组，否则无学期关联）。

实例 sourceId 必须存在，seriesId 长度 1..200，title 非空白、timezone 可识别。实例文本总计至多 4,000,000 个 UTF-16 单元（id,sourceId,seriesId,uid,title,timezone,location,description,periodLabel）。覆盖文本总计至多 2,000,000 个 UTF-16 单元（key,sourceId,seriesId,anchorDate,title,date,timingMode,location,teacher,note）。

覆盖 key 精确为：本地 `L:<seriesId长度>:<seriesId>:<anchorDate或all>`；导入 `I:<sourceId长度>:<sourceId>:<seriesId长度>:<seriesId>:<anchorDate或all>`。长度用 UTF-16 code unit 数。null sourceId 表示本地课程，seriesId 必须存在于 entries；非 null sourceId 必须存在于 sources。来源替换后不再匹配实例的旧覆盖保留，以便恢复。anchorDate 为 null 表示全周系列编辑，非空为指定原始目标日期。null 字段继承；空字符串显式清空；记录不存在表示重置。覆盖不能靠时间戳覆盖其他版本。

dayAdjustment mode 只能 `none`（该日停课）或 `weekday`（改用另一星期几的课程），weekday 模式取 1..7 且不能等于该日期本来的星期几；label 至多 100 个 UTF-16 单元。

profile termKey 非空白、timezone 可识别、week1Monday 必须周一，至少一个 phase，且第一周起点已有生效 phase。每 phase 至少一个 period，periodNumber >=1、end > start，时间在 05:00..23:59 之间。customProfileKeys 是完整 catalog 中自定义覆盖的键子集：即使内容等于内置预设也仍保留自定义身份。不能用接收端内置配置覆盖远端 catalog。

课程覆盖 title 为 null 或非空白且至多 160 个 UTF-16 单元；location/teacher/note 各至多 500 个 UTF-16 单元；全周编辑只允许 weekday，不允许 date；指定周编辑只允许 date，不允许 weekday。periodStart、periodEnd 各自允许 null 或 1..30；timingMode 只允许 null、`linked`、`custom`；startMinute 允许 null 或 0..1439，endMinute 允许 null 或 1..1439。稀疏字段独立继承；覆盖本身允许不完整成对值，投影时在继承完整课程和学期后再检查有效区间，无法得到有效区间的覆盖保留但不制造有效课程。不能引入悬空课程、来源或 catalog。兼容既有模型的精确有效值由跨语言实现按这些规则校验，拒绝不支持的语义。

## DAG、合并与冲突

初始提交无父节点。正常提交引用已验证的当前前沿；并发合并提交引用多个父节点。全部祖先必须可读取、校验且同 dataset；有环、缺失、多个无关根、已知提交改变均拒绝。时间、上传顺序、目录顺序、ETag 不参与选择版本。

从观察到的完整闭包计算没有后继的 tips。求祖先集合交集，再取不被另一共同祖先继承的最大共同祖先。唯一最大祖先可作三方基线；多个最大祖先内容相同可视为等价；否则停止并报告多重基线歧义。多个 tips 内容全部相同则不再发布纯合并提交，下一次编辑引用全部这些 tips（因此 M1/M2 等价合并不会无限发布）。无法证明基线时不选择任意 Writer 或时间戳。

三方按事件 id、本地课程 id、date、覆盖 key、termKey 合并。来源元数据与该 sourceId 的原始实例是不可分割替换单位；importedAt 仅附属元数据，同内容来源不因导入时间不同产生冲突。来源/本地课程删除与其覆盖组成关联冲突单位；学期配置变化与引用该学期的来源及覆盖组成关联单位。日期组成的 mode/weekday 改变，与 anchorDate 或显式目标 date 对应的指定周编辑/重置组成关联单位；纯 label 变化及其他日期仍独立合并。多分支先执行各对分支的同基线冲突检查，之后按提交 UUID 升序折叠；UUID 只确保可重复性，遇冲突必须请求用户决定，不能以排序决定胜负。

每个单位：两侧相等取该值；一侧等于基线取另一侧；其他情况产生显式冲突。缺失是删除/重置，与从未存在的区别由共同基线决定。真正冲突不修改课程，不自动发布；保存完整基线、全部分支及本地数据。多个远端分支冲突时，稳定顺序仅形成未提交的远端待确认方案，同时展示分支差异；未确认前绝不采用该方案。保留本地必须在全部冲突关联单位上采用本机当前值，即使本机该记录自基线起没有修改，也不能误采纳任意远端分支；采用远端使用已展示的方案。界面确认必须携带已展示冲突输入的校验 token，事务内拒绝对新出现但尚未展示的远端方案作决定。用户决定后在事务内保存课程和包含所有已决分支的待发布提交；发布后仍要合并后来发现的分支。界面全体保留本地/采用远端针对冲突及关联单位，独立修改继续合并；三个以上远端分支的展示与决定使用稳定折叠顺序，用户决定才赋予优先顺序。

## 不可变发布、重试与观察边界

1. 在本地事务保存完整候选、自身 commitId/parents 和精确本地 capture，此时不标记 PUT 已尝试。
2. GET 该唯一文件路径：已有完全相同且可校验对象可恢复回执；已有不同/损坏对象不得覆盖；从未尝试且 GET=404 才允许普通 PUT；紧邻实际写入之前，必须先持久化“已经尝试 PUT”标记。GET 阶段失败不产生 PUT 标记。
3. PUT 之后必须 GET 回读，精确比较字节并完整解码。上传中断、404、断流、哈希错误或成功响应丢失不代表已发布。
4. 重启后已有尝试标记时，不再次 PUT 原路径。GET 确认相同可恢复；GET 明确不存在或不同则为原候选更换随机提交身份，保留原文件，至多三轮；网络失败保持待确认。不因新身份跳过目录中的异常文件。
5. 读取未知文件发生断流、列举后 404 或外层完整性校验失败时，记录暂不可验证集合，报告“尚未完成全部版本同步”，有限退避重试。它不成为提交，也不成为合并基线。只有未被任何有效提交或本地已确认前沿引用的异常孤立文件允许暂缓；可继续整合完整可验证的 DAG，使一次中断不会永久阻断另一端接收后来已验证的重试提交。不得忽略后显示全部完成。已观察到的未验证路径必须在本地持久化，重启或后续列举暂时不包含它时仍按 ID 回查，直到重新验证；初次检查/绑定也保留这份清单。被引用祖先缺失/损坏、已验证 ID 内容改变、有效完整封装的不支持版本/语义、不同 dataset、身份不符、多根或有环均停止危险同步。本机保留原 ID/预期哈希的中断清单（最多 64），恢复成不同有效内容时拒绝；其他客户端独立检查异常，不信任远端任意忽略清单。初版不删除、修复或压缩异常文件，所有端持续显示异常计数，直到可重新验证；必要时由服务器管理员恢复正确字节。
6. 回执与本地课程重放原子保存。重放以 capture 为基线，上传期间的新编辑继续保留并发布后续提交。远端写入完成、本地提交失败时，下次按同一 ID 回读，不重复应用。

单轮图最多 256 提交（含祖先），总解码输入至多 32 MiB，tips/parents 至多 64，当前 tips 总编码字节数乘 tips 数不得超过 64 MiB 的合并工作预算；PROPFIND XML 至多 512 KiB。每次同步最多三次收敛循环，各图最多 256 个 GET；总计至多 512 次逻辑请求预算（发布按 3 次计）、64 MiB 图与隔离检查的累计读取字节，90 秒后不再开始新操作（已开始的单个 HTTP 调用仍受 60 秒超时约束）；新路径发布还需检查实际观察到的目录数量未达上限；之后至多一次 PUT、一次回读。普通 PUT 的重定向同样拒绝自动重放。普通 PUT 禁用 HTTP 客户端透明失败重放。HTTP 连接/读取/总超时分别 10/20/60 秒；Android 自动重试有界、指数退避。单轮最多处理 256 个冲突单位，冲突摘要有界并逐项查看，完整冲突输入不截断；超出处理能力时保留数据并明确失败。超过容量必须保留修改并明确失败；不截断图，不自动 GC/压缩。客户端可以缓存已验证内容，必须持久化 ID→内容哈希，并拒绝已验证内容改变/缺失。

PROPFIND 只给出实际观察集合。迟到文件可在后续前台/本地编辑/JobScheduler 检查中发现；成功文案必须限定“已检查观察到的版本/已发布”，其他设备是否接收未知。协议无法保证服务器永远及时列举文件，不声称全局实时一致，也不声称检测到了尚未观察的损坏文件。

## 协议升级与 v1 切换

未知 protocolVersion、内层 schema、未知字段/尾随字节或不支持语义，均保留本地数据并停止应用；本版不是容忍未知字段的 JSON。未来扩展应使用新版本与明确能力协商，不能更改 v2 既有字段含义或静默降级。

既有 v1 数据集在停用所有旧客户端后，通过只读旧 head 和完整历史证明旧共同基线与本地身份；合并尚未发布的本地编辑。已提交的待确认回执必须在旧历史中逐字节验证后重放；已证明未进入 head 链的候选保留本地修改并解除旧待确认状态。无法确认身份、历史、冲突或回执时停止切换，保留认证、原 checkpoint 与课程。第一个迁移端建立独立 v2 根，保留 datasetId；后续迁移端验证相同 dataset 与旧基线，再与 v2 合并。空接收端经明确确认可绑定；非空手机不能静默恢复、按名称猜身份或清空课程。新设备未绑定时可将本地课表创建为全新 dataset。

v2 与旧客户端没有双协议桥接；切换后所有端必须停止写 v1。不得通过更新旧 head 宣布切换，因为服务器不支持安全条件写入。用户/部署侧须保证停用旧同步。旧远端文件永久保留，允许人工回看，不能将其当成 v2 最新状态。

## 固定跨语言向量

`schedule-core/src/test/resources/sync-v2/` 包含 `.dpc` 原始字节、可读 JSON 字段说明和 manifest 中的长度/完整文件 SHA-256。empty、complex-root、left、right、multi-parent 是合法文件；bad-checksum 必须失败。复杂样例含中文、emoji、null 与空字符串、来源/实例/全周覆盖/自定义学期；left/right 从共同根独立编辑，multi-parent 保留双方修改。JSON 是说明数据，不是线上文件格式。

运行 `python tools/sync_v2_vectors.py` 独立校验；`--generate` 重新生成同一固定字节。Kotlin JVM 测试读取相同文件并重新编码做逐字节比较。第三方实现应通过同一组向量，并实现本规范的全部领域校验、关联冲突与故障行为；Python 参考仅作为格式/校验向量工具，不是完整同步客户端。
