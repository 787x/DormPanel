# PR41：Android 首次绑定已有 Sync v2 共享课表

验证日期：2026-10-22（Asia/Shanghai）。开发基线：已合并 PR40 的 main `7a078e5`，Android 正式版 0.1.2、fnOS FPK 正式版 0.3.2。结论：本文限定的 **X08E 首次绑定及三端互操作 LIVE PASS**；候选尚未发布，未创建 tag、Release 或自动合并 PR。

## 使用方式

1. 在 X08E「Control Center → WebDAV Settings」配置原有共享 WebDAV 账号。已有账号无需另配。
2. 打开「Schedule → Timetable → Sources → 共享课表管理（WebDAV）」，填写已有共享父目录完整 URL；不要填写末级 `dormpanel-sync-v2/`，应用会自动追加。
3. 空且未绑定的课表选择「绑定已有共享课表（预览）」。核对 Dataset、Commit、课程和事件数量、来源、学期、已验证历史及异常文件数量，再选择「确认下载并绑定」。取消只关闭预览。
4. 「创建新的共享课表」用于将本机课程创建为新数据集；已有远端提交的目录会拒绝另建无关根。绑定后显示「立即同步」，原有状态及冲突处理入口继续使用。
5. 本地已有课程、事件、来源、自定义学期、日期调整、课程覆盖或同步恢复状态时会拒绝接收覆盖。请保留原数据；本功能不提供清库、独立 dataset 自动合并或多工作区切换。旧 v1 绑定仍须明确迁移并停用旧客户端。

手机保留原账号设置与首次预览绑定入口，复用本次安全复验及事务绑定。X08E 不增加另一套凭据存储。

## 实现与安全边界

- `ScheduleV2Binding.inspect()` 复用 `ScheduleCommitGraph` 与既有编解码器，验证身份、完整性、祖先闭包、单根、版本图及既有容量限制。绑定检查有 512 次逻辑请求、64 MiB 累计读取和 90 秒启动请求预算；图本身继续限制 256 个提交、32 MiB 解码输入等。
- 预览携带账号/目录目标指纹、精确提交字节、全部已验证 ID→SHA-256 历史与暂不可验证集合。确认时重新列举和读取完整图，并回查预览中的已知历史及异常路径；身份、提交、历史、异常状态变化均拒绝旧预览，要求刷新。多个 tips 一律要求先由已有编辑端合并，不选任意 tip。
- 保留 Sync v2 原有异常孤立文件语义：未被有效提交引用的损坏/中断文件可以暂缓，但必须显示、复验并持久化清单，不能宣称完成全部同步；被引用祖先缺失/损坏、不同 dataset、身份不符、不支持语义、已验证历史变化均拒绝。
- `RoomScheduleStore.v2Bind()` 在一个 Room 事务中完成空数据及恢复保护检查、课程、catalog、基线、frontier、完整历史、异常路径与目标指纹保存。首次绑定复用原 `v2Start(binding=true)` 的核心逻辑，并加强对旧冲突、capture、迁移恢复记录等的保护。中途异常回滚全部状态；保留本机 writer，不采用提交作者的 writerId。
- 预览和绑定不调用远端发布或创建 collection。成功后的同步仍走原 `AppendOnlyScheduleSync`，本地后续编辑产生不可变后继提交，回执、离线修改、冲突及迁移语义不变。
- 确认框采用可滚动、可选择的文字，显示来源名称/类型/数量及学期时区、首周、阶段数量；操作按钮至少 48dp。关闭管理窗口会关闭其预览，并忽略迟到的预览回调。

本次复验确认的是服务器实际观察到的版本图，不能锁住远端，也不保证服务器及时列出未观察文件。复验完成后才到达的提交交由正常后续同步发现，不声称全局同时一致或其他设备已接收。

未修改 DPC2/DPSS 字节协议、FPK、Room schema、applicationId、签名、HA、ICS/CSV 导入或权限；未新增运行时依赖、后台轮询或设备侧 Root/ADB 行为。下述 ADB/管理员操作仅用于隔离测试与备份，不是产品功能。

## 自动化验证

实际运行结果见 [测试摘要](evidence/pr41/verification.txt)：

