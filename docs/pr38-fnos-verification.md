# PR38 fnOS 可编辑 Writer 验证记录

日期：2026-10-21 至 2026-10-22（Asia/Shanghai）。基线：PR37 已合入的 main `79148b0`；分支 `codex/pr38-fnos-writer`。没有合并 PR37、修改 Android 正式版本或发布 Release。

## 功能与本地验证

独立本地课程状态及原子事务；课程/事件、全周与指定周稀疏覆盖及重置、日期调整、来源和学期配置；ICS / 湖北大学 CSV 预览与确认。候选、精确 capture、PUT 尝试、回执、异常清单和完整冲突输入独立持久化。网络在事务外执行，上传期间的新编辑按 capture 重放。

Sync v2 普通 PUT 使用独立随机路径，不使用 head 或条件写。GET 预检与逐字节回读；已有尝试结果未知时不重复 PUT；旧路径保留预期哈希。三方合并保持 Android 记录及关联单位语义，多分支逐对检测、稳定展示；确认 token 绑定完整输入并在决定前重新检查远端。

- Node 最终 80 项：Windows 76 PASS，4 项 Linux 权限测试明确跳过；真实 fnOS 官方 Node22 最终全部 80 PASS、0 跳过。
- 原有五组合法 DPC2 向量逐字节重编码、坏校验及完整领域校验通过；Python 六组既有向量通过。
- 18 组独立 base/local/remote 输入及两种预期决策结果，由 Android JVM 未修改的 ScheduleMerge 校验冲突关联键及结果一致。涵盖独立/相同修改、删除/编辑、来源/覆盖、导入时间、学期 provenance、日期/指定周重置、null 与空文本。
- JVM 独立比较既有 WakeUp ICS 和湖北大学 CSV：Node 生成的身份、绝对时间、sourceId 前缀与指纹一致。
- 故障注入：三 Writer 收敛、多分支强制保留本地、旧 token、上传期间编辑、回执丢失、远端成功但回执事务失败、预检失败不标记 attempt、旧路径换 UUID、已知历史篡改/缺失、首次观察中尚未下载路径的重启回查、多重共同基线歧义。
- 逐写路由测试管理员、CSRF、Origin/端口、用户绑定、过期版本/重放、请求类型/大小、凭据泄密、换库保护及配置 rename 后数据库未提交的崩溃窗口。错误证书与同数据集端点迁移验证通过。
- 静态语法、依赖审计（0 漏洞）、`:schedule-core:testDebugUnitTest :app:assembleDebug :schedule-core:assembleDebugAndroidTest` 通过；JVM 共 136 项、0 失败。生产 Android 源码、协议字段、Room 数据库和正式资产不变。

## 真实 fnOS 与安全边界

环境：x86_64、fnOS 1.2.0800、官方 Node 22.18.0。包用户运行、Native FPK、统一网关和 Unix Socket，无 Docker、Root 运行时或 Node 公网 TCP 服务。

target 0700、Socket 0600、无有效 shared-user/group ACL。真实 www-data UID712、普通 user UID1001、nobody+GID712 对缺身份、非管理员、伪造管理员和写方法均 EACCES。管理员侧边浏览器正常配置、测试连接、离线新增与同步。root/包用户仍有完整代码/数据权限；包用户诊断 API 不是网关用户认证证明。本轮未再次完成普通账号网页登录/伪造 Header 测试，不用 PR37 旧结果冒充本轮复测。

实机修复：fnOS 转发从 Host 去掉端口，Origin 保留端口，改为用户+完整 Origin token 绑定，POST 仍精确匹配；TLS keep-alive 只为新连接注册并清理握手监听，避免累积监听器。

## 真实三端路径

所有写入仅在 `dormpanel/pr38-e2e-f2cfc702-4bb9-4e55-85c0-66834b78bb8b/dormpanel-sync-v2/` 合成目录。没有删除、修改或压缩已有历史，也没有向日常课程数据集写入。

第一阶段使用用户提供的公网测试 WebDAV。X08E 和 MIX 2S 隔离测试 APK 从同一根离线修改课程与事件；FPK 页面新增“FPK 新课”并修改日期说明。FPK 合并发布 `a9804afe-2e04-446d-b0fb-5828d0c93a72`，保留三端修改；两台设备的 sync 通过，随后独立检查其已确认 baseline 均包含全部修改。手机一次后续 verify 遇网络故障，记录保留，不当作该次 PASS。

随后测试域名在局域网 DNS 返回 NXDOMAIN。所有离线编辑保留。按用户指示，FPK 完整验证同一目录历史后迁至 `https://192.168.3.153:5006/`，不更换 writerId、不重建状态。服务公钥证书通过管理员 SSH 从真实 WebDAV 配置指向的证书取得；添加连接级 CA 并精确匹配证书，没有关闭 TLS 校验、修改系统 CA、WebDAV 或 FN Connect。替换成另一个证书的真实 HTTPS 请求被拒绝。

