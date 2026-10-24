# DormPanel Sync v3：RAW/1 对象协议

本文冻结 PR53 的线格式、对象语法及引擎必须验证的因果契约。PR53 只实现对象 codec、隔离存储适配器和私有持久缓存。PR54 增加独立同步核心、分页持久索引与 Room/Workspace 事务适配，状态机及实际验收见 [PR54 验证记录](pr54-sync-v3-verification.md)；**不提供正式 v3 绑定、生产启用、迁移或 GC**。不能把 codec 成功、隔离测试通过或一个发布回执当成完整同步成功。v2 的 DPC2/DPSS/1、256 个累计提交限制和既有迁移行为不变。

## 1. 身份、目录和初始化

用户选择的共享父目录下，唯一 v3 命名空间为 `dormpanel-sync-v3/`，与 `dormpanel-sync-v2/` 平级。内部只接受以下路径，区分大小写，不接受 URL 编码别名、点段、重复斜线、反斜线、符号链接、未知文件或额外层级：

```text
dataset-<datasetId>/descriptor-<objectId>.dpv
dataset-<datasetId>/epochs/<e00>/<e01>/.../<e15>/genesis-<objectId>.dpv
dataset-<datasetId>/epochs/<e00>/<e01>/.../<e15>/commit-<objectId>.dpv
dataset-<datasetId>/epochs/<e00>/<e01>/.../<e15>/transition-<objectId>.dpv
```

Epoch 路径为**UUID 原始十六进制的固定深度字节树**：从规范小写 epochId 删除四个连字符，得到 32 个十六进制字符，按从左至右每两个字符拆成 16 段 e00..e15（每段精确为 `00`..`ff`）。不哈希、不取模、不使用 epochNumber，不保留 `epoch-<UUID>` 叶名称。例如 `01234567-89ab-cdef-0123-456789abcdef` 的叶目录为 `epochs/01/23/45/67/89/ab/cd/ef/01/23/45/67/89/ab/cd/ef/`。这是身份的无损、一一对应编码，不会因分桶碰撞而使某个桶累计任意多个 Epoch。

各目录只列举直属子项：根仅 dataset 目录；dataset 目录仅 descriptor 文件及唯一的 `epochs/` 目录；`epochs/` 和不足 16 段的前缀目录仅接受两位小写 hex 子目录；恰好 16 段的叶才是 Epoch 目录，仅接受 genesis/commit/transition 文件。全零 UUID 的完整叶非法，允许全零的中间前缀。禁止旧版草案平铺路径、浅层业务文件、叶下子目录、多余第 17 段、大小写/编码别名或错误对象身份。引用中的 epochId 和解码后的叶 UUID 必须一致。

每个目录本轮仍至多 256 个直属子项（含目录），超限安全停止，不截断后宣布成功。字节树内部每个节点至多 256 个合法分支，叶唯一对应一个 Epoch，因而这一预算不会再成为单个数据集的累计 Epoch 数上限；叶内对象容量仍有限，引擎在达到容量前启动换代。根下 dataset 数量仍有单次列举限制，本次修订不引入多数据集管理扩展。多代发现仍可能跨轮耗尽请求/字节/时间预算，需要恢复有界巡检，不能承诺一轮完整发现。

本布局在 v3 正式启用前修订：DPV3 对象字节和引用不变，Kotlin/Node 路径接口及独立 `paths.json` 向量共同冻结新布局。旧草案的隔离测试目录/缓存不自动迁移、不删除；遇旧路径明确拒绝，使用新测试目录。v2 路径与字节不受影响。

UUID 是固定 36 字节小写 ASCII `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`，接受所有十六进制版本位；新身份用安全随机 UUIDv4。`ZERO` 为全零 UUID，仅用作 descriptor 的无 Epoch 哨兵。实际 dataset/object/writer/epoch 身份不得为 ZERO。UUID 不是认证信息：授权仍由服务器/官方本地目录权限管理。

