# PR88 Android Todo 与备忘验收

基线为 `3f1e27c`。测试日期为 2026-11-17（北京时间）。X08E 使用 1280×800、160 dpi、API 28 模拟器，Phone 使用 Pixel 10 Pro、API 37 模拟器。两个 testbed 使用开发机上的独立 WsgiDAV 目录，通过各自的 ADB 转发访问实际 HTTP/WebDAV。所有测试记录均为合成数据。

## 使用方式

- X08E 长按 Todo 或备忘卡片打开「数据来源」，也可从本机编辑器进入。升级后用原卡片 ID 读旧库。连接共享内容和切回本地只保存本机卡片绑定。导入预览按当前卡片显示源内容和共享目标，确认及绑定分别复核。
- Phone 从课表的「管理」进入 Todo 或备忘。数据来源可切回仅本机、连接当前 DPV3/4，或确认导入本机清单、任务和备忘。本机记录保留原 UUID 和内容。
- 尚未绑定共享时，可从现有 WebDAV 管理明确创建 DPV3/4 或加入服务器上的已验证 DPV3/4。既有 DPV3/3 的升级仍需安全迁移流程。
- Phone 在桌面分别添加 Todo 和备忘小组件。配置时选来源和记录，可在支持重新配置的 Launcher 中更换绑定。Todo 标题打开清单、正文进入任务编辑、勾选区提交明确的完成状态。备忘正文打开编辑。刷新先读取本机权威数据，共享刷新交给现有同步调度。
- 小组件和共享卡片以 datasetId 和记录 ID 识别目标。小组件还核对实例、绑定代次、任务所属清单、本机 revision 和业务哈希。删除卡片或实例不会删除业务记录。若数据集不再匹配，原绑定保持可辨识。

## 实测记录

| 检查 | 结果 |
| --- | --- |
| 构建 | X08E、Phone Debug APK 和隔离 testbed APK、instrumentation APK 构建通过。 |
| JVM 与跨语言 | X08E 定向 23 项、schedule-core 定向 9 项、Phone 89 项通过。Node PR87 效率业务 3 项通过。定向 JVM 覆盖共享记录预算、未知必需能力和课程重载边界。 |
| X08E 卡片 | API 28 上旧卡片读取、按卡片导入、共享清单绑定两张卡并同步更新、备忘换行、切回本地、删除卡片后保留清单、Timer 保留通过。 |
| 导入和配置 | 旧库导入与本机效率回归合计 6 项通过，覆盖已排队旧写、源指纹、目标内容或成员变化、同 ID 不同数据集、重复确认、绑定保存失败注入、旧库逐行一致、尺寸和 Timer。 |
| Phone 页面和小组件 | API 37 上验证已核验 DPV3/4 加入、显式导入预检与确认、原本机内容保留、共享添加任务、备忘超长输入保留、本机文件重开和过期删除保护。在 120% 字号从课表「管理」创建本机清单和空备忘。用原生 AppWidgetHost 配置三实例，验证尺寸、实例隔离、真实 RemoteViews 勾选及重复和过期请求。既有今日课表小组件的冷启动、复用 Activity 跳转用例通过。 |
| 断网与重开 | 强停 Phone testbed 后移除 WebDAV 端口转发。本机启动时 Widget 可读，网络请求拒绝连接，备忘仍能保存并进入待发布状态。恢复转发并重新启动进程后验证服务端回读、待发布清除、移除 Widget 仅清理实例绑定。同数据集上的 X08E 收到了 Phone 真实勾选结果和离线保存的多行备忘。 |
| 冲突 | 两个隔离 Room Writer 在真实 WebDAV 上修改同一备忘。验证真实基线、本机和远端文本、冲突期间编辑拒绝、过期 token 拒绝、正式决定与回读，课表完整刷新计数为 0。 |

全量 X08E JVM 另运行了 289 项，其中 `WebDavClientTest.lastModifiedFallbackAndTimeout` 失败，在 `main` 的独立检出复现。X08E 旧目录用例 `catalogPickerIndependentOwnersRepairAndEditMode` 期望遗漏两个已存在的课程卡片，在 `main` 的原生模拟器用例同样失败。

## 截图

| 界面 | 截图 |
| --- | --- |
| X08E 本地及共享卡片 | [本地卡片](x08e-local.png) · [数据来源](x08e-source.png) · [导入预览](x08e-import.png) · [共享卡片](x08e-shared.png) |
| Phone 管理界面 | [Todo](phone-todo.png) · [备忘](phone-memo.png) |
| Phone 原生小组件 | [195 × 135 dp](phone-widgets-compact.png) · [362 × 270 dp](phone-widgets-expanded.png) |

## 验证边界

使用隔离模拟器及 WsgiDAV。实体 X08E、实体 Phone、fnOS FPK 联调、OEM Launcher 的重启恢复和系统桌面完整手势未进行实测。共享状态按本机提交、待发布和精确回读分别呈现。普通 WebDAV 上仍可能有未观察的 Writer，回读证明对应的服务器对象，本机审批不能冻结服务器历史。
