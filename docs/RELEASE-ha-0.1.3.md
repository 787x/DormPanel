# DormPanel Home Assistant Integration v0.1.3

此版本与 **DormPanel X08E v0.1.13** 配套。X08E 连接后会在 Home Assistant 中注册为设备，提供亮度、媒体音量、主题、黑屏、常亮、开机启动等 10 项控制。每台设备有独立实体，可用于仪表盘和自动化。X08E 本机修改会同步到 HA，远程操作完成后会显示设备实际应用的值。命令超时或断线后，等设备重连并核对当前状态，再决定是否重试。

原生实体可直接控制 X08E，无需创建或绑定 Helper。原有 Helper 配置会保留，在连接旧版集成或原生会话暂时无法建立时仍可作为回退。原生会话恢复后由原生实体接管。ICS、受支持的 CSV/XLS 课表和 APK 仍可从 HA 发送，管理员权限及设备端确认流程照常使用。

## 安装或原位升级

1. 下载 `DormPanel-HA-0.1.3.zip`，用同页的 `SHA256SUMS.txt` 核对文件。
2. 解压，把 `custom_components/dormpanel/` 复制到 HA 配置目录的 `custom_components/dormpanel/` 下。升级时覆盖集成代码，保留现有配置条目和 HA 存储。
3. 重启 Home Assistant。首次安装时，从「设置 → 设备与服务 → 添加集成」选择 DormPanel。已有安装可继续使用原配置和设备身份。
4. 在 X08E 控制中心配置或重新连接 HA。首次注册使用管理员账户的长期访问令牌。

## 从侧边栏操作

使用 HA 管理员账户打开侧边栏「DormPanel」，在顶部选择已注册的 X08E。设备联机并上报状态后即可调整设置。展开「设备信息与诊断」查看版本与连接信息，展开「发送文件与传输记录」发送课表或 APK 并核对处理结果。

## 添加 Lovelace 概览卡片

在「设置 → 仪表盘 → ⋮ → 资源」添加 `/dormpanel_static/panel.js?v=0.1.3`，类型为「JavaScript 模块」。资源菜单需要在个人资料中启用高级模式。使用 YAML 管理资源时，把同一 URL 以 `type: module` 加入现有的 `lovelace.resources` 列表。

编辑仪表盘，选择「DormPanel 概览」，也可添加手动卡片并填写：

```yaml
type: custom:dormpanel-overview
```

升级已有卡片时，编辑原资源条目，把 URL 的 `v` 改为 `0.1.3`，保留卡片配置。重新加载仪表盘资源并刷新已打开的 HA 页面。仍显示旧界面时，强制刷新浏览器，必要时清除该站点的缓存后重新打开。

[完整安装与管理指南](https://github.com/787x/DormPanel/blob/ha-v0.1.3/docs/ha-dormpanel-management.md)
