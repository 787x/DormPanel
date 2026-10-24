# PR54 Draft：隔离引擎实施与验证

基线：`main` / PR53 merge `ebddee920823e5d668fc415f3a24b45d9f812bca`。

**本 Draft 尚未满足任务的全部 Done Criteria，不能作为 PR55 正式接入已验收的前提。** 以下区分已实现、实际运行的检查和阻塞项。不启用生产 v3，不修改正式绑定、v2、Room 业务表、签名、版本号或 applicationId，不迁移、合并、发布或 GC。

## 当前实现

- Kotlin/Node 独立 `V3Engine`、历史验证器、前缀发现器、持久发布协调器及私有事务适配器。普通业务合并复用 `ScheduleMerge` / 既有 Node merge；多分支冲突复用现有 v2 的业务计划算法，不写入任何 v2 envelope。
- 队列、已观察路径、前缀审计游标、对象审计游标、异常、基线、本地课程、精确 Pending/capture、冲突输入和选择均持久保存。目录的一批结果与进度原子提交；中断可重列，不持久化部分非法列表。已观察路径不因漏列删除。
- 验证 descriptor、全局 objectId、每 Epoch 根及容量、引用身份/哈希、PARENT 闭包、Checkpoint 业务结果、Transition 的精确前沿、LATE 的最大共同基线。普通 Commit 不要求等于自动 merge 输出。只有从初始 Epoch 沿已验证 Transition 可达的 Epoch 才进入活动集合。
- 验证栈保存路径及引用元数据；超过单轮展开/验证预算时保存栈，下一轮从未完成位置继续。原始快照只在需要时读缓存；历史证据元数据带独立校验摘要。该摘要检测本地元数据损坏，不是认证签名或远端存在证明。
- 约 160 个已观察对象时换代；计数包含 Genesis、Commit、Transition。先 Genesis PUT/GET，再 Transition PUT/GET，最后活动代回执。并发同代候选通过更高代承载及严格低代 LATE 整合。业务等价的并发前沿保留为 `EQUIVALENT_FRONTIERS`，不反复自动换代；调和换代最多连续四次，无法收敛则保留候选并停止。
- 迟到路径通过真实因果引用纳入，重复出现不再制造提交。本地离线编辑通过旧 baseline 与已验证远端状态重放。决策检查本地 revision，保留全部展示输入；提交期间的编辑在 receipt 中再次重放。

## 持久发布状态机

```text
STAGED → ATTEMPTED → READBACK_CONFIRMED → RECEIPTED
              └──→ UNCERTAIN ──原路径只读回查──→ READBACK_CONFIRMED
```

`STAGED` 保存原身份、精确字节、哈希及 capture。写网络前，必须 await/完成 `ATTEMPTED` 的文件和目录 fsync；新的 `putOnce` 只执行一次普通 PUT，协调器不调用 `createForIsolatedTest`。重开后的 ATTEMPTED/UNCERTAIN 只 GET，404 保持不确定，不重新 PUT。内容不同保留异常。receipt 与本地状态重放在同一私有原子文档提交，网络不持有存储事务。

持久替换在 rename 后报错时，Journal 进入禁止写入状态，要求重开；不能用旧内存帧把磁盘上的 ATTEMPTED 覆盖回 STAGED。进程间单 Writer 仍是调用者契约；fnOS 验证使用既有包用户与 flock。此处不是正式 Workspace/Room 产品适配器。

## 缓存结构

新 `sync-v3-index-v2/<pathSha前2位>/<后2位>/<剩余SHA>/` 按协议路径直接定位 `witness.json` 与 `object.dpv`。receipt 先于原始字节持久化，blob 经临时文件、fsync 和 rename 公开。正常打开不枚举历史、不解码历史快照；缺内容可按原路径 GET，预期哈希仍保留。pin 记录引用拥有者；本 PR 不删除任何对象。旧 PR53 平铺缓存不自动搬迁、覆盖或清理。

这修复了**新引擎对象缓存**的 4096 项启动阻塞；不等于发现器及历史元数据已经实现无限增长，见下方阻塞项。

## 实际执行

