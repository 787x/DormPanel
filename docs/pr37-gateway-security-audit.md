# PR37 网关 Socket 信任边界审计

日期：2026-10-21（Asia/Shanghai）。真实环境：fnOS 1.2.0800、x86_64、官方 Node.js v22.18.0。最终安全修订 FPK 版本为 **0.1.2**；版本号独立于 Android，Android 0.1.2 未修改。本记录覆盖并修正早期 PR37 对网关身份和共享组的假设。

## 结论

**原 0660 + www-data 组方案存在可复现的本机管理员身份伪造。最终采用 target 0700、Socket 0600，撤销 named-user/group 授权，关闭已验证的非特权本机入口。** 不以 Header、`remoteAddress`、CSRF 或某个进程名字证明连接来自网关。

真实内核对端是 **root 的 `/usr/trim/bin/trim_http_cgi`**，并非 www-data nginx worker。fnOS 网关以此系统代理连接应用私有 Socket；应用本身始终以包用户运行，无需 Root。root 和应用用户仍属于完整信任边界：二者能直连并伪造 Header，且本来就能读取配置、课程缓存或更改应用代码，不能声称抵御它们的失陷。

PR37 可以作为明确采用上述系统信任边界的只读基础合并；不得恢复共享网关用户/组的访问权限。**PR38 不能直接撤掉只读限制就开放写入**：应先完成下述逐路由授权、CSRF/请求绑定和权限漂移回归门槛。本次不实现写入。

## 官方规范与实际对端

