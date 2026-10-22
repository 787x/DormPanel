# PR45：Schedule Manager 管理体验与无向导安装

日期：2026-10-22（Asia/Shanghai）。基于 main `8ef6de9`（PR44 已合并），候选版本 0.4.4。

## 修改范围

- 管理区域放在周课表之前，优先展示课程、ICS / CSV 导入、学期、停课 / 补课和日期调整。已有记录分组编辑；共享数据集、冲突及同步诊断仍可展开访问，原周视图与课程编辑逻辑保留。
- 数据源分为“我的课程表”和“存储位置”。卡片展示当前使用、存储类型、本地修改 / 待回读 / 同步检查 / 冲突 / 连接异常；完整状态与内部身份放在“高级诊断”。重命名、切换、连接编辑、测试、端点选择及移除入口保留。连接、工作区、创建数据集、绑定已有数据集仍分别调用原管理操作。
- 本地连接改为文件夹列表、面包屑与上一级。浏览成功只允许选择，独立确认后才启用保存；编辑时只在原目录读取成功后回显选定位置。私有根目录明确说明自动创建专用文件夹。加载中、无子目录、权限 / 读取错误及重试均有文字反馈。保留 PR44 的弹窗会话、目录请求序号、过期响应隔离、保存禁用和版本校验。
- 构建不生成 `wizard/install`，并移除增量构建中的旧文件。保留 `wizard/config`、官方文件夹资源和生命周期回调。`configure.mjs` 不再消费任何旧 WebDAV 向导环境，不重写旧配置；既有启动迁移仍读取原 `webdav.json`，新安装进入未绑定工作区。
- 深色样式、响应式卡片、危险操作颜色、键盘焦点及 Escape / 取消反馈改善。无新增框架、轮询或持续动画。
- 版本更新至 0.4.4；备份只更新 appVersion 与兼容白名单（0.4.0–0.4.4），格式和身份边界不变，未知版本仍拒绝。

未修改 Sync v2、课程解析 / 编辑 / 三方合并算法、catalog / workspace schema、目录访问权限、授权 / 事务 / 回执语义或 Android。

## 自动验证

| 检查 | 结果与证据 |
| --- | --- |
| 真实页面浏览器交互 | 19/19 通过。[日志](evidence/pr45/frontend-tests.txt)。涵盖原 PR44 公网初始化与 CSV 预览，管理入口、本地编辑、WebDAV 保存 / 工作区切换 / 重命名 / 端点选择 / 创建与绑定确认 / 移除入口、目录确认参数 / 取消 / 面包屑 / 空目录 / 回显、失败重试、快速切换、关闭重开、旧响应隔离与 390×640 窗口按钮可触达。 |
| Windows Node | 147 项：96 通过、51 项按平台跳过、0 失败。[日志](evidence/pr45/windows-node-tests.txt)。 |
| fnOS 官方 Node 22 / 包用户 / 独立临时目录 | 147 项：146 通过、1 项按平台跳过、0 失败。[日志](evidence/pr45/fnos-node-tests.txt)。包括目录权限、私有 Socket、管理员、CSRF、工作区版本、Sync v2、备份恢复及生命周期。 |
| 安装与升级回调 | 直接执行无参数、`--upgrade`、`--configure`，遗留向导变量不创建新配置；无配置首次启动可本地编辑。非空双工作区实例生成真实历史与待发布提交，回调和重启后逐字节保留连接、writerId / installationId、历史、冲突、pending 与扩展字段。 |
| 静态与构建 | `npm run check`、`npm run build`、WSL 官方 `fnpack build --directory build/dormpanel-schedule-manager` 通过。 |
| 最终 FPK 检查 | `npm run verify:package`：无 `wizard/install`、保留官方目录资源 / 配置 / 回调，20 个源码 / Web / package 文件与工作区逐字节一致，无 Playwright 生产依赖。[记录](evidence/pr45/package-verification.txt)。 |

浏览器截图：[管理](evidence/pr45/manage-desktop.png)、[数据源](evidence/pr45/sources-desktop.png)、[窄窗口目录](evidence/pr45/folders-narrow.png)。这些是独立测试实例，不作为真实 FPK 安装验收。

## 测试 NAS 实际验收

测试机升级前为 0.4.2，有 5 个工作区。停止应用后完整归档并回读验证 25 个私有文件，安全归档留在 NAS 私有目录，未提交凭据或归档。CLI `install-fpk` 仅返回已安装，未据此宣称升级成功；之后通过应用中心正常流程更新。恢复验收时测试机已运行 0.4.4，安装源码与本候选一致，17 个关键持久化文件（原凭据、catalog、identity、各工作区和私有提交）哈希不变，5 个工作区保留。[核对结果](evidence/pr45/fnos-upgrade.json)。

侧边浏览器访问实际 fnOS 网关页面，确认常用管理、原课程与待发布状态正常。仅在已有官方共享授权范围内创建两个空测试目录，实际完成：私有 / NAS 范围切换、进入两级子目录、空目录提示、独立选择、面包屑返回后保存禁用、取消、已有本地连接回显和 Escape。目录“测试（不保存）”返回读写 / 排他原子发布验证通过、0 提交、0 异常。没有保存新连接或创建 / 绑定数据集，没有发布课程；验收后关键文件再次保持原哈希，空测试目录已移除。

真实截图：[管理](evidence/pr45/fnos-manage.png)、[数据源](evidence/pr45/fnos-sources.png)、[目录选择](evidence/pr45/fnos-folder-selection.png)。

## 未完成的真实验收

未在空白 NAS 上进行完整的首次安装（包括应用中心是否直接跳过安装表单），也未执行真实 fnOS 授权撤销 / 重新授权。测试机已有非空数据，本轮未卸载清空；无配置启动、遗留变量隔离、目录权限失败与重试由上述自动测试证明，不能替代全新 FPK 安装和系统授权变更验收。未执行 Android 全量实机或长时间 soak，未修改日用公网 NAS 和既有远端课程提交。

## 候选

本地文件：`artifacts/pr45/dormpanel-schedule-manager-0.4.4.fpk`（不提交二进制），配套 `.fpk.sha256`。

SHA-256：`87386c2a19d296946d128032006c16ce1061ff2c75d20c74cfebafae2121dcc9`。

仅创建 PR，不自动合并、创建 tag 或发布 Release。