| 检查 | 实际结果 |
| --- | --- |
| Kotlin 大历史 | `SyncV3EngineTest.publishes1024AndRotatesWithoutGlobalCommitLimit` 在实施阶段通过；同一逻辑数据集 1024 次编辑发布、至少 6 个 Epoch，随后可继续读取；与另外 3 个引擎测试及 9 个 PR53 测试同轮通过，共 13 项，约 11 分钟 |
| Node 大历史 | Windows 单项通过，约 163 秒；NAS 官方 Node 22.18.0 / 192 MiB heap 下同场景通过 |
| NAS 完整 v3 阶段回归 | 包用户 UID 993、独立随机 `/tmp/dormpanel-pr54-*`、flock；38 项 PR53 + 当时 8 项引擎测试，共 46 项全部通过、无跳过，约 291 秒。后续修订另做针对性检查，不将此记录当作最终源码整套重跑 |
| Kotlin 定向检查 | 多 Writer、可恢复冲突、并发候选更高代 Bridge、迟到分支去重、Checkpoint 夹带编辑拒绝、持久 Pending；已运行对应检查并通过 |
| 深层闭包恢复 | Node 4 个 Epoch、524 个快照对象的末端闭包在多轮重开后完成，验证栈确有保存；Kotlin 2 个 Epoch、302 个快照对象对应场景通过。没有增加单轮 256 项预算 |
| Node 故障与决策 | PUT 成功丢响应、404 不重 PUT、rename 后落盘失败、上传期间再编辑、Genesis/Transition 分别丢响应、过时决策拒绝、等价候选不产生换代风暴，通过定向测试 |
| 后续定向回归 | Kotlin 最后定向 4 项通过（含持久验证栈）；NAS 后续 10 项引擎检查通过，包含新索引、深层恢复、并发/离线/故障；Windows 另验证目录中断、漏列不遗忘和损坏元数据拒绝 |
| 新索引 >4096 项 | Kotlin/Node 及 Phone/X08E 实际创建 2100 对象、4200 个 receipt/blob 文件，冷打开按需读取、缺 blob 保留 witness、原身份变更拒绝；Android 测试按 21 轮写入 |
| 真实 HTTPS WebDAV | 仅随机 `dormpanel/pr54-engine-9adb190d-c550-4fb2-988d-358bbd124e88/`。Node 初始化 → Kotlin JVM 接收、换到第二代并发布 → Node 接收；随后 Phone/X08E 测试客户端发布，NAS 包用户读取五端记录，并模拟 PUT 成功后丢响应，重开恢复且实际仅 PUT 一次 |
| Android 实机 | 独立 `com.dormpanel.schedule.core.test` APK，Phone MIX 2S 与 X08E 均 2 项通过；Phone 约 24 秒、X08E 约 127 秒。只用测试私有随机目录和上述隔离远端，不覆盖日用 App |
| v2 回归 | Kotlin `AppendOnlyScheduleSyncTest` 16、`ScheduleV2BindingTest` 5、`WebDavCommitStoreTest` 8、`ScheduleMergeTest` 12 项通过；Node protocol/graph/writer/store/history-observation 共 71 项：70 通过、1 项平台跳过 |
| 构建 | App/Phone Debug APK、schedule-core 测试 APK 构建通过；`npm run build` 生成 FPK 工程通过。未据此声称完成官方 fnpack 最终 FPK 打包或安装升级 |

固定 DPV3 字节与 16 层远端路径格式未修改。原始日志及含凭据的连接配置只在忽略提交的 `artifacts/pr54/`；本文件不包含凭据。初次真实 Kotlin PROPFIND 暴露目录不支持可选 getcontentlength 的 404 propstat；现只请求必要 resourcetype，GET 仍限制字节。早期首次接收误把空本地当删除的隔离测试失败已修复；保留失败随机目录，不删除远端证据。一次测试筛选误启动大历史重复项，已停止，不计为通过。

持久验证栈与元数据摘要修订后，Phone 的 HTTPS 单项再次通过（约 10 秒）；X08E 一次并发复测因非 207 响应停止，首次未保存状态码，不能推断具体原因。加入不含凭据的状态码诊断后，X08E 串行重查通过（约 35 秒）。先前成功不能替代失败记录；没有把不确定网络结果记成回执。

## 合并前阻塞项

1. **引擎元数据尚未分页。** 发现器仍以 16384 scopes / 65536 observed 上限保护单个文档，历史证据仍放在至多 48 MiB 的 Journal。达到上限会安全停止，但不能通过跨轮游标继续增长。这不是任务要求的长期、可分页元数据方案，不能将本 Draft 描述成完整可持续引擎。
2. **故障矩阵未穷尽。** 尚无每个存储/网络边界的进程终止矩阵、真实掉电、所有缺失/漏列/篡改/并发路径变化与多协调者反复分叉组合 oracle；现有测试只能证明已覆盖场景。
3. **平台事务接入仍隔离。** 测试中的私有课程/receipt 单文档适配器通过恢复验证，但没有正式 Room 与 Workspace 接口接入，也没有对新引擎跨进程同目录双拥有者的排他机制做完整验收；不能绕过既有管理员、单 Writer 或 Workspace 保护。
4. **更广互操作待验收。** 实际 HTTPS 覆盖正常双向、多端、单次换代及不确定 PUT 恢复；1024 次、多代离线及复杂并发换代主要是 Fake Store。尚未在真实网络上逐项完成这些组合，也未验证大体积业务快照下的最大预算边界。

继续工作应先处理元数据分页及恢复日志索引，再补齐针对性的故障和权限验证。不得通过放大常量、跳过祖先证明、切生产绑定、清空数据库或删除旧历史来解除阻塞。
