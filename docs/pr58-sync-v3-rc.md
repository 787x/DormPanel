# PR58：Sync v3 统一发布候选 — RC ACCEPTED

日期：2026-10-26（Asia/Shanghai）。基线 `54ab0a52fc6966af8b7a431ac756cc6a5563bc69`，收尾核对远端 main 仍为此提交。用户要求 fnOS 候选改为 **0.8.1** 后，补齐正式升级及三端验收。**这是 RC 验收结论，尚未公开稳定发布。** 未合并、打 tag、创建 Release、迁移日用 v2 或删除远端历史。

## 最终产物

私有冻结目录：`D:\Projects\DormPanel\artifacts\pr58\candidate-3`。完整文件名、字节数、哈希、证书、构建输入和证据摘要见 [verification.json](evidence/pr58/verification.json) 与 [SHA256SUMS](evidence/pr58/SHA256SUMS.txt)。二进制、课程、凭据、证书材料和原始日志未提交。

| 组件 | 最终版本 | 构建源码 | 安装验收 |
|---|---|---|---|
| X08E | 0.1.9 / code 12 | `0bd001bd8148b3e722c0ba297f5d6383dce80d60` | 原 0.1.8 / 11，经 `adb install -r` 升级；当前安装字节与候选相同 |
| Phone | 0.1.8 / code 10 | 同上 | MIX 2S、Android 13 / API 33，原 0.1.7 / 9 原位升级；当前安装字节相同 |
| fnOS FPK | 0.8.1 | `777ffdd2f01ae9a8723b69add91192233ba27a17` | 官方应用中心网页从 0.8.0 原位升级；服务新进程、manifest/网页/CLI 为 0.8.1；44 个运行文件逐字匹配 |
| HA ZIP | 0.1.2 | 与基线集成源码相同 | 10 个 ZIP 条目与当前源码逐字匹配；没有重新部署 HA |

两款 APK：applicationId 未变，minSdk 28、targetSdk 37、非 debuggable，单一批准证书 SHA-256 `32d68b5c6ad0bfd1b87aa0d54514ef71c33838e7643eb038488af7ca2add17f7`，与旧安装包相同。沿用原 release keystore；历史证书 DN 含 Android Debug，但正式 Release APK 非 debuggable，也没有换用新的默认 debug key。

Android 由 Gradle Release 构建并执行 release lint，OpenJDK 25.0.3 / build-tools 37.0.0；FPK 在 NAS Node 22.18.0 使用官方 fnpack 构建并验证包。包名、安装权限、Writer 生成规则、HA 源码及 DPV3/3 语义未变。

candidate-1 的 0.8.0 FPK 因端点确认后页面管理版本未刷新的缺陷失效；candidate-2 的修复版 0.8.0 被最终 0.8.1 替代。两个旧目录保留、不得再作为交付候选。candidate-3 复用逐字一致的 APK/HA，FPK 为新版本新字节。文档提交不改变上述构建输入，最终 review commit 由 PR 提交记录定位。

## 修改与验证范围

- 发布脚本按各 App 独立 versionName / code 检查包名、SDK、签名、debuggable 和哈希，冻结目录拒绝覆盖；HA 使用真实 0.1.2，严格检查 ZIP 条目和源码字节。
- fnOS manifest 从 package.json 取版本，包/网页/CLI/DPBK 元数据为 0.8.1，备份读取保留 0.8.0 及既有兼容版本。
- 修复安装实测发现的网页缺陷：v3 端点 COMMITTED 回执不带 Catalog 新版本，页面现在先重新读取，随后打开备份页不再报“管理版本过期”。未修改服务端权限或协议。
- AGENTS 区分 v3 已实现、已验收和公开稳定发布；README 保留真实公开稳定/预发布版本，只把本次版本写作 RC。

