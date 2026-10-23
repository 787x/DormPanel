# PR48 合并前验收

本页以 `final/` 下日志为最终结果；此前根目录的 build / regression / week-ui 等日志和截图属于首轮过程证据，其中失败不代表最终状态。Phone UI 不修改投影、Sync v2、Room、课程编辑算法、X08E 或 FPK。

## 修复与测试环境

- FixtureProvider 的 authority 与测试 URI 一致，安装包中也确实注册。原问题位于测试包的组件解析/启动：新装测试 APK 后，instrumentation 内 query 返回 null，而 shell 对同一 authority 可读取；shell 先解析后即可运行完整导入测试。测试现在声明独立 `:fixtures` 进程，隔离测试目标包显式 queries，并在 setup 校验 provider。`tools/test-phone-pr48.ps1` 在安装后执行 shell provider 预检再启动 instrumentation，并检查 JUnit 通过摘要，不能只信 adb 的退出码。生产 Release 不包含这些查询和 provider。`final/provider-cold-failure.txt` 保留冷启动失败对照，未以跳过导入测试规避。
- MIUI 的“自动保存账号密码”系统窗口会抢占焦点，导致同步测试误报超时。`final/miui-password-overlay.png` 是直接证据。测试辅助函数仅识别该标题并取消保存，不保存测试密码、不取消应用对话框。远端预览等待也改为等待实际对话框，关闭管理窗口后等待 Activity 获得焦点。
- 修复 Activity 加载/连续同步刷新时，尚未布局的零滚动值覆盖恢复位置的问题；恢复回调绑定当前 scroller，过期回调不作用于新页面。
- 大字体截图发现时间刻度换行，现保持紧凑单行刻度；日期短标签限制结构字号，完整无障碍描述保留。横屏日期范围行压缩为单行，给课程区域留出高度。没有重设计周视图。

## 实际验证

- Phone 7 项完整课表 UI 回归通过：ICS / CSV 导入、非法文件保护、指定周/全周编辑与重置、日期调整、来源替换/删除、正常重开、七列/日视图切换。见 `final/timetable-ui.txt`。
- 同步相关 5 项 UI 测试全部通过：双向冲突处理、发布/绑定/离线重开、3 项远端检查只读保护。见 `final/sync-ui.txt`。这些测试使用独立数据库和 Fake WebDAV，不修改日用课表。
- WeekLayoutTest 3 项通过；PR47 的 ScheduleDatasetSwitchTest / ScheduleMigrationUiGuardTest 共 4 项通过，覆盖换库业务状态/身份保留及不安全状态拒绝。见 `final/switch-guards.txt`。没有重新执行跨平台全量换库。
- 最终 Phone Release、隔离包、AndroidTest 构建与 Release lint 通过，见 `final/build.txt`。
- MIX 2S 的 360dp 竖屏、横屏、1.5 倍字体和深色模式专项：七列不溢出、课程点击、两门重叠课程分别进入正确详情、横屏滚动至 22:00 课程、课程区域至少 90dp、同步通知后周次/滚动保留、Activity 重建滚动恢复。最终结果见 `final/timetable-dark-360dp.txt` 和最终截图。设备显示密度、字体和夜间模式测试后恢复原值（440 / 1.25 / no）。

## 正式候选与安装

Phone `0.1.5 / versionCode 7`，applicationId `com.dormpanel.timetable`，minSdk 28。最终本地文件：`artifacts/pr48-ready-candidate/DormPanel-Phone-0.1.5.apk`。

SHA-256：`fe06e89c3413f65923e5a91fb950e7f95d9e3813f71e83f8cb5bf09788bfe89a`。

批准证书 SHA-256：`32d68b5c6ad0bfd1b87aa0d54514ef71c33838e7643eb038488af7ca2add17f7`，apksigner 验证通过，见 `final/signature.txt`。这不是新建调试密钥；证书 DN 沿用既有身份，不能仅根据 DN 文本判断签名用途。

首轮候选已从 0.1.4（6）原位升级到 0.1.5（7）并冷启动成功，见 `upgrade.txt`。首轮 SHA `0dd925...` 仅对应旧候选，不能用它校验本页最终候选；最终安装与数据库哈希对照见 `final/install.txt`。未卸载正式包或清除数据。未将未签名构建称为正式候选。

## 验收边界

课程交互证据来自与 Release 共用 UI 源码的隔离 testbed 包，正式日用包仅作签名/版本/安装与数据文件保留核对；没有在日用库新增、编辑或删除验收课程。跨午夜和周边界有显示模型测试，未额外在真实导入库逐项人工复核。没有声称覆盖所有字体、机型或极端课程数量；没有执行无关 X08E / FPK 测试或四小时 soak。候选没有 tag 或发布，PR 不自动合并。
