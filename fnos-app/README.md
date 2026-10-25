# DormPanel Schedule Manager 0.7.4（PR55 测试候选）

0.7.4 接入显式 v3 工作区与 v2→v3 迁移，复用同一 Catalog / Workspace / Writer 和管理员边界。当前 v3 应用状态不能由 DPBK0001 完整备份，相关备份/恢复明确拒绝；v2 专属回退、归档和精简不适用于 v3。验收进度见 [PR55 记录](../docs/pr55-sync-v3-verification.md)。

0.7.1 增加随包 CLI 与官方 MCP stdio Server，共 69 项业务操作、只读资源和工作流 Prompt；沿用运行中进程的单 Writer、版本检查与保护规则。默认只读，管理员在原网页授权并独立批准高影响操作。实际安装路径、SSH 配置、权限、幂等语义和限制见 [CLI / MCP 使用说明](../docs/fpk-0.7.1-agent-guide.md)，候选、代码测试及已安装验收见 [PR49 验证记录](../docs/pr49-fpk-agent-verification.md)。

“备份与恢复”支持在 fnOS 官方授权目录保存带 scrypt / AES-256-GCM 认证加密的完整应用归档，回读验证后公开文件。同一 writerId + installationId 的恢复必须先验证和明确确认，保留旧状态、通过持久化恢复日志完成切换，外部身份/已知历史验证前不允许发布。WebDAV 和用户授权目录的远端历史不默认复制，不能将应用备份视作完整远端数据集备份。

恢复预览十五分钟到期自动撤销并清理，取消确认也会撤销预览；失败保持私有权限并延后重试。清理与恢复互斥，保留日志输入和恢复前快照。启动父 shell 在删除任何运行文件前取得 flock，未获锁的 start 不得清理另一个实例的 PID/Socket/ready。当前候选与增量验证见 [最终加固记录](../docs/pr42-final-hardening.md)。

启动只完整读取活动工作区。其余列表状态来自以原文件标识/时间/大小核对的可丢弃摘要；失效时显示待检查，切换时读取真实状态。0.3.2 原工作区文件和 writerId 保持，identity 仅新增一次 installationId。详细说明、资源预算、测试、实测数据和候选哈希见 [PR42](../docs/pr42-fpk-backup-recovery.md)。

fnOS Native 可编辑课表与 Sync v2 Writer。本次不修改 Android、DPC2 / DPSS/1 协议、Room 数据库或正式 APK。

## 使用

0.4.4 安装时不再填写 WebDAV。打开后可以直接本地编辑；需要共享时，再到“数据源管理”添加 NAS 本地目录或 WebDAV。添加连接只保存存储位置，不会创建课程数据集：随后新建课程表（工作区），切换到它，再从“更多操作”接收已有课表或在空目录创建共享课表。切换不会迁移或合并原课程，移除配置不会删除远端文件。

“管理”优先提供课程、ICS / CSV 导入、学期、停课 / 补课及日期调整；共享数据集和冲突入口保留在可展开分组中。数据源页分别展示“我的课程表”和“存储位置”，内部身份与完整状态在“高级诊断”中查看。本地目录使用文件夹列表、面包屑和上一级浏览，点击“选择此文件夹”后才能保存；浏览、关闭或 Escape 取消不会保存连接。私有根目录提供自动创建专用文件夹选项，编辑连接时回显原目录。

升级不消费旧安装向导变量，不重写旧凭据或工作区；系统目录授权入口及配置回调保留。详细验证与尚未验收项目见 [PR45 记录](../docs/pr45-fpk-ux.md)。

管理员通过 fnOS 桌面进入。课程卡片支持全部周次/指定周编辑与重置；null 继承、空文本清空，身份保持 sourceId、seriesId、originalStart、anchorDate。管理页支持课程/事件增删、日期停课/补课、来源替换/删除、学期/阶段/节次，以及 ICS / 湖北大学 CSV 后端预览与确认导入。CSV 需要对应学期配置；替换保留旧来源身份与覆盖，不自动下载 locator。

本地保存独立于网络。按需“检查并同步”最多三轮，无自动轮询。状态区分本地保存、待发布、候选待回读、本机已验证发布、观察时间、历史异常与冲突。已发布不代表其他设备已接收。冲突页展示关联单位、全部分支及稳定远端方案；决策只处理冲突关联单位，独立修改继续保留；暂不处理不改课程。

## 管理员配置

首页工作区选择器切换独立课程状态，“数据源管理”新增、命名、测试、编辑和移除连接/工作区。连接与工作区分别有稳定 UUID。每个连接可关联一个工作区；同一 dataset 的另一访问地址应使用“选择访问端点”并完整验证历史，不能创建第二个工作区后悄悄重复绑定。

WebDAV 支持 HTTPS 共享父目录、独立凭据及可选证书信任。测试只检查连接与历史，不改变任何工作区；写权限在实际创建/发布时验证。确认创建仅适用于空协议目录，确认绑定会校验完整 DAG。修改已绑定连接的地址、凭据或证书均重新验证身份、已知字节和历史闭包；不能通过改地址切换到其他数据集。

