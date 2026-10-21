# PR36 — 0.1.2-rc.2 正式候选与实机验收

**结论：RC NOT ACCEPTED，不能进入正式 GitHub Pre-release 发布。** 已冻结正式双端候选并补充真实升级、FN Connect 中断恢复、双端冲突、正常重启及人工共存证据；真实已有 v1 绑定（含未发布编辑）的正式包切换仍为 **UNVERIFIED**。不降低发布门槛，不创建 tag、Release、资产，不自动合并本 PR。PR34 的实际结论保持原样。

本轮补充：**13 个真实设备隔离 v1 迁移场景及独立在线审计已通过**，详见文末“v1 迁移补充验收”。正式候选真实既有日用 v1 绑定升级的证据等级未改变。

## 基线与冻结身份

2026-10-21（上海时间），`git fetch origin main` 后本地/远端均为 PR35 合并提交 `58b5545289b5b4d8e9830a31c9c664b1cf8d4b8f`。本 PR 的应用修改仅为版本；协议、数据库版本、applicationId、SDK、批准签名及 HA 功能未变。HA 独立保持 **0.1.1**。

候选 C01 位于本机 `artifacts/release/0.1.2-rc.2/`，SHA-256 即字节身份；不覆盖稳定版和 RC1。

| 应用 | 包名 | code / name | 字节数 | SHA-256 |
| --- | --- | --- | ---: | --- |
| X08E | `com.dormpanel.app` | 5 / 0.1.2-rc.2 | 16124488 | `19fbf59968765aeec630368df109d9f5f85a6d764f54403523ec4c90267b5071` |
| Phone | `com.dormpanel.timetable` | 3 / 0.1.2-rc.2 | 9825259 | `a6a8ec6b84821c51757b8abf097ac2e2aff198a0554457e8af80274154a70f81` |

实际 APK 经 aapt2 与 `apksigner verify --verbose --print-certs --min-sdk-version 28` 验证：SDK **28/37**、不可调试、唯一批准证书 SHA-256 `32d68b5c6ad0bfd1b87aa0d54514ef71c33838e7643eb038488af7ca2add17f7`。sidecar 与最终文件名及字节一致；两个安装包均已从设备回读并与上表匹配。详见[独立校验输出](evidence/pr36/candidate-verification.txt)、[升级摘要](evidence/pr36/upgrade-summary.json)。未经签名或隔离测试 APK 不属于候选。

构建入口沿用 PR33，新增 `-OutputDirectory`；已有目录立即拒绝，在 staging 验证完毕后通过目录移动暴露产物，拒绝覆盖冻结目录。后续影响 APK 字节的修改必须使用新候选目录并重跑受影响验收。冻结之后本轮只补充测试、工具与文档，没有重建或替换正式 APK。

```powershell
# 签名秘密仅在本机配置，不在命令记录或 Git 中保存。
./tools/build-release.ps1 -OutputDirectory artifacts/release/0.1.2-rc.2-c02
./tools/verify-release.ps1 -Directory artifacts/release/0.1.2-rc.2-c02
./tools/test-release-validation.ps1
./tools/test-release-artifacts.ps1 -Directory artifacts/release/0.1.2-rc.2
```

## 实际日用数据原位升级

设备：真实 API28 X08E（物理 1280×800 横屏）、API33 MIX 2S。两端原先均为正式签名 RC1：X08E code 4，手机 code 2；回读旧 APK 哈希分别为 `ba7135315d251e0f2425e1eeeb8e11748410e18c74c9c0cb29c506fe99a0a20f`、`16a6f581fd87d9f7a88ca58b031309554a27f5dc00487dc32482d87a45ade79c`，两份旧 APK 已用 apksigner 独立验证为同一批准证书；记录实际回读身份，不推断它们与历史冻结 RC1 字节相同。

升级前先停止两个日常客户端，保存回读 APK、包元数据、数据库（含 WAL）、偏好与 files 的本机私有备份；解包并执行 SQLite `integrity_check`，再用 SQLite backup API 生成独立可读副本并再次检查。**验证了备份可读取与独立数据库恢复副本，没有将备份回写实机，也没有验证跨设备 Android Keystore 恢复。** X08E 使用既有 `su 0`、手机使用既有 `su -c` 只读导出，不以 root 运行正式应用或写数据库。