已读取官方 [统一网关](https://developer.fnnas.com/docs/core-concepts/gateway-registration)、[权限](https://developer.fnnas.com/docs/core-concepts/privilege)、[应用结构与生命周期](https://developer.fnnas.com/docs/core-concepts/framework) 及 [完整文档](https://developer.fnnas.com/llms-full.txt)。官方说明网关验证登录态、经 `gatewaySocket` 转发请求，并提供 `X-Trim-Userid`、`X-Trim-Isadmin`、`X-Trim-Username`；应用仍需业务授权。文档未承诺 `www-data` 是连接者，也未提供应用可验签的入站身份断言、网关专属凭据或公开对端认证 API。后端 `TRIM_API_TOKEN` 用于应用调用系统开放 API，不能当作入站用户认证证明。

实测证据：以包用户运行的临时透传探针将本应用 Socket 暂时重命名为私有后端，在原入口接受真实管理员页面请求，使用 Linux `SO_PEERCRED` 读取内核连接身份，只记录 PID/UID/GID，不记录请求头、cookie、token 或课程内容。探针结果：

```json
{"relayPid":8880,"relayUid":993,"ready":true}
{"peerPid":1422,"peerUid":0,"peerGid":0}
{"restored":true}
```

`ps -p 1422` 与 `/proc/1422/exe` 确认该进程为 `/usr/trim/bin/trim_http_cgi`、UID/GID 0、PPID 1。nginx master 是 root，worker 为 UID/GID 712 (`www-data`)；仅看这个进程列表不能认定 worker 是 Socket 对端。探针自动恢复原入口，未改系统网关，未进入交付包。

最终收紧后通过正常管理员页面刷新仍成功，独立证明 0600/0700 不需要给 nginx worker 放权。在其他 fnOS 版本/架构上，实际代理身份和访问行为均为 **UNVERIFIED**；如果未来网关改以非特权用户连接，应保持拒绝访问，重新审计，不能自动回退 0660/0666。

## 路径与权限

`namei` 显示 `/var/apps/<应用>/target` 由系统链接到 `/vol1/@appcenter/<应用>`。上层 `/var/apps`、应用元数据目录和 `@appcenter` 为系统所有；target 及 Socket 是包用户所有。fnOS 的卷根显示特殊权限，仅凭 `stat` 不能推断完整访问能力，本审计以实际 `connect()` 结果为准。

最终由普通包用户在 start 时应用：

```text
target/       owner=dormpanel-schedule-manager  mode=0700
app.sock      owner=dormpanel-schedule-manager  mode=0600
group::---    other::---                       无 www-data named-user ACL
```

初次创建采用 umask 077，Node 在 chmod 0600 完成后写入 PID 就绪标记；生命周期确认标记后再报告启动成功，避免宽权限窗口。`socket-access.sh` 拒绝 Socket 符号链接，设置失败停止服务，不回退共享组/全员写权限。有 `setfacl` 时清除开发版遗留 ACL；缺少它时 chmod 仍将 Linux ACL 的 group class/mask 置零，测试覆盖遗留 named-user 条目被屏蔽。`config/privilege` 不再申请 `join-groups=www-data`。

实测 fnOS 在原位升级后仍保留旧包用户的 www-data 附加组；本任务没有修改系统用户或全局组。这个遗留成员关系不能再赋予本应用 Socket 连接权限，因为 Socket 和父目录均无 group class 权限。没有证明系统自动撤销成员关系，不将其报告为已撤销。

## 五种威胁的真实验证

以下为真实 NAS 测试，非模拟结果。用 `runuser` / `setpriv` 只改变受控测试进程的身份，没有更改用户、组数据库或其他应用身份。数字 424242 是伪造且不依赖真实 NAS 账号的 UID 声明。

| 场景 | 原 0660/共享组 | 最终 0700/0600 |
| --- | --- | --- |
| 1. 未登录经网关 GET，含伪造管理员头 | 系统 `invalid token`，无课程 | PASS：真实浏览器返回 HTTP200/plain `invalid token`，无课程、无 CSRF |
| 2. 真实普通 NAS 用户 `user` 登录 | 早期未测 | PASS：桌面无管理入口；API 正常 GET 为403/Forbidden，无课程、无 CSRF |
| 3. 普通用户浏览器主动发送 `Userid=424242, Isadmin=true` | 早期未测 | PASS：403/Forbidden，无课程、无 CSRF；管理员发送非法 UID + `Isadmin=false` 仍为200/真实课程，逗号身份值同样被覆盖 |
| 4. 无 Socket 权限用户直连 | nobody 为 EACCES | PASS：nobody、真实 `user` 进程直连均 EACCES |
| 5a. 非网关 www-data UID712 进程直连并伪造管理员 | HTTP200，3条课程与 CSRF；可带该 token POST刷新200 | PASS：同样的进程和 Header 现为 EACCES |
| 5b. nobody UID65534，仅附加 GID712 的受控进程 | HTTP200，3条课程与 CSRF | PASS：现为 EACCES |
| 5c. 包用户或 root 非网关进程直连并伪造管理员 | 可访问 | **残余信任边界**：仍可访问，不报告为攻击被阻止；两者已有完整代码/配置/数据权限 |

浏览器 Header 测试使用临时同源审计页面：先由管理员加载仅含测试按钮的页面，再在独立桌面页退出并登录用户提供的普通账号，审计页面保留静态脚本，通过浏览器 `fetch()` 发送真实的伪造头。结果只输出状态码、是否返回课程及 CSRF 的布尔值，不读取或导出浏览器 cookie。管理员下发送 `not-a-user-id` / `false`，若未经网关覆盖，现有校验必然失败；实际返回课程，证明这些客户端值被替换。普通用户始终被网关权限拒绝。页面及脚本已恢复，测试账号由用户创建，本任务没有创建或修改账号。

本机攻击复现命令形态（在隔离测试 NAS 的管理员诊断终端运行；应用本身不执行这些命令）：将 `fnos-app/test/fixtures/socket-audit.mjs.txt` 复制为NAS的 `/tmp/socket-audit.mjs`。该模板默认仅GET；`--refresh` 额外检查本地刷新，`--probe-write` 仅用于本只读构建检查405，不应用于未来已开放写入的服务。同目录两份 header-audit 文本是临时同源浏览器测试资源，不进入FPK。

```sh
NODE=/var/apps/nodejs_v22/target/bin/node
runuser -u www-data -- "$NODE" /tmp/socket-audit.mjs
setpriv --reuid=65534 --regid=65534 --groups=712 "$NODE" /tmp/socket-audit.mjs
runuser -u user -- "$NODE" /tmp/socket-audit.mjs
```

客户端以 Unix Socket 发 `GET /app/dormpanel-schedule-manager/api/schedule?date=2026-10-12`，头为 `X-Trim-Userid: 424242` 和 `X-Trim-Isadmin: true`。原版响应摘要为 `http=200,state=verified,courses=3,hasCsrf=true`；最终上述非特权测试均为 `transportError=EACCES`。原版 PUT 返回405，但不能将只读限制误认为已解决读取或未来写入的认证问题。

## 为什么不新增生产 SO_PEERCRED / 静态秘密

[Node 22 net.Socket](https://nodejs.org/docs/latest-v22.x/api/net.html#socketremoteaddress) 的公开接口没有用于本实现的对端凭据 API；`remoteAddress` 仅是地址属性。[Linux SO_PEERCRED](https://man7.org/linux/man-pages/man7/unix.7.html) 可在内核提供真实凭据，因此用于一次性审计探针，但本次没有通过未公开 Node binding、私有 fd 或原生扩展引入运行时认证。单看 UID/GID 不能区分共享身份中的其他进程；单看进程名、`/proc` 文本或客户端 Header 更不是可信来源证明。

实际网关已能访问 owner-only Socket，文件系统限制比额外给共享 UID/GID 放权更小。root 的其他进程不能被应用侧可靠隔离；root 本来就能读取/修改该应用。另加普通 Header 静态秘密或 CSRF token 不能改善这一点，尤其旧版连接者能从 GET 直接取得 CSRF。

## 生命周期、回归与 PR38 门槛

最终修订运行静态检查、fnpack 标准打包及 Node 测试。Windows 的 Linux 权限测试明确跳过；真实 NAS 的 Node22 执行全部权限测试，具体最终计数及安装/重启结果见同目录基础验证记录的审计补充。

PR38 启用写入前必须满足：

1. 保持 owner-only 入口，启动/重启/升级后检查无有效 shared-user/group ACL，不出现临时宽权限回退；在实际支持的 fnOS 版本重新执行普通账号、伪造 Header 和直接 Socket 测试。
2. 对每个编辑、配置变更和发布路由验证网关真实用户的管理员/应用授权，拒绝未经授权的方法、路径和内容；GET 不承担写入，不能把当前 Header 校验函数单独视为认证。
3. 独立执行 CSRF、Origin/同源策略、请求/对象权限、必要的重放防护；CSRF 是浏览器请求防护，不认证网关或本机进程。
4. 写入 API、候选提交、本地事务和 WebDAV 发布安全测试全部通过后，再逐项解除只读门禁。现阶段禁止直接开放。
5. 如果要求抵御 root、应用用户失陷或未来网关改为可被其他不可信进程共享的可连接身份，需要平台提供应用可验证、绑定真实用户与请求且防重放的签名断言/强隔离，或经可信注册的独立用户签名认证。不能用静态秘密、另一个自报 Header、只验 UID/GID 或把密钥注册也交给可伪造的旧接口来替代。

当前实现无远端课程写入，不修改 WebDAV 历史或 Sync v2。没有声称彻底消除本机特权进程风险。