两台 Android 在 192.168.137.0/24，不能直接访问 NAS 的 192.168.3.153。因此后续采用**临时 ADB reverse + Windows 127.0.0.1 单目标 TLS 字节转发**；Android 仍端到端校验 NAS 指定证书。仅隔离测试 APK 使用显式证书和地址别名参数，正式 Android APK 未改。转发及 ADB reverse 已清理。这是诊断网络适配下的实机测试，不是普通 LAN 直接互通验收。

冲突阶段：Android 上传 conflict-a / conflict-b，FPK 已有 conflict-fpk；三条 tips 保留，FPK 页面显示一个关联冲突及完整分支，两台 Android 的 conflict 检查均 PASS，各自本地值未静默覆盖。包用户诊断 API 携带当前 token 保留本地，先保存多父候选，再正常发布 `51a4b190-0ea9-4d42-820a-2f4aae785773`。两台 Android verify-resolution 均 PASS，课程值为 conflict-fpk。

上述实测证明真实 Android/Room/网络同步核心和 FPK Writer 互操作；不把隔离测试 APK 等同于已发布 Android 0.1.2 产品界面的完整三端 UI 验收。

## 重启、升级与产物

应用中心正常网页升级已完成 PR37 FPK 0.1.2 → 初版 0.2.0。三份文件升级前后哈希逐项相同：

| 文件 | SHA-256 |
| --- | --- |
| identity.json | 1ed864692357d66528a3e617d01e7716c33ce837723d84f996a6f8157f3233d3 |
| graph.json | 34f5a9ff8ac8cbf3adeef5f5c9bba30235aae674ec2ccc4554c2e08ec520c8bc |
| webdav.json | de0483f444500e5b4d87ef28f975361c0b4438e946097f0fa141b9310d215511 |

writerId 一直为 `815bc368-1ba9-4848-91ff-db4f02953e2a`。新增课程与日期说明后停止/启动，整个 workspace.json 哈希相同，未发布编辑保留。

中间诊断源码替换用于功能定位，**不计作 FPK 升级**。最终 0.2.1 标准包的正常升级结果见下方补充。没有用卸载/清数据/`install-local` 冒充升级。PR37 已记录更早 0.1.0 → 0.1.2 的正常升级；本轮未直接再测 0.1.0 → 最终包。

官方 fnpack 标准包 `artifacts/pr38/dormpanel-schedule-manager-0.2.1.fpk`（本地产物，不上传 Release），SHA-256：`898faec634851d6b40dfaf746982f0eef6c0996a1b44834ffd051e439c2b0c1d`。本地归档含原始测试日志与隔离诊断参数，后者含凭据，已忽略提交。包与参数不可一并上传。

升级曾被配置回调拒绝并回滚到 0.2.0，三份持久化哈希不变。已增加专用 `--upgrade` 模式，并兼容已安装的无参数 callback：已有配置默认仅校验保留，只有显式 `--configure` 才消费向导更新；回归验证更新后的绑定、凭据和证书逐字节保留。标准包重新构建后正常网页升级成功。

## 最终标准包升级补充

用户确认 fnOS 责任条款后，应用中心真实升级到 **0.2.1**，服务 running；页面显示 0.2.1，并正常按需检查 LAN WebDAV，仍为已发布的 `51a4b190-0ea9-4d42-820a-2f4aae785773`，无候选或待处理冲突。

升级前后 workspace、identity、WebDAV 配置逐字节哈希一致：workspace `fac182e1f0ff2cbc2216ce2b894d26a8384c268aa6e2ee6ab413b05eab33049c`，identity 仍为上表值，迁移后的 LAN 配置为 `91e14f8478487c26065bbcdfe156eaccc3ae639b1b53c045521d2a1bc3b3ecd8`。随后通过正常 appcenter-cli stop/start 重启；重启前后这三份文件哈希仍逐项一致。

最终包升级和重启后，target 0700、Socket 0600、无 shared-user/group ACL。www-data、user、nobody+GID712 的所有直连/伪造/写方法测试仍为 EACCES。新版本页面从持久化状态恢复课程并成功同步；正式 Android APK 未更新。临时 TLS 转发与 ADB reverse 已清理。

## 未验证范围与剩余风险

- 最终 0.2.1 已正常网页升级并重启验证。未单独直测 0.1.0 → 最终包，已有实测链为 0.1.0 → 0.1.2 → 0.2.0 → 0.2.1。
- 正式 Android 0.1.2 完整产品 UI 三端操作、真实普通账号网页负向复测、ARM、最大容量压力、真实磁盘满/强杀/服务断电未全覆盖。
- 大图下整份原子 JSON 写入的峰值内存、写放大和耗时未压力验证；协议预算仍保留，小样本通过不证明容量极限。
- 回执/断流故障主要为确定性注入，不等同服务器实际断电。没有机械安排 X08E 四小时 soak。
