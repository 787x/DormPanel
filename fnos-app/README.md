# DormPanel Schedule Manager 0.1.2（FPK 独立版本）

独立的 fnOS Native FPK 只读课程表管理应用。Android 0.1.2、Sync v2 协议、数据库、签名和 APK 发布资产均不变。

## 构建与安装

需要开发机 Node.js 22+、npm 和官方 [fnpack 1.2.3](https://developer.fnnas.com/docs/cli/fnpack)。

```sh
cd fnos-app
npm ci --ignore-scripts
npm test
npm run check
npm run build
fnpack build --directory build/dormpanel-schedule-manager
```

输出为当前目录的 `dormpanel-schedule-manager.fpk`。安装使用 fnOS 应用中心手动安装，或管理员运行：

```sh
appcenter-cli install-fpk dormpanel-schedule-manager.fpk --volume 1 --env config.env
```

`config.env` 是只在安装时使用的私密文件，权限应为 0600，完成后删除。字段如下，示例不含可用凭据：

```ini
wizard_webdav_url=https://nas.example/shared/test-schedule/
wizard_webdav_username=example
wizard_webdav_password=example
```

URL 必须是明确的 HTTPS 共享目录，以 `/` 结束；应用只访问它下面的 `dormpanel-sync-v2/`。留空可先安装，显示未绑定。安装向导和 `wizard/config` 声明相同字段；目标系统若尚未显示配置向导，可在安装时通过 `--env` 传入。已有实例升级使用网页手动安装；本机CLI对已安装实例不执行升级。不能用 `install-local` 更新已有实例：该开发工具会卸载重装并删除本地数据。

包内仅有 JavaScript 和网页，无原生二进制，因此 `platform=all`；运行时通过 `install_dep_apps=nodejs_v22` 使用 fnOS 对应架构的官方包，不使用 NAS 开发环境。实测架构为 x86_64，ARM 运行尚未实测。网关要求 `os_min_version=1.1.3100`，本次实机为 1.2.0800。

## 运行和权限

- 桌面管理员入口 `/app/dormpanel-schedule-manager/`；只监听 `${TRIM_APPDEST}/app.sock`，没有 TCP 监听、本地鉴权后门或开发身份回退。
- [官方统一网关](https://developer.fnnas.com/docs/core-concepts/gateway-registration) 校验 fnOS 登录态并提供 `X-Trim-Userid`、`X-Trim-Isadmin`。每次请求检查这些管理员身份声明、重复身份 Header 和传输类型；`remoteAddress == null`、Header 和 CSRF 本身都不能认证 Socket 对端。
- **安全审计修正**：实测连接者是 root 的 `trim_http_cgi`，不是 www-data nginx worker。应用用户所有的 target 为0700、Socket 为0600，撤销 shared-user/group ACL，不再申请 www-data 附加组；不依赖该组授权。原0660方案曾允许独立 www-data 进程伪造管理员，已经收紧。root 和应用用户本身仍有完整控制权，不能声称已隔离它们。其他fnOS版本若无法连接，应重新审计，不能放宽权限。
- 详细证据、五种威胁的独立验证与 PR38 写入门槛见 [网关安全审计](../docs/pr37-gateway-security-audit.md)。PR37可在上述系统信任边界下作为只读基础合并；PR38不能仅凭现有 authorize()/CSRF 直接开放写入。
- API 只提供 GET 视图和带 CSRF token 的 POST 手动刷新。其他写方法拒绝；没有课程保存、PUT、DELETE、MKCOL 或发布实现。
- `webdav.json` 在 `${TRIM_PKGETC}`，0600；身份、检查点、缓存和错误在 `${TRIM_PKGVAR}`，目录0700、文件0600。后端响应和日志不返回凭据，也不返回来源 locator。
- 凭据更新只允许原绑定地址。重启保留 writerId；真正卸载由 fnOS 删除其管理的数据目录，重新安装生成新的独立身份。不要把身份目录复制到另一个活动编辑端。
- 生命周期只控制本应用 PID，并核对进程完整脚本参数，避免误杀复用 PID。升级前停止旧进程，升级后保留持久化目录；不改 WebDAV、FN Connect 或其他应用服务。

## 协议与显示边界

`src/protocol.mjs` 实现 DPC2 / DPSS/1 的完整字段、双层 SHA、严格 UTF-8、UTF-16 排序、int64 BigInt、规范重编码、来源摘要、领域范围和引用关系验证，使用与 Kotlin 相同固定向量。Python 向量工具仅用于独立格式交叉检查。

`src/graph.mjs` 读取完整祖先闭包、拒绝环/多根/不同数据集/身份异常/不支持语义，计算 tips 和最大共同祖先。已验证哈希以及已观察的不可验证路径本地持久化，后续目录未列出时仍回查。单轮最多256提交、32MiB输入、64tips和64暂不可验证路径，遵循请求、字节、超时和分支工作预算。未知完整封装不能作为暂时坏文件忽略。

本版对于不同内容的多个 tips 采取保守阻断，显示分支数，不执行三方合并，也不选择任何 Writer。相同内容的多个 tips 可以展示同一内容。PR38 需要实现关联冲突单位、完整分支差异和用户决策。

`src/projection.mjs` 按 Android 的 **基础课程 → 全周编辑 → 目标日期调课 → 指定周编辑** 投影；保留 originalStart/anchorDate、稀疏继承、空字符串清空、无效覆盖回退、目标日停课和学期阶段联动。使用 Temporal 的兼容时区消歧规则对应 Java `atZone`，并检查相邻学术周的跨日实例。来源 catalog 直接来自远端，不以本机内置配置替换。

页面提供周/日期/教学周导航、显示时区、课程时间/教室/教师/备注、学期阶段、来源、数据集与历史状态。没有永久轮询；空闲只等待 HTTP。刷新失败保留上次验证缓存并标示为旧缓存，不能视为当前同步成功。页面检查时间是本地观察时间，不使用协议固定 createdAt 作为发布时间。

## PR38 边界

完整编辑、关联冲突决策、第三 Writer 本地事务、待发布意图/回执、不可变远端发布与回读、多端并发收敛、配置换绑流程均未实现。本版没有任何声称已保存的编辑操作。写入前必须完成审计文档中的真实用户授权、同源/CSRF/请求绑定和私有入口权限回归，不能直接解除只读门禁。

实测范围见 [验证记录](../docs/pr37-fnos-verification.md)。
