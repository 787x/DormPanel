# DormPanel Schedule Manager 0.2.1

fnOS Native 可编辑课表与 Sync v2 Writer。Android 0.1.2、DPC2 / DPSS/1 协议、Room 数据库和正式 APK 不变。

## 使用

管理员通过 fnOS 桌面进入。课程卡片支持全部周次/指定周编辑与重置；null 继承、空文本清空，身份保持 sourceId、seriesId、originalStart、anchorDate。管理页支持课程/事件增删、日期停课/补课、来源替换/删除、学期/阶段/节次，以及 ICS / 湖北大学 CSV 后端预览与确认导入。CSV 需要对应学期配置；替换保留旧来源身份与覆盖，不自动下载 locator。

本地保存独立于网络。按需“检查并同步”最多三轮，无自动轮询。状态区分本地保存、待发布、候选待回读、本机已验证发布、观察时间、历史异常与冲突。已发布不代表其他设备已接收。冲突页展示关联单位、全部分支及稳定远端方案；决策只处理冲突关联单位，独立修改继续保留；暂不处理不改课程。

## 管理员配置

页面内查看绑定状态、更新凭据、测试连接。HTTPS 共享目录以 `/` 结束，仅访问其直属 `dormpanel-sync-v2/`。可明确创建空数据集或接收已有数据集，不静默恢复非空本地数据。

切换不同数据集须确认原绑定地址；有本地修改、候选、冲突或异常时拒绝切换，允许时先归档。另有“同一数据集地址迁移”：完整验证已知字节哈希、数据集与祖先闭包后，保留全部本地状态与 writerId。配置与数据库切换间崩溃时按绑定不一致停止。

LAN 自签名 HTTPS 可显式提供从 SSH 等独立可信渠道取得的服务器公钥证书 PEM。仅该连接添加信任并精确匹配服务器证书，TLS 链和有效期校验仍开启；没有 `rejectUnauthorized=false`，不改系统 CA。不要上传私钥；证书变更须重新核验。页面可恢复系统 CA。

凭据只在私有 `etc/webdav.json`；密码留空保留。不向前端响应、课程快照或日志返回认证信息。

## 持久化与同步

首次升级从重新校验的 PR37 图缓存和检查点建立独立 `workspace.json`。旧缓存不充当可写数据库。单一原子文档包括课程、基线、前沿、精确 capture、候选字节、attempt、回执、异常清单和完整冲突输入。唯一临时文件、文件 fsync、rename、Linux 目录 fsync；失败不更新进程内已确认状态。本地队列串行事务，网络在事务外；网络同步/配置互斥，上传期间仍可本地保存。不能用两个进程同时写同一 workspace。

随机 UUID 普通 PUT 不覆盖历史，不使用共享 head/条件 PUT。预检 GET 后、实际写前持久化 attempt，完整解码且逐字节回读才能确认。未知结果不对同路径重复 PUT；换身份仍保留旧路径预期哈希。回执按 capture 重放后续编辑。保留协议图、字节、时间、请求和冲突预算，异常持续回查，不做远端 GC。

合并与 Android ScheduleMerge / ScheduleV2Plan 对齐：来源及原始实例不可分割，导入时间不判胜负，处理删除/覆盖、学期/来源、日期组合/指定周编辑关联。多分支逐对检测再稳定折叠；多重不等价基线停止，等价 tips 不产生无限纯合并。

## 权限边界

包用户运行，仅 Unix Socket；target 0700、Socket 0600，撤销 shared-user/group ACL，失败即停止，不放宽权限。真实网关为 root trim_http_cgi。root 和包用户已有完整代码/数据权限，属于残余信任边界，不能宣称隔离其 Header 伪造。[PR37 审计](../docs/pr37-gateway-security-audit.md)说明该边界。

每个写路由检查管理员、私有传输、用户及完整 Origin 绑定的 CSRF、请求版本、JSON 和 2 MiB 上限。fnOS Host 可能丢端口，页面完整 Origin token 保留精确端口校验。冲突确认绑定完整输入 token，决策前重查远端。拒绝重定向越界、路径穿越、XML 实体、回环/链路本地/元数据目标；LAN 仅为管理员明确配置的 NAS 地址。浏览器不直连 WebDAV；导入文本用 textContent，CSP 禁止内联/外部脚本。

## 构建

需要 Node 22+、npm、官方 fnpack：

```sh
cd fnos-app
npm ci --ignore-scripts
npm test
npm run check
npm run build
fnpack build --directory build/dormpanel-schedule-manager
```

使用应用中心正常手动升级。目标 fnOS 的 install-fpk CLI 对已有实例只报告已安装；install-local 会卸载并清数据，禁止当升级工具。保留 PR37 向导字段；没有向导输入的正常升级保留原配置和独立证书信任。

`node scripts/merge-vectors.mjs` 生成 18 组独立输入/预期结果和两组导入向量，由 JVM FnosWriterParityTest 校验。RealFnosSyncTest 仅显式启用的 pr38-e2e-UUID 隔离目录与 Room 命名空间，不操作正式课表。实测、诊断网络路径及未验证项见 [PR38 验证记录](../docs/pr38-fnos-verification.md)。