初次手机二进制 tar 流受 shell 转义影响，解包数据库校验失败，已拒绝该备份；改为设备端 base64 封装后，完整 tar 和 SQLite 校验成功才继续升级。该失败属于宿主采集方式，不归因应用数据损坏。

| 升级前实际存在的内容 | RC2 覆盖安装后即时核对 |
| --- | --- |
| X08E：Room v4，1 来源、13 系列、131 实例 | v4；来源、实例等全部表的内容摘要一致 |
| X08E：本地课程、事件、日期调整、覆盖编辑均为 0 | 空表保持；不声称非空覆盖编辑原位升级已验收 |
| Dashboard、效率工具数据库 | 所有表内容摘要一致；Todo/Memo 原本为空，Timer 为 READY/300000 ms |
| HA/WebDAV 加密配置、外观、自启、学期配置 | 相关偏好摘要一致；灯控状态记忆随真实控制改变 |
| 手机：Room v4，实际空课表 | 所有数据库表摘要与偏好文件摘要一致；不冒称手机已有非空日用课程升级通过 |
| 两端共享设置和 checkpoint | 升级前均无 v1/v2 绑定，checkpoint 表为空，无待发候选；X08E 已有独立 WebDAV 来源/账号配置 |

仅执行正常 `adb install -r`，两个首次安装时间保留。无卸载、清库、数据库替换或重新导入。升级后正常启动，并完成 force-stop 后重开。原始私有备份和包元数据位于本机忽略目录 `artifacts/pr36/{x08e,phone}-{before,after}/`，公共证据仅含数量和摘要。

**v1→v2 真实既有绑定成功迁移、无法证明共同历史时的真实正式包拒绝迁移、迁移时非空未发布编辑保留均为 UNVERIFIED。** 当前实际日用基线没有这些状态，不能用隔离数据库、代码推断或本轮新建 v2 绑定替代。PR35 原有迁移与 Room 测试证据仍归 PR35。

## 正式候选真实双端同步与冲突

使用原有 WebDAV 账号下全新 `pr36-daily-<UUID>/`，没有改写旧生产目录或旧 head/历史。X08E 用保存的加密凭据正常 UI 创建 v2，手机正常 UI 检查远端（1 来源/131 实例/1 学期配置）后确认绑定；不重新导入课程。新目录保存真实日用课表，不属于合成故障注入目录。两端最终保留该 v2 绑定。

两端关闭 Wi-Fi（手机原移动数据已关闭），正常 UI 分别将 2026-10-21 临时调整为无课，原因设为 `PR36 X08E offline` / `PR36 Phone offline`；离线本地显示与保存成功。恢复两端 Wi-Fi 后，手机显示 1 项真实冲突，详情包含两种原因且保留双方数据。正常 UI 选择手机“保留本地”，随后 X08E 的日期详情实际显示 `PR36 Phone offline`，证明决定传播。再从 X08E 正常 UI “Reset day to normal”，原课程重新显示；手机前台恢复后同样显示原课程。

两端 JobScheduler **3201** 均实际 START 并以 `app called jobFinished` 结束，见[系统执行历史](evidence/pr36/jobs-executed.txt)，不是仅证明安排任务；没有通过 shell 强制 run 制造自然调度结果。前台恢复、正常编辑和适用后台路径均有实测。Wi-Fi 恢复为原开启状态，手机移动数据未更改。

独立 Python 对真实远端全部提交 GET、解码和 SHA-256 审计，最终单一前沿、1 来源/131 实例、1 学期配置、日期调整 0、覆盖编辑 0，见[远端审计](evidence/pr36/daily-remote-audit.json)。没有删除真实课程或远端历史。UI 文本通过独立 instrumentation 的原生 Accessibility `ACTION_SET_TEXT` 输入，以绕过 X08E 中文输入法改写 URL；正常按钮完成保存/绑定/冲突处理，辅助代码未写应用存储。一次手机无关应用协议弹窗阻断自动点击，未接受协议，显式回到正式课表后继续核对，不将失焦点击计为通过。

## 真实 FN Connect 上传中断与进程恢复

新测试 `RealUploadRecoveryTest` + `tools/test-real-upload-recovery.py`：仅接受显式凭据及 HTTPS 测试父目录，每轮新建 `pr36-recovery-<UUID>/commits/`，独立 Room 数据库，全部为合成数据。与上面的日用目录完全分离。测试只改变网络传输节奏/回执交付时机，**实际 PUT 发往真实 FN Connect，没有 Fake 服务或伪造故障响应**；故障由宿主实际 force-stop 隔离发送进程触发。