`datasetId` 是业务数据集稳定身份，`writerId` 是安装级持久身份，独立安装不得复制同一活动 writer；`epochId` 是每个换代候选独立随机身份，`epochNumber` 是从 1 开始的有符号 I32 正整数，不能用序号、UUID、时间或 Writer 决定胜负。`objectId` 是每个对象独立随机身份（Commit 时即 commitId），与内容哈希不同。同 dataset 下 objectId 必须全局唯一，跨类型/跨代重复也非法；这一点须历史验证器校验。对象哈希为完整 `.dpv` 字节的 SHA-256，不是尾部校验和。

Descriptor 表示某次不可变初始化声明，其 datasetId 永久稳定，objectId、writerId 记录初始化者；其他非 descriptor 对象必须固定引用该 descriptor 的完整文件哈希。它不记录可变 head、设备成员或可变设置。一个绑定只认可一个经独立确认的 descriptor identity/hash。新设备从根列举 dataset，读取其 descriptor，由明确选择/迁移记录确定绑定；不得以“目录为空”自动认定唯一数据集。

普通 WebDAV 没有原子的“只有我初始化了这个共享目录”保证。并发初始化应各自分配新 datasetId，允许出现多个候选，不能自动把它们合并。相同 datasetId 出现多个 descriptor、不同初始 Genesis、已知对象变更或路径占用时安全停止。预检 404 不是 CAS；普通 PUT 的新路径安全依赖诚实 Writer 对新随机身份的独占使用，不能对恶意服务器/恶意写者承诺不可覆盖。检测到差异保留证据，绝不修复覆盖。

## 2. 通用逐字节编码

全部整数为大端有符号二补码；I32 为 4 字节，I64 为 8 字节。长度/数量在分配前检查非负及上限。SHA 为 32 原始字节，外部呈现为 64 个小写十六进制。文件没有 BOM、压缩、填充或尾随字节。当前版本只支持 RAW；压缩、分片、签名、字段组合并均须新版本显式定义，不解释保留字段或忽略未知字段。

所有四种对象都使用同一个 envelope，按下表顺序无条件写入所有字段：

| 字段 | 字节及限制 |
| --- | --- |
| magic | ASCII `DPV3`，`44 50 56 33` |
| protocolVersion | I32 = 3 |
| type | I32：1 Descriptor、2 Genesis/Checkpoint、3 Commit、4 Transition |
| datasetId | 固定 36 字节 UUID |
| objectId | 固定 36 字节 UUID，必须与文件名一致 |
| writerId | 固定 36 字节 UUID |
| epochId | 固定 36 字节 UUID |
| epochNumber | I32 |
| descriptorHash | 32 原始字节 |
| refCount | I32，0..128 |
| refs | 以下 116 字节引用记录，严格按**完整引用字节**无符号词典序升序 |
| stateLength | I32，0 表示无快照；否则为 RAW 状态字节数 |
| state | 恰好 stateLength 字节，下面定义的八集合编码 |
| checksum | SHA256(从 magic 到 state 最后一个字节，含所有长度/引用/元数据) |

最小文件长 232 字节；完整对象上限 `16*1024*1024 + 32768`。完整 DPSS 重构也必须不超过 16 MiB，即 RAW 状态最多 `16*1024*1024 - 141` 字节。读 stateLength 可先以 16 MiB 检查，然后在复用 DPSS 领域校验时检查更严格的总长；不得接受超限状态。未知版本/type、非法长度、缺失字段、坏 checksum、非法领域数据、尾随字节及不规范重编码均拒绝。对象身份预期必须来自文件路径、已保存绑定或已验证引用，不能仅从自身封装推导后声称独立身份验证通过。

每个引用记录顺序为：

```text
I32 role | I32 type | UUID epochId | I32 epochNumber | UUID objectId | SHA fullObjectHash
```

role：1=PARENT，2=INHERITED，3=LATE，4=BASELINE，5=TARGET。引用 type 只允许 2 或 3（Genesis/Commit）；Epoch 序号必须为正，epoch/object UUID 非 ZERO，hash 非全零。禁止引用自身 objectId；同一对象内 `(epochId,objectId)` 不得重复，即使 role/hash 不同也拒绝。严格顺序保证重复引用被拒绝。引用隐含同 dataset、同 descriptor，远端对象解码后必须独立核验 type、epochId、epochNumber、objectId、完整哈希；语法层不执行远端读取。

