# FPK 0.4.0 候选

增加页面内加密备份、认证验证、同一安装恢复、外部历史依赖与发布隔离，以及工作区按需加载。保留 0.3.2 的 writer、连接、候选、冲突、历史和升级路径；不改变 Sync v2 文件协议或 Android。

文件：`artifacts/pr42/dormpanel-schedule-manager-0.4.0.fpk`，1,752,736 字节。

SHA-256：`e344e5a59e9b7ecd5102505ee1aa471c0b2a4a2784c49f68b228441a473ef40d`。

环境声明：`platform=all`、`nodejs_v22`、`os_min_version=1.1.3100`；实际本轮验证环境为 x86_64、fnOS 1.2.0800、官方 Node 22.18.0。没有据此确认 ARM/其他系统兼容性。

备份需至少 12 字符口令，只导出到已授权目录；口令不保存，遗失无法解密。外部 WebDAV/NAS 历史不默认包含，跨安装恢复不支持。恢复前必须验证和输入确认文本；不可用它接管另一仍在使用的 FPK writer。

完整安全设计、预算、复现方法、真实验收和限制见 [PR42 验证记录](pr42-fpk-backup-recovery.md)。尚未创建 tag、Release 或发布资产。
