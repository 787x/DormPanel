# Android 0.1.3 候选说明（PR41，candidate2，未发布）

X08E 可以从「Schedule → Timetable → Sources → 共享课表管理（WebDAV）」首次绑定由 fnOS FPK、Phone 或其他 Sync v2 Writer 已创建的共享课表。未绑定时明确区分创建与绑定，继续使用已有 WebDAV 账号。

绑定前显示远端身份、课程/来源/学期与历史状态；确认时完整复验观察到的版本图，发现变化要求刷新。课程、基线、frontier、历史及目标身份在同一 Room 事务保存；非空本地课表、旧绑定、待确认发布、冲突和迁移恢复状态不能静默覆盖。

绑定后继续使用原双向 Append-only v2，不改变线上协议、FPK、applicationId、签名、Room schema、ICS/CSV 导入、HA 或权限。

candidate2 修复 Phone 已绑定后「测试连接 / 检查远端快照」被隐藏的回归：检查入口持续可见，已绑定时仅展示只读远端预览，不提供首次绑定确认。异步结果返回时重新判断绑定状态；关闭管理窗口会忽略迟到结果。X08E 仍仅在未绑定时显示首次绑定入口。

| 候选 | 版本/身份 | SHA-256 |
| --- | --- | --- |
| DormPanel-X08E-0.1.3.apk | `com.dormpanel.app`，code 7 | `73c18c037f2f23f04ebc71e815d635d4f98a65ca6c4635b75f8b4893747d90e2` |
| DormPanel-Phone-0.1.3.apk | `com.dormpanel.timetable`，code 5 | `0780296ec6abb554d5570eaa6046c2701b8cfdda5e62e78e4dafaa7cad77a0d6` |

两端 minSdk 28 / targetSdk 37、非调试包、沿用批准的正式证书。当前产物在本机 `artifacts/release/0.1.3-pr41-candidate2/`，旧 `candidate1/` 原样保留；未改变版本号、versionCode 或覆盖冻结目录，没有上传资产或创建 tag/Release。附带 HA ZIP 继续是既有 0.1.1，本次没有 HA 升级。新候选字节及签名记录见 [candidate2 清单](evidence/pr41/inspection-candidate2.json)。

旧 candidate1 的 X08E APK 曾在真实设备原位安装，使用 FPK 0.3.2 与 Phone 正式版 0.1.2 验证首次绑定、三方编辑、无重复提交和应用进程重启；旧字节清单保留在 [candidate1 清单](evidence/pr41/artifacts.json)。该实机结论仅对应旧哈希。

当前 candidate2 两端均完成正式签名、包身份、SDK、非调试及哈希验证；相同修复源码的 Debug 变体在 API 28、1280×800 上通过 Phone/X08E UI 回归。candidate2 没有安装真实设备，也没有重做完整三端实机验收、四小时 soak 或整机重启。完整自动化、实机范围、原数据恢复及未验证项见 [PR41 验收记录](pr41-android-existing-v2-binding.md)；最终发布仍需另行决定。
