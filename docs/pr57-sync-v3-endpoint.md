# PR57：Sync v3 安全访问端点更换与恢复

基线：`main` 的 `27268b7`（PR56），2026-10-25 核对远端 main 一致。本 PR 不修改 DPV3/3 字节、课程合并、Room schema、版本号或安装 Writer。

## 实现与操作

- fnOS「数据源管理 → 工作区 → 更多操作 → 选择访问端点」：先添加候选连接，再只读预检、继续、查询、取消或确认。服务端保存进度，重开页面可查询本管理员的上次任务。候选配置冻结为独立连接；原共享连接及其他工作区不变。
- CLI/MCP 共用 `/api/endpoint` 与 `V3EndpointMove`。`endpoint.preview/resume/status` 仅需 read（POST 用于避免凭据进入查询串）；`endpoint.cancel` 需 admin；`endpoint.confirm` 需 admin，Agent 还必须申请独立网页批准。批准摘要只含身份、目标地址、账号名、精确信任指纹、验证事实及影响范围，不含密码或证书。
- Android 的 X08E/Phone 共用「共享课表 → 安全更换 v3 访问端点 / 继续预检」。候选账号整体由现有 Keystore 密钥加密；确认后仅此 v3 课表采用独立账号，全局 WebDAV 导入账号保留。前台和 JobService 从同一活动 Room Store 读取已提交凭据。

预检只进行 GET/PROPFIND/授权本地读取；没有 publication port，不调用同步 round，不创建远端 Descriptor、Genesis、Commit 或目录。持久记录只落在私有检查任务与候选图中，不改业务、前沿、错误、冲突、Pending/capture 或回执。

每次先直接回读绑定的 Descriptor 路径、datasetId 和完整 RAW SHA-256；再分页回读全部已知 node，保留全部 observed 路径，复用既有 `V3History` 验证祖先、继承、Checkpoint、Transition 与迟到分支。候选图与活动图隔离；候选缓存命中不能代替候选端点实际读取。额外前沿不在端点操作中应用，切换后的普通 round 负责三方合并与冲突。

每轮至多推进 16 个源对象、16 个已观察路径、32 次 scope 发现和 16 项待验证任务，仍共享现有请求、字节、时间和闭包预算。进度逐页持久化。`READY` 表示已知引用及本次发现范围通过验证，不代表全目录一致或未知旧 Writer 停写。任务保留上限为 64，不做远端或缓存 GC。

确认绑定源 Workspace 状态、Catalog revision、活动前沿及已知 node/observed 输入；再次读取 Descriptor、候选前沿并列举当前前沿 Epoch。新本地编辑或已知证据变化拒绝旧确认；最终复查发现额外候选历史会使任务过期，必须重新预检。相同决策重复确认返回原回执。

fnOS 的连接配置、目标工作区连接引用和决策回执一次性 fsync/rename 提交到 Catalog；不需要跨 Workspace/配置文件的部分更新。保存结果不确定时立即使旧内存管理引用失效，重开读取同一持久决定。Android 的目录、endpointKey、加密账号及决策回执与同一 Room 事务提交，和引擎共用 networkGate；提交前回滚，提交后内存失败必须重开，不允许旧引用继续发布。

## Pending 与不支持边界

普通离线编辑和从未尝试 PUT 的 STAGED 材料保留。ATTEMPTED、UNCERTAIN、READBACK_CONFIRMED 尚未回执时，本实现保守拒绝端点更换，要求先恢复原端点，通过既有只读恢复及本地 receipt 完成原候选。新地址的 404 不能解除原 PUT 意图；本功能没有清空或强制重试入口。

初始化、换代、迁移未完成，或备份恢复仍隔离发布、已知历史异常/缺失时拒绝切换。不同 Descriptor、v2 目录、未知证书、关闭 TLS 校验、复制 Writer、删除旧历史和永久停写声明均不支持。

Android 支持现有 WebDAV 地址/目录、账号密码、系统 CA 与经独立确认的精确单证书信任；不新增本地文件访问方式。fnOS 保留原 WebDAV HTTPS/显式 HTTP 策略与 LocalGrants 授权目录身份检查，可选择符合这些边界的独立候选连接，不增加任意路径授权或 SSRF 豁免。HTTP 降级、授权本地目录与 WebDAV 互换没有新增实机矩阵，不能把本次 HTTPS 验收外推到这些条件。

公共 CA 签发叶证书的单证书信任同时保留系统链校验与精确叶证书比对；自行签发证书仍须独立提供。未提供证书时沿用系统 CA；没有自动接受网络未知证书的产品入口。

## 实际验证

原始输出与源码指纹见 [结构化记录](evidence/pr57/verification.json)。最终补充目标地址、账号名及证书指纹的批准摘要后，NAS 再跑 11/11 项端点与 CLI/MCP 定向测试通过；124 项回归和最终定向各自记录源码指纹。

| 验证 | 实际结果 |
|---|---|
| NAS 官方 Node 22.18.0、`dormpanel-schedule-manager` 包用户 | 124/124，通过新端点、CLI/MCP、Catalog、Web API、备份、PR56 维护、活动 v3 与迁移定向回归 |
| HTTPS 真实网络与独立生成证书 | 错误 pin 拒绝；URL/账号/密码变化及同地址证书轮换通过；原对象逐字不变；切换后原 Writer 正常发布 |
| fnOS 实际 HTTPS WebDAV 随机 `dormpanel/pr57-endpoint-*` | 系统 CA → 已验证叶证书 pin、回读、Catalog 重开、重复确认、离线 RAW 与原 Writer 保留通过；没有访问日用「课表ics」 |
| X08E Android 9 与 Phone MIX 2S | 各 3/3：正式共享界面、Room/Keystore 重开、决策提交前/后故障、缺失对象与过期编辑拒绝、重复确认；直连 NAS HTTPS CA → pin 后原 Writer 发布通过 |
| Android 构建 | App、Phone Debug、核心 instrumentation APK 构建成功；仅安装独立 `com.dormpanel.schedule.core.test`，未升级日用 APK |
| 核心 JVM 测试 | 初轮 187 项、0 失败、1 skipped；后续端点修订由上述两台实机定向测试覆盖，未重复全套增长测试 |
| FPK | npm 工程构建与 NAS 官方 fnpack 打包通过；本 PR 新 FPK 未安装，未宣称安装升级实测 |
| Web | 侧边浏览器在隔离实例走通正式数据源管理的预检、继续及确认，显示本机已切换与未知旧 Writer；后台权限由上述真实 CLI/MCP 和 Web API 回归覆盖 |

八类 RAW、`null`/空字符串、复杂导入时间的完整快照作为端点测试 oracle；测试发现并修复了 BigInt 状态指纹序列化问题。额外并发分支在候选图中验证、首次正式同步进入正常冲突；共享连接的另一个工作区保持原引用。Catalog 保存前后中断、Room 提交前后异常都实际注入并重开验证，没有把静态推断记为实测。

截图：[X08E 1280×800](evidence/pr57/x08e-endpoint-ui.png)、[Phone](evidence/pr57/phone-endpoint-ui.png)、[Web 确认完成](evidence/pr57/web-committed.png)。截图来自独立测试包/临时实例；Android 复用测试用前台 Activity，因此背景保留旧 PR55 验收标题，不是日用应用。

未做真实断电、长累计历史端点 soak、全部访问方式转换或新 FPK 安装升级；没有证明旧端点永久停写或远端全局一致。原端点可能仍接受未知 Writer 的新提交，管理者需保持原目录和恢复材料可用；本机切换成功不代表其他设备已收到。
