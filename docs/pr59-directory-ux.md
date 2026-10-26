# PR59：统一目录选择与 WebDAV 连接 UX

基线：`main` 的 `7b5aea7`。保持 X08E 0.1.9、Phone 0.1.8、FPK 0.8.1 版本号；本次不是发布或协议升级。

## 用户操作

- fnOS 添加 WebDAV：填写服务器访问地址与账号，必要时配置经独立核对的证书或明确允许 HTTP；点击“浏览服务器文件夹”，通过面包屑、上一级和文件夹按钮定位，点击“选择此文件夹”，最后测试或保存连接。服务器地址可以包含受限起始路径，浏览不能越过它。编辑连接回显已选位置，已保存密码不返回页面。
- 不支持 PROPFIND、列举方式不兼容、无法列出服务器根目录或需要特殊完整路径时，展开“高级：手动输入完整 URL”。仍由原连接配置和后端校验决定能否保存，不自动建立远端目录或数据集。
- NAS 本地连接、备份、历史导出、独立归档恢复、精简迁移候选共用同一个导航组件。选择授权范围后浏览，再明确确认当前位置；取消不保存。私有连接根目录的“自动创建专用文件夹”仍只发生在原连接保存流程。
- 备份页先选择文件夹，页面直接显示已有备份的文件名和验证状态。选择具体文件并输入口令后，才认证解密、验证完整性和预览影响。最终恢复仍要求输入原确认文本。切换文件夹撤销旧预览及 token。
- 历史页用按钮选择归档目标。恢复缺失缓存时，选择包含原始 `verified.json` 的独立归档目录；目录可浏览不代表清单有效，后端继续校验身份、哈希和恢复保护。
- 精简候选优先显示本次生成的位置及当前授权目录下发现的 `compact-*` 子目录，也可浏览其他授权位置；目录名仅是建议，准备换库时仍验证归档、数据集、连接和事务约束。不递归扫描 NAS。
- 工作区协议、迁移的创建/加入模式和 Descriptor 候选改为点击选择。datasetId、恢复确认文本和管理员审批保留。
- Android X08E 与 Phone 的正式共享课表对话框均提供“浏览共享目录”。显示当前共享位置；完整 URL 收入高级入口。浏览只更新表单草稿，不保存账号、改绑定或发布。已绑定课表仍使用安全换库或 v3 端点更换入口。
- v3 安全端点更换可使用候选账号、地址及独立核对的证书浏览目录；选中路径进入原只读预检表单，后续 Descriptor/历史验证、Pending/UNCERTAIN 阻断和原子确认保持不变。
- X08E 的 WebDAV 导入在同一个弹窗内进入子目录、返回上级、选择 ICS/CSV/XLS，或跟踪当前目录最新文件；点击文件或跟踪操作后，才进入原下载与导入预览流程。

## 实现边界

`POST /api/connections/browse` 仅供现有管理员页面使用，执行原 Origin/CSRF 校验；候选配置复用 `Catalog.candidate`，不写入 Catalog。已保存连接在后端取凭据，候选账号通过 JSON 请求体提交，不放入查询参数或日志。

普通目录解析位于 `folder-browser.mjs`，与 DPC 历史列举分离。传输复用原 WebDAV 的 DNS/SSRF、显式 HTTP 授权、系统 CA/精确证书信任、拒绝重定向和超时策略。一次导航仅一个 Depth 1 PROPFIND；最多 4 个并行浏览、512 KiB 响应、1024 条目、64 层路径。拒绝越界、路径穿越、编码分隔符、重复链接、DTD/实体声明；要求服务器确认当前位置为 collection，仅返回直属 collection，并隐藏 v2/v3 协议目录。

位置结果区分 local 的 `rootId/relative` 与 webdav 的 `root/url`。各调用方继续使用原保存/备份/恢复/历史/迁移 API；选择器没有第二套连接身份或写入路径。备份列表捕获目录身份，异步结果同时核对工作区与请求代次；切换位置、取消和关闭后不会把旧恢复预览用于新位置。

没有修改同步协议、合并规则、Room schema、备份格式、持久 Writer、远端删除或历史回收规则。

## 实际验证（2026-10-26）

