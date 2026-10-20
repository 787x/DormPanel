# PR31 — WebDAV 完整课表单向同步

PR30 已合并后在 main 上开发。PR31 只有 X08E 发布、手机只读跟随；没有手机回写、多写者合并、HA 同步或常驻后台服务。没有修改正式 applicationId、版本、签名、数据库文件名、已有 WebDAV 偏好键或 Keystore alias。

## 使用

1. X08E 先在原 WebDAV 设置中配置账号。在课程表 Sources → Publish shared timetable (WebDAV)，填写同一服务器上**独立且已存在的目录完整 URL**。无需修改原 ICS/CSV 文件或原导入绑定。
2. 可先运行条件写入诊断，再首次创建共享课表。每次发布也会自动诊断。已有 `dormpanel-head-v1` 时首次创建拒绝覆盖；后续手动发布要求 revision 和强 ETag 均仍匹配。
3. 手机管理 → WebDAV 共享课表，填写服务地址、用户名、密码和相同目录。密码留空保留已有密文凭据。检查会下载并验证快照，展示 dataset/revision、发布时间、课程/事件/来源/实例/编辑/学期数量；确认后才恢复。
4. 初次绑定只允许课程、事件、来源、日期调整、编辑和自定义学期均为空的接收端。不按文件名、名称或 hash 合并独立来源。拒绝非空数据时不清空原数据。
5. 绑定后隐藏新增/日期调整/导入/删除/学期编辑入口，详情编辑按钮禁用。Source 和 Room 写入事务也拒绝本地修改；学期存储以只读 Room catalog 为准。未绑定时保留 PR30 离线功能。
6. 手机可立即同步，每次进入/恢复前台也检查一次；没有轮询、永久服务或后台定时器。错误/离线显示缓存；超过 24 小时未成功验证显示过期提示。目录绑定后锁定，仍可更新认证凭据。

## 协议与限制

`ScheduleSnapshotCodec` 是独立于 UI/WebDAV 的完整状态协议，**不是** Room 文件或 ICS/CSV。v1 是严格、有界的二进制格式：大端 magic `0x44505353`、Int schemaVersion=1、Int payload 字节数、payload、32 字节 SHA-256。payload 的 UUID datasetId、UUID revision、可空 parentRevision、毫秒 createdAt、Long generation 后，依次为事件、本地课程、来源、原始导入实例、日期调整、课程覆盖、完整学期 catalog、自定义覆盖键集合。完整字段顺序由编解码器显式定义。

字符串为 Int UTF-8 字节数和严格 UTF-8；可空字段使用单字节 0/1 tag；集合使用 Int 数量。拒绝未知 schema、缺少字段、非法编码/tag、尾随数据、重复身份、错误编辑键、悬空来源、无效时间/配置、元数据计数或摘要不符。已不再匹配当前导入 series 的旧编辑仍保留，避免破坏未来来源替换时的恢复语义。

上限：整个文件 16 MiB；单字段 UTF-8 1,000,000 字节；本地事件和课程各 10,000；来源 16；实例 50,000；日期调整 1,000；课程编辑 5,000；实例文本 400 万字符、编辑文本 200 万字符；学期 128、每学期阶段 128、每阶段节次 30。导出超限明确失败，不截断已有合法记录。

所有本地 id、sourceId、seriesId、实例 id、originalStart、anchorDate、空值/显式清空编辑均原样传输，不重新解析原文件生成 UUID。内置学期也传完整定义，跟随端不会用自身默认值替代；自定义覆盖键单独保留，连“与内置值相同的自定义覆盖”也保留其身份。snapshot 不包含认证、HA Token、Keystore 或其他应用偏好；来源 locator 仅作元数据，不作为下载指令，拒绝带 URL userinfo 的 locator。

generation 从 1 开始，发布者每次递增，并引用上一 revision；接收端可跳过中间版本，但拒绝旧 generation、同 generation 的不同 revision，以及相同 revision 的不同字节。保留 parent/generation/稳定身份供 PR32 扩展；它们不构成双向冲突合并。

## 原子恢复与进程中断

Room v4 只新增 `schedule_sync_checkpoint`。保留 v1→v2→v3，并增加 v3→v4 migration，无 destructive migration，不替换或删除 `schedule.db` 文件。

应用前完整验证，事务内再次检查首次绑定条件或既有 dataset/revision。课程实体、完整快照 checkpoint、revision 和**跟随端学期配置**在同一个 Room 事务提交。跟随模式下 `TermScheduleProfileStore` 读取 checkpoint 对应的 catalog；不向原 SharedPreferences 镜像写入配置，因此不需要跨存储两阶段提交，也不会把半写偏好当成成功。普通本地模式仍使用原偏好。导出配置严格读取，不能静默忽略坏条目。

回调前重新加载完整课程和 catalog 再通知 UI；失败保留旧状态。初次绑定和所有普通写操作均在事务中检查只读 checkpoint，防止不同 Store 连接间绕过只读检查。相同 revision 和字节再次应用是 no-op。进程在课程写完、checkpoint 提交前退出时，SQLite 回滚整个事务；重开从已提交 checkpoint 恢复只读状态和配置，不依赖“同步成功”偏好标记。

