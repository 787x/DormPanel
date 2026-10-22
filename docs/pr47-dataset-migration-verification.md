# PR47 验收记录

日期：2026-10-23（Asia/Shanghai）。基线 main / PR46 `d2f983e`；候选 FPK 0.6.0、Android 0.1.4。不合并、不创建 tag、不发布 Release。

## 已执行

| 项目 | 实际结果 |
| --- | --- |
| Windows Node 全套 | 177 项，114 通过、63 个 Linux 专项跳过、0 失败。[日志](evidence/pr47/windows-node-tests.txt) |
| fnOS 官方 Node 22，非 root 包用户，隔离目录 | 串行全套 175 项，174 通过、1 项平台跳过、0 失败。[日志](evidence/pr47/fnos-node-tests.txt) |
| 最终迁移、观察路径及写 API 定向复核 | 27 项全部通过，覆盖完整业务夹具、归档 / 候选 / 新地址、晚到提交、漏列、重启、停写及负向权限。[日志](evidence/pr47/fnos-targeted-node-tests.txt) |
| JVM / Android 构建 | schedule-core 144 项、app 198 项 JVM 通过；Phone JVM 任务无独立测试源。两款 Debug / Release、核心 instrumentation APK 构建通过。[日志](evidence/pr47/android-build.txt) |
| Android 9 / 1280×800 模拟器，Room | 11 项通过：换库事务及检查点故障、持久预检、原有首次绑定与快照回归。[日志](evidence/pr47/room-regression.txt) |
| 真正进程终止及重启 | 在预检、准备、Room 提交、SharedPreferences 写入后分别 force-stop；重新启动后继续。另在 Room 提交与目录设置之间正常重启模拟器，最终原课程 / Writer / 恢复档案保留，独立新 witness 正确，恢复目标同步。[日志](evidence/pr47/process-reboot.txt) |
| 浏览器 1280×800 | 21 项通过，包含历史 / 回退、换库入口、人工确认来源、零回收提示、错误恢复入口。[日志](evidence/pr47/frontend-tests.txt)、[换库截图](evidence/pr47/migration-1280x800.png) |
| 实际 HTTPS WebDAV | FPK 在 NAS 新建隔离目录，旧多提交图 → 完整归档 → 单根候选 → 经实际目标地址验证并切换新工作区；同一 Android 9 模拟器上的两份独立 Room 经该公网地址保留 Writer 换库。[网络记录](evidence/pr47/network-verification.txt) |
| 换库后的双向互操作 | 两份 Android Room 分别编辑 / 发布，FPK 接收双方课程后发布自己的编辑，两份 Room 都接收成功；旧提交哈希和数量不变，完整归档保留。**这是模拟器与真实 NAS / WebDAV 的互操作，不是两台 Android 实机 LIVE PASS。** |
| 官方 FPK | npm check / build、官方 fnpack、最终包 22 份 src / web / package 与源文件逐字节核对通过，生产依赖审计 0 漏洞，无 Playwright。[包检查](evidence/pr47/package-verification.txt) |
| Android 签名 | 两款 APK 均通过实际 apksigner / aapt 检查：原 applicationId、minSdk 28、targetSdk 37、non-debuggable、原批准证书。[结果](evidence/pr47/release-verification.txt) |

Node 全套完成后单独回收完整日志，再定向复核最终修改；没有重复四小时 soak。

## 数据安全证据

迁移测试使用全部八个业务集合的复杂协议夹具，并添加独立课程 / 事件。比较 `stateBytes` / `ScheduleMerge.same` 全字段及稳定身份；新 dataset 从单根开始，迁移本身没有 PUT 业务新提交。后续 Android / FPK 编辑才产生新祖先链。

Room 测试在 id 21、96、102、200 写入时注入 SQLite 失败，检查事务回滚后仍是原 dataset，恢复资料与课程不丢失。重新打开数据库及真正 force-stop / 正常模拟器重启覆盖跨 SharedPreferences 间隙；暂停由 Room 状态决定，所有后台同步共用保护。