| 验证 | 结果与范围 |
| --- | --- |
| fnOS JS 静态检查及 FPK 工程生成 | `npm run check`、`npm run build` 通过；未安装/升级日用 FPK |
| fnOS 浏览器 | Edge/Playwright 29 项通过；实际前后端测试夹具，目录与远端响应按用例注入。覆盖本地/WebDAV、1280×800/420×800、取消、重试、迟到响应、备份身份与预览撤销、历史/迁移选择和归档清单失败 |
| NAS Linux 安全回归 | 在独立 `/tmp/dormpanel-pr59-*` 源码与临时数据中执行目录、API、Catalog、安全、LocalGrants、备份、PR56 维护与 PR57 端点测试：99 通过、2 跳过。跳过的是非 Linux 拒绝用例及环境条件不满足的 PR57 自建 HTTPS 用例；不计作 PASS |
| 最终目录传输/API 回归 | NAS 15/15 通过；包含真实 LAN HTTP 的 PROPFIND、401/403/405/501、重定向、超量响应、恶意 XML、SSRF，以及已保存/未保存候选凭据。Windows 对应组 13 通过、2 个 Linux 本地目录用例跳过，Linux 上已覆盖 |
| Android JVM | `WebDavFoldersTest` 4、`WebDavTlsTest` 2、`SharedScheduleTransportTest` 7，共 13/13 通过 |
| Android 构建 | X08E/Phone 的 Debug、独立 `.testbed` APK 与对应测试 APK 均构建通过 |
| X08E 实机 | 1280×800，独立 `.testbed` 包，`X08eFolderUiTest` 1/1 通过：正式共享对话框、取消/重开、草稿不落盘、同一导入导航器、候选端点目录选择；浏览前后活动端点及远端对象数量不变 |
| Phone 实机 | MIX 2S，独立 `.testbed` 包，`PhoneFolderUiTest` 1/1 通过：从管理页进入正式共享入口，同上导航与候选端点检查 |
| 真实测试 NAS WebDAV | 在既有随机 `pr53-*` 隔离目录核对目录选择；记录的 4 个请求全为 PROPFIND，前后列表一致。另以不匹配的独立证书请求，连接被拒绝。未创建远端文件或改动数据集 |

可复现的主要命令：

```sh
cd fnos-app
npm run check
npm run build
npm run test:frontend
node --test test/folder-browser.test.mjs test/folder-transport.test.mjs test/write-api.test.mjs
# Linux 隔离环境的安全回归（独立临时目录）：
node --test test/folder-browser.test.mjs test/write-api.test.mjs test/security.test.mjs test/catalog.test.mjs test/local.test.mjs test/backup.test.mjs test/pr56-maintenance.test.mjs test/pr57-endpoint.test.mjs
```

```powershell
.\gradlew.bat :schedule-core:testDebugUnitTest --tests '*WebDavFoldersTest' --tests '*WebDavTlsTest' --tests '*SharedScheduleTransportTest' :app:assembleDebug :phone-app:assembleDebug
.\gradlew.bat :app:assembleX08eTest :app:assembleX08eTestAndroidTest :phone-app:assembleX08eTest :phone-app:assembleX08eTestAndroidTest '-Pdormpanel.testBuildType=x08eTest'
# 仅安装 .testbed 后执行对应的 X08eFolderUiTest / PhoneFolderUiTest。
```

截图在 [evidence/pr59](evidence/pr59/)；全部来自隔离状态或合成目录/账号，不含日用数据和真实凭据。

## 验证限制

- 没有升级日用 APK/FPK，没有修改发布 tag、创建 Release 或执行日用数据迁移/恢复。设备测试仅安装 `.testbed`。
- 浏览器自动化覆盖使用真实应用前后端的隔离夹具；没有把测试机已安装的 FPK 替换成此分支。尝试通过 SSH 转发打开 NAS 上隔离 UI 时，测试机禁止 SSH TCP 转发，已停止该临时服务；不将此项算作已安装 FPK 的浏览器验收。
- 导入导航已在实机验证；未从真实日用服务器下载并确认导入课程，也未对日用备份执行恢复。文件解析、来源替换与恢复安全由既有针对性测试覆盖。
- PROPFIND 的兼容性只覆盖夹具与实际测试 NAS，不代表全部 WebDAV 服务；特殊服务继续使用高级手动完整 URL。没有机械重跑 PR55–58 的长时协议验收，也不声称三端 RC 重新验收。