| 检查 | 实际结果 |
| --- | --- |
| `:app:testDebugUnitTest` | 198 项通过 |
| `:schedule-core:testDebugUnitTest` | 141 项通过，含新增 5 项绑定检查测试 |
| `:phone-app:testDebugUnitTest` | 任务成功，无独立 JVM 测试源码 |
| API 28、1280×800：绑定 Room、Append-only Room、进程恢复、既有双向同步 | 28 项通过，含新增 5 项 Room 测试 |
| 同一 API 28：X08E 正式入口创建与已有数据集绑定 UI | 2 项通过，包含取消、预览后新增提交被拒绝、刷新绑定、重建 Activity、编辑、重复同步 |
| 同一 API 28：手机共享、双向编辑冲突、重启 UI | 3 项通过 |
| 三模块 Debug 与 AndroidTest APK 构建 | 通过 |
| 三模块 `lintDebug`、`lintRelease`，两端签名 Release 构建 | 通过 |
| `python tools/sync_v2_vectors.py` | 6 个固定跨语言向量通过；JVM 绑定测试读取复杂固定向量 |
| 发布守卫、真实 APK 拒绝测试 | 37 项 mock 守卫、8 项真实产物拒绝检查通过 |

新增测试直接覆盖预览后新增后继/并发分支、缺祖先、已知历史内容变化、不同 dataset/目标、离线、异常孤立文件复验、多根和数量上限。Room 测试逐项比较拒绝前后课程与 checkpoint；覆盖课程、事件、导入来源、自定义学期、日期调整及旧绑定/冲突/pending/capture/迁移恢复记录。分别在写课程中途注入异常、最后目标 checkpoint 插入时用 SQLite trigger 注入失败，证明课程、catalog、baseline、frontier、历史全部回滚，重开数据库后可重试。

既有 Append-only 测试继续覆盖离线编辑、上传回执丢失、路径隔离、冲突恢复、多端合并与 v1→v2 迁移。Fake WebDAV 的 X08E/Phone 界面测试检查真实控制器与 HTTP 路径；首次绑定不产生 PUT，绑定后新编辑只产生一个后继提交，重复同步不增加提交。

## 真实 X08E、Phone、FPK 验收

仅使用测试 NAS 的独立合成目录 `dormpanel/pr41-existing-7996acde-1480-4db0-a787-9f54182b192d/`。未访问日用 NAS 的「课表ics」。Android 均由正式界面配置/绑定/编辑，普通公网 HTTPS WebDAV、系统信任 TLS；没有 ADB reverse、hosts 固定、关闭 TLS 验证、instrumentation 或测试包替代正式实机应用。FPK 使用已安装 0.3.2 的实际 Socket API，调用者为 SSH 管理员；本次不重复网关鉴权验收。

| 设备/数据集 | 实际结果 |
| --- | --- |
| X08E | 原 `com.dormpanel.app`，0.1.2/code 6 → 同签名 0.1.3/code 7 原位安装；1280×800、Android 9；未卸载或清库 |
| MIX 2S 手机 | 保持正式 0.1.2/code 4，不以候选手机版替代跨版本互操作 |
| FPK | 已安装 0.3.2 创建独立根及三门合成课程；Android 没有先创建根 |
| datasetId | `600b460d-09d1-42ab-9749-ac1e5106daf7` |
| FPK writer | `815bc368-1ba9-4848-91ff-db4f02953e2a` |
| X08E writer | `bd3668fb-3759-4645-858e-4702dcfd895f` |
| Phone writer | `33cd6732-8bfa-42cb-989c-9ce74b0024c6` |

执行顺序与证据：