### 四类对象的局部约束

| type | Epoch/状态/引用 |
| --- | --- |
| Descriptor | epochId=ZERO，epochNumber=0，descriptorHash=32 个零字节，refs 为空，stateLength=0 |
| Genesis/Checkpoint | 正 Epoch，descriptorHash 非零，必须有状态。第 1 代无引用；后续代 1..64 个 INHERITED，来自同一个旧 epochId，旧序号精确为新序号减 1 |
| Commit | 正 Epoch，descriptorHash 非零，必须有状态。1..64 个同 epochId/epochNumber 的 PARENT；可选 1..62 个更低序号、不同 epochId 的 LATE；有 LATE 时精确一个更低序号、不同 epochId 的 BASELINE，没有 LATE 时不得有 BASELINE；无其他 role |
| Transition | 正 Epoch，descriptorHash 非零，stateLength=0。1..64 个自身 Epoch 的 INHERITED 加精确一个 TARGET；TARGET type=Genesis，epochId 不同、epochNumber=自身加 1；最大 I32 代不能再换代 |

生成器可排序引用；解码器必须拒绝输入无序。局部形状合法不表示存在有效共同基线、Checkpoint 或 Bridge，不能据此应用快照。

## 3. RAW 状态与 BusinessStateHash

RAW 状态精确等于规范 DPSS/1 payload 中的八个业务集合，**不含** DPSS magic/version/length/checksum，也不含 datasetId、revision、parentRevision、createdAt、generation。以下基本类型、字段表和领域规则冻结自 v2，不改变现有 DPSS 文件。实现可通过构造两个身份均为 ZERO、parent=null、createdAt=generation=1 的临时 DPSS 复用现有校验；临时封装不能写成真实 v2 提交。

完整 RAW 保留 `sources.importedAt`。业务等价编码则将所有 source.importedAt 置 I64 0，其他字段、null/空串、来源/实例、覆盖及自定义 catalog 身份全部保留，并按以下规范排序。定义：

```text
BusinessStateHash = SHA256(ASCII "DPBS" | I32 1 | RAW(state with every source.importedAt=0))
```

它不含任何 dataset/commit/epoch/writer 身份；不能把包含 commitId 的完整 DPSS 或 DPV3 文件 SHA 称为业务哈希。对于通过本协议领域校验的状态，在不考虑 SHA 碰撞的前提下，相同哈希等价于现有 `ScheduleMerge.same()` 的 records 语义：集合顺序不敏感，来源导入时间是附属元数据，同内容来源不会因时间不同冲突。完整对象哈希仍保护原始 importedAt。`sync`、invalidRecords 不属于线格式；存在无效本地记录时不能以忽略记录方式发布。

SHA 不证明合并或因果关系；跨代业务哈希相同也不能代替继承证明。来源是元数据和原始实例的整体，人工覆盖分层保留，不能通过名称/日期猜身份。

## 4. 历史验证与跨代契约

每个 Epoch 的 PARENT 边形成 DAG，只有一个有效 Genesis 根，所有普通提交都必须可追溯到该根。祖先缺失、环、身份复用、不同 dataset/descriptor、同 ID 改变、引用哈希不符都使相关闭包不可用。不可因路径没有被当前目录列举而省略已知引用。局部 codec 完全不认证这些性质。

Checkpoint 是 type=Genesis 在新一代中的用途，完整保存上一代已验证前沿的业务合并结果；其 INHERITED 引用精确固定旧闭包前沿，不是声称旧代此后停写。完整源闭包、共同基线、业务有效性及合并结果必须验证；有冲突则保存完整输入并停在待决状态，不生成自动采用某一分支的 checkpoint。第 1 代 Genesis 的状态是明确初始化/迁移事务选择的初始状态。

