# Android 0.1.3 候选说明（PR41，未发布）

X08E 可以从「Schedule → Timetable → Sources → 共享课表管理（WebDAV）」首次绑定由 fnOS FPK、Phone 或其他 Sync v2 Writer 已创建的共享课表。未绑定时明确区分创建与绑定，继续使用已有 WebDAV 账号。

绑定前显示远端身份、课程/来源/学期与历史状态；确认时完整复验观察到的版本图，发现变化要求刷新。课程、基线、frontier、历史及目标身份在同一 Room 事务保存；非空本地课表、旧绑定、待确认发布、冲突和迁移恢复状态不能静默覆盖。

绑定后继续使用原双向 Append-only v2，不改变线上协议、FPK、applicationId、签名、Room schema、ICS/CSV 导入、HA 或权限。

| 候选 | 版本/身份 | SHA-256 |
| --- | --- | --- |
| DormPanel-X08E-0.1.3.apk | `com.dormpanel.app`，code 7 | `e4a416e96d93fab9974f1006eae2bf7da54973bcc2245fa1f38843f704c7a4a4` |
| DormPanel-Phone-0.1.3.apk | `com.dormpanel.timetable`，code 5 | `29b9a73799001c8790d7d5501f85c14b2a325188ef9570c7d3f2d60b7d6ca2c6` |

两端 minSdk 28 / targetSdk 37、非调试包、沿用批准的正式证书。产物在本机 `artifacts/release/0.1.3-pr41-candidate1/`，不可覆盖冻结目录；没有上传资产或创建 tag/Release。附带 HA ZIP 继续是既有 0.1.1，本次没有 HA 升级。

X08E 候选已在真实设备原位安装，使用 FPK 0.3.2 与 Phone 正式版 0.1.2 验证首次绑定、三方编辑、无重复提交和应用进程重启。Phone 候选本次没有实机安装。完整自动化、实机范围、原数据恢复及未验证项见 [PR41 验收记录](pr41-android-existing-v2-binding.md)；最终发布仍需另行决定。
