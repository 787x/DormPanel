# DormPanel 0.1.2-rc.1 Release Notes

这是供真实 X08E 与 Android 手机验收的候选版本，**尚未正式发布，也尚未通过本轮实机验收**。本次仅冻结 PR30–PR32 的功能，完成发布元数据、签名、双 APK 构建与校验。实际执行结果与最终候选哈希见 [PR33 验证记录](PR33-release-preparation.md)。稳定版 0.1.1 与 [PR29 历史验收](PR29-release-acceptance.md) 的事实不变。

## 两个独立应用

| 应用 | applicationId | versionCode | versionName | minSdk / targetSdk |
| --- | --- | --- | --- | --- |
| X08E | `com.dormpanel.app` | 4 | `0.1.2-rc.1` | 28 / 37 |
| 手机课程表 | `com.dormpanel.timetable` | 2 | `0.1.2-rc.1` | 28 / 37 |

手机 App 提供独立的原生课程表、课程编辑、ICS / 湖北大学 CSV 导入、来源管理、日期调整、学期与节次配置。它不是 X08E 桌面或米家替代品，不包含 X08E 的 HA 控制、Dashboard 与设备控制页。两个 App 共用 `schedule-core` 的模型、Room 持久化、解析、编辑投影、合并与同步代码，各自拥有独立的 Android 沙箱、数据库与账号配置。通过 WebDAV 交换完整快照，不直接共享数据库文件。

## WebDAV 完整课表与双向同步

完整 v1 快照包含本地课程、日历事件、导入来源与原始实例、日期调整、全部/指定周次编辑及自定义学期配置；不携带认证信息。不可变 `snapshot-<revision>.dps` 和 `dormpanel-head-v1` 指针维护版本链，保留原有身份与编辑继承语义。

两端均可离线编辑。重新联网后，以共同基线、当前本地和已验证远端做三方合并；独立修改自动合并，同一记录或关联记录的不兼容修改保留冲突，不按时间戳强行覆盖。管理页可暂缓处理，或统一保留本地/采用远端冲突内容，独立修改仍保留。保存到本地不等于上传成功。

前台恢复会检查远端，本地提交后约 1.5 秒去抖安排同步；后台使用有网络约束的一次性 JobScheduler 任务及有界重试，不做固定周期轮询。冲突、离线/重试、失败、待上传与超过 24 小时未验证的状态分别展示。Force-stop 后重新打开恢复持久化状态；OEM 后台调度不保证准时。

服务器必须提供**强 ETag**，正确执行 `If-Match` 更新及 `If-None-Match: *` 条件创建；陈旧条件写入必须被拒绝。两端会探测条件写能力，校验快照/指针回读与历史身份；弱 ETag、不支持条件写、历史缺失、损坏或并发冲突不能通过无条件覆盖绕过。建议先用独立测试目录验收。历史与探测文件不会自动删除；不要手工清理正在使用的版本链。

PR31 的 v1 网络格式保持兼容。手机原 checkpoint 升级为共同基线；X08E 需核实既有发布历史后才能建立基线，不能将当前本地内容猜作旧已发布状态。若历史丢失，恢复历史文件或修正账号/目录后再同步，保留现有本地数据。参见 [PR31](PR31-webdav-timetable-sync.md) 与 [PR32](PR32-bidirectional-timetable-sync.md)。

## 安装、校验与升级

本地候选位于 Git 忽略的 `artifacts/release/0.1.2-rc.1/`：

- `DormPanel-X08E-0.1.2-rc.1.apk` 及同名 `.apk.sha256`。
- `DormPanel-Phone-0.1.2-rc.1.apk` 及同名 `.apk.sha256`。
- 若提供 HA ZIP，使用真实名称 `DormPanel-HA-0.1.1.zip`。

两 APK 均为不可调试、正式批准身份签名的 Release APK。证书 SHA-256 必须为：

```text
32d68b5c6ad0bfd1b87aa0d54514ef71c33838e7643eb038488af7ca2add17f7
```

从仓库根目录运行独立校验，无需签名密码：

```powershell
pwsh -NoProfile -File tools/verify-release.ps1
```

校验使用真实 `apksigner verify --verbose --print-certs --min-sdk-version 28` 和 `aapt2 dump badging`，逐份检查包名、版本、SDK、不可调试、唯一批准签名及最终 APK 的 SHA-256 与 sidecar 文件名。安装前也可在资产目录使用 `sha256sum -c <文件名>.apk.sha256`；仅哈希相符不代替签名和元数据校验。

X08E 从 0.1.1（code 3）**原位升级**：

```powershell
adb -s <X08E序列号> install -r artifacts/release/0.1.2-rc.1/DormPanel-X08E-0.1.2-rc.1.apk
```

也可使用系统安装器覆盖安装。保留原包名和签名，保留现有 Dashboard、HA/WebDAV 账号、relay 身份、课程、本地效率工具与设置；Room v1→v2→v3→v4 链及 PR31 同步状态升级仍由原有实现处理。**不要卸载、清除数据或使用降级来解决签名冲突**；这些会失去本地数据及 Keystore 密钥。出现签名冲突时停止，核对原安装证书。该 RC 的真实 0.1.1→RC 数据保留仍待实机验收。

手机首次安装使用：

```powershell
adb -s <手机序列号> install artifacts/release/0.1.2-rc.1/DormPanel-Phone-0.1.2-rc.1.apk
```

已有同签名手机版时使用 `install -r`。开发 debug 安装可能使用不同证书，遇到冲突应先核对；不承诺直接覆盖任意开发包。两 App 可并存；手机不能覆盖 X08E 包。手机版从本次 RC 起必须延续此签名身份与 `com.dormpanel.timetable`。后续 RC 或正式 0.1.2 必须分别递增 versionCode：X08E 大于 4，手机大于 2，不能仅修改 versionName 或复用本次 code。

## Home Assistant 与原系统

HA 自定义集成实际版本仍为 **0.1.1**，PR30–PR32 没有升级其功能或协议。现有 HA 0.1.1 集成继续适用于 X08E RC，无需为了 Android RC 版本而替换 manifest。ZIP 内仍为 `custom_components/dormpanel/`，manifest 仍为 0.1.1。手机版课表同步直接使用 WebDAV。

X08E 仍作为普通 App 与小米原系统共存；不改变小爱、MIUI Home、原生闹钟、Bluetooth Mesh 网关、自启策略或设备权限。HA、原 WebDAV ICS/CSV 导入、Dashboard、设备控制和本地效率工具均保留。

## 限制及待验收

- 快照保留 16 MiB 及记录数量限制；旧 ICS/CSV 传输限制仍为 1 MiB。任意 CSV/XLS/XLSX 不在支持范围。
- 历史不做自动 GC；长历史可分段核实。冲突按记录及必要关联单位处理，不承诺同一记录内逐字段自动合并。
- 未保存草稿/导入预览不能视为持久化课程；不同设备独立导入同一文件不按文件名自动识别为同一来源。
- 自动化/MockWebServer/模拟器通过不能替代真实 WebDAV、TLS/反向代理、X08E 或手机厂商后台调度验收。
- 待真实 X08E 0.1.1 原位升级、两设备离线交错编辑/冲突、进程中断恢复、真实 WebDAV 条件写、MIUI Home/小爱/闹钟/Mesh 共存、开机与长时间低资源常驻验收。原有 0.1.1 实机结果不自动适用于本 RC。

本候选不附尚不存在的 GitHub Release 下载链接；发布和 tag 均为后续独立步骤。