| 场景 | 实际网络阶段与结果 |
| --- | --- |
| 正文上传中断 | 16:01:50.503 第一批 256 字节 flush 到真实网络，同时新本地编辑已持久化；16:01:51.039 杀 PID 21139。重启回读原路径确实为 **256 字节部分文件**；原路径字节不变，换新提交身份发布，有效修改被另一台实机发现；仍显示 1 文件未确认、尚未完成全部同步。 |
| PUT 已接收但同步层未获确认 | 16:02:29.139 在真实服务器 **201** 返回后、响应交给调用方前命中；16:02:30.130 杀 PID 21299。重启 outbox 仍在且 attempted=true，真实回读原提交 **2361 字节** 与候选一致，确认回执；上传中新增编辑继续发布并被另一端发现。 |

两轮均断言候选 capture 与上传中新编辑不同、重启保留新编辑、原不确定路径未覆盖、有效结果最终由真实手机收到。预期被杀轮出现 `Process crashed`，不冒充该 instrumentation 已完成；恢复和接收阶段才返回 `OK (1 test)`。详见[阶段汇总](evidence/pr36/real-upload-recovery.txt)、[正文阶段证据](evidence/pr36/body-witness.txt)、[回执阶段证据](evidence/pr36/ack-witness.txt)及同目录各阶段 runner 日志。正文中断后远端部分文件按协议保留，不清理或隐藏。

**归属限制：这是当前同步实现的真实服务器/真实设备隔离测试包证据，不是在不可调试正式包中注入故障。** 不替代日用数据升级、正式绑定或 OEM 常驻验收。PR35 真实三 Writer、并发 PUT、目录发现及协议向量证据复用，不机械重跑。

## 正常重启、系统共存与观察

两设备实际正常 reboot，boot ID 改变。X08E 未手动拉起，系统启动流程后 **16:15:32.329** 记录 `Boot launch requested` 并实际以日常 MainActivity 为 resumed Activity，见[开机日志](evidence/pr36/x08e-boot-launch.txt)。启动策略保持 ON，HOME 按键能返回 `com.xiaomi.micolauncher/.Launcher`；没有替换 Launcher。手机重启后正常打开保留课表和 v2 绑定，保存凭据无需重输，前台检查成功；日期重置最终恢复。

用户在本轮正式 RC2 安装后明确回复“**全部检查通过**”：小爱实际语音回复、原生 Bluetooth Mesh 控制并恢复、临时原生闹钟实际响铃并清理、音量/媒体、HA 灯光控制并恢复。这是本轮人工观察，不复用 RC1 人工结果。重启后的 HA 连接/状态与本地效率功能补充结果见下方最终记录；进程存在本身不算功能通过。

## 自动化与最终门槛

- 正式双端 assembleRelease 成功；JVM app **198**、schedule-core **134**，0 失败/错误/跳过；phone 无独立 JVM 用例。三模块 Release Lint 成功；不引入 baseline 或压制。
- 发布解析/控制流守卫 **37** 项通过；真实工具/真实 APK 反例 **9** 项通过，包括旧 RC1、错误包、错误签名、未签名、篡改 sidecar、成功但无输出的 Gradle、冻结目录覆盖拒绝。
- 新隔离恢复测试在真实 X08E/手机执行；最终 Android 测试 APK 构建成功。没有将 PR35 模拟器或旧 RC1 测试结果改写为 C01 新实测。
- `git diff --check` 和 Python 工具语法检查通过。HA 功能无变化，保持原独立版本。

| 门槛 | 判定 |
| --- | --- |
| 正式签名、冻结 APK、sidecar、已安装字节 | PASS |
| 实际已有非空 X08E 课程、配置原位升级 | PASS，按实际空表范围记录限制 |
| 手机实际安装状态原位升级 | PASS，但基线为空课表，无非空日用课程保留证据 |
| 正式双端新 v2 绑定、离线编辑、冲突决定及恢复原数据 | PASS |
| 真实既有 v1 绑定切换（成功及安全拒绝）与未发布编辑 | **UNVERIFIED，阻止 RC ACCEPTED** |
| 真实 FN Connect 上传中断、回执丢失、进程恢复 | PASS（真实设备隔离测试包，范围见上） |
| 正常重启、X08E 自启、前台恢复与实际后台同步 | 已执行，范围见上；最终观察另记 |
| 正式 X08E 的人工 XiaoAI/Mesh/闹钟/媒体/HA | PASS（用户本轮人工确认） |

