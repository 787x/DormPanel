# PR60：Android 共享课表管理与日常同步状态

基线：PR59 合并后的 `main`（`74c3a01`）。不调整正式版本，不安装日用 APK/FPK，不创建 Release。

## 状态—界面—动作

X08E 与 Phone 共用 `SharedSchedulePresentation` 和对话框。控制器在工作线程读取持久状态；页面按枚举模式、冲突、待发布阶段、保护状态及机器结果码决定操作，不解析中文日志来决定按钮。

| 状态 | 主要界面 | 可执行动作与入口 |
| --- | --- | --- |
| 未配置 | 尚未开启共享、本机课表已保存 | 连接设置；浏览/手工确认仅更新草稿，独立确认保存连接 |
| 已配置未绑定 | 尚未加入或创建 | 加入已有共享课表、创建新的共享课表；默认新建 v3 |
| 已有 v1 绑定 | 旧版兼容升级说明 | 管理 → 旧版兼容与升级 → v1 到 v2；保留停止旧客户端的确认与原预检 |
| 正常 v2 | 共享位置、本机保存、最近检查结果 | 立即同步；升级、加入已升级目标、换库、只读检查与写入诊断分别在对应管理分组 |
| 正常 v3 | 共享位置、本机保存、最近检查结果 | 立即同步、连接设置、管理与维护；不显示迁移、重开存储或恒定历史免责声明 |
| 待发布 / 待回读 | 本机已保存，分别提示待发布、待服务器回读 | 使用当前绑定的同步路径；已回读但尚未落本机回执时单独说明；`RECEIPTED` 不再被误报为待回读 |
| 网络失败 / 检查较久 | 检查未成功或超过 24 小时未成功检查 | 检查连接后重试；不把本机课表称为失效，也不把未开启共享称为离线 |
| 真正冲突 | 查看并处理修改冲突 | 完整差异、取消，以及“全部保留本机修改”或“全部采用远端方案”；明确覆盖全部冲突和必要关联记录 |
| 创建、加入、迁移或换库未完成 | 未完成操作及保留数据说明 | 继续同一任务；换库进入原验收日志；仅有迁移记录时可检查取消未创建目标的升级 |
| 本地事务恢复保护 | 已停止写入、需要恢复 | 恢复同一数据库的未完成操作及技术诊断；不新建课表、不生成替代 Writer |
| 无法安全确定 | 明确说明不能安全判断 | 技术诊断；暂不提供创建、切换或升级操作 |

管理按“连接与位置”“旧版兼容与升级”“更换课表与恢复”“技术诊断”分组，各组只列当前模式支持的少量操作。v3 的同步不会调用 v2 检查，控制器也拒绝对活动 v3 执行旧版检查。

普通加入先发现并验证 v3 候选；没有新版候选时，只读核验已有 v2 课表再预览确认。发现失败不会猜测协议或创建数据集。多个新版候选由用户明确选择。旧版兼容新建仍需在二级入口独立确认。

## 草稿与安全边界

- 取消浏览、关闭窗口、取消创建/加入预览、检查失败均不隐式保存账号和共享目录。只有“保存连接配置 → 确认保存连接”会独立保存配置；后续业务操作先核对当前草稿与已保存配置一致。
- X08E 继续使用控制中心的全局 WebDAV 导入账号，保留 ICS/CSV/XLS 绑定和跨服务器变更确认。当前 v3 独立账号仍由 PR57 的端点事务选定，目录浏览不能替代端点切换。
- 同一访问身份的留空密码复用、独立证书指纹核对、绑定前回读、迁移捕获、换库日志、冲突 token 和恢复保护继续使用原流程。写入诊断另有确认，说明会保留独立测试文件。
- 打开未绑定设置及后台空任务不再通过 `v2Frame()` 分配安装 Writer。未绑定 v3 只读预检复用已有 Writer；尚无 Writer 时只使用私有预检阶段身份，正式确认加入才建立活动 Writer。既有 Writer 不替换。
- 只读预检仍可按原后端规则保存 checkpoint 207 和私有验证缓存，便于有界预检继续；取消不删除这些证据，不改课程、活动绑定或待发布记录。正式加入仍重新核验本地与远端。
- 服务器回读、本机保存、其他设备接收保持不同含义。底层技术结果保留在诊断；后台 Job 同样记录机器结果码，前台不再将后台失败展示为成功。