1. X08E 打开真实预览并取消；再次预览期间让 FPK 发布更新。X08E 确认旧预览时明确拒绝，仍显示未绑定入口。见 [1280×800 预览](evidence/pr41/x08e-preview.png)、[过期预览被拒绝](evidence/pr41/x08e-stale-preview.png)。
2. 刷新预览并确认，X08E 接收 FPK 最新三门课程。Phone 0.1.2 也从产品界面绑定同一目录。远端逐文件 GET/SHA-256 检查仍只有 **2 条 FPK 提交、1 个根**，最初根哈希保持；没有 Android 根或新 dataset。
3. Phone 与 X08E 分别从课程编辑界面保存全周标题 `_Phoneedit`、`_Xedit`，FPK 同步接收两项覆盖。FPK 再发布 `PR41 FPK after binding`，两台 Android 手动同步后显示三方内容。见 [X08E 三方内容](evidence/pr41/x08e-three-writers.png)。
4. 两台 Android 立即同步、FPK 再执行无修改同步，读取远端全部文件并校验，保持 **5 条提交、1 个根**；无 pending/conflict。没有通过“本机已发布”推断对端接收：三方接收结论分别来自各端真实课程状态。
5. 两台 Android 正常 force-stop/启动进程，再次停止后读取各自数据库；课程、覆盖和全部同步 checkpoint 逻辑内容与重启前完全一致，包括 datasetId、各自 writerId、frontier、历史。远端仍 5 条提交。此项是应用进程重启，不是设备系统重启。

详见 [真实同步与文件哈希](evidence/pr41/real-sync.txt)、[Android 重启身份/状态摘要](evidence/pr41/android-restart.json)。

## 数据保护与收尾

所有修改性实机验证前，先停止 Android 应用，在设备生成完整 tar，经 Base64 安全传输；完整遍历成员、读取全部文件并检查 tar 零尾部后才隔离日用目录。X08E 原归档 333,824 字节、32 个数据文件；Phone 936,960 字节、22 个数据文件。原 `databases/shared_prefs/files/no_backup` 原样保留在应用私有隔离目录；合成课表由正式应用生成，没有数据库注入。

结束时归档合成状态，将其保留到独立私有目录，原样移回日用目录。启动前与原始备份逐文件 SHA-256 比较一致；X08E 三个、Phone 两个数据库 `integrity_check=ok`。X08E 保留新 0.1.3 APK，手机仍 0.1.2；两端恢复原日用数据后重新启动。见 [恢复摘要](evidence/pr41/android-restoration.json)。

FPK 修改前完整归档实际 `var/etc`，24 个普通文件逐成员哈希比对通过。结束时正常停止服务，完整归档 23 个结束文件；除正常停止会删除的 `app.pid/socket.ready` 外，原 **22 个持久化文件**逐项与初始值核对，恢复原 catalog 并再次核对，随后启动，恢复原五个工作区及 activeId。合成工作区文件、远端提交与私有归档保留，未删除历史。

收尾工具有两次安全停止：FPK 脚本起初将上述两个运行标志的正常删除当成持久化差异，尚未恢复 catalog 即退出；明确排除这两个运行标志后，完整归档及持久化核对通过。Android 原文件哈希已核对一致后，本机 SQLite 检查因连接关闭清理本机副本的 `-shm` 导致扫描退出；随后重新从已校验 tar 建立独立副本，只枚举数据库主文件，全部完整性检查通过。这些是验收脚本问题，未用异常扫描结果宣布通过，也未向设备恢复损坏数据。

凭据、完整私人页面、APK、数据库、原始备份及 SSH 材料留在忽略的 `artifacts/pr41/`、本机候选目录或 NAS 私有归档，不进入 Git。公开证据只包含合成数据和脱敏摘要，[SHA-256 索引](evidence/pr41/SHA256SUMS.json)覆盖所有公开证据。

## 未实测项与限制

- 本次真实设备验证了成功、取消、预览后远端更新及后续三端同步。不同 dataset、缺祖先、损坏提交、多分支、离线和数据库故障注入以 JVM/Fake WebDAV/API 28 测试为证，不标成 X08E 实机故障注入通过。
- 候选 Phone 0.1.3 经过 API 28 UI、构建、lint、正式签名验证，未安装到真实手机；实机三端用例使用 Phone 0.1.2。
- 未重复四小时 soak、整机重启、自启、小爱/Bluetooth Mesh/原生闹钟专项验收、真实 HA 回归或真实非空 v1 日用库迁移。相关产品路径未修改，核心迁移/回执/冲突自动化回归通过，不将代码推断写作实机结论。
- 多分支首次绑定需在已有编辑端合并；非空本地课表不能自动合并独立 dataset。异常孤立文件仍显示未完成全部同步，服务器列举观察边界保持原协议限制。
