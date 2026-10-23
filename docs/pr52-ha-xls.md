# PR52：HA XLS 课表传送

HA 集成 0.1.2 新增 `schedule_xls` / `schedule_xls_v1`，使用原上传和签名下载端点、临时目录、保留时间及回执。仅传递二进制，不解析 Excel。限制 `.xls`、1 MiB、管理员上传和已声明能力的目标；CSV/ICS 不能用 XLS 扩展名绕过校验。面板沿用文件、目标、保留时间和逐目标状态，并提示升级不兼容设备。

X08E 新增能力注册和 XLS 下载分发，校验格式对应文件名、长度、SHA-256 和既有签名路径；使用 PR50 TimetableImporter，复用 Ready / NeedsProfile 预览和确认。解析或完整性失败进入受保护的 rejected_invalid 终态；本地提交成功才 imported，取消 dismissed。没有课程存储、同步、数据库或网络协议命令变更。

## 部署

先覆盖 HA 的 `config/custom_components/dormpanel` 为 0.1.2，并重启 HA。当前 async_unload_entry 返回 False，不能依靠重新加载替代重启。然后升级 X08E 并重新连接 HA，以原身份和密钥重新注册能力；刷新面板，从 XLS 区域选择文件和兼容设备发送。未知学期由用户配置日期与作息，不能猜测。

main 已由 PR51 更新 X08E 至 0.1.7 / 10，因此本次使用 0.1.8 / 11，避免降版本；applicationId 与签名配置保持原样。Phone 0.1.7 和 FPK 0.7.2 不变。

## 实际验证（2026-10-23）

- Python unittest discover：20/20。覆盖管理员、文件名、跨类型、目标能力、大小、保留时间、下载 MIME/字节、重启恢复、SHA-256 元数据、回执、终态清理及缺少 kind 的旧记录。HTTP 使用真实 aiohttp 响应对象及 HA 边界桩，非真实 HA 服务。运行测试需 Python aiohttp。
- `node --check homeassistant/custom_components/dormpanel/frontend/panel.js`、Python compileall、git diff --check 通过。
- `:app:testDebugUnitTest --tests '*HaRelayDownloaderTest'`：5/5，含真实脱敏 BIFF8 下载、13 系列/131 实例、MIME、跨类型、路径、长度和哈希拒绝。
- `:schedule-core:testDebugUnitTest --tests '*HubeiXlsReaderTest'`：4/4，含 NeedsProfile、XLS/CSV 身份等价和无效文件。
- `:app:assembleRelease`、`:app:lintDebug` 通过。初次使用不存在的编译任务、混合 testBuildType 的任务选择及测试字段拼写失败后已修正，最终上述命令通过。
- X08E API 28 隔离 testbed：HaRelayProfileOverrideTest 2/2（CSV 与 XLS 自定义学期），HaRelayRecoveryAndroidTest 9/9（含 XLS 中断恢复、重复通知及原 APK 回归）。HaRelayInvalidatedPreviewTest 1/1（确认前配置失效保护）。相同测试由 Gradle 同时在已连接 MIX 2S 隔离包运行并通过；没有改动 Phone 产品代码或版本。多类过滤首次仅执行第一类，后续改为逐类执行。

## 候选与验收边界

本地候选位于 `artifacts/pr52/`：HA 0.1.2 zip、X08E 0.1.8 unsigned APK 和各自 SHA-256；二进制不提交 Git。当前环境没有注入 release 签名参数，因此不能把未签名 APK 当作可直接原位升级的正式签名包；发布前须使用现有证书重建并验证签名。

尚未进行真实 HAOS 面板上传 → X08E 可视预览 → 用户确认 → HA imported 的全链路验收，亦未验证正式签名日用包原位升级、历史数据保留与最终 1280×800 可视布局。设备测试使用 MockWebServer / Fake channel，不是 LIVE PASS。没有发布 Release、tag 或自动合并。


## 真实环境补充验收（2026-10-24）

使用用户指定的 HA 环境，在 Codex 侧边浏览器通过 Filebrowser 更新本次变更的 const.py、http.py、relay.py、manifest.json、frontend/panel.js；重启 HA 后 XLS 区域正常出现。没有更换 HA 的注册记录或密钥。

使用本机原 keystore 构建正式签名 X08E 0.1.8 / 11，apksigner 与 aapt 检查通过：包名 com.dormpanel.app、minSdk 28、targetSdk 37、非 debuggable，证书 SHA-256 与既有 32d68b5c6ad0bfd1b87aa0d54514ef71c33838e7643eb038488af7ca2add17f7 一致。正式候选为 artifacts/pr52/DormPanel-X08E-0.1.8.apk，附 SHA-256；前述 unsigned 包仅保留作构建记录。签名口令未写入文件或仓库。

真实 X08E 通过 adb install -r 从 0.1.7 / 10 原位更新成功，启动后沿用同一 installation_id 注册 XLS 能力。HA 面板选择脱敏 BIFF8 fixture、1 小时保留和该 X08E，发送后 HA 显示 preview_ready。设备 1280×800 预览显示 Hubei University XLS、13 series、131 classes、学期日期和分阶段作息；选择 Import as new timetable，点击 Import 后设备显示 Timetable imported / 131 classes，HA 实时更新为 imported。此路径为真实 HA 与日用 X08E 网络、解析、预览和本地提交回执的全链路通过，不使用 Fake channel。

本地证据：artifacts/pr52/live/x08e-preview.png、ha-imported.jpg、device-ui.xml。截图不提交到公共仓库。此次以新来源添加脱敏测试课表，没有替换已有课表来源；测试来源保留在设备，便于复核。没有执行清数据、卸载日用包或系统服务调整。

剩余边界：日用包所有历史数据的逐项对照、真实 HA 断线/超时/坏文件组合与未知学期交互未全部重演；这些边界由前述定向测试覆盖，不能表述为所有真实场景均已验收。
