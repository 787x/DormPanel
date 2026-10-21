# PR37 fnOS 基础验证记录

日期：2026-10-21（Asia/Shanghai）。基线：main `5d7079789026fb6c27ee4f04752bf0d1def14c12`，Android 已发布0.1.2。前半部分记录最初 FPK 0.1.0 的验证；当前交付为独立 FPK 0.1.2，以文末安全审计补充和 [完整安全审计](pr37-gateway-security-audit.md) 为准。

## 本地检查

- Node 测试54项通过：5个合法固定向量逐字节重编码及坏校验向量；完整领域校验；有效双层哈希下的非法内容/UTF-8/tag；多父DAG、共同基线、等价/不同tips、有环、多根、缺父、深祖先链、不同数据集、已知内容改变与缺失；异常持久化；日期调课、指定周覆盖、学期阶段、DST与跨日投影；实际Socket API权限、CSRF、WebDAV错误输入与缓存/错误状态恢复。
- `npm run check` 静态语法检查通过；`npm audit` 为0漏洞。
- `python tools/sync_v2_vectors.py` 6组既有向量通过。
- `:schedule-core:testDebugUnitTest :app:assembleDebug` 成功；现有单测任务为最新状态，Android源码、协议、数据库及正式发布资产未修改。
- 官方Windows fnpack 1.2.3成功生成真实标准FPK。最终包保留在本机 `artifacts/pr37/`（忽略提交），不发布GitHub Release。

## 真实 fnOS

- 测试NAS：x86_64、fnOS 1.2.0800。官方应用中心安装 `nodejs_v22`；标准 `install-fpk` 安装0.1.0成功，桌面入口能在fnOS窗口中打开。
- 最初服务进程属于独立 `dormpanel-schedule-manager` 用户，无root运行；初版Unix Socket归属应用用户和www-data组，权限0660。此授权在后续审计中证实过宽，已改为0600。未发现Node TCP监听。初始服务RSS约70MiB，桌面空闲显示CPU0%（非长期压力测量）。
- 早期网关未登录请求以及伪造管理员Header均返回系统的 `invalid token`，没有管理数据（系统HTTP状态仍为200，不能只靠状态码断言登录成功）。后端Socket非管理员请求为403；`nobody` 无权连接Socket。早期普通NAS账号登录未测，不能据这些结果宣称有Socket权限的进程也安全；后续独立结果见审计补充。
- 测试NAS的指定共享目录只有ICS/CSV，没有v2历史。因此使用既有PR36 **独立迁移验收目录** 的真实WebDAV数据作GET/PROPFIND只读验证，没有访问日常课表的数据集目录。读取1个DPC2根提交，包含导入实例、本地课程、两个学期配置、全周编辑、日期调课和指定周编辑；页面正确显示2026-10-12这一周的有效结果。
- 从页面手动刷新成功。停止、启动均由appcenter-cli完成；同一安装的identity.json、graph.json与webdav.json在停止/重启前后SHA-256完全一致；页面重新打开直接恢复缓存，不依赖再次下载。
- 真正升级另行使用仅供测试的0.0.9基线包：安装时通过 `--env` 写入隔离目录配置并刷新；经用户确认fnOS未经验证应用提示后，网页手动上传最终0.1.0 FPK完成升级。应用恢复running，三份持久化文件SHA-256逐项与升级前相同，API仍返回verified和3条有效课程。测试基线未发布。
- 配置文件0600、归应用用户所有，数据目录0700。浏览器未收到WebDAV用户名、密码或来源locator。
- 所有WebDAV接入只允许GET/PROPFIND；未产生新提交，没有修改、删除或重写远端协议文件，没有修改Android安装、正式资产、WebDAV或FN Connect服务。

## 实测限制与恢复

本机 `appcenter-cli install-fpk` 对已有实例仅报告已安装，未升级版本，应使用网页手动安装走升级流程。为验证最终界面，曾使用官方 `install-local`；实测其行为是先停止并卸载旧实例、删除本地持久化目录，再从源码重装，本机该路径报10237。随后使用标准FPK重新安装恢复，并另行完成真正网页升级。此开发重装不等同版本升级，卸载后的新实例生成新writerId。

