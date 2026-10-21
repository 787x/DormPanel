# DormPanel 0.1.2 发布说明

这是基于已接受的 RC2 C01 发布的 **0.1.2 正式版**。用户已明确报告后续 1–4 项验证全部完成，并授权发布稳定版；该报告按人工确认记录，不补写未提供的时长或 CPU/PSS 指标。正式产物与安装核对见 [发布验收记录](RELEASE-0.1.2-acceptance.md)。

## 正式支持范围

支持现有本地课程与配置原位升级，以及基于 **Append-only Schedule Sync v2** 的新建数据集、绑定、双向编辑、离线合并与冲突处理。X08E 继续与小爱、MIUI Home、Bluetooth Mesh、原生闹钟等共存，普通设备控制不依赖 LLM。

**已有 v1 共享课表绑定不提供兼容性保证（N/A — Out of Scope）。** 原 v1 数据保护代码、checkpoint 与旧远端历史继续保留；隔离迁移测试通过不等于该历史路径受正式支持。不要用清库、删除历史或无条件覆盖强制切换。PR34 的 RC1 NOT ACCEPTED 历史结论不变，FN Connect 的 v1 条件写失败未被宣称修复。

## 双端正式产物

| 应用 | applicationId | versionCode / versionName | minSdk / targetSdk | SHA-256 |
| --- | --- | --- | --- | --- |
| X08E | `com.dormpanel.app` | 6 / 0.1.2 | 28 / 37 | `bb18847a5bf7dd3b4004099744d5e8b55a4cafd018e57058b7739b080450821f` |
| 手机课程表 | `com.dormpanel.timetable` | 4 / 0.1.2 | 28 / 37 | `a6a5840debf2b77dbe16f118528bdc73051102fff25949aa419c3bb600940958` |

两份为不可调试的正式签名 Release APK，批准证书 SHA-256 为 `32d68b5c6ad0bfd1b87aa0d54514ef71c33838e7643eb038488af7ca2add17f7`。正式版保留 RC2 的全部 DEX/资源；APK ZIP 条目只有 AndroidManifest 版本信息和 Git 构建元数据变化，签名与整包哈希相应更新。RC2 C01 原文件保持冻结，未用重命名 RC2 APK 代替正式构建。

[GitHub v0.1.2](https://github.com/787x/DormPanel/releases/tag/v0.1.2) 提供双端 APK 与 `.apk.sha256`，另附独立版本的 `DormPanel-HA-0.1.1.zip` 与哈希文件。**HA 集成仍为 0.1.1，无需因 Android 版本变化替换现有集成。** 安装前核对哈希、包名及签名，使用同 applicationId/批准签名正常覆盖安装；不要卸载、清数据或降级。任意开发包不承诺可直接覆盖。

## Sync v2

每次发布使用独立 UUID、自包含不可变提交；写前持久化意图，写后 GET/字节/SHA-256 核对，避免依赖共享可变 head 和 v1 条件 PUT。双端离线修改、候选 capture、上传中新增编辑、重启恢复及明确冲突决定均由共享状态层处理。不确定路径不会被危险覆盖，部分文件不能冒充完整同步，历史不自动 GC。

前台恢复、正常编辑和有网络约束的有界 JobScheduler 负责同步；没有新增永久服务或高频周期轮询。达到容量、时间或历史预算时保留本地修改并明确提示，成功发布不等于另一端已接收。详见 [Sync v2 协议](sync-v2-protocol.md)。

## 验证与限制

- 继承 RC2 的正式双端 v2、真实 FN Connect 故障恢复、重启、自启、共存与隔离协议证据；原始证据范围不改写。
- 用户报告手机非空升级、Todo/Memo 非空场景、重启后 HA 实控、日常稳定性/网络恢复四项均完成；属于人工确认，不假称新增自动化或指定时长 soak。
- 新 0.1.2 APK 已在真实 X08E/手机从 RC2 正常覆盖安装；两端实际非空课程及全部数据库表摘要保留，安装 APK 回读哈希与正式冻结产物一致。签名/元数据、JVM、Lint、发布守卫和真实 APK 反例验证通过。
- 不承诺任意设备或无限期无异常、OEM 后台准时调度、跨设备 Android Keystore 备份恢复。使用前保留可恢复备份；发现数据损坏、不可恢复同步或严重稳定性问题应停止继续写入并保留证据。
