# FPK 0.7.1 CLI 与 MCP 管理接口

本版随包安装 `bin/dormpanel-cli`、`bin/dormpanel-mcp`，不依赖系统 PATH。主进程仍由 fnOS 生命周期启动，持有原来的 Writer 锁、Catalog、Workspace 和网络状态。两个客户端只请求 `target/agent.sock`，不会自动启动应用或初始化存储。原网页网关继续使用 `app.sock`，未增加 TCP 或 HTTP MCP 服务。

## fnOS 实际入口

在 NAS 管理员 SSH 会话执行（`sudo` 在这里允许交互询问 NAS 管理员口令）：

```sh
sudo -u dormpanel-schedule-manager /var/apps/dormpanel-schedule-manager/target/bin/dormpanel-cli help
sudo -u dormpanel-schedule-manager /var/apps/dormpanel-schedule-manager/target/bin/dormpanel-cli health --json
sudo -u dormpanel-schedule-manager /var/apps/dormpanel-schedule-manager/target/bin/dormpanel-cli workspaces list --json
sudo -u dormpanel-schedule-manager /var/apps/dormpanel-schedule-manager/target/bin/dormpanel-cli schema --json
```

`help`、`version`、`schema` 可以在应用停止时读取安装文件提供的信息；其他命令必须连接已运行的 FPK。不要直接运行 `server.mjs`，也不要为 CLI/MCP 建立第二套服务、var 或可写 Catalog。

命令采用 `<领域> <操作>`，也接受同名点分操作。`--json` 输出单个 JSON 对象，默认输出缩进 JSON；正文默认从 stdin 输入，也支持 `--input /明确/本机/文件.json`。仅接受有界普通文件，拒绝符号链接和特殊设备。WebDAV 密码、备份口令只放 stdin 或受保护的输入文件，不提供密码命令行选项。脚本调用无正文的命令时可重定向 `</dev/null`，避免等待交互输入。

```sh
# 示例工作区必须换成 workspaces list 返回的实际 UUID。
printf '%s\n' '{"workspaceId":"00000000-0000-4000-8000-000000000001","table":"entries","offset":0,"limit":20}' |
  sudo -u dormpanel-schedule-manager /var/apps/dormpanel-schedule-manager/target/bin/dormpanel-cli records list --json
```

查询工作区的 `sync status` 返回 `version`、`catalogVersion`、`datasetId`、待发布状态、回执及保护信息。每次写入都必须携带这些实际值；未绑定数据集填写 `datasetId:null`。管理其他工作区时还需按 schema 提供 `targetId`、`targetVersion`。切换操作明确改变当前工作区；所有其他操作仍以输入的 `workspaceId` 为准。

```json
{
  "workspaceId": "实际工作区 UUID",
  "datasetId": null,
  "version": 0,
  "catalogVersion": 0,
  "edit": {
    "id": "administrator-local-course-1",
    "value": {"title":"合成测试课程","day":1,"startMinute":480,"endMinute":540,"location":"测试"}
  }
}
```

将以上 JSON 作为 `entry upsert` 的 stdin，即可通过原 `applyEdit` 新增/修改指定 ID 的课程。事件、覆盖、日期调整、学期和来源各有独立 schema，使用 `schema` 查看完整字段；学期的 `phases[].periods[]` 同时管理阶段和节次。删除导入来源、替换导入和重置覆盖保留原有关联规则。

`--workspace-id`、`--dataset-id`、`--version`、`--catalog-version`、`--offset`、`--limit`、`--table`、`--id`、`--filter`、`--date`、`--zone`、`--confirm` 可以覆盖 stdin 同名字段。复杂参数继续使用 JSON，避免 shell 引号和秘密泄露。

## 能力覆盖

同一操作目录生成 CLI、MCP Tools 和 JSON Schema；0.7.1 共 69 项操作，MCP 将点号替换为下划线。

