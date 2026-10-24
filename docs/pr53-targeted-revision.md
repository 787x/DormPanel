# PR53 合并前定向修订

修订基线为 Draft PR53 的最新审查 head `8963efe298baf43c635dc5f21c9441c2b069721b`。继续更新原 PR，不启用 v3 正式同步、换代、迁移、UI 或 GC。

## 两项修复

1. 本地候选暂存在官方授权父目录的随机兄弟目录 `.v3-stage-<UUID>/` 中，与 `dormpanel-sync-v3/` 平级，权限精确检查为 0700。复用既有 LocalCommitStore 的 FD/O_NOFOLLOW、已验证文件系统、文件/目录 fsync、排他 hard link 边界；新增同设备检查。公开后仍精确回读；已有不同内容或并发 EEXIST 绝不覆盖。正式目录没有临时文件白名单。仅清理本次创建的暂存目录，未知崩溃残留不自动删除，也不影响正式 Epoch 列举。
2. Epoch 改为 `dataset-<datasetId>/epochs/<16 个两位 hex 段>/`。16 段是 epochId 去连字符后的原始 UUID 字节，一一对应完整身份；每层天然最多 256 个分支，不会把累计 Epoch 数挤在某个固定深度桶中。Kotlin/Node 同步更新严格路径、叶身份、目录类型与分层 WebDAV/Local list。新 `scopeInfo/describe` 可区分前缀和完整叶；完整引用可直接计算路径，无需先全树扫描。独立 Python 路径向量冻结中间 scope、对象位置、旧平铺拒绝及边界。

对象 codec、业务合并规则和 `.dpv`/manifest 原有字节没有改变；新增加的是 `paths.json`。不自动改写旧草案隔离缓存或目录，旧路径明确拒绝。正式 v2 源码、数据、目录和绑定入口未修改，正式版本、签名、applicationId 与日用 FPK 未改。

## 本轮真实执行

| 检查 | 结果 |
| --- | --- |
| 旧实现的路径 oracle | 新路径向量最初对旧平铺实现明确失败，修复后通过 |
| Kotlin `:schedule-core:testDebugUnitTest --tests com.dormpanel.app.schedule.SyncV3Test` | 9 项通过，包含 codec、引用身份、缓存、严格列表/回读、目录树模拟 WebDAV 的 300 Epoch 分批发现；单个前缀实际返回 256 个合法子目录，每轮最多 3 次列表请求 |
| Windows Node `node --test fnos-app/test/v3.test.mjs` | 38 项中 34 通过，4 个 Linux 项明确跳过 |
| NAS 官方 Node 22 / FPK 包用户，同一 v3 测试文件 | 38 项全部通过、0 跳过；源码和数据仅在随机 `/tmp/dormpanel-pr53-revision-*` 隔离位置，未修改已安装包或其依赖 |
| fnOS 本地故障 | 暂存期间同时列举正常；写前故障清理正常；预检后目标被其他写者占用时 link EEXIST、不同内容完整保留；子进程分别在暂存完成后、公开并 fsync 后被 SIGKILL，重建 Store 后 list/read/create 正常，未知兄弟暂存残留仍保留 |
| fnOS 实际多 Epoch 目录 | 同一个数据集实际保存 300 个有唯一身份的 Genesis/Checkpoint 形状对象，逐项核验前代引用哈希；在含完整 256 分支的前缀上仍正常列举，跨预算轮次恢复 JSON 前缀队列后发现全部 300 个叶。该测试不声称已实现正式换代/Transition 图验证 |
| 真实测试 WebDAV | 新随机测试目录保存并精确回读 7 对象，缓存重开 7 个命中；实际遍历 16 个前缀目录发现 2 个 Epoch。未访问日用服务器的课表目录 |
| 独立 oracle | v3 28 个固定文件逐字节一致，原 `.dpv` 和 manifest 相对 `8963efe` 无变化；v2 6 个原有向量通过 |
| 必要构建/静态检查 | 上述 Gradle 测试包含 schedule-core 主/测试 Kotlin 编译；修改的 Node 存储、测试及故障子进程脚本语法检查通过 |

无凭据摘要见 [revision-verification.json](evidence/pr53/revision-verification.json)，原始日志在忽略提交的 `artifacts/pr53-revision/`。本轮不机械重复无关 JVM 全量、Android 实机、Release APK、FPK 打包或长时间测试；初始 head 的这些检查保留在[原验收](pr53-sync-v3-verification.md)中，不作为本轮重测声明。真实 WebDAV 未创建 300 个 Epoch；超过 256 的实际目录验证在 NAS 本地文件系统完成，Kotlin WebDAV 使用 MockWebServer 覆盖该边界。

## PR54 前置条件和剩余风险

- **4096 项缓存恢复上限仍在。** 一个对象通常对应 blob + receipt，约 2048 个对象就可能触及数量上限，字节预算也可能更早耗尽。远端 Epoch 树解决了单层累计目录上限，尚未解决本地全缓存启动恢复。PR54 在正式启用长期多代同步前，必须实现版本化分桶/可游标持久索引、分批重建游标、按需对象重验、恢复日志/候选引用 pin，以及超过 4096 项和批中断恢复测试；不能简单取消预算。详见协议第 6 节的明确前置条件。
- 16 层树增加首次创建的目录操作和深度。当前测试服务器已实际验证；其他 WebDAV 的深度/权限限制可能失败，须明确报错，不能退回平铺、放松规则或误报成功。
- 普通 WebDAV 仍可能漏列；已完成前缀扫描不代表永久封存。PR54 需持久队列/直接引用回查和公平轮转重扫，保留并发候选及迟到对象。本轮只提供路径接口和测试中的有界恢复，不实现同步引擎。
- 杀进程不是物理断电测试；未知私有暂存残留保留，后续生产协调器仍须关联 attempted 日志并决定恢复。缓存单拥有者、现有 Writer/事务保护等前提不变。
