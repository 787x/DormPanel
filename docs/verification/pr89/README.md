# PR89 fnOS 共享 Todo 与备忘验收

基线 `ee22f3b15908c9683921b6610444a9d30144ae98`。测试日期为 2026-11-18（北京时间）。测试记录为合成数据，远端目录与应用空间单独建立。

## 界面与业务检查

- [Todo 管理](fnos-todo.png)、[备忘管理](fnos-memo.png)和[备忘冲突](fnos-conflict.png)来自 1280×800 Chromium 的独立管理实例。网页测试另以 360 像素宽度实际完成保存与删除操作。
- HTTP 定向检查覆盖完整 CRUD、授权与 CSRF、精确删除成员、UTF-8 边界、原始换行、空正文、分页、旧格式 / 未初始化 / 恢复保护、过期版本和事务故障。确定拒绝为 `writeApplied:false`，提交结果待核对为 `null`。
- CLI/MCP 定向检查使用实际 CLI 进程和 MCP stdio 客户端。验证默认只读、编辑权限、独立管理员批准、参数绑定、回执重试、事务不确定和正文隔离。
- 网页定向检查覆盖多清单和多备忘、草稿取消、过期草稿、迟到读取、取消已发送的操作、回执丢失后只读核对、重开网页以及冲突决定。冲突决定覆盖整次冲突的各组，决定提交后仍需检查并同步。

## 可复跑的检查

```sh
cd fnos-app
node --test test/productivity-api.test.mjs test/productivity-conflicts.test.mjs test/productivity.test.mjs test/active-v3.test.mjs test/security.test.mjs test/agent.test.mjs test/course-management-agent.test.mjs
node --test browser-test/productivity.browser.mjs browser-test/courses.browser.mjs
npm run build
fnpack build --directory build/dormpanel-schedule-manager
npm run verify:package
```

将 `PR89_CONFLICT_DAV_URL` 指向新的隔离 WebDAV 目录，可用真实 WebDAV 运行双 Writer 冲突测试。该检查覆盖不同任务独立合并、备忘正文分歧、编辑与删除、清单删除与远端新增子任务，以及过期 token / revision 拒绝。一次 WebDAV 运行的数据集是 `ebdd1a6a-b138-43af-8fbc-c85ab985f6ce`，两名 Writer 收敛哈希为 `b1c6f7f59e95b6b91683b85e614eeab4a67016d4364b02e481af3d93ac724445`。