定向自动检查：发布门禁模拟 39 项、原生 APK 负例 9 项、HA 真实 ZIP 负例 6 项；NAS 包用户备份/维护 53 项通过（0bd001b 后端），网页回归修复前失败、修复后 1/1 通过；0.8.1 备份版本往返 1/1。最终 candidate-3 再次通过原生 APK 签名/SDK/debug/hash 与 HA 正向验证。没有把模拟检查当作安装实证，没有重跑增长、70 次生命周期、全套故障注入或四小时 soak。

## 安全原位升级与恢复

Android 操作前保存旧 APK、证书、版本及停止后的可读私有数据备份。原位升级不自动创建 v3 身份或迁移日用绑定。X08E 业务表保持一致；Phone 初次升级后通过既有 v2 正常接收导入数据，属于合法变化，不能要求 SQLite 文件永久同哈希。

用户明确允许换测试库后，原 Room 及关联配置移入同包私有保留目录，正式 APK 使用隔离合成库。收尾再次归档测试库、恢复原文件并重开：两端全部业务表与升级后原库快照一致，v2 Writer/绑定、加密全局 WebDAV 配置不变，无活动 v3 标记。HA 注册/凭据、布局、Todo/Memo、启动配置等保留。配置差异限正常检查时间、Phone 滚动位置、X08E 控制记忆（音量记忆与本次灯控状态）；没有清数据、重置 Keystore 或卸载 Android。

最终 FPK 官方网页 0.8.0→0.8.1 升级后，91 个旧持久状态文件逐字不变、15 个工作区及原连接定义保留，原安装 Writer `815bc368-1ba9-4848-91ff-db4f02953e2a` 保留；没有自动迁移或开始旧工作区发布。运行源码 44/44 与候选匹配。完成 RC 操作后其他工作区 64 个状态文件仍逐字一致，活动选择已恢复原工作区。

### 必须保留的 0.8.0 阶段事故

初版 0.8.0 曾从 0.7.6 官方网页升级成功。修复后同版本包被官方网页拒绝；随后尝试 `appcenter-cli install-local`，工具实际先卸载再安装失败（10237）。**这是执行事故，不能算原位升级 PASS。** 当时已有停止状态完整备份；经官方安装、停止服务、隔离新空状态、恢复已校验 var/etc，96 个文件及原 Writer 恢复，未用新空 Writer 发布。

用户改为 0.8.1 后，用最终包完成较高版本官方原位升级，上述新证据补齐最终候选升级条件，但不抹去事故。原 0.8.0 阶段阻塞摘要保留于 [历史记录](evidence/pr58/verification-080-blocked.json)。**禁止使用 install-local 绕过已装版本检查；升级必须使用官方应用中心较高版本流程。**

## 同一真实数据集与网络边界

全程使用同一随机 RC 子目录与合成数据。v3 datasetId `7d3d4110-56ed-48ce-baf8-1ab7cb29dab0`，Descriptor SHA-256 `a8e90bd9d5893e8cbb214b8257ea0e4d66c2fd8b003a2098af9bdcea314fbcaa`。安装 Writer 各自稳定且不同：

| 客户端 | Writer |
|---|---|
| fnOS | `815bc368-1ba9-4848-91ff-db4f02953e2a` |
| Phone | `feaef44a-03af-440e-a614-86ebd96ae020` |
| X08E 隔离库 | `f057f4c6-a3e6-4652-ba17-b6ebfca85a4d` |

HTTPS-A 是测试 NAS 公网 WebDAV；HTTPS-B 是另一 NAS 的独立随机子目录。早期 A 在设备上 TCP 超时，B 可用；后期 B 在 NAS Node 出现 ENOTFOUND，错误被保留，没有强行确认。合成不可变对象 A→B、后续 B→A 的复制均逐个 GET/PUT 后回读，不覆盖已有不同对象、不复制日用数据、不迁移或删除原 v2。

最终 Android 两端通过 **公网 HTTPS-A / 系统 CA**；fnOS 通过 **LAN HTTPS / 192.168.3.153:5006 / 精确独立核验的 NAS 证书**。证书 SHA-256 `1adbc220f108c0942c436e2522d1b3ed6a682b2b6d6d4ee8f561bbf90941257e`。两条实际路径进入同一个 NAS 测试存储，但不是相同网络/权限模式。没有 ADB 隧道、模拟 Store 或关闭 TLS 校验。

