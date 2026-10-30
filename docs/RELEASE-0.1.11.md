# DormPanel Android v0.1.11

X08E **0.1.11 / versionCode 14**；Phone **0.1.10 / versionCode 12**。Android Stable / Latest，minSdk 28、targetSdk 37，正式签名非 debuggable。

## 首次交付
- Phone：统一课程颜色、手动选色和共享颜色，Sync v3 可扩展共享记录与课程安排，更完整课程管理；近日、一周桌面小部件，近日独立滚动、日期/教学周和刷新修复，同步快捷入口、日期选中体验及 ICS 导出。
- X08E：新版颜色及课程安排兼容读取；今日、近日首页卡片，支持既有逻辑网格调整尺寸、拖动、持久化，以及触控、日期导航与性能修复。保留 timetable、calendar 和原桌面功能。
- 已发布能力：原有课表布局、横划导航、Phone 今日课程紧凑/宽版小部件和基础课程管理已随 v0.1.10 交付，本轮继续保留。

## 下载与升级
[Android Release](https://github.com/787x/DormPanel/releases/tag/v0.1.11) 提供 DormPanel-X08E-0.1.11.apk、DormPanel-Phone-0.1.10.apk 与 SHA256SUMS.txt。
核对哈希和签名后，分别使用系统安装器或 adb install -r 原位升级对应 APK；包名及批准签名保持不变，versionCode 分别从 13→14、11→12。不要卸载、清数据或重置 Keystore。HA 保持 0.1.2，本轮不构建、不附带 HA ZIP。fnOS 0.8.2 独立 Pre-release。

## 同步与迁移边界
新版支持 Sync v3 扩展记录 DPV3/4。原有 v2 / DPV3/3 绑定不会因安装自动迁移；旧 v3 与 shared v3 目录不自动持续桥接。完整共享颜色和课程安排需要所有参与客户端兼容，并明确绑定新数据集。迁移前先处理本地未发布修改、Pending、UNCERTAIN、冲突及已知设备状态。某一端升级或远端回读成功不代表所有设备已接收。旧客户端不得忽略新业务类型后写回破坏新版状态；不兼容客户端应保留旧绑定，先升级再按迁移流程加入。旧数据和历史不自动删除。本轮仅发布二进制，不操作日用数据集迁移。

ICS 是外部日历交换，不是 DormPanel 完整业务备份；最多 366 天、20,000 事件、8 MiB，另有 5 秒生成预算。ICS 不保留完整来源、颜色、覆盖与历史，不保证完整回导为原课程结构；当前导入另受 256 VEVENT / 1 MiB 限制。

## 验收证据与边界
复用 [PR71](pr71-validation.md)、[PR72](verification/pr72/README.md)、[PR74 修复](verification/pr74/review-fixes/README.md)及 [PR75 ICS](ics-export.md) 的专项验收；PR73 证据见[缓存有效期](verification/pr73-cache-expiry/README.md)、[教学周](verification/pr73-factory-week/README.md)与[审查修复](verification/pr73-review-fixes/README.md)。本轮只补正式产物版本、身份、签名、包内容及哈希门禁，不重复增长、故障矩阵、Widget 全套或长期 soak。PR75 已记录的 Kotlin 缓存读取断言及 Node 增长/worker 基线失败保持披露，不声称全量回归全绿。本轮未安装日用 APK/FPK，未更改 Writer、工作区、凭据或同步绑定；历史隔离验收不等于本轮正式候选安装 PASS。
