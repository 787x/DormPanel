# ICS 课程表导出 v1

导出是外部日历事件交换，不是业务备份或双向共享。只读取当前 Workspace / Phone 持久业务快照，不执行同步或 Writer。两端分别使用正式 `ScheduleProjection.week()` / `projectWeek()`。

- 日期为所选 IANA 时区的闭区间；实际窗口是开始日零时至结束日次日零时。事件与窗口存在严格时间交集即纳入，保留完整起止时间。
- 最多 366 天、20000 个事件、8 MiB UTF-8、5 秒生成预算；任何超限整体失败，无部分成功。周查询结果按实际身份去重。
- 课程身份为 JSON 数组 `["course", sourceId|null, seriesId, occurrenceId, anchorDate]`，日历事件为 `["event", id]`；SHA-256 小写十六进制加 `@ics.dormpanel` 构成 UID。身份与显示标题、地点和时间解耦。原始实例 ID、安排 ID、补课 anchor 区分不同实际事件。
- 按身份字符串排序。UTC `yyyyMMdd'T'HHmmss'Z'`、可注入 DTSTAMP；不生成 RRULE。RFC 5545 无亚秒表达，遇到亚秒业务起止时间整体拒绝，避免静默丢失精度。
- UTF-8、CRLF、终止 CRLF；TEXT 依次转义反斜杠、换行、分号、逗号。每物理行最多 75 字节，连续行空格占一个字节，不拆 Unicode 字符。
- 固定 VERSION、PRODID、CALSCALE；SUMMARY 必有。LOCATION 和 DESCRIPTION 仅非空时存在，描述依次包含非空教师、备注、节次，以换行分隔。
- Phone 通过 CreateDocument(text/calendar) 保存预览文件；流关闭后才提示成功。失败尽力删除目标。配置重建保留内存请求，进程丢失后明确拒绝旧请求并尽力清理目标，不使用其他导出请求替代。
- fnOS 管理员私有 Socket GET `/api/export/ics` 必须带 workspaceId、version、start、end、zone、includeEvents、origin。版本不符返回 409。文件是预览时下载到浏览器的有界 Blob；保存仍使用此快照，界面标明工作区。响应使用 text/calendar、固定格式附件名、no-store、nosniff；课程文本不参与头或路径。
- 默认全部有效课程，可选日历事件。不推测学期结束日期，不改变选择学期、课程或同步数据。
- DormPanel 当前 ICS 导入限制 256 VEVENT、1 MiB；超过任一限制的标准外部导出可能无法直接回导。ICS 不保留分组、颜色、来源、覆盖规则、同步历史或冲突。

# PR75 验收记录

使用隔离 Room 测试库、隔离 fnOS 临时实例、独立 testbed APK；没有升级日用 APK/FPK、修改版本或访问真实同步目录。

已执行：
- Kotlin 导出专项：共享旧协议样本及复杂安排固定字节与 Node 完全一致；非连续周、替换、复制、停用、补课、重叠、跨周午夜、中英文特殊文本、两个时区与 DST 缺口。
- Node：独立 ical.js 解析、UID 唯一及标题编辑稳定、UTF-8 行长、注入、范围拒绝；正式 Socket 管理员/工作区/版本/跨源边界，导出前后 frame 字节表示不变。
- Edge/Playwright：正式后端、私有测试 Socket 网关代理，预览并触发真实浏览器下载，核对 VCALENDAR 文件。
- Android 9 / 1280×800 模拟器：三个 Espresso/Intent 专项验证取消、成功写入及注入写入失败，课程不变。实际系统 DocumentsUI Downloads 保存成功，并读取生成文件；该手工样本为空课表（109 字节），复杂课程由核心固定向量验证。
- Phone Debug、隔离 testbed APK 和 instrumentation APK 构建。

限制与待补：
- 实际 NAS 网关部署与 Phone 真机深浅色/较大字体、进程重建与保存器低空间的完整交互矩阵尚未验收。
- 全量 schedule-core JVM 测试首次运行中，既有 `SyncV3EngineTest.publishes1024AndRotatesWithoutGlobalCommitLimit` 失败；尚未完成基线复现，不将其声称为既有缺陷或本 PR 回归。
- 当前尚未满足全部 Done Criteria，PR 保持 Draft，禁止转 Ready 或合并。
