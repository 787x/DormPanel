# PR59 合并审查修复

审查起点为 `234dba51ad609195d80edaf850103aa9d2adf1b6`；继续更新 `codex/pr59-directory-ux` 和原 PR59。没有更改协议、数据库 schema、备份格式、发布版本或日用绑定。

## 修复行为

### 凭据复用

- fnOS 在 `Catalog.candidate()` 中统一处理空密码：仅规范化后的 Origin、用户名、证书信任身份均相同时，才使用已保存密码。证书按 X.509 指纹比较，系统 CA 与独立证书不同；同一证书的 PEM 空白差异不算身份变化。路径和连接名称不参与认证身份判断。
- 浏览、连接测试、正式保存和 PR57 候选端点准备都经过此后端门禁。身份不同必须重新输入密码，验证失败发生在远端实例创建/请求之前，不靠页面提示阻止泄露。没有将密码发回页面或改变 TLS、SSRF、HTTP 授权与持久化方式。
- Android 的共用 `WebDavCredentials` 对实际用于请求/保存的候选账号做相同检查，供 X08E 普通账号测试/保存、Phone 共享账号保存、双端目录浏览及 v3 候选浏览/预检调用。跨 Origin、用户名或证书信任不能空密码回退。
- 原独立证书设置保留用户明确授权复用的路径：确认内容指明目标服务器、用户名与新信任方式，按钮明确写“复用密码”。活动 v3 通过安全端点更换入口处理，不使用普通证书设置修改其访问身份。

### 首次共享位置

- 不再用账号基础地址填充尚未配置的共享目录。首次创建、加入、测试等需要共享位置的操作，须先浏览选择目录，或在高级 URL 入口点击“确认使用此共享目录”。仅输入 URL 不算确认。
- 选择/确认只修改草稿；触发后续操作时才保存已确认配置。取消、关闭或浏览失败不会持久化服务器根路径。创建 v3 的最终确认明确显示共享父目录。
- 已保存的有效目录正常回显，无需反复选择。已绑定 v2/v3 的普通账号与目录字段不可编辑，普通表单保存不重写活动配置；v3 回显读取其持久端点。PR57 原预检和原子确认不变。

### fnOS 编辑起点

编辑时服务器访问字段回显原连接 URL，浏览根与原访问路径一致，不再扩大到 Origin 根。只有用户主动修改起点、明确选中目录并保存，才改变真实连接地址。目录无权限时保留错误、重试和高级手动 URL 退路；同 Origin 内改路径可按身份规则安全复用密码。

## 实际执行的验证（2026-10-26）

| 验证 | 结果 |
| --- | --- |
| fnOS 浏览器 | 30/30 通过，包括 PR59 原目录/备份/历史测试和新增“原路径回显、失败不扩大范围、取消不保存”用例 |
| fnOS NAS 定向回归 | 48/48 通过、无跳过：`write-api`、`catalog`、`security`、`folder-browser`、`folder-transport`、`pr57-endpoint`、`pr57-lifecycle` |
| 实际出站认证 | 上述 API 用例启动独立 A/B HTTPS 服务器，生成临时证书与合成凭据。浏览/测试/保存对跨 Origin、改用户名、更换/清除证书信任的空密码均拒绝，A/B 收到的 HTTP 请求数为 0；同身份改路径/名称和同证书 PEM 格式变化允许复用；明确新密码后，B 的 Authorization 仅包含新凭据。保存结果也核对了新密码持久化 |
| 原 PR57 HTTPS | 在隔离 NAS 测试环境配置 `PR57_TLS_HOST`，原真实 HTTPS、错证书、改账号/URL及不可访问旧端点用例通过；未访问日用 WebDAV |
| Android JVM | 16/16 通过：`WebDavCredentialsTest` 3、`WebDavFoldersTest` 4、`WebDavTlsTest` 2、`SharedScheduleTransportTest` 7。MockWebServer 核对实际 Authorization、跨身份零请求、同身份路径复用、精确证书和空共享目录拒绝 |
| Android 构建 | 双端 Debug、`.testbed` 与测试 APK 构建通过 |
| X08E 1280×800 | 独立 `.testbed` 上 `X08eFolderUiTest` 1/1 通过：首次无目录阻止创建/加入、取消/重开不保存、手填但未确认仍阻止、明确确认后正常发现、已有位置回显；保留原导航、导入和端点选择检查 |
| Phone MIX 2S | 独立 `.testbed` 上 `PhoneFolderUiTest` 1/1 通过，从正式管理入口执行相同检查 |
| 双端候选端点 | 实机用例还实际修改候选到服务器 B：空密码浏览和原只读预检均显示重新输入提示，A/B 请求计数不变；明确输入新密码后可浏览 B，核对新 Authorization，活动 v3 端点和对象数量不变 |
| fnOS 检查/构建 | `npm run check`、`npm run build` 通过 |

复现主要定向命令：

```sh
node --test fnos-app/browser-test/connections.browser.mjs
# 在隔离 Linux NAS 中执行；PR57_TLS_HOST 指向该测试机自身 LAN 接口，需 openssl。
PR57_TLS_HOST=<测试机自身LAN地址> node --test test/write-api.test.mjs test/catalog.test.mjs test/security.test.mjs test/folder-browser.test.mjs test/folder-transport.test.mjs test/pr57-endpoint.test.mjs test/pr57-lifecycle.test.mjs
```

```powershell
.\gradlew.bat :schedule-core:testDebugUnitTest --tests '*WebDavCredentialsTest' --tests '*WebDavFoldersTest' --tests '*WebDavTlsTest' --tests '*SharedScheduleTransportTest'
.\gradlew.bat :app:assembleDebug :phone-app:assembleDebug
.\gradlew.bat :app:assembleX08eTest :app:assembleX08eTestAndroidTest :phone-app:assembleX08eTest :phone-app:assembleX08eTestAndroidTest '-Pdormpanel.testBuildType=x08eTest'
```

Windows 的真实 A/B API 用例通过 `PR59_OPENSSL` 指定本机 Git 附带的 OpenSSL；该次 Windows API/Catalog 组 20 通过、2 个 Linux 本地目录用例跳过，这两项已在 NAS 上通过。Android 截图见 [evidence/pr59-review](evidence/pr59-review/)，均为隔离账号和状态。

## 验证边界

未升级日用 APK/FPK，未清理账号、重置 Keystore、访问或迁移日用课程。浏览器使用隔离前后端夹具，没有替换已安装 FPK；没有重跑完整 Sync v3 矩阵、1024 增长、三端 RC 或长时 soak。没有自动合并、tag 或 Release。以上定向验证通过，本次审查修复达到 READY TO MERGE；这不代替仓库维护者的合并决定。