fnOS 本次只调整首页日常文案，保留实际冲突、部分验证、恢复和写保护；管理/历史页面的完整重构留给 PR61。Phone 首页使用可换行的状态文本，状态更新只修改文本，不再为同一状态反复重建整个课表视图。

没有修改协议字节、远端目录格式、合并决策、Room schema、备份格式或历史回收策略。

## 验证记录（2026-10-26）

验证全部使用随机 Room/偏好命名空间、合成凭据、MockWebServer 或临时 fnOS 前后端夹具。没有使用日用 WebDAV 目录做写入实验。

| 验证 | 实际结果 |
| --- | --- |
| Android JVM 定向检查 | 37 项通过：状态/操作矩阵 4、账号身份与草稿比较 4、目录 4、证书 2、传输 7、本地课表及恢复后清除错误标志 14、迁移 UI 写保护 2 |
| Android 构建 | X08E 与 Phone 的 Debug、独立 `.testbed` 与对应测试 APK 构建通过 |
| fnOS 文案单测 | 3 项通过：正常、待发布/待回读、冲突、网络、恢复、部分检查及真实写保护 |
| fnOS 浏览器 | Edge/Playwright 31 项通过，包含新状态注入与既有 PR57/59 端点、目录和凭据流程；真实临时前后端夹具 |
| fnOS 静态检查和打包 | `npm run check`、`npm run build` 通过；新文案模块包含静态资源路由 |
| X08E 1280×800 | 4 项通过：正式共享入口、目录/账号草稿取消与重开、默认 v3 创建、正常操作矩阵与实际 v3 请求、端点候选凭据边界、旧版创建/绑定、真实编辑后发布 |
| Phone MIX 2S | 最终组合 6 项全部通过：目录与草稿、3 项只读检查（含普通加入自动核验旧版）、v3 创建/编辑/取消加入后重开及加入、冲突整组两种决策。状态变化仅更新文本后，组合用例不再丢失管理入口点击 |
| 本地提交确认丢失注入 | X08E 独立 core 测试 1 项通过：进入恢复保护并显示恢复动作、重开同一库、保留创建候选、完成回执后不误报待回读 |

截图位于 [evidence/pr60](evidence/pr60/)，均为隔离合成数据。真实界面断言核对配置、checkpoint、绑定、远端对象数量和请求协议路径；预检的持久验证证据另计，不把代码推断算作设备实测。

复现主要命令：

```powershell
.\gradlew.bat :schedule-core:testDebugUnitTest --tests '*SharedSchedulePresentationTest' --tests '*WebDavCredentialsTest' --tests '*WebDavFoldersTest' --tests '*WebDavTlsTest' --tests '*SharedScheduleTransportTest' --tests '*ScheduleTest' --tests '*ScheduleMigrationUiGuardTest'
.\gradlew.bat :app:assembleDebug :phone-app:assembleDebug :app:assembleX08eTest :app:assembleX08eTestAndroidTest :phone-app:assembleX08eTest :phone-app:assembleX08eTestAndroidTest '-Pdormpanel.testBuildType=x08eTest'
# 只向 .testbed 安装 APK，分别执行 X08eFolderUiTest / SharedSchedulePublisherAndroidTest / X08eV3UiTest
# PhoneFolderUiTest / PhoneSharedScheduleInspectionTest / PhoneV3UiTest / PhoneBidirectionalUiTest
# core 独立测试包仅执行 ActiveScheduleV3AndroidTest#lostLocalCommitReceiptReopensSameStoreAndSameCreationCandidate
```

```sh
cd fnos-app
node --test test/sync-status.test.mjs
npm run test:frontend
npm run check
npm run build
```

## 未验证范围与风险

- 未升级测试 NAS 的已安装 FPK，也未升级日用 APK；fnOS 浏览器结论来自隔离夹具，不代表已安装服务验收。
- 未用真实旧课表进行协议迁移、端点切换或恢复。迁移可见性有矩阵/保护测试，恢复有故障注入；不宣称所有旧模式与故障组合均完成实机验收。
- 系统后台 Job 的结果码接线已构建核对；测试命名空间禁止调度真实系统任务，未把前台/控制器测试算作 JobScheduler 端到端实测。
- 未重跑 PR58 三端 RC、增长测试、长时间 soak 或全服务器兼容性测试。没有自动合并、发布或改变正式版本号。