Transition 存在于源 Epoch，固定源前沿与目标 Genesis 的完整哈希。验证器必须检查：TARGET Genesis 的 dataset/descriptor 一致，其 INHERITED 列表与 Transition 的 INHERITED 逐项完全一致，目标状态是源前沿按现有三方合并规则得到的合法完整快照。Genesis 先持久发布且回读，再发布 Transition，避免循环引用。单独发现一个新 Genesis 不能证明换代已提交；反向检查无 Transition 的孤立候选只进入恢复/审计清单。目录中的新高序号不证明有效继承。

多父 Commit 只允许同一 Epoch 的 PARENT。多个 Writer 可从同源前沿并发产生不同 epochId、相同 epochNumber 的合法换代候选，各自有独立 Genesis 与 Transition；编号没有唯一性或锁含义。它们都必须保留，不能按序号/UUID/mtime 选一个胜者。PR53 不处理候选收敛。后续引擎在未实现有证明的共同继承合并前安全停止，显示并发候选；人工选择活动分支也不能丢弃另一候选或声称它不存在。若后续通过再进一代后将另一候选作为更低序号迟到分支整合，仍须满足下面的共同基线及冲突契约；本格式不允许同序号 LATE 伪装普通父。

迟到整合使用 **Commit 的持久 LATE + BASELINE 引用**，不是本地忽略列表或无证据的“已桥接”标志。该 Commit 的 PARENT 固定当前目标代前沿；LATE 固定各旧代待整合 tip；BASELINE 固定一个可从目标继承链及所有 LATE 闭包证明的共同基线状态。验证器必须展开 PARENT、Genesis.INHERITED、必要 Transition 与 LATE 的祖先闭包，证明 BASELINE 是双方祖先，并验证它是唯一最大共同基线，或所有最大共同基线业务等价；否则拒绝应用。不能只检查序号大小。

跨代三方合并以已证明基线为 base，以目标前沿合并状态与迟到前沿状态为分支，沿用 `ScheduleMerge` 的来源、课程、日期、学期关联冲突规则。所有分支对同一基线先成对冲突检查，再按稳定 objectId 顺序折叠；顺序不能决定业务胜负。生成端必须复算合并，并在未来本地事务中持久保存合并输入、精确 capture 和全部用户冲突决策；未决冲突禁止自动发布。LATE 及其祖先的因果覆盖关系从此 Commit 的可验证闭包导出；重启必须还能找到这些引用/精确原始对象。后续重复巡检发现相同 tip 可验证其已引用，但未知后继仍要整合。若多个迟到分支无法共用一个可证明基线，本版必须分别处理或安全停止，不能创造虚拟共同基线。

须区分生成端合并策略与远端可验证事实：普通 Commit（包括带 LATE 的 Commit）与 v2 一样允许 Writer 携带新编辑和人工解决后的完整状态，接收端必须验证业务领域、完整引用闭包和共同基线，**不要求普通 Commit 的 state 等于自动合并函数的输出**。无签名快照不能证明人类实际确认了哪项冲突，也不能证明 Writer 没有故意删除课程；因果覆盖不能称为“所有业务内容已保留”。目录权限允许的 Writer 是业务编辑信任边界。接收多个并发 tips 时仍须执行三方合并并保留其新冲突。Checkpoint 不携带新业务编辑：其状态必须精确等于源前沿的无冲突合并结果；有冲突时先在源 Epoch 生成有全部父引用的人工解决 Commit，再对该明确前沿创建 Checkpoint。不得用任意自述业务哈希绕过这些检查。

对象缓存命中可支持闭包验证，但不证明远端仍存在对象，也不证明目录完整。Checkpoint 可以界定活跃 DAG 的遍历边界；不允许仅凭 checkpoint 业务哈希相等将旧代祖先证明删除。跨代证明所需旧对象仍可从持久缓存或独立归档提供；缺失时停止相应操作。

## 5. 分代发现与普通 WebDAV 的限制

新设备有界列举根/dataset/epochs 前缀树，只有完整 16 段叶才进入 Epoch 对象列举，读取 descriptor、所有本轮发现的 Transition 及引用目标；每条继承链须验证到已确认根。叶目录存在或可为空只是一项路径观察，不证明存在合法 Genesis 或换代已提交。预算内未完成则持久化发现队列和待验证引用，不绑定成“唯一最新”。某次空目录、404、最大序号或服务器 mtime 都不能证明没有其他代。