| 功能域 | 操作 |
| --- | --- |
| 系统、诊断 | `version`、`health`、`capabilities`、`schema`、`diagnostics`、`maintenance.status`、`operations.receipt` |
| 课表查询 | `schedule.week/day/range/events`；范围最多 31 天，日期查询使用相同 start/end |
| 原始业务数据 | `records.list/get`，覆盖 entries、events、sources、imported、classOverrides、dayAdjustments、profiles、customProfileKeys；按稳定 ID、文本筛选及分页 |
| 编辑 | `entry/event/profile/date/source.upsert/delete`、`override.upsert/reset`；profile 管理自定义学期身份、作息阶段和节次 |
| 导入 | `import.preview/rows/confirm/cancel`；ICS/CSV 预览、警告、分页、新增或替换来源 |
| 工作区 | `workspaces.list/add/switch/rename/remove/endpoint` |
| 连接 | `connections.save/test/remove`、`directories.browse`；授权目录、本地私有目录、WebDAV 和证书配置；端点更新须验证同一 dataset |
| 同步、冲突 | `sync.status/run/create/bind`、`conflicts.get/resolve`；tips、branches、deferred、冲突关联记录、原冲突 token 和实际发布回执 |
| 历史 | `history.list/snapshot/diff/check/preview/preview-diff/rollback/archive/compact`；包含 DAG、容量、观察异常及完整快照分页 |
| 加密应用备份 | `backups.list/create/verify/cancel/restore`；回读验证、外部依赖、恢复预览和恢复后隔离 |
| 数据集迁移 | `migration.status/verify/prepare/device/freeze/switch/recheck/cancel/retire`；进度、预检、管理员设备证据、冻结、切换、复查、取消与退役 |
| 独立批准 | `approvals.request`；只能申请，不能授予自身批准 |

历史版本详情由 `history.list` 的稳定提交 ID、父引用、Writer、哈希和比较摘要，以及 `history.snapshot` 组成；完整冲突远端原始快照由关联记录/历史查询取得，避免一次返回整库。`maintenance.status` 与迁移摘要提供人工清理前的保护检查，不提供旧库删除接口。归档和精简结果可从操作回执查询，目录仍只限 fnOS 授权范围。

CLI 导入可以用 `import preview --file /本机/课程.ics --input /本机/版本上下文.json`，单文件最多 1 MiB。MCP 只接受 `filename` 和有界 `base64`，没有任意 NAS 路径读取接口。预览最多 8 份，15 分钟过期；可显式取消；与原网页预览一致，过期条目在后续预览时清理，不新增常驻轮询。预览绑定入口身份、工作区及 revision，不能跨网页/Agent 冒用。

## 权限与独立批准

FPK 初始只授权 `read`。管理员在原 fnOS 管理网页底部展开「CLI / MCP 权限与管理员批准」，读取当前权限后明确授予所需能力。授权保存在服务端，客户端不接受 `--admin`、管理员 Header 或自报角色。

| 权限 | 允许范围 |
| --- | --- |
| read | 查询、诊断、历史比较及只读资源 |
| edit | 业务编辑、导入、同步和远端历史观察 |
| admin | 工作区、连接及连接测试 |
| maintenance | 历史回退/归档/精简、加密应用备份、迁移 |

权限按集合授予，`admin` 不自动隐含 `edit` 或 `maintenance`。权限作用于可信本地 OS 边界内的所有 Agent 客户端，不是多租户凭据系统。包用户和 root 已具备代码与存储管理权限，不能把恶意 root 或任意执行包用户代码的进程当作可隔离的低权限租户。普通 OS 用户不能连接 owner-only Socket；管理员须经 OS `sudo` 明确切换为包用户。

普通课程编辑无需逐次人工批准。创建加密备份（可能导出凭据）、恢复、回退、归档、精简，以及迁移准备/设备证据/冻结/切换/取消/退役，需要独立批准：

