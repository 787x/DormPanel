# 0.1.2 正式发布验收记录

**结论：正式版发布验收通过。** 用户在本轮明确回复“1-4全部验证完成，发布v0.1.2”，确认先前列出的手机非空数据升级、Todo/Memo 非空场景、重启后实体灯控制以及日常稳定性/网络恢复。此为人工确认；没有附带原始截图、持续时长或资源采样，故不补造这些指标，也不将它描述为在新 0.1.2 APK 上重新执行了整套硬件验收。

## 身份、基线和改动

基线为 PR36 已合并 main `017babdfbdd3415ee58ccbc0078d0a9a45c0ef31`。构建源码提交 `4678048c9adbdf10996d612f532430d7dad45a02` 仅递增双端版本为 X08E code 6 / Phone code 4、名称 0.1.2，并调整发布工具的目标身份和输出文案。生产 Kotlin/XML、数据库 schema、协议、权限、签名身份和 SDK 配置未改。

新正式候选 S01 冻结于本机 `artifacts/release/0.1.2/`，未覆盖 RC2 C01。之后仅增加文档/证据，不重新构建。APK 内全部 DEX、资源及其他原有 ZIP 条目与 RC2 相同，仅 AndroidManifest 与 `META-INF/version-control-info.textproto` 不同，见 [条目比较](evidence/release-0.1.2/apk-diff.json)。本记录将原 RC2 的行为证据与新正式 APK 的身份/安装验证分别标明。

[产物清单](evidence/release-0.1.2/artifacts.json)及[实际工具输出](evidence/release-0.1.2/artifact-verification.txt)记录两份新 APK：唯一批准证书、SDK 28/37、non-debuggable、版本/包名及真实 SHA-256 全通过，sidecar 与最终字节/文件名匹配。HA 仍为 0.1.1，已有 archive 完整性与 manifest 版本检查通过。

## 新正式包实际原位升级

在真实 API28 X08E 与 API33 手机分别从已安装 RC2 code 5/3 覆盖安装到 0.1.2 code 6/4。升级前停止应用，私有备份数据库（含 WAL）、偏好、files 和旧安装 APK；SQLite integrity_check、独立 backup 副本再次检查均通过，随后只执行正常 `adb install -r`，无卸载/清库/降级/数据库替换。

两端基线实际均有 1 来源/131 实例，且 v2 绑定存在；不沿用更早“手机空库”的假设。升级后正式 Activity 正常启动，再停止并只读回读：全部数据库表（含同步 checkpoint、Dashboard 和效率工具实际存在的表）摘要保持，首次安装时间保留，已安装 APK SHA-256 分别与冻结 S01 相同。见[升级摘要](evidence/release-0.1.2/upgrade-summary.json)。

偏好只有同步结果记录变化：X08E 更新 last_success；手机从“离线/等待重试”变为“已检查观察到的版本/已发布”，更新成功时间并清除旧重试计数。folder/dataset/账号、加密凭据及其他配置未变化，不声称偏好文件逐字节全部相同。两个日用应用已正常恢复打开，私人备份保留在本机忽略目录 `artifacts/pr36/stable-0.1.2/`，未提交明文凭据或私人课程行。

## 实际检查与发布范围

- 正式构建通过；JVM app 198、schedule-core 134，0 失败/错误；Phone 无独立 JVM 用例。
- 三模块 Release Lint 通过；发布解析/守卫 37 项及真实 APK 反例 9 项通过，包含 RC2 旧版本拒绝与冻结目录覆盖拒绝。
- 新 APK 签名、元数据、哈希、正式安装和数据保留单独验证；未将 RC2 哈希/安装身份代替新产物。由于逻辑 DEX/资源一致，不机械重跑原 13 项隔离迁移及整套硬件测试。
- 用户确认的四项后续验证作为人工结果保留。RC2 的原 UNVERIFIED 记录属于当时事实，不覆写；新手机非空 RC2→正式版升级另有本轮自动回读证据。
- 现有本地数据升级及 Sync v2 新建/绑定/双向同步在支持范围内；已有 v1 共享绑定仍为 N/A — Out of Scope，不删除旧保护代码、checkpoint 或远端历史。PR34 历史结论与 RC2 C01 产物保持不变。

未发现新增实质发布阻塞。用户已明确授权发布 v0.1.2；发布 tag 应指向包含本版本元数据和本记录的 main 提交，上传 S01 原文件及 sidecar，将正式版设为稳定 Latest，保留 RC2 Pre-release。最终发布状态以 [GitHub Release](https://github.com/787x/DormPanel/releases/tag/v0.1.2) 为准。