路径接口 `epochsScope/epochs(dataset)` 返回字节树根，`epochScope/epoch(dataset, epochId)` 可在不列举祖先目录的情况下精确定位任意引用的 Epoch；`scopeInfo/describe(scope)` 返回 root/dataset/prefix/epoch、datasetId、已展开的 epochPrefix 及完整叶的 epochId。`list(scope)` 只返回经过严格验证的完整直属路径，可直接存成下一轮的前缀队列。路径前缀深度有界为 16，每个列表最多 256 项，无需服务器分页、CAS、LOCK、可信 mtime 或可变全局 head。

PR54 的增量发现实现应持久保存协议布局版本、已绑定 dataset/descriptor、待扫描 scope 队列/已观察子路径、已知引用及巡检轮次。一个目录的列表验证完后再原子提交其子任务与游标；提交前中断可重列当前 scope，不能将部分解析的列表记成已完成。已观察的子项即使暂时漏列也保留并直接回查。队列很大时分批落盘，不能无界保存在内存。跨轮暂停不得在同一轮重置预算；下一轮恢复时重新验证 scope，实际重列进度与远端当前完整性是不同证据。新前缀或迟到 Epoch 可能在已扫描前缀出现，完成一次前缀扫描不代表永久封存，必须公平轮转重扫已知内部前缀（包括 `epochs/`）并沿 Transition/父引用直接定位对象。此处定义可实现的恢复接口，PR53 只在测试中模拟分批队列，不启用生产发现算法。

已绑定设备从持久已知代及待验证路径增量读取，按各源代的 Transition 发现目标，再核验 Genesis 和继承；可重新列举 dataset 目录补充漏列的独立候选。已知 Transition/父引用必须按精确路径回查，即使后续列表漏列它。观察过但不可验证的路径不能因下一次漏列而忘记，未来引擎需持久化该清单与分页/巡检进度。没有全局可变 head。

纯 WebDAV 不能证明所有 Writer 已出现、旧代永久停写、一次列举是全局一致视图或再无迟到提交。正常模式只能在有界资源内轮转巡检旧代，允许发现延迟；PR54 实现长期离线重放与迟到 Bridge，但不能据此省略旧代巡检。没有可验证停写证据时，既不能承诺无需旧代巡检，也不能自动物理删除历史。服务器持续漏列时无法保证最终发现；客户端只能报告实际观察证据。

可选 fnOS 协调封存可以另外验证服务端实际禁止旧代写入，并把完整旧闭包和独立归档校验记录提供给客户端；已知设备停写登记不等于服务器封存或未知 Writer 全部退役。协调服务不是普通同步的必要依赖。本版线格式没有封存证书，不能把普通 Transition 当成该证明。物理 GC 至少要求可验证停写 + 完整独立归档 + 对恢复日志/引用对象的保护；PR53 不实现任何删除。

## 6. 预算、缓存、事务与错误

一轮共享预算跨全部 Epoch、网络/本地 Store 和缓存读取：512 次逻辑请求、64 MiB 累计输入/上传及缓存校验字节、90 秒后不再开始新操作；单对象上述上限、列表 XML 512 KiB、每目录 256 项。HTTP 连接/读取/总超时 10/20/60 秒；普通 PUT 禁止 HTTP 透明重放和重定向。单轮历史验证另限 256 个新展开/新验证对象、64 parents/tips、64 MiB 合并工作预算（tips 编码总长乘 tips 数），跨代审计须分轮持久推进，不能取消这些保护。PR53 的旧测试缓存仍有 4096 项启动限制；PR54 隔离引擎使用下述按路径索引，不启动扫描旧缓存、不提升旧常量。PR54 使用 SQLite 分页索引、持久前沿和可继续证明任务，不再将累计元数据放入单个 Journal；不取消每轮预算或每 Epoch 容量限制。

