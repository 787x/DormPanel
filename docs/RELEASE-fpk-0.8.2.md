# DormPanel Schedule Manager 0.8.2

[fnOS fpk-v0.8.2](https://github.com/787x/DormPanel/releases/tag/fpk-v0.8.2) 为 Pre-release，不替换 Android Latest。

## 首次交付
相对 0.8.1：共享管理、历史及维护界面改进，通用共享记录与课程安排、共享颜色、完整课程管理、多时段原子编辑、网页及 CLI/MCP 管理，PR71 UI/竞态修复和 ICS 导出。原有 DPV3/3 分代同步、备份及安全恢复已在 0.8.1 发布，继续保留。

## 安装与升级
下载 dormpanel-schedule-manager-0.8.2.fpk 并核对 SHA256SUMS.txt。依赖 fnOS 官方 Node 22（nodejs_v22）；网页管理员私有 Socket 网关与目录授权边界保持不变。
0.8.1 → 0.8.2 使用官方应用中心上传较高版本 FPK 原位升级，保留工作区、Writer、备份、权限、连接和凭据。先保留可恢复备份，升级后核对安装身份、状态及服务。不要卸载，也不要使用会先卸载的 appcenter-cli install-local 绕过升级。合法旧版本备份（含 0.8.1）仍被接受；恢复仍要求既有安装身份与依赖验证。本轮未执行日用安装或恢复。

## 同步与迁移边界
新版支持 Sync v3 扩展记录 DPV3/4。原有 v2 / DPV3/3 绑定不会因安装自动迁移；旧 v3 与 shared v3 目录不自动持续桥接。完整共享颜色和课程安排需要所有参与客户端兼容，并明确绑定新数据集。迁移前先处理本地未发布修改、Pending、UNCERTAIN、冲突及已知设备状态。某一端升级或远端回读成功不代表所有设备已接收。旧客户端不得忽略新业务类型后写回破坏新版状态；不兼容客户端应保留旧绑定，先升级再按迁移流程加入。旧数据和历史不自动删除。本轮仅发布二进制，不操作日用数据集迁移。

ICS 是外部日历交换，不是 DormPanel 完整业务备份；最多 366 天、20,000 事件、8 MiB，另有 5 秒生成预算。ICS 不保留完整来源、颜色、覆盖与历史，不保证完整回导为原课程结构；当前导入另受 256 VEVENT / 1 MiB 限制。

## 验收证据与边界
复用 [PR71](pr71-validation.md)、[PR72](verification/pr72/README.md)、[PR74 修复](verification/pr74/review-fixes/README.md)及 [PR75 ICS](ics-export.md) 的专项验收；PR73 证据见[缓存有效期](verification/pr73-cache-expiry/README.md)、[教学周](verification/pr73-factory-week/README.md)与[审查修复](verification/pr73-review-fixes/README.md)。本轮只补正式产物版本、身份、签名、包内容及哈希门禁，不重复增长、故障矩阵、Widget 全套或长期 soak。PR75 已记录的 Kotlin 缓存读取断言及 Node 增长/worker 基线失败保持披露，不声称全量回归全绿。本轮未安装日用 APK/FPK，未更改 Writer、工作区、凭据或同步绑定；历史隔离验收不等于本轮正式候选安装 PASS。
