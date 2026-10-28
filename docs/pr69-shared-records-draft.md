# PR69 通用共享记录：候选协议与实现进度

当前为未完成的 Draft。DPXR/1 是通用记录块的候选规范，尚未成为正式共享状态或活动同步协议。不能用本记录块替换 DPV3/3 RAW，也不能声称已完成课程颜色同步。

## 已实现的记录块

所有整数为有符号大端 I32。顺序为 ASCII `DPXR`、版本 1、记录数量、记录。记录依次包含长度前缀 typeId、长度前缀 recordId、schemaVersion、required（0/1）、依赖数量、按字节升序排列的依赖 typeId、长度前缀 payload。文本限定 ASCII，从而 Kotlin 与 Node 排序、长度和身份字节一致。

- typeId：`[a-z][a-z0-9]*(?:[.-][a-z0-9]+)+`，最多 96 字节。
- recordId：`[A-Za-z0-9][A-Za-z0-9._:-]{0,199}`，由适配器分配稳定身份。
- schemaVersion：1..2147483647。required 是影响安全写入的能力标记，不代表客户端理解了可透传内容。
- 记录按 `(typeId, recordId)` 的 ASCII 字节序排序；重复身份拒绝。每记录最多 16 个不重复依赖，payload 最多 65536 字节；最多 4096 条记录，整个记录块最多 1048576 字节。
- payload 不可为 null；空 payload 是否合法由类型校验器决定。删除为记录缺失；空 payload 与删除不同。
- 解码必须重编码精确相等，拒绝非规范排序、尾随字节、非法版本与超预算输入。

`businessHash` 当前仅为候选记录块 SHA-256，**不是**已经集成的全状态 BusinessStateHash。活动 DPBS/1 未改动。

## 类型注册示例

Node：`new Registry([courseColor, {typeId:'example.note', schemaVersion:1, maxPayload:32, validate(record, records) { /* 校验 payload 与引用 */ }}])`。
Kotlin 实现 `SharedTypeAdapter`，注册到 `SharedTypeRegistry`。无需修改通用记录编解码器或合并引擎。尚无业务投影/特殊冲突适配接口；引用检查可使用 validate 的 records 参数，课程关联尚未接入。

Registry 的 replace/remove 限制为已注册且版本完全受支持的类型。未知可选记录可解码、保留；未知必需记录、未知依赖或依赖存在不支持版本时，全状态写入保守拒绝。未知可选类型不获得业务写入权限。没有运行时插件加载。

默认三方合并以 `(typeId, recordId)` 为身份，对原始规范记录保守合并。双端一致、单端变化自动采用；双端不同变化（包括删除与修改）返回显式冲突。调用者必须处理 conflicts，不能将部分 records 当作已解决状态提交。合并结果仍需 Registry 校验引用及能力。

`course.color` schema 1 候选 payload：`00` 为自动；`01 RR GG BB` 为手动 RGB。自动 RGB 是 recordId ASCII 字节 SHA-256 前三字节。尚未接入 CourseBatch 逻辑身份与 Phone 缓存迁移，因此不是可交付的产品课程颜色接口。

## 固定向量与实际验证

两端测试使用相同的完整十六进制记录块，包含 course.color/local-1 手动 010203 和 test.note/one 的 opaque 字节。SHA-256 为 `426234c80e57aade8b5af6d5df87cc997c31c09cb7209790fef3906cd97aa055`；local-1 自动 RGB 为 59e27c。

2026-10-28 实际执行：Node 专项测试 5 项通过；JVM SharedRecordsTest 3 项通过；Phone 与 X08E app assembleDebug 通过。测试没有访问正式课表、绑定、WebDAV 或设备。

## 尚未完成的交付与兼容边界

- 版本化完整共享状态与新对象格式、隔离协议目录及旧 Writer 拒绝保护尚未实现；现有 DPSS/1、DPC2、DPV3/3 未修改。
- Room/Workspace、正式 Writer、捕获与回执、Pending 恢复、Checkpoint/LATE、缓存、历史、备份尚未接入。记录块测试不能证明这些路径保留扩展。
- 显式可恢复数据集升级、迁移预览/确认/取消、旧 Writer 迟到写入处理尚未实现。未改变任何现有绑定。
- 课程稳定逻辑身份、引用/关联冲突、Phone 颜色迁移预览和受保护的持久化业务接口尚未完成。
- 未执行 Room/Workspace 故障恢复、双端 WebDAV 互操作。第二类型仅证明记录块与注册层可扩展，尚不能证明同步链路可扩展。

不得将该 Draft 视为 PR69 Done Criteria 已满足，不应在此状态接入 PR70/71 或启用正式迁移。未来 Todo、Memo、偏好或附件同步均未启用。