1. 先执行业务预览/验证。恢复用 `backups.verify`，回退用 `history.preview`，迁移用 `migration.verify`；保留原业务 token 和最新版本。
2. 选定最终操作 ID，调用 `approvals.request`，输入 `operation`、最终完整 `input`、`targetOperationId`。申请自身也使用一个不同的调用操作 ID。
3. 管理员在原网页核对具体操作、工作区、dataset、版本、目标及预览范围，明确批准。恢复批准额外显示备份时间、应用版本、工作区/文件数量和外部依赖；不会显示口令或明文暂存。
4. 使用完全相同的操作名、输入和目标操作 ID，并附带返回的 `approvalId` 执行。批准最长十分钟、一次使用；参数变更、版本过期、恢复或原领域保护都不能被批准绕过。

批准摘要使用服务端 HMAC 绑定完整输入（包含未显示的凭据和 token）；无 Agent 批准工具。原管理 Socket 的网关管理员认证和 CSRF 是独立授权入口。Web UI 中批准本身不会替 Agent 执行业务。

## 幂等、限制与结果

写操作的 `operationId` 格式为 `<13 位毫秒时间戳>-<UUID>`，需在 24 小时内且不能比服务端超前一分钟。CLI 默认生成并向 stderr 预先输出 ID，结果也返回该 ID；SSH 自动化宜提前生成并用 `--operation-id` 固定。MCP 写工具必须显式传入 ID。

服务端先 fsync 操作意图，再执行业务，再 fsync 结果回执。24 小时内最多 512 份意图，达到预算会拒绝新写入，不淘汰仍可重试的 ID。相同 ID 和参数返回原回执；同 ID 不同参数拒绝。崩溃发生在意图与回执之间时返回 `UNKNOWN`，查询 `operations.receipt` 和业务状态后由管理员判断，不能把这种结果当作安全重试授权。超过窗口的 ID 也不会重新执行。批准申请自身不占业务意图额度，独立受最多 32 个、十分钟过期预算限制。

回执大于 8 KiB 时只保留结果指示，业务数据通过有界读取重新查询。操作账本、批准、权限和审计独立于应用备份，恢复旧备份不会恢复旧权限或清除重试保护。审计最多 256 条，记录接口类别、时间、工作区、操作名、结果类别和可信边界身份，不记录原始课程、导入正文或密码；成功的高频读取不写审计。接口类别用于追踪，不用于身份认证。

单请求和响应最多 2 MiB；分页默认 40、最大 100，最大 offset 50000；IPC 最多 16 条连接、8 个正在处理的操作；客户端超时 60 秒，服务端沿用 65 秒请求预算及原领域网络/资源预算。超时不表示取消已提交业务。单写业务在 Agent 队列中串行，仍与网页共用原来的领域版本检查、备份/历史/迁移互斥。只读响应超限会明确报 `LIMIT`，不会悄悄截断业务记录。

成功响应为 `{ok:true,data,operationId?}`，错误为 `{ok:false,error:{code,message},operationId?,data?}`。正文不回显底层凭据或客户端输入。冲突 token、导入预览 ID、历史/恢复 token 是执行原工作流所需的短期绑定句柄，不替代 OS 权限、版本检查或独立批准。

| 退出码 | 含义 |
| --- | --- |
| 0 | 操作完成；仍须读取 dirty、pending、receipt 等实际业务状态 |
| 1 | 其他明确业务失败，例如目标不存在、缺少连接 |
| 2 | 参数或确认条件不满足 |
| 3 | revision、catalogVersion、数据集或预览过期 |
| 4 | 未授权或缺少独立批准 |
| 5 | 保护状态或身份约束拒绝 |
| 6 | 互斥操作进行中 |
| 7 | 资源预算超限 |
| 8 | 应用不可用或连接验证失败 |
| 9 | 已提交与否无法确认，须先查询 |
| 10 | 同步存在冲突或暂不可验证历史 |

