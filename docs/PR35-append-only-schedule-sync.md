# PR35 — Append-only WebDAV 课表同步 v2

基于 main `e0f7fe7`（已包含 PR34 验收记录），在同一 PR 交付核心、Room 恢复与双端入口。PR34 的 **RC NOT ACCEPTED** 结论保留；不创建 tag、Release 或新候选，不修改 applicationId、正式版本、签名或冻结 RC1 文件。

## 实现

- 共享目录下独立 `dormpanel-sync-v2/`，每次发布随机 UUID 自包含 `.dpc` 文件。普通 PUT 前检查路径，写前持久化意图，写后 GET/逐字节/SHA-256 校验；禁用 PUT 的 HTTP 自动重放。没有共享 head、强 ETag、条件 PUT 或 LOCK 依赖。
- `CommitStore` 分离存储和合并。多 Writer DAG 验证全部祖先与 dataset，采用共同基线与 PR32 课程关联规则；不同课程独立合并，真正冲突持久化并由用户决定。未修改课程的第三端选择保留本地时也采用其当前值；确认 token 防止采用未展示的新远端方案。日期组成变化与相关指定周编辑/重置参与关联判断。等价 M1/M2 不产生无限纯合并提交；多重基线歧义安全停止。已保存冲突会继续检查远端，其他端提交因果上的处理决定后可正常接收。
- Room v4 和 v1→v4 migration 不变。v2 使用新 checkpoint ID：20 Writer、21 已验证虚拟基线、22 前沿、23 候选、24 候选的精确 capture、25/26 冲突基线、27 分支 ID、29 写意图、30..93 冲突分支、96 已验证 ID/hash、97 本机中断路径，102 保存目录/账号身份的哈希，防止重启后把待确认上传转移到另一个目标（密码可轮换），103 保存已观察的未验证路径并跨重启/列举遗漏继续回查。本地待发布标记从 Room 的当前数据、候选与已确认状态派生，界面本地提交后立即标记，避免后台任务的旧回执文案掩盖新编辑。原 1..7 不赋予新协议含义，2 继续表示本地完整 catalog。
- capture 保存候选计算时的状态，不能在 stage 时重新捕获较新的 Room 状态。上传期间，以及读取候选后、stage 之前的新编辑，回执处理后仍保持待发布。候选、重放、课程/catalog、前沿及冲突决定均通过事务保存。
- 损坏或未知远端文件不被当成提交；本机有持久化证据的中断路径可显式隔离并换新身份重试，持续显示“未确认/尚未完成全部版本同步”。其他端也将外层校验失败的孤立文件列为未完成项，继续检查完整可验证的版本；被引用的损坏祖先、已确认内容改变或有效封装的身份/语义错误仍拒绝合并。初版不删除、压缩或自动修复历史。
- 两端原共享管理入口提供创建、检查/绑定、同步、冲突处理、独立目录诊断及明确的 v1 切换。保留离线导入、增删、重置、学期编辑、原账号加密方式和普通 ICS/CSV 导入。前台/本地提交/JobScheduler 触发有界工作，无新永久服务或周期轮询。

规范、完整字段和领域约束见 [跨平台协议](sync-v2-protocol.md)。固定向量位于 `schedule-core/src/test/resources/sync-v2/`，独立 Python 编解码与 Kotlin 逐字节交叉校验；真实测试亦使用 Python 作为第三个独立编辑端，不实现 FPK。

## 切换与边界

先停止所有旧客户端的 v1 同步，再从管理页确认切换。仅读取旧 head/历史，验证 dataset、共同基线及候选回执；不能证明时停止。已提交回执精确恢复，未进入已证明历史链的候选保留本地修改。开始变更旧同步状态前，在新 ID 100 保存完整原本地课程/catalog，101 保存原记录存在集合，111..117 保存原 1..7 的恢复副本；切换失败也保留这些信息和凭据。第一个迁移端创建同 dataset 的 v2 根，其他已绑定端使用已验证旧基线合并 v2。非空独立手机拒绝直接下载覆盖，不按课程名猜身份。

旧 head、snapshot、探测文件不修改；旧客户端不会读取 v2，切换后禁止继续写 v1。若旧首次发布从未激活 head 且无法证明共同历史，初版会拒绝自动迁移，保留原数据与回执，不要求卸载或清库。

资源边界：256 个提交/图、64 个前沿或父引用、32 MiB 图输入、512 次单轮逻辑请求预算、64 MiB 图与隔离检查累计读取内容、最多 256 个冲突单位，较多条目逐项查看；90 秒后停止开始新操作（在途 HTTP 仍受 60 秒超时约束）；单提交至多 16 MiB + 8192 字节。达到上限明确失败并保留修改。目录成功仅表示检查了观察集合，不表示其他设备已经收到或服务器具备全局实时一致性。

## 验证方法

```powershell
./gradlew.bat :app:assembleDebug :phone-app:assembleDebug
./gradlew.bat :app:testDebugUnitTest :schedule-core:testDebugUnitTest :phone-app:testDebugUnitTest
./gradlew.bat :app:lintDebug :schedule-core:lintDebug :phone-app:lintDebug
python tools/sync_v2_vectors.py
git diff --check
```

