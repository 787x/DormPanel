# Android 0.1.7 与 FPK 0.7.2 发布记录

基线：PR50 合并提交 `470157b8c57ad2bc9b4ab289638b0356858a9152`。本 PR 仅调整 X08E 版本及共享发布校验配置；Phone、FPK 与生产业务代码不变。

## 最终资产

| 资产 | 字节数 | SHA-256 |
| --- | ---: | --- |
| DormPanel-X08E-0.1.7.apk | 16285819 | `b1c50a01f2b5ebb0cc8e1e5512b5ca9d3f1703ed635d5eb73de8872fa0ceb683` |
| DormPanel-Phone-0.1.7.apk | 9929227 | `1498e0c6b1bc7180bcf2d98ab7d908f7f3682377d5f83f10a79c45f018292c7c` |
| dormpanel-schedule-manager-0.7.2.fpk | 4532519 | `603bc89a19f7405b07a98bff93474eded8e925784262f7f020425a8e653875c2` |

Phone、FPK 从 `artifacts/pr50/` 读取真实字节核对指定 SHA-256 后原样复制，收尾再次核对一致；原目录未覆盖。新资产及对应 ASCII SHA-256 sidecar 位于本机 `artifacts/release/0.1.7/`；二进制与私有备份不提交 Git。初次构建无签名环境生成 unsigned APK，随后使用用户提供的既有口令及批准 keystore 正式签名；未使用 debug fallback。

仅运行 `:app:assembleRelease`，未构建 Phone 或 HA。两份最终 APK 均经 apksigner/aapt 身份验证：X08E `com.dormpanel.app` / 0.1.7 / code 10，Phone `com.dormpanel.timetable` / 0.1.7 / code 9，均 minSdk 28、targetSdk 37、non-debuggable，唯一签名证书 SHA-256 `32d68b5c6ad0bfd1b87aa0d54514ef71c33838e7643eb038488af7ca2add17f7`。修改后的发布校验脚本 37 项 guard 检查通过（模拟工具输出，不替代真实签名验证）。

## 本次实际升级验收

- X08E：原日用 0.1.4/code 8 → 0.1.7/code 10，Phone MIX 2S：原日用 0.1.6/code 8 → 0.1.7/code 9。均先停止目标应用，在设备生成完整 tar，经 Base64 安全传输并逐成员读取、校验完整归档尾部，再 `adb install -r`；另保存原安装 APK。未使用降级、卸载或清数据。
- X08E 32 份、Phone 20 份数据库/偏好/files/no_backup 文件，安装后及正常启动后与升级前逐文件 SHA-256 完全一致，覆盖课表、设置和 Sync v2/绑定身份。安装成功，版本核对正确。X08E 1280×800 横屏首页正常，打开已有课程详情（最短等价实际功能检查）。Phone 复用 PR50 XLS 实机证据，未重复导入测试。未修改小爱、Mesh 或系统服务；本次未独立重测小爱语音及 Mesh 网关端到端功能。
- fnOS：原 0.7.1 经应用中心网页上传原始 FPK 正常升级到 0.7.2，无 install-local、卸载或恢复迁移。停止服务后完整归档解引用 var/etc，实际读取 46 文件；升级后及正常 stop/start 后 46 份文件哈希全一致，包括原工作区、连接、凭据/证书、writerId 与数据身份。CLI health 0.7.2 正常；实际安装包装器 MCP stdio initialize 与 69 项 tools/list 通过（初次本机 GBK 解码失败，指定 UTF-8 后通过）。
- 在已安装 FPK 正式网关网页切换到原有 PR49 合成验收工作区，仅新增预览所需学期 Profile，再通过文件选择器上传脱敏 XLS，预览显示 131 实例后关闭，未确认导入、未替换来源。切回原默认工作区。收尾原 46 文件中仅 catalog 及合成工作区的 workspace/summary 三份因切换及新增测试 Profile 改变；其余 43 份仍逐字节一致。测试 Profile 留在隔离合成工作区，不做破坏性恢复。私有备份保留本机及 NAS。
- 备份下载首次因 root 私有目录权限失败，未据此开始升级；调整备份目录归属给既有管理员并完整下载读取验证后才上传 FPK。

装后网页预览证据：本机 `artifacts/release-017-private/fpk-xls-preview.png`；Android 脱敏核对摘要见 [证据](evidence/release-017/android-summary.json)。原始归档、UI 与身份材料留在忽略目录。

## 继承与限制

PR50 XLS/CSV 全课程等价、Android 单元测试、Lint、隔离实机、fnOS Node、CLI/MCP 与隔离浏览器证据继承自 [PR50](pr50-hubei-xls.md)，本次未重跑。未执行四小时 soak、全部 instrumentation、整套 fnOS Node 或无关 HA 测试。

冻结 FPK 的页眉仍显示 0.7.1、入口按钮仍写 CSV；实际包与 CLI 为 0.7.2，文件选择与 XLS 预览正常。作为已知显示限制披露，不修改冻结候选。

发布目标：`v0.1.7` Stable/Latest，4 个资产；`fpk-v0.7.2` Pre-release、非 Latest，2 个资产。发布前确认 tag/Release 不存在；从本 PR 合并后的 main 创建，禁止覆盖。发布后只读取 API 元数据验证 tag SHA、状态及资产名称/大小，不重新下载。此前发布元数据与资产不修改。
