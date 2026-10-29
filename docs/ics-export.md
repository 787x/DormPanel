# ICS 课程表导出 v1

导出是外部日历事件交换，不是业务备份或双向共享。只读取当前 Workspace / Phone 持久业务快照，不执行同步或 Writer。两端分别使用正式 `ScheduleProjection.week()` / `projectWeek()`。

- 日期为所选 IANA 时区的闭区间；实际窗口是开始日零时至结束日次日零时。事件与窗口存在严格时间交集即纳入，保留完整起止时间。
- 最多 366 天、20000 个事件、8 MiB UTF-8、5 秒生成预算；任何超限整体失败，无部分成功。周查询结果按实际身份去重。
- 课程身份为 JSON 数组 `["course", sourceId|null, seriesId, occurrenceId, anchorDate]`，日历事件为 `["event", id]`；SHA-256 小写十六进制加 `@ics.dormpanel` 构成 UID。身份与显示标题、地点和时间解耦。原始实例 ID、安排 ID、补课 anchor 区分不同实际事件。
- 按身份字符串排序。UTC `yyyyMMdd'T'HHmmss'Z'`、可注入 DTSTAMP；不生成 RRULE。RFC 5545 无亚秒表达，遇到亚秒业务起止时间整体拒绝，避免静默丢失精度。
- UTF-8、CRLF、终止 CRLF；TEXT 依次转义反斜杠、换行、分号、逗号。每物理行最多 75 字节，连续行空格占一个字节，不拆 Unicode 字符；拒绝不受 ICS 支持的控制字符及无效的最终起止时间。
- 固定 VERSION、PRODID、CALSCALE；SUMMARY 必有。LOCATION 和 DESCRIPTION 仅非空时存在，描述依次包含非空教师、备注、节次，以换行分隔。
- Phone 通过 CreateDocument(text/calendar) 保存预览文件；流关闭后才提示成功。失败尽力删除目标。配置重建保留内存请求，进程丢失后明确拒绝旧请求，提示检查选择器是否创建空文件，不触碰未知目标内容，不使用其他导出请求替代。
- fnOS 管理员私有 Socket GET `/api/export/ics` 必须带 workspaceId、version、start、end、zone、includeEvents、origin。版本不符返回 409。文件是预览时下载到浏览器的有界 Blob；保存仍使用此快照，界面标明工作区。响应使用 text/calendar、固定格式附件名、no-store、nosniff；课程文本不参与头或路径。
- 默认全部有效课程，可选日历事件。不推测学期结束日期，不改变选择学期、课程或同步数据。
- DormPanel 当前 ICS 导入限制 256 VEVENT、1 MiB；超过任一限制的标准外部导出可能无法直接回导。ICS 不保留分组、颜色、来源、覆盖规则、同步历史或冲突。

# PR75 验收记录

使用隔离 Room 测试库、隔离 fnOS 临时实例、独立 testbed APK；没有升级日用 APK/FPK、修改版本或访问真实同步目录。

已执行（2026-10-29 至 2026-10-30）：
- Kotlin 导出专项 5 项全部通过：共享旧协议样本及复杂安排固定字节与 Node 完全一致；非连续周、替换、复制、停用、补课、重叠、跨周午夜、中英文特殊文本、两个时区、DST 缺口、事件预算、亚秒拒绝和无效记录拒绝。
- Node 导出/下载边界专项 6 项全部通过：独立 ical.js 解析、UID 唯一及标题编辑稳定、UTF-8 行长、注入、范围拒绝、空课表和超量拒绝；正式 Socket 管理员/工作区/版本/跨源边界与读取失败；导出前后 frame 字节表示不变。
- Edge/Playwright 专项通过：正式后端和私有测试 Socket 网关代理，预览并触发真实浏览器下载；预览后修改后端课表，下载仍包含原快照课程；请求期间修改日期会要求重新预览。
- Android 9 / 1280×800 模拟器：取消、成功写入及注入写入失败 3 项在深色 + 1.3 倍字体和浅色下通过，课程不变；另有持久读取失败专项通过。截图检查日期、保存和错误提示可见。
- 实际系统 DocumentsUI Downloads 保存通过；在系统选择器打开期间重建 Activity 后，旧预览请求仍正确保存。最终文件 1045 字节，经独立 ical.js 解析为 4 个 `ICS 测试课程` 事件。此测试需 `interactiveSaf=true`，由操作者点击系统保存按钮；默认测试跳过该手工流程。
- Phone Debug、隔离 testbed APK 和 instrumentation APK 构建通过；fnOS `npm run build`、JS 语法检查通过；NAS 隔离 `/tmp/dormpanel-pr75-fpk-*` 使用官方 fnpack 成功构建，最终 FPK 校验通过（54 个源文件一致、管理员 Socket 网关配置保留），未安装。
- 首轮全量 Kotlin：257 项，1 失败、2 跳过。失败为 `SyncV3EngineTest.publishes1024AndRotatesWithoutGlobalCommitLimit` 第 201 行缓存 contentReads 期望 0、实际 1；已在独立 PR74 原始基线复现同一断言。
- 全量 Node：415 项，333 通过、79 跳过、3 失败。增长测试 `v3-engine.test.mjs:109` 同样期望缓存读取 0、实际 1；另两项为 `node --test` 将无参数 worker 辅助脚本作为测试执行。这 3 项均已在独立 PR74 原始基线复现；不修改同步实现或测试期望掩盖失败。

剩余验证范围和风险：
- 浏览器验收在隔离服务与管理员网关测试代理进行，未在 NAS 日用应用中部署新版本；NAS 实际网关部署后的下载需后续候选安装验收。
- MIX 2S 额外真机测试受 MIUI 的跨应用测试 Provider / 启动授权影响，未完成；上述 Phone 验收结果来自 Android 9 模拟器。未将该真机尝试报告为通过。
- 未覆盖每一种 SAF Provider 的物理空间不足、进程被系统杀死后返回、每种 IANA 历史时区转换。进程丢失预览字节时明确拒绝请求；写入失败尽力清理并提醒核对目标。
- 现有全量回归存在上述基线失败，不能声称全量测试全绿。导出定向验收通过，可供代码评审；不自动合并、发布或升级日用设备。
