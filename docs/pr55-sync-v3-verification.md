# PR55：Sync v3 生产接入与迁移验收（进行中）

基线：`fea09bebe9f3d3865bbbe6e81f4e61b587098238`，已与远端 main 核对。此文档记录阶段证据，尚不代表 Done Criteria 全部通过；PR 在最终验收前保持 Draft，不合并、不发布。

## 接入方式

- Android 保持 `ScheduleDatabase` v4 和日用 `schedule.db`，在同一个 Room 连接中增加私有 `v3_records` 表。课程行、八类 RAW 快照、候选、capture 和回执共享事务；不打开第二个可写课程库。v2 checkpoint 与原学期目录保留。协议标记位于 checkpoint 205，切换前完整本地快照保存在 206；既有 200/203 迁移保护继续生效。
- 生产 `ScheduleStores` 为进程内唯一 Store；Activity 与 JobService 共用。页面仍通过 `ScheduleSource` 投影，编辑、导入和学期配置进入 v3 本地事务。旧 v2 发布入口检查活动模式；首次加入未验证完成及迁移冻结期间暂停业务编辑。
- fnOS Catalog 显式保存工作区协议；v3 Workspace 使用 PR54 SQLite 适配器和宿主持久安装 writerId，沿用同一编辑、导入、revision、管理员、CSRF、IPC 及目录授权入口。无独立 HTTP Writer。
- 创建时 Descriptor、Genesis 精确候选与 binding 同事务持久化，覆盖了 PR54 原先两次事务之间的中断窗口。恢复使用原候选，ATTEMPTED/UNCERTAIN 不盲目重 PUT。
- Android checkpoint 205 仅保存轻量模式/端点/迁移 ID；完整迁移证明使用同一 SQLite 的 `hostMigration` 命名空间及 PR54 不可变快照引用。UI 只取控制摘要，不序列化 local/baseline/capture；首次加入的只读预检使用独立私有索引缓存，不是第二个可写业务 Store。确认并复检前不切换业务库。
- fnOS 的迁移清单是轻量引用，完整 source capture 与合并快照同样保存在目标私有 SQLite 中；冷启动不把全部迁移课程和旧 DAG 重复装入 Catalog。非活动 v3 Writer 关闭并释放锁，重开等待关闭完成，旧引用立即失效。

## 迁移边界

迁移预检只读取 v2，不调用其发布引擎；256 提交时不要求再发布。验证已知哈希、前沿、祖先、原基线及完整 RAW。本地未发布修改按既有三方合并语义处理；真实冲突、待确认 PUT 和不可验证历史停止迁移。

确认后持久暂停源 Writer，保留源本地快照、基线和全部已知提交证据；仅生成一个新 v3 目标。新 Genesis 精确回读、RAW 字节等价并再次验证源后激活。没有跨协议父引用。后续设备应加入已验证的目标身份，不能各自重新迁移创建数据集。

后续 v2 设备可以选择已有 Descriptor，经只读完整历史/前沿验证与 RAW 等价检查后加入同一目标，不生成第二个 Genesis。此入口当前只接受完整 RAW 等价；若目标已有业务变化或存在冲突，不执行跨协议推测合并，保留两端并停止切换。创建新目标时，源端尚未发布的本地修改仍按已证明 v2 基线进行既有三方合并。fnOS 的未绑定本地 Workspace 也可保留完整快照创建 v3，不必先发布 v2。

已注册元数据帧/安装 Writer 缺失时安全停止，不自动建空数据集。提交确认不确定时旧内存停止写入；Android 提供重开同一个活动 Store，fnOS 清单确认不确定时阻止旧引用继续写入并要求服务重开。重开、取消只读预检均保留原始快照和历史；已经激活或产生本机发布的目标不允许简单回退。

普通 WebDAV 无法发现未知旧 Writer、证明所有设备确认或强制旧目录停写。原 v2 历史仍保留在远端，不等同于独立、回读验证的备份。迁移后旧源检查只记录跨协议分叉，不当作 v3 LATE 整合；不删除历史。目标已有候选或已接受 v3 编辑后禁止简单切回 v2。

当前 DPBK0001 备份格式不能完整覆盖 v3 SQLite、Pending、身份与迁移证据；存在相关状态时明确拒绝创建/恢复完整应用备份，不生成标为完整的残缺包。v3 历史为只读摘要；v2 回退、归档、精简明确拒绝。v3 已绑定连接变更和工作区移除仍保守拒绝，等待安全端点迁移适配。

## 已实际执行的阶段检查

| 检查 | 阶段结果 |
| --- | --- |
| Kotlin/JVM 初始化原子性、丢响应恢复、双 Writer 冲突回归 | 已通过定向运行；不代表最终 HEAD 整套重跑 |
| Kotlin/JVM v2→v3 预检 | 3 项通过：256 提交、RAW 保持、未发布修改合并、真实冲突、Pending、哈希变化、未知祖先、预览过期 |
| Android Phone 生产 Room Store 隔离测试 | 2 项通过；另一次迁移恢复测试 1 项通过 |
| X08E Android 9 生产 Room Store 隔离测试 | 2 项通过；另一次迁移恢复测试 1 项通过 |
| NAS 官方 Node 22 定向测试 | 一批 75 项通过、0 失败、0 跳过；含新接入、迁移及原 CLI/MCP、写 API、权限、备份回归 |
| Android App / Phone Debug 与 schedule-core 测试 APK | 已构建通过阶段版本 |

后续阶段：Phone 实际 testbed 界面创建、编辑发布、空库加入 1 项通过；X08E 活动 Room Store 6 项通过，包含后续设备加入同一迁移目标、元数据帧缺失拒绝重建、丢失提交确认后重开同一 Store。Phone 的 MIUI 后台测试启动被限制，测试夹具通过显式 MAIN/LAUNCHER 启动 testbed，没有修改系统权限。正式网络使用系统 DNS 与正常 HTTPS 校验；库测试 APK 另提供前台 probe Activity，避免把后台 UID 的 DNS 限制误判为业务恢复错误。

fnOS 按用户授权更新了包版本：0.7.3 已通过侧边浏览器安装验证，后续恢复/加入补全形成 0.7.4 验收候选；不创建 tag/Release。0.7.3 的官方 fnpack 与 39 份包内文件核验通过；实际安装前后已有 identity 与所有旧 Workspace 业务文件哈希保持不变。最终包与最终源码的结果将在下面补记，阶段包不作为最终 HEAD 验收。

Android 检查只更新独立 `com.dormpanel.schedule.core.test` 测试 APK，数据库均为 `pr55-*` 随机文件。没有打开或迁移日用绑定。NAS 测试使用随机 `/tmp/dormpanel-pr55-*` 目录；首次 Linux 运行因测试夹具打包不全出现两项失败，补齐夹具后上表 75 项全部通过。原始日志与私有连接材料仅在忽略提交的 `artifacts/pr55/`。

## 最终验收尚待完成

- 对最终 HEAD 执行相应回归与故障注入，补足源变化、恢复保护及过期后台任务的定向证据。
- 在真实隔离 HTTPS 上通过正式活动 Store 完成 Node / Phone / X08E 双向编辑、冲突、重开与 Epoch 链路。
- Phone 与 X08E 的实际页面创建、加入、编辑、迁移与恢复操作及 1280×800 视觉验证。
- fnOS 网页/CLI/MCP 的新增迁移独立批准闭环、授权本地目录生产 v3 接入及官方 FPK 内容核验。
- 核对恢复状态表达和错误脱敏，更新本文件为最终实测结果后才转 Ready for Review。