后续必须补齐尚未验证的真实 v1 场景及记录中的缺口，才能重新评估 RC ACCEPTED。若需要修改应用，则生成新候选，不能继承 C01 受影响的通过结果。PR36 保持未合并，不具备发布授权。

## 最终收尾记录

空闲观察为 **16:16:12–16:21:32，约 5 分 20 秒**，两端各三次采样，详见[采样输出](evidence/pr36/idle-samples.json)。X08E PID 2867，PSS **49458–50508 KiB**、线程 **30–31**；手机 PID 2688，PSS **79279–106502 KiB**、线程 **27–29**。所有第二个 top 快照 CPU **0.0%**；各端 boot ID/进程身份保持，采样崩溃缓冲区没有对应包记录。原始样本含 /proc stat、status、meminfo、任务历史与前台信息；这是离散采样，不能证明排除了全部瞬时 ANR、网络请求或长期 OEM 调度问题。没有重新要求四小时 soak；本轮另有约半小时升级/真实交互验证。未进行完整连续网络追踪，长期资源与调度仍不作保证。

收尾期间自动化误在 Control Center 操作卡片透明度，已通过正常 Accessibility 滑条操作精确恢复 **20%**；最终 `appearance.xml` 与升级前摘要相同，没有写偏好文件修复。断网期间正式课表可用；计时器由正常 START 按钮启动，持久化 RUNNING 且实机截图可见倒计时，随后正常 RESET 回到 **05:00 READY**。持续倒计时导致 uiautomator 无法获得空闲新层级、旧 XML 使外围断言失败，该断言不计通过；截图和后续稳定层级/数据库回读单独证实计时及复位。首次使用 Timer 增加一个默认 owner 行，原 owner/Memo/Todo 内容保留，最终两个 owner 均为 READY/300000 ms；没有删除该正常使用产生的行。Todo/Memo 非空离线编辑未另行实测，仍为 UNVERIFIED。

正常重启并再次断网/联网后，正式控制中心实际显示 **HA CONNECTED**，灯/传感器显示可用；启动策略仍 ON，WebDAV 配置仍在。重启后的实体灯再次切换未单独实测，不能把安装后人工确认转换为重启后实控证据。最终 Wi-Fi 为开启，X08E 正式 Dashboard 和手机正式课表正常打开。

最终再次私有备份、完整性检查和回读 APK，见[最终摘要](evidence/pr36/final-data-summary.json)：X08E 课程所有业务表与升级前一致（同步 checkpoint 正常新增），Dashboard 全表摘要一致，外观/设备控制/HA 凭据/HA 设置/自启/学期/WebDAV 凭据与设置摘要一致；临时日期调整及覆盖仍为 0。手机保留已绑定的真实课表。最终两份已安装 APK 哈希仍为 C01，不因测试工具或文档变动替换候选。

复现真实网络恢复：构建安装 `:schedule-core:assembleDebugAndroidTest` 到两台明确设备，将 JSON `{sender, peer, parent, user, password}` 通过 **UTF-8 stdin** 传给 `python tools/test-real-upload-recovery.py`。`parent` 必须为 HTTPS 测试父目录，密码从机器本地安全容器读取，不放命令行/文档/Git；每次输出独立私有证据目录。只 force-stop `com.dormpanel.schedule.core.test`。测试未命中目标边界时直接失败，不能称为中断通过。

最终结论仍为 **RC NOT ACCEPTED / Pre-release 发布 BLOCKED**：真实既有 v1 绑定的成功切换与安全拒绝、非空待发布编辑保留，以及上面明确列出的未验证项尚未补齐。其余通过项保留其候选身份、证据类型和实际范围。

## v1 迁移补充验收（2026-10-21，保留 C01）