## WebDAV 安全与服务器兼容性

原 `WebDavClient`、`WebDavSettings` 和 AES-GCM `TokenCipher` 移至共享核心，包名、账号偏好及 Keystore alias 不变；原 ICS/CSV、APK 下载仍走同一实现。手机只增加网络权限，备份仍关闭。保持 HTTP/HTTPS（与原 X08E 策略一致）、Basic 认证、同源限制、有界读取、超时和严格资源重定向边界；禁止 URL 内凭据、查询参数和跨源重定向。实际网络错误不输出密码或服务器响应体。

目录中的文件：

- `dormpanel-head-v1`：小型有界版本指针，包含协议标识、datasetId、revision、整个快照 SHA-256。
- `snapshot-<revision>.dps`：随机 UUID 命名的不可变快照。以 `If-None-Match: *` 创建，随后 GET 全量比对并重新解码，通过后才提交指针。
- `probe-<UUID>.dps`：诊断文件。验证创建、GET/强 ETag、重复 create 必须 412、错误 If-Match 必须 412、正确 If-Match 更新及新 ETag。每次发布均验证；不支持时拒绝发布，不退化为无条件覆盖。

指针首次写使用 `If-None-Match: *`，后续使用已保存强 ETag 的 `If-Match`。409/412、认证失败、超时、断流、校验不符或远端 revision 变化均拒绝危险覆盖。未完成上传没有有效指针；历史快照、孤立上传和诊断文件保留且读者忽略。PR31 不自动清理它们，应在专用目录使用；大量手动发布会累积文件。

发布前持久化待确认 revision/hash/generation。若进程在远端提交后、本地回执保存前退出，下次仅在待确认身份与完整远端校验均相符时恢复回执，再正常条件发布。不会采纳任意外部 revision。无法确认的远端变化需要人工检查，不提供强制覆盖。诊断是能力检查，不能证明任意服务端实现、缓存或代理始终正确。

## 验证与复现

自动测试使用独立数据库、偏好命名空间和本机 MockWebServer，不读写真实用户 WebDAV 文件。

```powershell
./gradlew.bat :app:assembleDebug :phone-app:assembleDebug
./gradlew.bat :app:testDebugUnitTest :schedule-core:testDebugUnitTest :phone-app:testDebugUnitTest
./gradlew.bat :app:lintDebug :schedule-core:lintDebug :phone-app:lintDebug
$env:ANDROID_SERIAL='emulator-5554'
./gradlew.bat :schedule-core:connectedDebugAndroidTest
```

新增测试入口：`ScheduleSnapshotTest`、`SharedScheduleTransportTest`、`ScheduleSnapshotPersistenceTest`、`SharedSchedulePublisherAndroidTest`、`PhoneSharedScheduleTest`、`PhoneSharedScheduleRestartTest`。Android 测试可用 runner 的 `class` 或 `package` 选择。Windows 的 gradlew.bat 多 class 参数曾只运行首个类；应核对实际测试数量，必要时使用独立 runner 调用，不把退出码或零测试当成覆盖证据。

跨进程中断测试：安装两个手机 debug APK 后，对 `com.dormpanel.timetable.test/androidx.test.runner.AndroidJUnitRunner` 分别传 `class=com.dormpanel.phone.PhoneSharedScheduleRestartTest`、同一个 `pr31Run=<UUID>` 和 `pr31Phase=seed|interrupt|verify`。在 interrupt 输出 `DormPanelSnapshotTest: INTERRUPT_READY <UUID>` 后，对测试应用 `com.dormpanel.timetable` 执行 force-stop，再运行 verify。中断阶段 runner 被杀是预期结果，不能算通过；seed/verify 必须各有一项通过，verify 核对旧课程、稳定身份、旧 catalog/revision 和只读状态。仅操作该 UUID 的测试数据库。

实际验证（2026-10-20）：两应用 debug 构建通过；app 198、core 99 项 JVM 测试全部通过，手机模块没有独立 JVM 用例。三模块 Lint 零错误，保留 96/18/11 项警告，无新忽略基线。API 28 核心 16 项通过；X08E 课程表 UI/导入/重开共 9 项、WebDAV/relay 包 20 项和新增发布入口 1 项通过。Pixel API 37 手机完整 8 项通过；API 28 1280×800 另跑共享发布/订阅/新版更新/损坏保护/离线重开流程通过。Pixel 的 seed → 事务中断标记 → force-stop → verify 另行通过，确认 SQLite 回滚和跨进程只读 catalog 恢复。两种布局截图已检查；`git diff --check` 通过。本地日志/截图在忽略的 `artifacts/pr31/`。

未验证 X08E/手机实机、真实 WebDAV 服务、OEM 生命周期、长期常驻和各类代理行为；模拟器与 MockWebServer 结果不能代替实机验收。诊断和历史文件会累积；服务器必须支持经检测的强 ETag 和条件写入。双向编辑仍由 PR32 实现。