本地连接从服务端提供的目录列表选择：FPK 私有本地目录可直接自动建立专用文件夹；官方 `data-share` 资源目录通过 `TRIM_DATA_SHARE_PATHS` 获取；其他 NAS 文件夹先在 fnOS 应用设置中授权，通过 `TRIM_DATA_ACCESSIBLE_PATHS` 获取。授权变更后重启应用以更新系统注入的目录列表；每次访问仍按包用户权限重新打开并核对目录 inode。应用没有任意绝对路径文件访问接口。

本地后端使用同一 Sync v2 核心，只接受直属合法 DPC2 文件；Linux 逐级 `O_NOFOLLOW` 和目录 fd 防止链接越界，提交读取有字节/条目/时间预算并检查文件变化。父目录中 0700 暂存目录内完整写入、fsync，随后同文件系统硬链接排他创建只读提交并 fsync 协议目录和父目录；已有路径绝不覆盖。只对允许的 Linux ext、Btrfs、XFS、tmpfs、overlay 文件系统开放写入，未知文件系统和不支持 link/fsync 的目标安全失败。崩溃残留留在协议目录之外，不自动扫描清理旧暂存或历史。将同一目录经 WebDAV 暴露并授予读取权限后，可由既有 v2 客户端读取。

LAN 自签名 HTTPS 可显式提供从 SSH 等独立可信渠道取得的服务器公钥证书 PEM。仅该连接添加信任并精确匹配服务器证书，TLS 链和有效期校验仍开启；没有 `rejectUnauthorized=false`，不改系统 CA。不要上传私钥；证书变更须重新核验。页面可恢复系统 CA。

新连接凭据只在私有 `var/catalog.json`（0600，父目录 0700）；密码留空保留。PR38 原始 `etc/webdav.json` 为升级兼容保留，迁移成功后不再作为日常配置源。不向前端响应、课程快照或日志返回认证信息。

## 持久化与同步

PR38 原位升级先逐字段完整复制 `workspace.json` 到 `var/workspaces/<稳定迁移ID>/workspace.json`，验证精确候选仍属于原安装 writer，再原子提交 `catalog.json`。首次来自 PR37 时仍重新验证图缓存与检查点。原始 workspace、identity、WebDAV 配置和所有旧归档均保留，不先删除再写入。固定迁移 ID 和最后提交的索引保证中断后可幂等重试；注册工作区文件缺失时拒绝重建空状态。新目录的父项也执行 fsync。已完成迁移后回滚到旧版只能看到保留的迁移前状态；新编辑以新目录为准，不能把旧副本当作最新备份。

每个工作区的原子文档包含本地课程、baseline、frontier、DAG、checkpoint、精确 pending/capture/attempt、abandoned、receipt、异常和冲突。切换仅改变 catalog.activeId。移除非当前工作区需要名称确认，有本地修改、候选、冲突或恢复异常时拒绝；移除配置仍保留状态文件，不删除远端文件。连接仍被引用时不可移除。

每条课程写请求携带明确 workspaceId 和 revision，管理操作额外携带 catalogVersion，跨目标操作校验目标版本。读取数据端点同样绑定 workspaceId（初次 schedule 引导例外）。导入预览绑定用户、工作区和版本；冲突 token 绑定工作区及完整输入。网络操作按工作区互斥，首次绑定身份检查互斥；上传期间允许本地 capture/replay 编辑及切换到其他工作区。没有任何后台轮询，也不自动同步全部工作区。连接修改期间阻止并发写操作，已验证配置变更使用恢复日志完成落盘；端点切换发生中断时绑定不匹配会停止同步，重新验证端点可恢复。不能用多个服务进程同时写同一管理目录。

随机 UUID 普通 PUT 不覆盖历史，不使用共享 head/条件 PUT。预检 GET 后、实际写前持久化 attempt，完整解码且逐字节回读才能确认。未知结果不对同路径重复 PUT；换身份仍保留旧路径预期哈希。回执按 capture 重放后续编辑。保留协议图、字节、时间、请求和冲突预算，异常持续回查，不做远端 GC。

合并与 Android ScheduleMerge / ScheduleV2Plan 对齐：来源及原始实例不可分割，导入时间不判胜负，处理删除/覆盖、学期/来源、日期组合/指定周编辑关联。多分支逐对检测再稳定折叠；多重不等价基线停止，等价 tips 不产生无限纯合并。

## 权限边界

包用户运行，仅 Unix Socket；target 0700、Socket 0600，撤销 shared-user/group ACL，失败即停止，不放宽权限。真实网关为 root trim_http_cgi。root 和包用户已有完整代码/数据权限，属于残余信任边界，不能宣称隔离其 Header 伪造。[PR37 审计](../docs/pr37-gateway-security-audit.md)说明该边界。