写入结果返回操作 ID、工作区 revision、catalogVersion、当前工作区、dataset、dirty、pending、receipt、异常与状态。新增工作区另返回 `resultWorkspaceId`；归档/精简的输出数据集另返回 `resultDatasetId`，不会混淆当前工作区的 dataset。`localSaved` 只表示本地帧状态存在，`receipt` 才是本机发布回读确认；`otherDevicesReceived` 始终为 `unknown`，不推测离线设备已接收。

## MCP stdio / SSH

运行依赖使用官方 `@modelcontextprotocol/server@2.3.1`，开发测试使用官方 `@modelcontextprotocol/client@2.3.1`。生产包不包含客户端 SDK、Playwright 或其他开发依赖。官方 SDK 的 `serveStdio` 同时处理 `2026-07-28` 的 `server/discover` 和旧客户端 `initialize`；测试断言实际协商 `2026-07-28`、`2025-11-25`，不通过伪造版本号宣称兼容。[官方协议说明](https://github.com/modelcontextprotocol/typescript-sdk/blob/main/docs/protocol-versions.md)

本机 MCP 配置（运行该客户端的 NAS 管理员必须已获适当 OS 权限）：

```json
{
  "mcpServers": {
    "dormpanel": {
      "command": "sudo",
      "args": ["-n", "-u", "dormpanel-schedule-manager", "/var/apps/dormpanel-schedule-manager/target/bin/dormpanel-mcp"]
    }
  }
}
```

远端配置示例，`w` 替换为管理员实际 SSH 账号：

```json
{
  "mcpServers": {
    "dormpanel-nas": {
      "command": "ssh",
      "args": ["-T", "-o", "BatchMode=yes", "w@192.168.3.153", "sudo -n -u dormpanel-schedule-manager /var/apps/dormpanel-schedule-manager/target/bin/dormpanel-mcp"]
    }
  }
}
```

SSH stdio 不能在 JSON-RPC 流中交互询问密码。管理员需先配置 SSH 密钥，并按 NAS 本地安全策略使用 `visudo` 明确允许该管理员免密运行**上述唯一 MCP 入口**，例如：

```sudoers
w ALL=(dormpanel-schedule-manager) NOPASSWD: /var/apps/dormpanel-schedule-manager/target/bin/dormpanel-mcp
```

这不是安装包自动加入的 sudoers；不要授予通用 shell、任意 Node 参数、全量免密 sudo，或扩大 Socket 权限。无法满足时，先由管理员在 NAS 本地交互式 SSH 会话启动/检查 CLI，MCP 配置保持受阻并明确报错。包装器自行切换到安装目录，使用 fnOS 官方 Node 22；不依赖 SSH HOME 或调用者 cwd。SSH 启动警告只能进入 stderr，stdout 保持 JSON-RPC；断开 stdin 后 SDK 退出，不增加后台守护进程。

Resources 包括 `dormpanel://capabilities`、`dormpanel://workspaces`、`dormpanel://diagnostics`；模板包括 `dormpanel://workspace/{workspaceId}/sync|history|migration|maintenance`、`records/{table}/{offset}`、`snapshot/{id}/{table}/{offset}` 和 `diff/{from}/{to}/{offset}`。URI 只映射固定操作和经校验的业务 ID，不接受磁盘路径或任意 URL。

Prompts 提供 `week_summary`、`compare_versions`、`import_schedule`、`diagnose_sync`、`prepare_compaction`、`migration_acceptance`。它们只说明现有工具工作流，不执行自动冲突决策、不推断所有离线设备迁移完成，也不解释课程标题或导入文本为 Agent 指令。

## 明确边界

未开放可选 loopback HTTP MCP：本版本使用 stdio/SSH，安装不会新增公网端口、FN Connect 路由、反向代理或 Socket 转发。未增加 SQL、shell、JavaScript 执行器、私有持久文件编辑或旧库批量删除功能。无法从协议自动证明所有独立设备已停写或完成换库；设备清单仍是管理员证据登记，实际网络验证沿用原后端。真实设备、目录授权、证书或管理员权限缺失时，预检和执行返回受阻状态，不虚构成功。