FPK 测试覆盖 catalog 已提交而响应中断的幂等恢复，原工作区编辑 / stage / publish / resolve / 配置变更拒绝，目标变化及旧库新增提交转入重新检查，异常观察路径跨重启和漏列仍须回查。管理员确认不会跳过远端验证。旧源原始 DPC 按全部哈希验证不变，没有删除旧记录后重新绑定。

完整历史归档及 PR46 `verified.json` 原样保留。新的迁移日志独立保存，不把旧库哈希写入新库 witness。

## 候选与边界

本地最终文件：`artifacts/pr47/dormpanel-schedule-manager-0.6.0.fpk`、`artifacts/pr47/release-final/DormPanel-X08E-0.1.4.apk`、`DormPanel-Phone-0.1.4.apk`，均未发布。实际 SHA-256 见包检查、签名校验记录及本地 sidecar；二进制和带凭据的执行工具不提交。

侧边浏览器只读确认测试机已安装实例仍为 **0.4.4**，未升级该实例、未改其工作区及课程。真实 NAS 测试运行独立候选源码 / 包用户；另一个新建隔离网络命名空间以目录所属测试用户执行实际 WebDAV 互操作，不等同已安装 FPK 网关 / 原位升级验收。共享目录的默认 ACL 仅在新建测试子目录内处理，没有放宽已有目录授权。

后续连接 X08E 和 MIX 2S Phone，独立测试 APK 的 Room 换库及恢复专项分别 2 项通过（[X08E](evidence/pr47/physical-room-x08e.txt)、[Phone](evidence/pr47/physical-room-phone.txt)），包含最终本机验收记录的持久化。没有覆盖日用应用数据。两台设备的独立测试 APK 已完成直接公网 HTTPS WebDAV 换库、四阶段 force-stop 恢复，并正常重启实际设备（分别核对启动身份变化）后继续编辑 / 发布 / 接收。FPK 接收两端后发布新编辑，两台均接收通过。原旧库 2 个提交及其哈希、完整归档仍保留。此为三端同步核心实机联调；没有执行两台实机正式应用的原位升级与手势 / 触控换库界面验收。Android 共享设置入口由两款应用共用、编译通过；本次没有将模拟器的 Room 测试当作实机 UI 验收，也没有做真正 2GB 设备的最大 16MiB 快照内存压力测试。

没有证明源 WebDAV 目录已经在 NAS 服务端停写，没有把“管理员确认”当作永久停写证明。真实日用公网课表服务、ICS 文件夹及原旧库未用于破坏性测试。隔离测试的旧库、候选和归档同样保留；**实际磁盘回收为 0 字节**。退役状态只说明隔离换库及管理员登记流程完成，人工清理条件仍须另行落实。

## 补充：物理设备最终联调

两台均通过普通网络直接访问原测试公网 WebDAV，未使用 USB 隧道或自签名测试连接。测试只使用独立 APK、独立 Room 命名空间及独立 NAS 数据集，未改日用课表。

- [X08E 换库](evidence/pr47/physical-network-x08e-migrate.txt)、[重启后编辑](evidence/pr47/physical-network-x08e-edit.txt)、[接收 FPK 修改](evidence/pr47/physical-network-x08e-receive.txt)。
- [Phone 换库](evidence/pr47/physical-network-phone-migrate.txt)、[重启后编辑](evidence/pr47/physical-network-phone-edit.txt)、[接收 FPK 修改](evidence/pr47/physical-network-phone-receive.txt)。
- [FPK 接收并发布、核验旧库及归档](evidence/pr47/physical-network-fpk-publish.txt)。
- [X08E 四阶段实际进程终止](evidence/pr47/physical-process-x08e.txt)、[Phone 四阶段实际进程终止](evidence/pr47/physical-process-phone.txt)。

正常重启分别通过 boot_id 前后变化及 sys.boot_completed=1 确认；之后的独立测试客户端重新打开原数据库继续同步通过。管理员登记完成与服务端不可写仍分别判断，物理磁盘回收仍为 0 字节。
