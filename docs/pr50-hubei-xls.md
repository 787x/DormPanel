# PR50：湖北大学原版 XLS 导入

## 使用

X08E、Phone 的本地文档选择和 X08E 临时 LAN 上传现在可直接选择 `.xls`。fnOS 网页支持 XLS 文件预览与确认；CLI 使用 `import preview --file 文件.xls`，MCP 使用 `import_preview` 的既有有界 base64 输入。之后仍使用原来的 rows / confirm / cancel；未确认不写入。

未知学期仍须先配置学期，再预览。文件中的钟点不参与计算，日期、时区和夏秋作息完全由 Term Schedule Profile 决定。Android WebDAV 支持精确 XLS 文件与最新 XLS 文件夹；Profile 改变会绕过旧 ETag 缓存重新计算。HA 中转协议保持原样。

替换 CSV 来源时保留 sourceId；相同课程结构和 Profile 产生相同 seriesId、originalStart 和实例身份。fnOS 确认框显示替换实例范围及可能不匹配的课程；覆盖记录沿用既有保留规则。预览 token、15 分钟有效期、workspace version 和事务保护不变。

## 实现与边界

XLS 与 CSV 都先生成单元格矩阵，然后使用同一个湖北大学结构解析器及 Profile Resolver。解析器身份 `hubei-personal-xls-v1` 参与有效指纹，CSV/ICS 原指纹不变。没有 Room schema、同步编码或 Android 包名改动。

评估了 [Apache POI HSSF](https://poi.apache.org/components/spreadsheet/) 的事件读取接口与 [SheetJS](https://docs.sheetjs.com/docs/getting-started/installation/nodejs/)。它们覆盖的 Office 能力远多于本项目所需；本次选择有限范围读取器，避免增加生产依赖及两个平台不一致的第三方行为。这不是通用 Excel 引擎，后续增加记录类型须同时补充两端测试。

仅接收 OLE v3、512 字节扇区中的 BIFF8 Workbook，支持普通链与 mini stream、LABELSST、SST/CONTINUE、压缩/UTF-16 字符串、空单元格、合并范围和多个工作表。工作表名称和顺序不参与选择；多个候选拒绝。合并范围不复制文本，保留原始非空单元格（湖北大学备注行存在这种导出特征）。

资源限制：1 MiB 文件、109 个 FAT 扇区（此范围无需 DIFAT）、32768 条记录、每记录 8224 字节、16 张表、每表 256 行 × 64 列、8192 个共享字符串、单字符串 16384 字符、总共享字符串 262144 字符、2 秒解码期限。链检查循环和边界，所有读取有界。公式、FILEPASS、旧版文本单元格和不支持的数值课程内容明确拒绝；宏、外链、对象从不执行或加载。`.xlsx`、HTML 伪 XLS、加密文件、其他学校及任意 Excel 布局不在支持范围内。

## 隐私与 fixture

仓库仅包含从既有脱敏 CSV 重建的 BIFF8 XLS：空首表、第二张有效课表、合并标题、第三张附表的长 Unicode 字符串跨 SST CONTINUE，保留课程换行、13 系列/131 实例及军事训练备注。附表文本为重复测试文字。原始 XLS 未复制进仓库或上传 NAS；仅在本机读取并比对业务实例，不记录个人表头。

独立工具 xlrd 实际读取原始文件：三张表，第一张 9×8，后两张空。Node 导入原始 XLS 的 13 系列、131 实例，与 CSV oracle 全部实例字段及身份完全一致。

## 验证记录

- Kotlin 核心、X08E/Phone 单元测试通过；包含 XLS/CSV 完整实例对象等价（内置 Profile 覆盖夏秋切换及 9–10/9–11 节）。
- Android 两端 Debug Lint 通过；Release 构建通过。
- X08E 实机 API 28：隔离 testbed 的 CSV 与 XLS WebDAV Profile 变更重算测试 2/2 通过，日用包未安装覆盖。
- Windows Node：191 测试，126 通过、65 平台跳过、0 失败（增加最终负向测试后的结果见补充记录）。
- fnOS 192.168.3.153：Node 22，`/tmp/dormpanel-pr50-isolated` 隔离运行 XLS、Writer、Agent 测试 30/30；覆盖实际 CLI `--file` 与 MCP stdio 的 XLS 预览、取消、确认及 CSV 来源替换。
- 原有数据未做破坏性升级操作；未执行无关的迁移验收和 soak。

候选版本：X08E 0.1.5 / code 9，Phone 0.1.7 / code 9，FPK 0.7.2。使用现有 keystore 生成正式签名候选；证书与项目既定指纹核对。未覆盖安装日用包，因此不报告原位升级通过。

- Phone MIX 2S 实机：隔离 testbed 的 XLS 文件选择、预览、确认、横屏和重启保留测试 1/1 通过。首次两次失败分别来自 Provider 未启动、fixture 白名单遗漏；修复测试白名单并使用现有隔离启动脚本后通过。

### 补充验证与限制

fnOS 完整回归实际运行 192 项：190 通过、1 项跳过、1 项失败。失败是备份测试硬编码旧 appVersion（0.7.1），已随候选版本更新为 0.7.2 并单独重跑备份文件；完整原始输出保存在 evidence/pr50。没有把初次失败记成通过。

正式签名证书 SHA-256：`32d68b5c6ad0bfd1b87aa0d54514ef71c33838e7643eb038488af7ca2add17f7`，两端 apksigner verify 均通过。候选文件位于本地 `artifacts/pr50/`，不提交二进制产物进 Git。

尚未验证：新版 FPK 安装后的浏览器文件选择、日用包原位升级及历史保留的整体验收；fnOS 验证使用真实 NAS 上的隔离服务和 CLI/MCP，Phone 与 X08E 使用隔离 testbed。没有把这些等同于已部署生产应用的验收。

最终补充：修正版本断言后 fnOS 备份回归 36/36 通过；本地 Edge 浏览器隔离服务的 XLS 文件选择、预览、取消、替换 CSV 来源及实例身份保持测试 1/1 通过。该浏览器测试不等同于新版 FPK 已安装到 NAS 的桌面网关验证。
