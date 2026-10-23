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