存储抽象提供严格路径的 `list(scope)`、`read(path)` 和仅隔离测试可调用的 `createForIsolatedTest(path, bytes, beforeWriteIntent)`。create 先读取占用情况，写前 await/调用意图持久化边界，单次 PUT/link 后精确回读与解码。此回调本身不证明事务已持久化，调用者负责真正落盘；PR53 没有生产发布协调器，不能将 primitive 接入 UI/CLI/MCP 并声称安全发布。预检读取失败不得启动写入；已存在相同字节可确认内容，任何不同/损坏字节或不确定回读均不能成功。重复调用底层 primitive 不是恢复方案，PR54 协调器根据持久 attempted/uncertain 状态阻止再次 PUT，发布额度在 ATTEMPTED 前检查；回读确认与本地 receipt 分开。

未来完整发布/换代事务至少保存以下状态，不存在跨网络/本地原子事务：

1. `STAGED`：持久候选精确字节/身份/引用/hash、用户编辑的精确 capture、待发布对象顺序、源绑定与预期闭包；保护所有引用的缓存对象。
2. `ATTEMPTED`：紧邻每个实际 PUT 前持久化标记，失败不回退成从未尝试。
3. `UNCERTAIN`：网络失败或回读不确定，先只读恢复；相同字节可确认，不同字节隔离，404 不能证明该次 PUT 从未成功，不得盲重试原路径。
4. `READBACK_CONFIRMED`：逐字节核验本候选，不代表其他 Writer 已收到。
5. `RECEIPTED`：本地事务更新回执，并以 capture 为基线保留/重放上传期间的新编辑。失败后按原候选只读恢复，不重复应用。

换代先确认目标 Genesis，再确认源 Transition，最后在本地事务提交活动代选择；任一步中断保留日志，不能因为新代目录存在就切换。更换候选身份须保留原不确定对象和相应 Genesis/Transition 引用；由后续协调器生成整体一致的新候选，不能只改某个 objectId。冲突、并发候选、共同基线未知时停在待决状态。

本地 create 沿用既有 LocalCommitStore 的安全边界：通过官方 LocalGrants 与目录身份检查打开选定父目录，仅在已验证文件系统且与目标同设备时，在 `dormpanel-sync-v3/` **之外**创建随机兄弟目录 `.v3-stage-<UUID>/`（精确权限 0700）。所有访问通过固定目录 FD 和 O_NOFOLLOW；候选 content 文件写入、chmod 0444、fsync，暂存目录 fsync 后，紧邻公开前调用持久意图回调，通过排他 hard link 原子公开到目标路径，再 fsync 目标目录并精确回读。已有不同内容或 link EEXIST 绝不覆盖。正式协议文件名规则不允许任何 `.v3-stage-*`。写前/公开后测试钩子只用于隔离故障测试，不进入正式发布协调器。正常 finally 仅清理本次暂存目录并 fsync 父目录；SIGKILL 等造成的未知残留留在协议目录外，不会出现在任何正式 list 中，也不自动清理以免破坏恢复证据。后续协调器仍须处理 attempted 日志与残留的关联，但正常 Epoch 列举不依赖残留清理。

缓存独立目录名 `sync-v3-cache-v1`，位于应用私有存储（Android 用独立文件日志，不改 Room schema；FPK 与 workspace.json 分离）。完整 `.dpv` 按完整内容哈希命名；不可变路径→预期哈希 receipt 使用原子 rename、文件 fsync 和受支持平台的目录 fsync，在 blob 前持久化。索引项从经验证原始对象重建，记录 path、hash、size、type、dataset、Epoch 和全部 refs；缺 blob 的 receipt 保留预期身份，远端恢复必须匹配。对象写后索引未更新时，重启可校验孤儿并重建索引；残缺 temp 不可信。已知哈希文件损坏/路径同身份不同内容安全停止，不自动覆盖修复。缓存只有一个拥有者/写队列；不能让多个进程各自打开目录竞争，后续集成必须复用既有 Writer/事务保护。

