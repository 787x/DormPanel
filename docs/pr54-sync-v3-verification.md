# PR54：同步核心合并验收

审查基线为 `c5ebc339f3f9084a50adabcafc5935049af3395c`，继续更新原 PR54。初始阶段的证据和当时的阻塞项保留在 [c5ebc339 验收记录](https://github.com/787x/DormPanel/blob/c5ebc339f3f9084a50adabcafc5935049af3395c/docs/pr54-sync-v3-verification.md)，不能把该历史记录当成本轮复测。

本轮实现元数据分页、增量前沿、可恢复事务、Room/Workspace 适配和跨进程拥有者。生产 v3 创建/绑定、UI、v2→v3 迁移、归档与 GC 仍未启用。正式版本号、applicationId、签名、现有 ScheduleDatabase v4、v2 checkpoint 和正式生产工厂未修改。

## 1. 私有格式与分页

新的私有状态为 `v3-state-v2.sqlite`：SQLite B-tree，WAL、FULL synchronous、2 MiB 页缓存、关闭 mmap、64 页自动 checkpoint。`v3_records` 使用 `(ns,k)` 唯一索引和 `(ns,seq)` 游标索引。每行 SHA-256 绑定 namespace、key 与精确 JSON；读取时按需验证，不启动扫描全部历史。固定查询的 prepared statement 缓存有界。

独立 namespace 保存节点、全局 objectId、Epoch、Epoch 成员与前沿、全局因果前沿、已观察路径、首次扫描队列、验证队列、失败证据、审计游标、验证栈和祖先证明任务。队列用稳定 sequence 作 keyset cursor，每批最多 256 项；没有累计 16384 scopes / 65536 observed 上限。

本地完整业务快照另存为私有、不可变的 RAW 快照引用，同一快照不重复塞进 local/baseline/capture/冲突。此封装仅用于本地存储，不作为远端协议对象注册或发布。它不压缩、不分片、不改变 DPV3 字节。48 MiB 上限只约束小型控制记录及精确 Pending；未增加该常量。快照行与指向它的控制行在同一事务提交，禁止 snapshot 行 GC。

`v3-engine-v1.json` 是旧 Draft 的隔离测试状态：发现它时明确报 `INCOMPATIBLE_V3_DRAFT_STATE`，原文件保持不变，选择新私有测试目录。不会自动迁移或删除它。发现生产 `workspace.json` / `identity.json` / `schedule.db` 等文件时也拒绝接管；元数据目录不得位于 v2/v3 协议目录中。Kotlin/Node 私有业务封装不是跨平台迁移格式；跨语言共享的是冻结的 DPV3 数据集。

## 2. 增量和证明

普通轮次读取持久化前沿及新对象队列，最多处理有界批次，不枚举累计 nodes/observed，不重建全数据集前沿。新对象只更新自身、相关身份、Epoch 和直接因果覆盖。Epoch 激活按持久队列推进；没有源 Transition 的 Genesis 不进入正式活动集合。

普通 Commit 直接复用已验证父节点的共同根。单链祖先使用持久跳表；一般 DAG 的共同祖先查询使用按输入身份固定的持久证明队列、分源访问标记和最大共同祖先集合。预算不足保存有效队列/栈，下一轮继续，不提高单轮预算。普通 Commit 保留人工编辑语义；Checkpoint 仍要求源前沿无冲突且业务结果一致。阈值处有真实源冲突时，先普通决策 Commit，再换代，不能将选择塞进 Genesis。

前缀审计冻结每轮 high-water mark，避免新尾部不断增长导致旧前缀永远无法重扫；扫描/审计 lane 跨调用持久化，单请求轮次也公平。有效列表、子任务、游标在同一短事务提交。漏列不删除已观察路径。预算不足不伪造远端错误，也不推进未检查范围。

无变化轮次可以不 GET、不解码任何旧快照。对象字节巡检由独立、有界 `auditObjects` 执行；缓存有效、目录本轮观察、精确 GET 回读、闭包证书和旧历史审计是不同证据。receipt 保存实际确认轮次/端点，重开后的旧回读事实不会伪装成本轮重新 GET。普通 WebDAV 不能证明未知 Writer 全部出现，API 不返回“所有历史/设备全部同步”。

## 3. 原子事务与拥有者

本地课程、baseline、Pending 精确字节/capture、决定输入、receipt、活动 Epoch 与索引控制使用同一 SQLite 事务边界。所有新引用先有持久对象/快照；发布回执要求精确候选已完成祖先索引验证。索引先提交而回执未完成时，重开重放索引与回执，不再 PUT。commit 已落盘但确认失败时禁止旧内存继续写，必须重开。

`V3RoomAdapter` / `V3RoomDatabase` 是独立、显式的 Room 适配：复用既有课程实体与 DAO，课程行与 v3 状态在同一数据库事务更新，原 v2 checkpoint 行保持原样。网络始终在事务外。未改变正式 Room 工厂或数据库版本。

`openV3Workspace` 复用 Workspace 的编辑、revision、guard、mutationGuard；课程和 v3 控制保存在同一 SQLite-backed Workspace 行。调用者必须传入现有 catalog/管理员/目录授权 guard，并保留宿主服务原有 flock 与 IPC 边界。CLI/MCP 不应自行打开服务已拥有的私有库，第二个拥有者会在接触 Journal/缓存之前失败。

Node 在独立 `v3-owner.lock` 上持有 SQLite OS 字节范围锁；Kotlin 的整个文件 FileChannel 锁覆盖相同范围。锁文件不删除、不替换，不用 PID 文件冒充互斥。SIGKILL 由内核释放锁，新实例恢复 SQLite WAL。Node 使用 NAS 官方 22.18.0 的 `node:sqlite`；JDBC 仅为 JVM 测试依赖，不进入 APK。

本地编辑/决定拥有独立的有界本地事务预算，不继承过期的网络轮次；同步轮次内 Store、cache、元数据、验证、换代共享同一个 Budget。上传字节额度在 ATTEMPTED 前检查；不足时保持 STAGED。UNCERTAIN / ATTEMPTED 的 404 仍不能触发重 PUT。损坏 blob 先保留到私有 damaged 文件，再按原身份/哈希补读，不覆盖远端或丢弃 witness。

上传期间再次编辑造成的真实冲突会持久保存并阻止自动发布。重开不能将它当作相对新 baseline 的普通本地编辑而默认选择 LOCAL；再次编辑会更新展示 revision，旧选择失效。旧展示/决定输入保存在分页索引中。

## 4. 验证与证据

本轮结果见 [结构化记录](evidence/pr54/verification.json)。原始日志、私有连接配置和 FPK 均只在忽略提交的 `artifacts/pr54/`；文档不包含凭据。

| 最终实现验收 | 实际结果 |
| --- | --- |
| Kotlin/JVM | 68 项、0 失败；含 1024 次发布、分页尺度、同源冲突阈值、回执再编辑、索引/激活故障、实际第二 JVM 进程终止及 41 项 v2 回归 |
| NAS 官方 Node 22.18.0 / 包用户 | 78 项、0 失败、0 跳过，192 MiB heap；含 1024 次发布、8 个 SIGKILL 边界、6 个索引/换代边界和官方授权本地目录生产协调器 |
| 正常轮次，32 → 1024 次发布 | Node 历史节点行读取 2 → 2，元数据行读取 106 → 113，GET/旧快照解码 0 → 0；NAS 进程峰值 RSS 140452 KiB（约 137 MiB） |
| Android 实机 | X08E Android 9：3 项通过，48.930 秒；Phone Android 13：3 项通过，15.161 秒；均使用 Room 和测试私有目录 |
| 真实 HTTPS | 双向发布、第二代、迟到 Bridge、丢回执恢复仅 1 次 PUT、重开后增量 GET=0，实际记录及因果覆盖全部检查 |
| 独立回归 | Node v2 70 项通过、1 项平台跳过；28 个 v3 冻结文件逐字节通过 |
| 构建/最终包 | App/Phone Debug 和测试 APK 通过；官方 fnpack 及 37 份文件逐字节核验通过，版本 0.7.2 不变 |

最终 FPK SHA-256：`eb94d9f33e734025d864226b9313a08aee65b67152e8fceb9246e85fa62c38a7`。没有安装、升级或发布此包。

- Kotlin/JVM：普通和并发编辑、跨代候选、多个旧 Epoch 的迟到分支及后继、幂等、持久冲突、换代阈值先决策、深层栈恢复、索引/Gen/Transition/激活/receipt 故障边界、缓存损坏补读和本地预算解耦。
- Kotlin/Node 共享 `recovery-scenarios.json`：STAGED、ATTEMPTED、PUT 后、回读前后、READBACK_CONFIRMED、UNCERTAIN、RECEIPTED；检查本地数据、capture、实际 PUT 次数和只应用一次。
- Node 实际子进程在 8 个上述边界被 SIGKILL，另一个进程开库失败且没有修改胜者锁/状态；杀死持有者后可恢复。JVM 也有实际第二进程争锁和强制终止恢复。另有双端 6 个索引/换代边界的确定性故障与业务结果检查。
- 分页尺度使用 70400 scopes 与 70400 observed、每条含 768 字节填充，超过旧数量和 48 MiB 总量；冷打开、64 条分页、单项更新及回滚不全读/全写。此为索引尺度夹具，不宣称生成了同数量真实协议对象。
- 全局身份使用真实、经验证的 130 个候选 Epoch，在冷打开后复用非首尾页面的 objectId；必须拒绝，旧对象及全局索引保持不变。前缀中断、漏列、坏元数据、缺失祖先、错误 GET 不污染缓存均有定向检查。
- 同数据集 1024 次真实引擎 Fake Store 发布与多 Epoch 回归在新索引结构上运行。比较 32/1024 次发布后的正常轮次：历史节点行读取均为 2，旧快照 GET/解码为 0；元数据行读取有固定有界工作量，见 JSON 的实测数值与进程峰值 RSS。不能把小快照峰值外推为所有最大输入的上界。
- Android 9 X08E / Android 13 Phone：独立测试 APK、独立 Room 数据库，实际验证课程/receipt/索引原子一致、上传期间编辑、回滚、commit 确认失败和重开；HTTPS 场景也使用 Room 适配。
- 真实测试 HTTPS 仅随机 `dormpanel/pr54-engine-62e7a15b-f16f-4c21-824a-4d46bde3fcb3/`：Node 初始化 → Kotlin 换到第二代 → Phone/X08E 发布 → fnOS 包用户 Workspace 接收。随后旧第一代真实发布迟到提交，当前端通过合法 Bridge 整合；PUT 成功丢响应后重开，实际仅 PUT 一次，后续增量 GET 为 0。六个业务记录及迟到因果覆盖均检查。
- v2 定向回归、28 个冻结 v3 文件字节 oracle、Android 构建及官方 fnpack 最终产物内容核验均记录。FPK 只构建核验，不安装升级，不创建 Release/tag。

早期固定 8 轮冷发现的测试断言不足，改为在既有单轮预算内有界跨轮等待有效进度；没有把未完成发现当作接收成功。此前 c5 的成功记录不代替本轮测试。每个最终/阶段结果的准确计数、平台跳过和包 SHA 以 JSON 为准。

## 5. PR55 接入与限制

PR55 应复用以上适配器与既有宿主 Writer/授权入口，使用持久安装 writerId，通过 Engine.edit 或 SQLite-backed Workspace 编辑。不要直接写课程 DAO、创建第二个 Writer、复用 v2 私有目录或把业务确认/迁移放进引擎。节点/快照和所有恢复日志仍保留；本 PR 没有压缩、物理 GC 或归档删除。

本轮验证是可重复故障注入、进程终止与隔离 HTTPS，不是物理断电、所有 WebDAV 实现、网络文件系统锁或任意最大业务快照的硬件压力验收。私有索引应位于具有可靠 SQLite/WAL/文件锁语义的本地应用目录。服务器持续漏列、未出现的离线 Writer、永久缺失证明和容量耗尽仍只能保留证据并安全停止，不能宣称全局完整或自动删除历史。