为先安全恢复旧 B 端点，只在测试 NAS 短暂添加经 CA、主机名和 Descriptor 精确验证的解析条目；正式历史审计恢复后再走端点预检与确认。原 `/etc/hosts` 已逐字恢复，未持久改变客户端 DNS 或放宽证书。

## 最终候选实际场景

| 场景 | 结果与证据 |
|---|---|
| 同 Descriptor 加入 | PASS：最终 APK 三方身份核对，三 Writer 不同；X08E 首次失败仍留 v2 的库另存，未冒充成功；后用空隔离库明确加入 |
| 三方实际编辑/传播 | PASS：Phone 新增/离线课程、X08E 独立新增、fnOS 课程覆盖和 Epoch 记录，各端最终完整 RAW 相同；分别核对本地保存、远端回读与另一端观察 |
| 离线重开与并发 | PASS：Phone Wi-Fi 关闭后保存全周覆盖、强停重开仍在；fnOS 修改同一字段并发布；Phone 显示双方真实值并选择本地；X08E 独立提交推进前沿使首个决策过期，重新审阅决策后完成，独立新增保留 |
| 正常 Epoch | PASS：最终 FPK 普通编辑/发布越过默认 160 门槛，153 次后续普通编辑到 E154；第二代 `26742e1f-66d1-47e1-8b5e-6fa8abd2efbc`、Checkpoint/Transition/父引用由真实引擎生成；未改变预算或调用测试换代捷径；两端继续接收 |
| Phone 后台 | PASS：保存第六条后立即进入原生 Home；Job 3201 在 waiting 后用系统调度强制运行，正式 JobService 使用已封装 v3 端点发布，fnOS 实际收到；不是前台同步替身 |
| 完整 RAW | PASS：两端 Room 持久快照通过安装版协议编解码器与 fnOS 比较，6 条课程及覆盖等完整业务集合 SHA-256 均为 `9bb214876e45ce8ea86bacad8285fbfad147b6208bb466ffa4d571cd1c1d85f4`，null/空值和业务身份按协议保留 |
| 非空 v2 显式迁移 | PASS（串联恢复）：早期 0.8.0 正式入口从 1 条合成 v2 创建唯一目标，最终 Phone APK 按 RAW 等价预检加入已有目标；最终 0.8.1 通过“恢复 v3 迁移/查看证据”继续同一任务，ACTIVE、exactReadbackConfirmed/rawEquivalent/sourceHistoryRetained=true；未在最终 0.8.1 重做新 Genesis 创建，迁移后协议核心源码逐字未变 |
| 旧 v2 保留 | PASS：源 datasetId `138ab693-fe57-47f6-b4da-83d0ab4d6241`；原 v2 RAW `8f285ef2133840d7dfc395eec71ec2f4be1c88be02743c9784f6018117d14444` 仍实际可读；同一目标未出现第二个第一代 Genesis。第二个 type 2 是第二代 Checkpoint |
| 历史分页与 RAW 差异 | PASS：最终 FPK 网页 5 页，CLI 每页 100；Checkpoint E153/5条对最新 E154/6条，完整 RAW 差异为新增1、修改1 |
| 新提交式回退 | PASS：正式网页回退到 Checkpoint，再回退恢复原最新业务状态，各自产生新 Commit；公网回读核对所有此前 167 个文件哈希未变 |
| 独立历史归档 | PASS：最终 FPK 同一持久任务分批归档167个 v3 对象（含 Descriptor），84页，VERIFIED；清单哈希 `260f697d92fdbee644067d224d56c76bc971e97ac4a72d85ac1376f9a35e582e`；对象/清单实际回读 |
| 私有整理/远端 GC | PASS：网页预览后确认清理160个无引用快照，实际回收1,656,576字节，业务 RAW 未变、缓存/回执等保护保留；GC仍 NOT_ELIGIBLE、automaticDelete=false |
| DPBK0002 | PASS：最终0.8.1正式网页创建并认证回读15工作区完整备份；实际0.8.0和0.8.1档案均由安装版0.8.1恢复引擎在无监听器的隔离私有目录受保护恢复；4个v3工作区完整records/frame哈希、15工作区和安装身份一致，业务RAW与档案一致；恢复保护保留、0次远端PUT。档案本身dirty=false，未将其描述为未发布内容恢复测试 |
| 端点变更 | PASS：最终FPK实际预检/继续/取消/重预检/确认到LAN地址与核对证书；确认后页面刷新及备份入口正常。两端正式APK实际切到公网A，原Writer继续、其他端实际观察；全局导入账号、旧连接和其他工作区未误改 |
| 网页/CLI/MCP与权限 | PASS：最终0.8.1、86工具、同工作区记录及版本完全一致；网页高影响入口继续走管理员保护。Epoch脚本临时edit经用户单独批准，只用普通edit；操作ID持久化、拒绝非法ID发生在意图前；完成立即撤销并验证仅read，未授予admin/maintenance |

