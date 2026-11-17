# PR89 fnOS 共享 Todo 与备忘验收

基线 `ee22f3b15908c9683921b6610444a9d30144ae98`。测试日期为 2026-11-18（北京时间）。测试记录为合成数据，远端目录与应用空间单独建立。

## 界面与业务检查

- [Todo 管理](fnos-todo.png)、[备忘管理](fnos-memo.png)和[备忘冲突](fnos-conflict.png)来自 1280×800 Chromium 的独立管理实例。网页测试另以 360 像素宽度实际完成保存与删除操作。
- HTTP 定向检查覆盖完整 CRUD、授权与 CSRF、精确删除成员、UTF-8 边界、原始换行、空正文、分页、旧格式 / 未初始化 / 恢复保护、过期版本和事务故障。确定拒绝为 `writeApplied:false`，提交结果待核对为 `null`。
- CLI/MCP 定向检查使用实际 CLI 进程和 MCP stdio 客户端。验证默认只读、编辑权限、独立管理员批准、参数绑定、回执重试、事务不确定和正文隔离。
- 网页定向检查覆盖多清单和多备忘、草稿取消、过期草稿、迟到读取、取消已发送的操作、回执丢失后只读核对、重开网页以及冲突决定。冲突决定覆盖整次冲突的各组，决定提交后仍需检查并同步。

## 可复跑的检查

```sh
cd fnos-app
node --test test/productivity-api.test.mjs test/productivity-conflicts.test.mjs test/productivity.test.mjs test/active-v3.test.mjs test/security.test.mjs test/agent.test.mjs test/course-management-agent.test.mjs
node --test browser-test/productivity.browser.mjs browser-test/courses.browser.mjs
npm run build
fnpack build --directory build/dormpanel-schedule-manager
npm run verify:package
```

将 `PR89_CONFLICT_DAV_URL` 指向新的隔离 WebDAV 目录，可用真实 WebDAV 运行双 Writer 冲突测试。该检查覆盖不同任务独立合并、同一任务不同修改、备忘正文分歧、编辑与删除、清单删除与远端新增子任务，以及过期 token / revision 拒绝。WebDAV 实测数据集为 `2b36a9b0-6df4-4501-b74c-7d77b4a0e846`，两名 Writer 收敛哈希为 `18dc0a42cf494006fe54fa9dd98d0e296e7d52a428ac67cd9512303c8dcfc829`。

## 已安装 FPK 与 Android WebDAV 往返

在 fnOS VM 的 App Center 安装独立应用 `dormpanel-pr89b`，验收包 manifest 版本为 0.8.3，使用 NAS 自带 Node 22.23.5。使用 fnOS 网关核对实际 Todo、备忘和发布状态，从 FPK 自带的 CLI 读取当前清单。测试机上的 WsgiDAV 使用全新目录。X08E 使用 API 28、1280×800、160 dpi 模拟器，Phone 使用 API 37 模拟器。两端为独立 `.pr89testbed` 应用。

1. 用 X08E 卡片导入和发布 12 项合成任务及一条包含 CRLF 的长备忘，验证原卡片切回本机、共享绑定及 Timer 保留。fnOS 显式选择并加入该 DPV3/4。
2. 通过已安装 FPK 的管理员 HTTP 事务新增「fnOS 新增的共享任务」并修改同一备忘。保存后验证 `dirty=true`，同步后取得远端精确回读。fnOS 网关显示对应任务、备忘和回执提示。
3. Phone 原生 AppWidgetHost 加载该 Todo 和备忘，核对备忘原文，点击真实 Widget 的任务勾选，确认剩余计数从 **12 → 11**。随后发布并由 fnOS 与 X08E 接收。
4. 在上述收敛检查点之后临时停止隔离 WebDAV 服务。已安装的 FPK 仍保存含换行与 Unicode 的新备忘，报告 `dirty=true` 与同步异常。原版本重放被拒绝。恢复同一 WebDAV 服务后取得精确回读，使用新版本重试相同创建 ID 仍被拒绝，同 ID 记录仅一条。

| 检查点 | 实测值 |
| --- | --- |
| datasetId | `47eafab7-fd42-4156-ac05-28fc8126dea1` |
| 任务 ID / 状态 | `7d7e8d82-b809-44ea-85bc-c6895c8842d1`，三端均完成 |
| 备忘 ID / 正文 | `2b893eb6-2e1b-3f3c-ba28-71d3f7cad4c8`，保留 `"  fnOS 编辑\r\n第二行🙂\n  "` |
| fnOS 发布对象回执 SHA-256 | `b6bc108d9c7bab3d0eea5d6200ae4a6eab8264c562bae60cbc5e0ff0963ac4f2` |
| fnOS / Phone / X08E 完整业务 SHA-256 | `b02da2c013ea5b8d1b78456f4d136501d449d60552fb57afbfcc1e7d1047c02c` |
| 断网恢复备忘 ID / 回执 SHA-256 | `3a927512-7818-43a3-896e-588118c9f253` / `5ca6d0001511ff5158605166d03e2ee26a1ae6b8f5b2700e7e211c9017a9f26d` |

[Phone 原生 Widget 截图](phone-widget.png)。PR88 报告的计数滞后在本轮对照中复现。修复后，API 31 及以上的小型 Todo 清单在一次 RemoteViews 更新中提交标题、剩余数和任务行。较大的清单与旧 API 保留按需读取。此定向复验覆盖 API 37 原生 AppWidgetHost。[Android 集合 Widget 文档](https://developer.android.com/develop/ui/views/appwidgets/collections)

复跑时用 `-Pdormpanel.testBuildType=x08eTest -Pdormpanel.testSuffix=.pr89testbed` 构建 `:app:assembleX08eTest`、`:app:assembleX08eTestAndroidTest`、`:phone-app:assembleX08eTest` 和 `:phone-app:assembleX08eTestAndroidTest`。安装到独立的模拟器应用后执行 `node --test test-fixtures/productivity-android.mjs`。设置 `PR89_X08E_SERIAL`、`PR89_PHONE_SERIAL` 及指向全新 `/pr89-<UUID>/` 目录的 `PR89_DAV_URL`。Android 经 adb reverse 使用测试代理，WebDAV 测试账号为 `fixture`。验证已安装包时，另设指向隔离 FPK 管理代理的 `PR89_FPK_HTTP` 和 fnOS 访问同一目录的 `PR89_FPK_DAV_HOST`。本次从同一隔离数据集续跑 Widget 修复验证，使用 `PR89_RESUME=1`，并核对原始任务、备忘和最终哈希。

## 构建、回归与边界

- 官方打包工具生成 0.8.2 FPK，`verify-package.mjs` 对比 58 个源文件、运行依赖、Socket 与授权入口。包 SHA-256 为 `81eea1d52d2d1f5e850d808145a683ba778df3b2b18aaa1ee2271a0c43643c3b`。0.8.3 仅用于独立安装验收。
- 网页套件通过课表/颜色 19 项和效率 4 项。已安装 FPK 的历史、连接管理、备份维护和课程读取端点返回 HTTP 200。Phone JVM 89 项通过。X08E 定向 JVM 与卡片实测覆盖本地 Todo、备忘及 Timer。
- Windows 全量 `npm test` 曾报告三项失败。两个 worker 脚本被直接发现为测试，缺少子进程参数。v3 1024 次发布用例在断言额外 GET 次数时失败。三项均在独立 `main` 检出复现。
- 实体 X08E、实体 Phone、独立 OEM Launcher 以及触发降级的大型 Widget 清单未实测。