ARM架构、长期运行、卸载保留选项尚未完成实机验证。安装后配置向导在目标系统界面未显示，本版支持安装时向导/`--env`传入；不应通过开发重装修改已有实例配置。后续PR需继续核验各fnOS版本的配置向导入口。

## PR38

待实现完整编辑与本地事务、关联冲突展示和决策token、不可变发布/尝试标记/精确回读/失败恢复，以及多端并发同步验收。PR37仅为可运行的只读管理和协议基础，不声称完整第三Writer同步已完成。

## 网关信任边界审计补充（FPK 0.1.2）

- **已复现原风险**：真实NAS上，独立UID712/www-data进程和仅附加GID712的nobody进程都可伪造管理员头，GET为200、返回3条课程及CSRF；前者用该token手动刷新也为200。没有把未登录/普通Header403/nobody权限失败当作这些场景安全的证据。
- **真实内核连接者**：普通包用户运行的短时透传探针以SO_PEERCRED取得PID1422、UID/GID0；`ps`及`/proc/.../exe`确认是root的`trim_http_cgi`，不是nginx的www-data worker。探针已恢复入口，不进入交付包，也没有修改系统服务。
- **最终权限**：应用target目录0700、Socket0600；清除遗留ACL，不再声明加入www-data组；权限设置失败停止启动，无宽权限回退。普通包用户能够自行设置，不要求root运行应用。fnOS保留的旧附加组成员关系未被手动修改，不能再借此连接本Socket。
- **真实浏览器会话**：用户提供现成普通账号`user`后，其正常API请求及伪造管理员Header都得到403/Forbidden，无课程、无CSRF；桌面无管理员入口。管理员发送非法UID+Isadmin=false或逗号身份值仍正常读取，证明这些客户端声明被覆盖。退出登录后相同浏览器伪造管理员返回HTTP200/plain invalid token，无管理数据。临时同源审计页面只做GET、只输出布尔值/计数，已恢复资源并恢复管理员会话。
- **最终本机进程验证**：www-data、真实普通用户及nobody+GID712均为EACCES；包用户和root直接伪造Header仍可200，这两者已有完整配置/缓存/代码权限，明确属于残余信任边界，不报告为被隔离。对特殊DAC绕过能力进程没有另行创建测试，属UNVERIFIED特权场景。
- **测试与构建**：Windows 59项测试中55通过、4项Linux专用权限测试明确跳过；真实NAS官方Node22全部59项通过、0跳过。含原向量和领域校验、实际Socket Header/只读门禁、遗留ACL撤销、无ACL工具时mask置零、设置权限失败、Socket符号链接拒绝。JavaScript静态检查、两份shell语法检查和官方fnpack标准FPK打包通过，npm依赖审计0漏洞。
- **原位升级**：0.1.0→诊断中间包0.1.1→最终0.1.2均使用网页手动升级；最终target/Socket自动获得0700/0600且无www-data ACL。identity、webdav配置和已验证graph三份文件哈希在最终升级前后逐项相同，服务running。中间named-user ACL方案经实机核查仍允许同UID进程，已经撤销，不作为交付方案。
- **最终停止/重启**：appcenter-cli依次报告stopped、running；三份持久化文件哈希仍不变，target/Socket保持0700/0600且无named-user ACL。重启后www-data仍为EACCES；管理员桌面重新打开恢复缓存，并通过正常网关再次手动刷新成功。
- **边界与合并**：PR37可在root/应用用户为完整信任边界下作为只读基础合并；PR38当前不能仅凭authorize()/CSRF直接开放编辑或发布，应满足完整审计列出的用户/对象授权、同源/CSRF/请求绑定和升级权限漂移回归门槛。无任何课程远端写入、历史修改、Android变更、tag或Release。
