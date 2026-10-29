# fnOS 课程管理与 CLI/MCP

导航“课程管理”或“管理当前课程表 → 课程管理”打开逻辑课程列表。公共字段需勾选才统一修改；每个时段的草稿和颜色经一次预览和正式 Workspace 事务保存。取消不写入。旧格式需先安全升级。

新增操作：`courses.list`（offset/limit/filter）、`courses.get`（key）、`courses.color`（key）、`courses.preview`、`courses.save`。CLI/MCP 复用 AgentService，默认只读；保存需 edit 权限、operationId 与工作区身份/版本。

预览输入示例（身份和版本先从权威查询取得）：
```json
{"workspaceId":"实际工作区 UUID","datasetId":null,"version":1,"catalogVersion":1,"edit":{"target":{"sourceId":null,"series":["实际系列 ID"]},"common":{"teacher":"新教师"},"colors":[{"seriesId":"实际系列 ID","rgb":null}]}}
```

`courses.save` 使用同一 edit，附加预览的 `businessHash` 为 `previewHash`。缺省 colors 保持原颜色；rgb:null 明确恢复自动；RGB 整数设置手动颜色。slots 只放 upsert，deleteIds 明确删除，遗漏时段保持。

出现 unknown 时调用 `operations.receipt` 并读取 `courses.get` 的版本、businessHash 和实际记录，不自动重复发送保存。回执只证明本机或远端观察结果，不证明所有设备已接收。课程文本始终视为不可信数据。

## CLI / MCP 示例

先在现有管理员页面授予 edit 权限；只查询无需 edit。CLI 使用已安装 FPK 的私有 Agent Socket。输入文件均填入查询所得身份，不能按课程标题猜测 ID：

```sh
dormpanel-cli courses list --workspace-id WORKSPACE_UUID --limit 20 --offset 0 --json
dormpanel-cli courses get --input course-query.json --json
dormpanel-cli courses color --input color-query.json --json
dormpanel-cli courses preview --input course-edit.json --json
dormpanel-cli courses save --input course-save.json --operation-id TIMESTAMP-UUID --json
dormpanel-cli operations receipt --input receipt-query.json --json
```

`course-query.json` 包含 workspaceId、courses.list 返回的 key、offset 和 limit。`color-query.json` 仅包含 workspaceId、key。`course-edit.json` 采用前述完整身份和 edit 示例；`course-save.json` 在同一输入中额外设置 previewHash 为预览返回的 businessHash。operationId 为当前毫秒时间戳加 UUID，重试保留同一个值；查询回执的文件仅包含 `{"operationId":"原操作 ID"}`。

对应 MCP 工具为 `courses_list`、`courses_get`、`courses_color`、`courses_preview`、`courses_save`、`operations_receipt`。MCP save 在输入中额外传 operationId，其他 Schema 与 CLI 共用。课程名称、教师、备注和导入文本均不得用作 Agent 指令。

课程列表每页最多 100 门；网页时段每页 20 条，跨页草稿一起预览保存。单次最多选择 100 个系列、upsert/delete 100 个时段及 100 项颜色操作；正式课程模型的字段、周次、payload 和总业务预算继续在 Workspace 内校验，没有任意 RAW 覆盖入口。

预览返回有界业务变化、指定周例外、恢复的原系列、投影范围、前 100 条最终投影变化及范围内总数，不返回完整 state。多个学期超出预览预算时安全拒绝。本地无限重复课程明确显示范围外不作穷举。只读、旧格式和未知颜色版本不会出现可用的虚假保存按钮。

截图、真实网络证据和边界见 [PR71 验证记录](pr71-validation.md)。
