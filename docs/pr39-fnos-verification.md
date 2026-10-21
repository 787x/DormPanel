# PR39 多工作区与连接管理验证记录

日期：2026-10-22（Asia/Shanghai）。基线为已合并 PR38 的 main `37ce072`；分支 `codex/pr39-multi-workspace`。候选版本 0.3.0，未合并、未发布 Release，Android APK、Room、WebDAV 服务及 FN Connect 均未修改。

## 实现与数据边界

- `catalog.json` 原子保存连接配置、工作区描述和 activeId；每个工作区单独保存完整 `workspaces/<UUID>/workspace.json`。所有工作区使用同一原安装 identity，子目录不生成第二个 writerId。
- PR38 迁移使用固定默认 ID，完整复制旧帧并验证候选身份，再提交管理索引。原 workspace、identity、WebDAV 配置及旧归档不删除。迁移前后索引落盘中断、端点更新日志中断均有恢复测试；已注册帧丢失时拒绝创建空帧。新目录父项也 fsync。
- 按工作区隔离网络互斥、课程事务、导入预览、版本和冲突 token；上传期间可切换及编辑，回执继续写入原工作区并按 capture 重放。切换仅保存 activeId，不重置帧；没有后台轮询。
- 已绑定连接修改 URL、凭据或证书前完整验证 dataset、已知哈希和祖先闭包。已观察但尚未确认绑定的历史不能通过修改配置绕过验证。同 dataset 的另一地址必须作为原工作区的端点验证，拒绝重复绑定。
- 本地适配器复用 Sync v2 核心。Linux 目录 fd、逐级 `O_NOFOLLOW`、中间祖先 `O_PATH`、选定目录 dev/inode 校验、有界读取及文件变化检查。协议目录外 0700 暂存、完整文件 fsync、硬链接排他发布只读提交、目录 fsync；EEXIST 不覆盖，未知文件系统不写，崩溃残留不自动清理。
- 本地范围只来自 FPK 私有目录、官方 `TRIM_DATA_SHARE_PATHS` 或 `TRIM_DATA_ACCESSIBLE_PATHS`；浏览器选择授权根 ID 和相对目录。目录浏览拒绝符号链接，不提供任意绝对路径读写 API。密码仅服务端 0600 配置，浏览器响应无密码/证书材料；关闭配置对话框清除临时输入。
- fnOS 系统配置只保留说明入口，不再消费旧 WebDAV 向导。目录授权同样触发 config_callback，因此 0.3 回调允许系统授权完成，并保持页面配置不变。

## 自动化验证

最终 Node 测试共 101 项：Windows 90 PASS、0 FAIL、11 项 Linux 文件系统/权限测试明确跳过；真实 fnOS 官方 Node 22.18.0、普通包用户下 100 PASS、0 FAIL、1 项仅非 Linux 平台测试跳过。

覆盖迁移完整帧（pending 原始字节、capture 后编辑、attempt、abandoned、receipt、冲突、异常、扩展恢复字段及凭据/证书）、迁移索引提交前后中断、连接更新恢复日志、A 候选/B 冲突切换及重启、上传期间切换/编辑/回执、重复绑定、不同数据集与缺闭包端点拒绝、移除配置保留状态文件/历史、缺帧拒绝重建。

安全负向包含管理员、CSRF/Origin/用户、工作区 ID、源/目标/管理版本、跨工作区导入预览、相同恢复帧冲突 token 重放、请求类型和大小、凭据泄漏、授权根/相对路径、符号链接、目录替换、只读/不可读、损坏文件及预算。最大 256 提交本地图可完整校验，第 257 条目拒绝；这不是最大快照容量压力测试。

保留并通过既有固定 DPC2 字节向量、三方合并、课程覆盖、投影、ICS/CSV 导入、候选/回执恢复及端点迁移回归。Python 标准库独立六组固定向量通过。`npm run check` 通过；构建依赖审计为 0 漏洞。本轮未重复 Android 硬件验收或 JVM 全套测试。

## 真实 fnOS 后端与目录 ACL

环境：192.168.3.153、x86_64、fnOS 1.2.0800、4GB RAM、官方 Node 22.18.0。新源码在独立 `/tmp/dormpanel-pr39/` 下以 `dormpanel-schedule-manager` 包用户执行；不把这次独立源码运行冒充已安装 FPK 升级或正式网关 UI 验收。

真实 WebDAV 仅写入合成目录 `dormpanel/pr39-e2e-2197552f-de83-4075-ab60-3671b183fc81/`（另有前一轮独立测试目录保留）。公网测试域名已有 DNS 不稳定背景，本轮使用既有 LAN HTTPS 端点和从独立可信渠道取得的连接级证书；不关闭 TLS，不更改 WebDAV/FN Connect。