测试隔离包使用 `-Pdormpanel.testBuildType=x08eTest`，两应用的 applicationId 追加 `.testbed`，仅测试构建受影响。fixture provider authority 随测试包变化，避免与已安装测试包冲突。CSV profile 回归用例使用独立偏好命名空间，避免既有测试残留的自定义配置使“首次使用内置预设”的前提失效。

真实服务预检：`tools/test-sync-v2-webdav.ps1` 需要明确 TestParent 和机器本地加密 CredentialFile，创建全新 `pr35-v2-<UUID>/`；并发普通 PUT、GET/SHA-256 和有限次数 Depth:1 列举，保留合成文件。

真实三 Writer：`tools/test-sync-v2-real.ps1` 使用两个明确设备及相同凭据，在全新 `pr35-e2e-<UUID>/commits/` 运行独立 Room 数据库的离线编辑、并发上传、Python 第三个 Writer、收敛/幂等、同课程冲突及处理决定传播。不得传入生产提交目录；测试类严格验证随机目录标记。凭据、原始日志和截图仅保存在忽略的 `artifacts/pr35*`。

跨进程测试：向核心 runner 指定 `AppendOnlyProcessRecoveryTest`、同一 `pr35Run=<UUID>`，先 `pr35Phase=seed`，对**隔离核心测试包** force-stop，再 `pr35Phase=verify`。验证持久化候选/capture/后续编辑以及确认后的重放；这不等于在真实 HTTP 上传中杀进程的证据。

手机 MIUI 对 instrumentation 的 Activity 启动会显示拦截确认。经用户授权，测试 APK 可显式传入 `pr35RootUi=true`：只在测试线程实际等待 startActivitySync 时用 root 辅助拉起 `.testbed` Activity。辅助代码仅存在 androidTest；应用仍以普通 UID 运行，不更改 OEM 权限、系统设置或日常安装。默认模拟器路径不启用此参数。

## 实际结果（2026-10-21，上海时间）

| 范围 | 实际结果 |
| --- | --- |
| 构建、JVM、Lint | X08E/Phone debug 构建成功；app 198、schedule-core 134 项 JVM 测试全部通过；三模块 Lint 无 error（现存 warning 分别 96/22/11）；`git diff --check` 通过。隔离双端 APK 与测试 APK 构建成功。 |
| 跨语言 | Python 独立校验 6 组固定向量；Kotlin 逐字节交叉验证通过。 |
| Room 与恢复 | 最新 12 项新增持久化测试在真实 API28 X08E、API33 手机各通过一次，覆盖三数据库、迁移、回执恢复、事务失败、上传间编辑、未确认路径和冲突 token。API28 模拟器完整核心 36 项通过；独立 seed→force-stop→verify 两阶段各 1 项通过。 |
| 双端 UI 与既有功能 | 手机经明确授权的 root 测试辅助启动，7 项课表 UI 测试通过；API37 模拟器手机 7 项通过；API28 X08E 隔离发布 UI 1 项通过；API28 普通 WebDAV/CSV/HA 回归 20 项通过。root 只用于隔离测试 Activity 启动。 |
| 真实 fnOS + FN Connect HTTPS | 独立随机目录内两客户端并发普通 PUT、GET/SHA-256、Depth:1 发现全部文件通过；真实 X08E/手机与独立 Python 第三 Writer 的离线并发、收敛、幂等、同课冲突及决定传播通过。只使用合成课表与隔离目录，未操作旧 head、生产课表或历史。 |

设备自动化日志、截图、真实预检报告与各阶段回执保存在本机忽略目录 `artifacts/pr35*`，不提交凭据。最新真实三 Writer 全阶段再次通过，日志位于 `artifacts/pr35/real-final-latest/`，最新 X08E UI 1 项通过。API37 核心完整重跑曾被 ADB 中断，不将截断日志记作通过；上表区分模拟器与真实设备以及最终新增用例和较早完整回归。手机最新完整 UI 重跑另遇小米“自动保存账号密码”系统弹窗，失焦导致失败，截图已确认；取消该弹窗，不保存测试凭据或改变系统设置，该次失败不计为通过。随后分别重跑最新共享课表与冲突 UI，各 1 项通过（22.615/31.818 秒），日志 `pr35-phone-shared-final.log`、`pr35-phone-conflict-final.log`。

尚未验证：本分支正式包对真实日用数据的原位升级/迁移、真实 FN Connect 上传中断时杀进程、设备重启后的恢复、OEM JobScheduler 长期调度、长期常驻与真实 HA/小米服务完整回归。Room/Fake 故障注入与隔离包通过不能替代这些实机验收。无法证明旧共同历史的首次发布仍安全拒绝迁移，需要保留原数据后人工确认。

可进入 **0.1.2-rc.2 发布准备与补充验收**，不等于 RC 接受或发布授权。PR34 的 RC NOT ACCEPTED 结论不变，RC1 APK/哈希、正式版本、签名及发布历史均不改变；本 PR 不生成新候选。