**范围与身份。** 本次只新增 Android 测试、宿主工具和证据，没有修改生产源码、资源、版本或构建配置，没有重建/替换 C01，也没有新建 PR。真实 API28 X08E 与 API33 MIX 2S 上使用 `com.dormpanel.timetable.testbed` / `.testbed.test`；每例独立 `pr36-migration-<UUID>-<case>.db`、偏好、Keystore alias 和真实 FN Connect 同名 HTTPS 子目录。测试拒绝其他 targetPackage、非 HTTPS、非白名单场景或不匹配的目录名，未降级、清空、覆盖或直接写入任何日用数据库。

**v1 fixture 的真实性及限制。** 非空 fixture 包含本地课程、事件、来源、导入实例、3 条系列/指定周编辑、日期调整、2 个学期配置和自定义学期标记；使用实际 DPSS/1 编码、head、父版本与 generation、Room checkpoint 1/2/4/5、已保存的加密账号及真实服务器 GET 返回的 ETag 构造绑定。全新测试目录内真实 PUT/GET 回读 root/head，再构造远端推进或故障历史。这里用普通 PUT **布置受控 v1 历史**，没有调用或绕过旧正式发布器的安全检查；不代表 FN Connect 已支持 v1 条件写入，更不代表旧正式客户端曾安全发布过这些历史。

所有迁移均由实际管理页“WebDAV 共享课表（双向同步）→ 将已有 v1 绑定切换到 v2 → 旧客户端已停止，切换”触发，没有以直接调用 `v2Migrate()` 代替客户端路径。手机沿用 PR35 已授权的 `pr35RootUi=true` 隔离 Activity 启动辅助；root 仅用于启动 `.testbed` Activity，应用仍为普通 UID，不写日用存储、不改变 OEM 权限。X08E 的隔离 Activity 正常启动，不使用该辅助。

| 场景 | X08E | 手机 | 实际核对 |
| --- | --- | --- | --- |
| 成功迁移，新建 v2 根 | PASS | PASS | 证明共同基线、合并独立本地/远端修改，完整课程/来源/编辑/学期字段与预期一致 |
| 接入同 dataset 已有 v2 | PASS | 未重复 | 走已有 v2 分支，旧 checkpoint 身份与未发布本地课程保留 |
| v1 候选已在 head 历史中 | PASS | PASS | 确认待确认候选并清除活动 outbox，精确恢复 capture 后的新增编辑并发布到 v2 |
| v1 候选文件存在但未进入 head | PASS | 未重复 | 证明完整历史后释放未激活候选；保留本地与上传中新编辑，不覆盖或删除孤立 v1 文件 |
| 缺失祖先 | PASS | 未重复 | 管理页安全拒绝；独立审计证明 MISSING_ANCESTOR，重新打开后旧状态保留 |
| 损坏祖先 | PASS | 未重复 | 安全拒绝；独立审计证明 CORRUPT_ANCESTOR，损坏 fixture 字节未被改写 |
| dataset 不一致 | PASS | PASS | 安全拒绝，旧 checkpoint、课程及账号仍可用 |
| 基线不在远端祖先链中 | PASS | 未重复 | 安全拒绝；独立审计证明 UNPROVABLE，没有按相似课程猜测身份 |
| 已保存恢复副本、课程事务中断 | PASS | PASS | 命中实际 Room 事务边界后 force-stop 隔离进程；新进程恢复原数据及旧 checkpoint，再通过管理页继续迁移成功 |

合计 **13 个场景、31 个完成阶段通过**；另有 2 个预期被杀的中断阶段，不把其 `Process crashed` 当作测试完成。[阶段汇总](evidence/pr36/migration/runner-summary.txt)、[实际 UI/网络阶段](evidence/pr36/migration/network-ui-witness.txt)、[X08E 中断](evidence/pr36/migration/x08e-interrupted-interruption.txt)、[手机中断](evidence/pr36/migration/phone-interrupted-interruption.txt)及同目录分阶段 runner 输出可追溯。宿主观察到事务标记后才终止进程；设备与宿主时钟独立，不用跨时钟时间差计算中断延迟。

“上传中新编辑”在真实 v1 snapshot PUT 首批 **256 字节 flush 后**、剩余正文完成前，通过正常 Store API 持久化事件修改，随后才完成 PUT 并分别布置 head 已接收/未激活两种状态；不是单纯事后改 fixture 的预期字段。迁移后课程 ID、来源/系列/实例 ID、稀疏编辑、日期调整和完整学期参数均逐字段核对，包含源导入时间等元数据，不用只比较数量替代。成功后活动 checkpoint 4/23 不再存在，原 1..7 在 111..117 精确备份，100 保留原始完整本地快照；被拒绝的场景中原活动 1..7 字节不变。管理页保存账号会正常重新加密密码，因此以“解密仍为原账号且实际远端请求成功”验证凭据保留，不要求 AES-GCM 密文字节相同。

