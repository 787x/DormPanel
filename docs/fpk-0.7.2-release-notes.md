# DormPanel Schedule Manager 0.7.2 — fnOS Native App

预发布版本。网页文件选择、CLI `import preview --file` 和 MCP `import_preview` 支持湖北大学原版 BIFF8 XLS；沿用预览、确认、取消流程，确认前不写入。

限湖北大学特定个人课表格式、OLE v3/BIFF8、最多 1 MiB；不支持通用 Excel、XLSX、加密文件或任意学校布局。未知学期先配置，时间以学期 Profile 为准。原有 CSV/ICS、来源、工作区、连接与数据身份兼容；替换来源沿用现有身份和覆盖保留规则。

安装环境沿用 fnOS x86_64、官方 Node 22。先备份应用 var/etc 与工作区，再从应用中心手动上传 FPK 正常升级；勿使用开发用 install-local、卸载重装或破坏性恢复。实际完成 0.7.1 → 0.7.2 正常升级及安装后网页 XLS 预览。

已知显示限制：冻结候选网页页眉仍写 0.7.1，入口按钮仍写 CSV；实际安装包及 CLI health 为 0.7.2，文件选择接受 XLS。为保持已验证候选不可变，本次不修改该显示文字。

完整资产与验证范围见 [发布记录](release-android-017-fpk-072.md)。