**PR54 的私有存储。** `sync-v3-index-v2` 提供按路径的 blob/witness；`v3-state-v2.sqlite` 按页保存节点、身份、发现/验证/审计队列与证明栈。本地完整 RAW 快照以不可变引用保存，控制态、Room/Workspace 课程与回执同事务提交；不提高旧 4096/48 MiB 常量。已验证元数据、缓存内容、当轮目录观察和 GET 回读仍是不同证据。旧 `v3-engine-v1.json` 隔离状态明确拒绝隐式升级，保留原始文件；不读取或迁移生产 v2。恢复日志、Pending/capture、baseline、未决冲突与迟到路径通过持久引用保护，不执行 GC。详见 [PR54 验收及接入约束](pr54-sync-v3-verification.md)。

当前不自动删除孤儿、temp 或 blob，避免误删尚被恢复日志引用的数据。启动不修改 v2 graph.raw/checkpoint、Room 业务行或 FPK 日用工作区。Windows JVM 的目录 fsync 不可用，只用于开发测试；目标 Android/Linux 实施目录 fsync，真实掉电保证仍取决于私有文件系统，不能用 JVM 测试代替实机掉电测试。

证据四类独立：①本地内容已验证（每次命中重验 hash/codec）；②特定 endpoint/round 实际列举或直接读取到该路径；③本轮远端字节重新读取并与预期哈希精确核验；④历史验证器对特定输入闭包完成校验。②不推出③，①不推出②/③/④，④不推出服务器本轮完整。远端 round 标志不从磁盘缓存继承；缓存 API 不自动标记闭包，只有未来历史验证器可记录已验证闭包证据。PR53 的 Observation 是该调用契约的数据载体，不能认证调用者是否真的执行了图算法。

错误分类（引擎持久保存原因和证据并安全停止，PR55 负责正式 UI 映射）：`FORMAT/DOMAIN` 非法格式或业务；`IDENTITY/IMMUTABILITY` 身份/预期哈希/路径占用；`MISSING/NETWORK` 缺失或无法读取；`BUDGET` 预算耗尽；`CAUSAL/CONFLICT` 图/基线/冲突；`AUTH/FILESYSTEM` 鉴权、授权撤销、目录身份或文件类型改变；`UNCERTAIN_WRITE` 已尝试但未确认；`UNSUPPORTED` 未知版本/平台能力。不得吞异常后返回成功。必需祖先不可验证时停止应用；未引用的异常候选可留待复查，但不能报告完整同步。

## 7. v2→v3 边界及实现入口

未来迁移必须保留完整 v2 历史及归档、原绑定、已知停写与服务端停写的不同证据，并持久化源 dataset/精确前沿/hash、目标 descriptor/Genesis/hash 和业务等价证明。v2/v3 是独立协议身份与目录；即使迁移保留业务 datasetId，也不能将 DPC2 父引用加入 DPV3 或自动改写旧绑定。没有完整可恢复迁移事务前，不启用现有用户绑定，不以重装/清库修复。PR46/47 的登记和恢复日志不是 v3 迁移证明，v2 仍受原全局 256 提交限制。

实现：`SyncV3Codec.kt` / `v3-protocol.mjs` 是纯对象层；`SyncV3Store.kt` / `v3-store.mjs` 是隔离适配器；`SyncV3Cache.kt` / `v3-cache.mjs` 是私有缓存。PR54 的 `SyncV3History/Engine/Metadata/Room` 与 Node 对应模块实现图、换代、Bridge、离线重放和持久事务；正式产品绑定/UI/迁移留给 PR55，压缩/归档/物理 GC 留给 PR56。SDK 验收不表示生产 v3 已启用。

`tools/sync_v3_vectors.py` 用标准库独立构造线格式，复用独立 Python 的 DPSS 集合编码，不调用 Kotlin/Node codec。固定向量覆盖四类型、checkpoint、多父和 Bridge 引用形状，manifest 固定完整哈希/长度/业务哈希；错误向量重新封 checksum 后覆盖字段、排序、长度、UUID、hash、UTF-8、领域和尾随字节。Kotlin/Node 必须同时通过，Bridge 向量只证明对象编码，绝不宣称历史因果已验证。

## 附录 A：冻结的 RAW 集合和领域规则

下面的基本类型、集合及规则属于本规范的完整组成部分。

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
