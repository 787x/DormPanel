# Phone 顶部立即同步入口

在应用内“管理”左侧增加“刷新”按钮，直接调用已有 SharedScheduleController.sync()，与管理 → WebDAV 共享课表 → 立即同步相同。按钮启用条件复用 SharedAction.SYNC 和原入口的读取／同步状态条件；额外在文件操作期间禁用。未配置、未绑定、读取失败、恢复保护或同步中不可点击，使用系统默认灰色禁用样式。同步过程中显示“同步中”，状态变化直接更新按钮。

验证：assembleX08eTest、lintDebug 成功；API 28 与 MIUI API 33 各 1 项 Android 测试通过，检查按钮位于管理左侧、未配置／恢复状态禁用、可同步状态启用及正在同步禁用。后两种状态使用隔离测试中的 presentation／busy 控制，不配置真实账号或发起远端请求；没有将其称为真实 WebDAV 同步测试。

MIUI 实际未绑定画面确认刷新 enabled=false，范围 [783,111][915,243]；管理 enabled=true，范围 [915,111][1047,243]。见 miui-unconfigured.png。MIUI 测试期间关闭了遮挡窗口的 USB 模式选择弹窗，没有更改 USB 模式。只安装 .pr72testbed 隔离包，未改日用包、凭据或同步协议；既有同步控制器未改动，未重复远端同步矩阵。