最终一轮公网 A 有界递归观察 37 个目录、168 个文件：v3 Descriptor1、Genesis/Checkpoint2、Commit163、Transition1，另含旧 v2 RAW1。它只代表本轮已观察的随机目录，**不是全局一致、旧分代全量审计、未知 Writer 停写或服务器封存证明**。归档即使 VERIFIED，WebDAV GC 仍无资格。

## X08E 兼容性与未执行事项

PASS：1280×800横屏首页、课程卡片、正式共享入口；Wi-Fi关闭时本地时间、缓存课程、计时器实际Running/Paused，恢复05:00及网络；在线HA实时传感器与灯状态，真实灯控ON后恢复原OFF/1%亮度；进入MIUI原生Home并返回、重开后原库恢复；保留原start_after_boot配置，定向调用自有BootReceiver后Activity恢复。

代理操作阶段未执行整机重启、小爱语音唤醒和实物 Mesh 控制；当时只观察原生 Home 及进程/服务共存。用户后续确认前三项补测通过，证据来源见下节。四小时 soak、日用闹钟触发及重新部署 HA 未执行；没有修改小米系统组件、默认 Launcher、日用闹钟或网关设置。

## 用户补充的人工验收

用户在本会话明确确认上一条补测清单的“前三项检查通过”，对应：

| 项目 | 结果 | 证据来源 |
|---|---|---|
| 最终 0.8.1 从新的隔离非空 v2 完整迁移，另一端加入、单一 Genesis 与旧 RAW 保留 | PASS（用户确认） | 用户人工验收；未提供新目录、Descriptor、哈希或操作日志，代理未独立重跑 |
| X08E 整机重启后按原配置启动，课程与 HA 恢复 | PASS（用户确认） | 用户人工验收；与上文代理定向 BootReceiver 检查分开记录 |
| 小爱语音唤醒与安全的实物 Bluetooth Mesh 控制 | PASS（用户确认） | 用户人工验收；与上文代理进程/服务观察分开记录 |

该确认补齐此前最终 FPK 全新迁移的阶段差异，维持 **RC ACCEPTED**。上文随机数据集 ID、RAW 哈希和归档清单仅属于代理原有场景，不能移用为本次人工补测的原始证据。未补造截图、设备日志或迁移对象身份。长期日用观察仍可继续，不新增机械长时测试门槛。

## 发布与后续操作

候选目录不再重建覆盖。发布前按完整SHA256再次核对，不上传私有日志/备份/连接材料；正式发布文案、各端升级步骤与回退限制见 [发布草案](RELEASE-sync-v3.md)。如后续修复改变产物字节，应冻结新候选并重新核对受影响的安装场景。本 PR 保持人工审核，不自动合并、tag或发布Release。