每个写路由检查管理员、私有传输、用户及完整 Origin 绑定的 CSRF、请求版本、JSON 和 2 MiB 上限。fnOS Host 可能丢端口，页面完整 Origin token 保留精确端口校验。冲突确认绑定完整输入 token，决策前重查远端。拒绝重定向越界、路径穿越、XML 实体、回环/链路本地/元数据目标；LAN 仅为管理员明确配置的 NAS 地址。浏览器不直连 WebDAV；导入文本用 textContent，CSP 禁止内联/外部脚本。

## 本地连接按钮回归测试

`npm ci` 后执行 `npm run test:frontend`，使用真实页面脚本、独立临时服务和 Playwright 验证弹窗交互。Windows 默认使用已安装的 Edge；其他平台先执行 `npx playwright install chromium`，也可通过 `BROWSER_CHANNEL` 指定浏览器。视口固定为 1280×800。测试代理仅在隔离实例注入管理员身份，不连接真实课表；目录列表在交互测试中使用桩数据，Linux 本地目录的真实读写、授权边界和发布由 Node API 测试覆盖。Playwright 仅为开发依赖，FPK 使用 `npm ci --omit=dev`，不包含浏览器或自动化运行时。

实际公网故障、来源校验兼容方案和验证范围见 [本地连接修复记录](../docs/fpk-local-connection-fix.md)。

0.4.3 增加 CSV 预览进度、真实错误和重试反馈，预览成功后才允许保存；已用 NAS 导入用户指定 CSV 并让 X08E 接收，详见 [候选及实机同步记录](../docs/fpk-0.4.3-candidate-notes.md)。

## 构建

需要 Node 22+、npm、官方 fnpack：

```sh
cd fnos-app
npm ci --ignore-scripts
npm test
npm run check
npm run build
fnpack build --directory build/dormpanel-schedule-manager
```

使用应用中心正常手动升级。目标 fnOS 的 install-fpk CLI 对已有实例只报告已安装；install-local 会卸载并清数据，禁止当升级工具。保留 PR37 向导字段；没有向导输入的正常升级保留原配置和独立证书信任。

`node scripts/merge-vectors.mjs` 生成 18 组独立输入/预期结果和两组导入向量，由 JVM FnosWriterParityTest 校验。RealFnosSyncTest 仅显式启用的 pr38-e2e-UUID 隔离目录与 Room 命名空间，不操作正式课表。实测、诊断网络路径及未验证项见 [PR38 验证记录](../docs/pr38-fnos-verification.md)。

PR39 实机与升级记录见 [验证文档](../docs/pr39-fnos-verification.md)。独立真实后端验收可显式配置 `PR39_TEST_DIR` 运行 `scripts/verify-workspaces.mjs`：目录名及 config.json 中 URL 必须为同一 `pr39-e2e-UUID`，只建立合成数据集并保留所有结果。

FPK 0.3.2 首次独立发布的真实三端、权限与数据保留验收见 [PR40](../docs/pr40-fnos-release-acceptance.md)，支持环境、产物哈希及限制见 [发布说明](../docs/fpk-0.3.2-release-notes.md)。该记录不代表已经创建 Release。

同一已获官方授权的合成目录可继续运行 `scripts/verify-local-webdav.mjs`，验证本地发布的提交由 WebDAV 完整回读，以及同数据集端点迁移与重复绑定保护。环境必须带 fnOS 官方注入的 `TRIM_DATA_ACCESSIBLE_PATHS`，不在脚本内授予目录权限。

0.3.1 的提示、确认、名称输入均为页面自带对话框，不调用浏览器 alert/confirm/prompt。支持取消/Escape，保留导入预览及冲突输入；等待确认后会再次检查表单和工作区版本。

## 0.5.0 历史与版本

新增因果历史列表、完整快照分页、基于稳定身份的比较和新提交式回退，以及受保护的完整远端历史归档与独立单根精简候选。不自动切换任何已绑定客户端，不删除旧 DPC。完整说明及管理员后续换库步骤见 [PR46](../docs/pr46-fpk-history.md)，实际测试和未验证范围见 [验收记录](../docs/pr46-fpk-history-verification.md)。

## 0.6.0 安全换库与退役管理

「历史与版本」新增可重启续做的精简数据集换库向导：验证 PR46 归档 / 候选、实际目标 WebDAV、独立新工作区、后端停写保护、人工设备清单、退役检查及清理说明。Android 0.1.4 的已绑定设备可保留本地课程、Writer 与恢复资料换库。旧工作区和原始提交保留；没有自动删除或全设备在线证明。操作及限制见 [PR47](../docs/pr47-dataset-migration.md)，实际执行及未验证范围见 [验收记录](../docs/pr47-dataset-migration-verification.md)。

0.6.1 完成正式应用原位升级与网关 UI 定向验收，修复 FPK 切换后的活动工作区衔接；Android 0.1.4 同时修复迁移保护期间的乐观 UI 状态与拒绝提示。最终候选与证据见 [合并前验收](../docs/pr47-final-acceptance.md)。