`scripts/verify-workspaces.mjs` 在普通包用户下创建两个私有本地目录、两个独立 WebDAV 连接和四个不同 dataset，完成合成课程创建、发布、逐字节回读与完整 DAG 检查。A 保留精确候选/capture 后编辑，WebDAV B 保留真实远端并发分支与本地冲突；多次切换及重新打开 Catalog 后全部帧一致。重复绑定实际 WebDAV dataset 被拒绝。

随后通过 fnOS 系统设置的“访问权限”仅授权上述合成目录，没有授权整个 dormpanel、用户根或日常课表。PR38 配置回调返回旧向导错误，但系统实际记录和 ACL 已生效；正常重启后，`TRIM_DATA_ACCESSIBLE_PATHS` 包含精确测试目录。`scripts/verify-local-webdav.mjs` 使用该官方运行环境，在同一物理 `a/` 目录执行：

1. 包用户本地绑定已有 WebDAV 数据集，编辑并以本地 CommitStore 发布。
2. fnOS WebDAV 回读新文件，完整 DPC2 解码与 DAG 校验成功，writerId 属于本次独立测试安装。
3. 本地工作区保留离线编辑，完整验证后改用 WebDAV 端点并发布；datasetId 和 writerId 不变。
4. 对同一物理数据集再建本地工作区，明确拒绝重复绑定。

此实测发现 fnOS 只给祖先目录遍历权限时，要求 O_RDONLY 打开每一级祖先会误报 EACCES，已以 O_PATH 修复，并补充真实非 root 权限回归。没有以 Root 或放宽整个卷权限解决。

再通过官方页面将同一测试目录改为只读，本地写权限测试按预期失败；撤销该授权后，即使独立测试进程仍持有旧目录列表，重新打开目录也安全失败。未删除任何测试提交；其他工作区文件和旧安装数据不变。旧 0.2.1 回调在只读/撤销时同样报向导错误，系统 ACL 的实际效果已分别验证；0.3 回调兼容修复有自动化验证，仍待候选包升级后再次走完整页面链路。

## 浏览器与标准包

在本地诊断服务上用真实前端完成 1280×800 检查、A 新增保存课程、切到 B、切回 A 保留课程、独立数据源页面、页面刷新恢复。诊断服务经 loopback 代理注入测试管理员 Header，后端连接为 Fake；这不证明真实 fnOS 网关鉴权、普通用户网页负向或真实 FPK 前端已通过。

官方 `fnpack build` 已生成 `artifacts/pr39/dormpanel-schedule-manager-0.3.0.fpk`。最终 SHA-256：`c3e0b42b03b3bf989fa098266efd47088387902f1f9ad88dd6d8991b76526162`；构建目录及原始证据均为本地忽略文件，凭据不进入提交或 Release。

正常升级已从应用中心“手动安装 → 从 NAS 添加”选择候选，当前停在 fnOS 的未经验证应用责任条款确认，等待用户当次同意。**尚未完成 0.2.1 → 0.3.0 正常 FPK 升级**，不将目录外测试源码替换或 install-local 计为升级；未调用 install-local。

已在 NAS 私有目录备份旧 var/etc。现有 0.2.1 正常 stop/start 后仍 running；本轮操作前后旧文件哈希一致：

| 文件 | SHA-256 |
| --- | --- |
| identity.json | `1ed864692357d66528a3e617d01e7716c33ce837723d84f996a6f8157f3233d3` |
| workspace.json | `05334436f3af2d730f9796799ba214a79791e26720dd2d75417950bcd70ae532` |
| webdav.json | `91e14f8478487c26065bbcdfe156eaccc3ae639b1b53c045521d2a1bc3b3ecd8` |

## 待完成与限制

- 责任条款确认后的正常 FPK 原位升级、升级后默认帧/凭据/identity 哈希比对、标准包重启及真实 FPK 页面全流程仍待执行。迁移断点通过的是确定性注入及 NAS Node 测试，不是设备实际断电。
- 尚未用未修改的 Android 0.1.2 实机客户端接收这次新本地提交；已完成固定字节向量和真实 WebDAV 回读互操作，不将其写作完整三端产品验收。
- 未实测独立其他品牌 WebDAV、ARM、磁盘满/断电、复杂 ACL/挂载切换压力及多个最大图工作区的内存峰值。当前启动会加载各已保存帧；大量大图工作区的内存与写放大仍需压力验证。
- 撤销/只读后仍可能因其他 ACL 或文件所有权保留部分系统访问权；应用不会绕过系统 ACL，也不会把环境变量列表当作 Root 权限。重新启动会更新系统目录列表。
- 回滚旧版只能读取保留的迁移前旧帧，不能自动包含 0.3 新编辑；新工作区目录和 catalog 必须一起备份。保留的旧配置副本含凭据，应继续受私有目录权限保护。

官方路径和授权约定依据：[环境变量](https://developer.fnnas.com/docs/core-concepts/environment-variables/)、[应用权限](https://developer.fnnas.com/docs/core-concepts/privilege/)、[应用资源](https://developer.fnnas.com/docs/core-concepts/resource/)。
