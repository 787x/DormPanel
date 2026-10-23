# PR48 最终验收记录

本目录是当前验收来源。`../final/`、`../ux/` 为历史候选和诊断过程；其中“未完成”和失败日志已经由本页的新运行取代，不作为当前版本的通过证据。

## CSV 保存失败根因与修复

保存阶段诊断将路径区分为 editing → clicked → parsed → stored → callback → done。失败现场为 editing，说明保存监听器未执行，不能归因于解析、持久化或导入回调。`profile-before-fix.png` 显示多行作息框随完整文本长高，IME 打开时底部确认区被挤到可触区域之外；测试框架的可见性判断不足以反映输入窗口遮挡。

Phone 的阶段输入框现限制为六行并内部滚动，对话框使用 ADJUST_RESIZE。解析、存储及导入算法不变；保存异常自动滚到错误信息，空异常信息也有反馈。测试保留 IME 场景、明确匹配 Dialog、只做一次真实点击，断言六行测量界限、保存最终阶段及真实导入预览。未使用 performClick 或内部导入接口替代该流程，也没有跳过原测试。

未知学期 CSV 经配置、保存、预览、确认后得到 13 个系列、131 个实例，并在关闭重开后再次核对。阶段断言保证以后能区分点击未分发、保存失败和异步预览未完成。

## 运行结果

- 浅色 360dp、1.0 字体：完整 Phone 11 项 + 同步/绑定/检查 5 项，16/16 通过，见 light-verified-ui.txt。
- 深色 360dp、1.5 字体：完整 Phone 11 项，11/11 通过，见 dark-verified-ui.txt。
- 另有 dark-palette.txt 的六色色板测量与对比度专项通过。诊断期失败日志置于 diagnostics/，不改写失败事实。

## 设备与布局

MIX 2S 独立 testbed 包，随机 Room 数据库及隔离账号命名空间；测试调用现有 Phone UI 与编辑器。360dp 竖屏与横屏均实际展示湖北大学 CSV 第五教学周，并检查安全内容区域中的课表高度不小于 60%。断言未放宽；以真实安全区为分母，系统栏不计入课表可用高度。

- 浅色、常规字体：`light-verified-ui.txt`；真实截图 `light-portrait.png`、`light-landscape.png`。
- 深色、1.5 倍字体：`dark-verified-ui.txt`；真实截图 `dark-portrait.png`、`dark-landscape.png`。
- 卡片测量覆盖 34/80/120dp 宽、1/16/48/80/200dp 高、长标题和长教室；逐个检查可见 TextView 的左右/上下测量边界。短时卡片允许隐藏文字，内容通过详情与无障碍描述访问。容器仍保持裁切。
- 六色色板逐色验证可见文本的合成对比度至少 4.5:1；同一业务身份跨日期颜色一致，同名不同 seriesId 示例颜色不同。有限色板仍可能碰撞，名称与详情始终是身份依据。
- 实际导入 night.ics 验证跨午夜两个日期片段均进入原始实例详情，短时课程可打开；重叠课程逐项打开、晚间滚动、Activity 重建及同步通知后的周次/滚动位置保留均有 UI 断言。

## WebDAV 与换库

目录测试实际覆盖进入/取消、不改变选择与绑定、403/404、拒绝连接、无效 XML、空目录、加载时关闭、延迟返回、进入子目录后返回上级的旧响应隔离；这些目录测试所有记录请求均为 PROPFIND。中文/空格、编码分隔符、跨源/越界 href 与 XML 大小边界另由 WebDavFoldersTest / 既有客户端单测覆盖。

共享流程测试已从目录浏览选择 URL，再通过原 Sync v2 完整图预览与确认绑定。绑定后普通浏览入口被拒绝；安全换库入口复用选择器填写目标 URL，datasetId 必须另行明确填写，取消表单不准备迁移。选择目录不执行 PUT，不绕过原验证。PR47 换库的身份、pending、图校验及 writerId 保护继续由既有 ScheduleDatasetSwitchTest / ScheduleMigrationUiGuardTest 回归；未改这些业务逻辑。

MIUI 系统密码保存窗口会抢焦点，测试辅助机制只取消已识别的该窗口，不保存测试凭据。原测试还修复了“同步尚忙即点击换库”、未明确等待预览 Dialog、无 Dialog 时错误限定 Dialog 根窗口等同步问题；未降低 busy 或迁移保护。

## 构建与候选

`build.txt`：Phone Release 构建/lint、布局单元测试 3 项、schedule-core 149 项（含目录安全和 PR47 换库保护）通过。Android UI 测试包均由当前代码构建。

正式候选：`artifacts/pr48-final-016/DormPanel-Phone-0.1.6.apk`。

- applicationId：com.dormpanel.timetable
- 版本：0.1.6 / versionCode 8；minSdk 28。
- 批准证书 SHA-256：32d68b5c6ad0bfd1b87aa0d54514ef71c33838e7643eb038488af7ca2add17f7。
- APK SHA-256：f21dfc679d267a94dba0de1ba4ade52bc1ad47c0b77603f18d5bd93adc912812。

见 `signature.txt`，此前 0.1.6 中间候选的哈希不适用于该文件。安装与数据保留核验见 `install.txt`。MIX 2S 已从 0.1.5（7）原位升级到该 0.1.6（8），安装前保存完整本地应用数据 tar 和旧 APK；备份保存在用户私有 .dormpanel 目录，不加入 Git。安装前后 databases/ 与 shared_prefs/ 所有文件哈希一致（data-before.sha256 / data-after.sha256 可逐行比较），设备取回的 APK 哈希与候选一致。正式包未启动，未在日用库触发同步或写验收课程。

## 边界

交互验收使用 MIX 2S，不代表已经在每种手机或 Android 9 实机执行；维持 minSdk/API 28 兼容。没有在正式日用库新增、删除或编辑课程。未在真实 fnOS 目录运行此次浏览/换库，相关网络和保护验证使用隔离 MockWebServer 数据集。没有重跑 FPK/X08E 全量回归或四小时 soak；没有修改协议、Room schema、稳定身份或课程投影语义。未合并、打 tag 或发布。
