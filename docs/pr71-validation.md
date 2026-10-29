# PR71 fnOS 课程管理验证（进行中）

基线 main：a1c99dedbfa07be7175f9af2661e2acff33b9468。使用隔离临时 Workspace；没有修改日用绑定、安装日用 APK/FPK 或发布。

当前已执行：
- Node 原课程安排、覆盖审查、Agent 定向：40 PASS、2 SKIP、0 FAIL。跳过项目不计通过。
- 新增课程查询/预览：2 PASS。
- Chromium/Edge 浏览器：课程列表、公共教师草稿、只读预览和确认保存 1 PASS；确认前 revision 不变，确认后仅增加一次。
- `npm --prefix fnos-app run build`：PASS，生成 FPK 工程，未安装。

真实 NAS SSH、WebDAV 端口可达；ADB 发现 Phone 和 X08E。三端网络互操作、异常生命周期及更多 UX 验证仍在进行，当前不宣称 Done Criteria 全部完成。

![课程列表](screenshots/pr71/list.png)
![课程编辑](screenshots/pr71/editor.png)
![保存预览](screenshots/pr71/preview.png)