**独立核对。** `tools/audit-v1-migration.py` 使用独立 Python 解析 v1/v2 封装和所有课程字段，验证 payload/checkpoint SHA-256、父链、generation、dataset、原始恢复副本及预期状态；仅规范化记录排列顺序，不忽略任何字段。在线审计重新从 FN Connect GET 全部旧 head/快照及有效 v2 提交，13 项均通过：旧 v1 文件集合与字节不变，拒绝场景 v2 目录返回 404，成功场景 v2 DAG 单一前沿且其完整状态等于预期。详见[独立审计](evidence/pr36/migration/independent-audit.json)。

**日用数据与清理。** 开始/结束均只读导出两个日用包做摘要核对，未停止、写入或替换日用数据库；全部数据库表（含同步 checkpoint）摘要相同，已安装 APK 与冻结 C01 哈希保持一致。其他偏好相同；两端 `shared_schedule_v1.xml` 仅 `last_success` 更新时间变化，folder/dataset/账号配置不变，不声称全部偏好字节未变。见[日用保护核对](evidence/pr36/migration/daily-sentinel.json)。13 个主场景及 2 个前期失败 fixture 的隔离数据库、偏好内容和对应 Keystore alias 已通过受限 cleanup 阶段清理；合成导出与 UUID 远端目录保留作证据，不执行远端历史删除。两端正式日用 Activity 已正常恢复。

**相关回归与失败归属。** 测试包构建成功，既有两项 Room/v1 迁移实机回归 **2/2**、相关 Append-only JVM **15/15**、Phone 隔离构建 Lint 成功；Python 语法与 `git diff --check` 通过。没有重复 RC2 签名/整套 HA/共存/长期观察。前期两个 smoke 的迁移结果核对失败分别源于 fixture/Room 记录顺序及错误要求加密密文不变，均保留为失败记录；修正测试断言后，完整字段独立审计通过。没有修改应用以制造通过，未发现需要 C02 的生产修复。公开 runner 文本仅归一化换行和行尾空白，原始日志保留在本机忽略目录 `artifacts/pr36/migration/`。

复现方式（仓库根目录，机器本地安全容器供应凭据，勿将密码写入命令记录或 Git）：

```powershell
./gradlew.bat :phone-app:assembleX08eTest :phone-app:assembleX08eTestAndroidTest '-Pdormpanel.testBuildType=x08eTest'
adb -s <serial> install -r phone-app/build/outputs/apk/x08eTest/phone-app-x08eTest.apk
adb -s <serial> install -r phone-app/build/outputs/apk/androidTest/x08eTest/phone-app-x08eTest-androidTest.apk
# cases: success, join, committed, uncommitted, missing, corrupt, dataset, unprovable, interrupted
# 将 {parent,user,password,devices:[{role,serial,cases,rootUi}]} 作为 UTF-8 stdin 输入：
python tools/test-real-v1-migration.py
# 本地审计；--online 时另从 UTF-8 stdin 读取 {parent,user,password}：
python tools/audit-v1-migration.py <本机运行目录> --online
# 同一 runner 的输入增加 cleanupDirectory，并保留 devices 的 role/serial，可清理对应隔离状态。
```

**发布决定保持：RC NOT ACCEPTED / Pre-release BLOCKED。** 以上补齐的是“真实设备 + 真实 FN Connect + 人工构造非空 v1 状态 + 实际客户端管理页”的隔离证据，不是正式 C01 从真实既有日用 v1 绑定原位升级的证据。已知两台日用设备原基线没有 v1 绑定，现已运行 C01/v2；不能通过降级或改写它们的日用数据库补造升级历史，故该正式候选升级项目仍为 **UNVERIFIED**。

产品决定待明确：仅知道这两台没有历史 v1 绑定，不足以证明产品无需支持该升级路径。若确认真实 v1 绑定确实不属于需要支持的升级路径，应明确决定是否取消它作为发布阻塞；本次没有作出或代替该决定，也未降低门槛、宣布 RC ACCEPTED、合并 PR、创建 tag/Release 或上传资产。
