# PR54 合并前定向修复：Workspace 事务与预算

审查基线：`b649d18c1e8fe68c780364a1befee55432700391`。修复代码 HEAD：`1c2dab35eb779316f6615faad5b3076152006179`；后续文档提交不改变被测源码。原 PR54 保持 Ready for Review，未合并、发布、安装或升级日用 FPK。

## 修复内容

此前 `Workspace.tx()` 先执行引擎回调，之后才由适配器 `store.save()` 打开 SQLite 事务。`resolve()` 的 `decision`、`saveConflict()` 的 `conflictHistory`、回执及混合 put/remove 因此可能位于事务之外。同时，课程对象 `local` 遮蔽了布尔事务标志，导致同步控制事务错误使用本地预算。

- Workspace 增加可选的**同步提交钩子**。v3 适配器在调用准备回调之前开启 SQLite 事务；回调、队列执行时权限/revision 检查、mutationGuard、课程领域校验、revision 增量、元数据与帧保存共同提交或回滚。v2 文件存储仍走原有 save 路径。
- 只有提交钩子成功返回后，才更新 `Workspace.frame`。COMMIT 前错误不会发布克隆帧；COMMIT 后确认失败保留已有的禁止写入/重开机制，重开读取完整提交，不用旧内存覆盖磁盘。
- `isLocalTransaction` 与 `courseState` 分别表示事务类别与课程内容，删除通过课程对象真假值分类的 WeakSet。预算在回调之前选择，并在 finally 恢复原 Budget。用户编辑/决定使用既有本地预算；同步控制/元数据继续共享原同步预算，不提高上限。
- 保留 `V3Metadata.put/remove` 的事务检查、已有单 Writer 锁及 Workspace guard/revision/领域规则；新增队列执行时的权限复查。网络仍由引擎在同步 SQLite 回调之外执行，没有第二套 Workspace 状态。

检查了引擎内所有 `journal.transaction()` 嵌套元数据路径：`decision` 写入、回执冲突刷新/归档、过期冲突输入归档和 `saveConflict`。此次统一事务边界覆盖这些回调，不只针对 `resolve()` 特判。

## 实际测试

新增 `fnos-app/test/v3-workspace-transaction.test.mjs`，全部通过真正的 `openV3Workspace()` 运行。初始 6 项用例在旧实现上有 4 项失败，直接复现事务外写入和同步预算被绕过；修复后扩展为 **9 项全部通过**：

1. 两个隔离 Writer 在同一课程产生真实冲突，完整 base/local/remote/keys/paths 保留；过期网络预算下决定成功，决定、课程、revision 与索引关闭重开一致，另一端接收最终选择。
2. decision 写入后、Workspace 帧保存前注入失败，检查全部 SQL 行、内存帧和 revision 都未改变，重开仍为待决状态。
3. conflictHistory 与新冲突帧已在事务内写入时，于 COMMIT 前抛错，两者一起回滚；重试后关闭重开均存在。
4. 混合元数据 put/remove 后领域校验失败，连同课程/revision 全回滚；过期 revision 和拒绝权限不执行写入回调。
5. 零字节同步预算下控制更新正确失败；显式本地回调在独立预算下读取/写入，退出恢复原 Budget；本地编辑在过期网络轮次下成功。
6. 旧决定和排队后失效的 revision/权限拒绝时，无 decision、课程或新索引残留。
7. COMMIT 已落盘但确认失败：内存帧不提前切换，存储禁止继续写，重开恢复完整业务与索引提交。
8. Workspace Pending/回读恢复：服务端已创建对象却丢响应，重开只读确认；receipt 索引与帧一起回滚/重试，实际 PUT 为 1 次。
9. Fake 网络的 read/list/put 均断言 `meta.depth === 0`，避免网络位于 SQLite 事务内。

| 本轮检查 | 实际结果 |
| --- | --- |
| Windows Node 新 Workspace 定向文件 | 9 通过、0 失败 |
| Windows Node 受影响 v3 回归 | 23 通过、0 失败；涵盖 Pending、冲突、跨代、索引边界及拥有者；明确排除 1024 次增长等无关长测 |
| Windows Node v2/权限/Workspace/迁移/备份回归 | 95 项：50 通过、45 个 Linux 项跳过、0 失败 |
| fnOS 官方 Node 22.18.0 / 包用户 UID 993 / 192 MiB heap | 同一新测试 + 既有恢复/拥有者 + 上述 v2 回归，共 114 通过、0 跳过、0 失败，约 60 秒 |
| fnOS 工程检查 | `npm run check`、`npm run build` 通过，生成工程；未执行本轮官方 fnpack 或安装升级 |

NAS 源码与测试只在随机 `/tmp/dormpanel-pr54-*`，使用包用户和独立 flock；未更换已安装应用。测试数据均为临时隔离数据。原始日志仅保存在忽略提交的 `artifacts/pr54/workspace-*.log`，不包含生产凭据。

## 证据范围与剩余风险

本轮不重复 Android 实机、1024 次历史增长、完整多端 WebDAV、官方 FPK 包及长期运行；原验收文档中的这些结果属于此前实现阶段，不是新 HEAD 的全套重跑。本次未修改 Kotlin、冻结 DPV3/Epoch 布局、业务合并规则、生产绑定、版本号或正式入口。

事务保证依赖适配器同步提交钩子与可靠的本地 SQLite/WAL；同步回调禁止 Promise/网络 I/O。宿主服务仍须保留现有权限 guard、实例锁和 IPC。物理断电及所有存储实现不作为本轮实测结论。未发现本轮范围内的剩余阻塞项，等待再次审查。
