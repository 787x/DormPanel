# DormPanel 0.1.7 — X08E & Phone

X08E 与 Phone 统一为 0.1.7，支持直接选择湖北大学原版 BIFF8 `.xls` 课表并预览导入。X08E 临时 LAN 上传和 Android WebDAV 精确文件/最新文件夹也支持 XLS。

仅支持湖北大学特定个人课表布局及有界 BIFF8/OLE v3 文件（最多 1 MiB），不支持任意 Excel、其他学校、XLSX、HTML 伪 XLS、加密文件或公式课程内容。未知学期须先配置；钟点以学期 Profile 为准。预览确认前不写入。

原有 CSV/ICS、课程来源及 Sync v2 保持兼容；替换 CSV 来源保留 sourceId，相同结构及 Profile 保留课程实例身份。升级前建议备份，使用相同正式签名 APK 原位安装，勿卸载或清除数据。X08E code 10，Phone code 9，Android 9/API 28 起；已安装更高 code 时勿降级。

本次两端日用包原位升级、启动及数据逐文件核对通过；详细资产及验收范围见 [发布记录](release-android-017-fpk-072.md)。
