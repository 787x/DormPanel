# DormPanel Android v0.1.13

X08E 和 Android Phone 均更新到 **0.1.13**，支持 Android 9（API 28）及以上，目标 SDK 为 37。本次更新让音箱和手机可以共享 Todo 清单与备忘，并为 X08E 首页加入独立开关、组合开关和更多紧凑天气尺寸。

## Todo 和备忘跨端共享

现在可以在手机上整理待办，在音箱上查看或勾选同一张清单。纯文本备忘也能跨端编辑，保留换行。

1. 将两端升级到 v0.1.13，在 WebDAV 共享管理中创建或加入**同一个扩展版 Sync v3（DPV3/4）数据集**。普通 WebDAV 即可使用。
2. 在 X08E 长按 Todo 或备忘卡片，打开「数据来源」。在 Phone 打开课表的「管理」，进入 Todo 或备忘页面，选择共享来源。
3. 可以绑定已有共享内容，也可以先预览、再确认导入本机内容。源内容会留在设备上，可切回本地使用。

断网时可继续在本机保存，恢复连接后再同步。如果两端同时改了同一项，按提示检查并处理冲突。本机同步结果只代表此设备已确认的进度，其他设备会在各自同步后收到更新。计时器仍在本机使用。

覆盖安装会保留原有同步绑定。已有 Sync v2 或旧版 Sync v3（DPV3/3）数据集需要按共享管理流程显式迁移，原目录和历史请保留。启用共享 Todo 前请确认同一数据集内的客户端都兼容，旧版 Writer 遇到含 Todo 的数据集可能停止写入。

## Android Phone

- 在「管理」中创建和编辑 Todo 清单、任务及纯文本备忘，可选择仅本机或共享来源。
- 在 Android 桌面添加可调整大小的 **Todo** 和 **备忘** 小组件。每个实例可选择不同清单或备忘。Todo 支持直接勾选任务，点击任务可进入编辑。点击备忘正文可打开编辑器。尺寸和重新配置入口由手机桌面决定。

## X08E

- **独立开关**默认占 1×1 格，适合把一盏接在 HA 开关上的灯或一个插座放到首页。**组合开关**可将同一 HA 设备的 1～3 路开关放进一个卡片，支持横向 2×1 和纵向 1×2，可逐路控制、自定义名称和顺序。长按首页进入编辑后添加卡片，再选择 HA 实体。设备不可用或实体身份无法确认时，卡片会暂停相应控制。
- **「2×3 紧凑天气」**现在可调整为宽 2～4 格、高 1～3 格的九种尺寸。默认 2×3 保留专门的三条预报布局，其余尺寸使用同规格的普通天气布局。已有卡片可以直接调整大小，地点和天气来源会保留。

## 下载与原位升级

| 设备 | 版本 / versionCode | APK |
| --- | --- | --- |
| Redmi 小爱触屏音箱 Pro 8（X08E） | 0.1.13 / 16 | [DormPanel-X08E-0.1.13.apk](https://github.com/787x/DormPanel/releases/download/v0.1.13/DormPanel-X08E-0.1.13.apk) |
| Android 手机 | 0.1.13 / 14 | [DormPanel-Phone-0.1.13.apk](https://github.com/787x/DormPanel/releases/download/v0.1.13/DormPanel-Phone-0.1.13.apk) |

1. 下载对应 APK 和 [SHA256SUMS.txt](https://github.com/787x/DormPanel/releases/download/v0.1.13/SHA256SUMS.txt)。也可使用同名 `.apk.sha256` 文件核对单个 APK。
2. 在 PowerShell 运行 `Get-FileHash -Algorithm SHA256 .\DormPanel-X08E-0.1.13.apk` 并与清单比对。手机请换成对应文件名。
3. 使用系统安装器覆盖安装，或运行 `adb install -r <APK 文件>`。包名及正式签名沿用原版，X08E 的 versionCode 从 15 升至 16，Phone 从 13 升至 14。请保留已安装应用和数据，避免卸载、清除数据或重置 Keystore。

### 配套组件

- [HA Integration 0.1.2](https://github.com/787x/DormPanel/releases/tag/v0.1.9) 继续独立提供。源码中新增的 HA 侧边栏、概览卡片和 X08E 原生设置控制需要配套新版集成。
- [fnOS FPK 0.8.2 Pre-release](https://github.com/787x/DormPanel/releases/tag/fpk-v0.8.2) 继续独立提供。fnOS 网页、CLI/MCP 的 Todo 与备忘功能已在源码中实现，等待配套 FPK 发布。

## 验收依据

- Todo 和备忘复用 Kotlin/Node 专项、API 28 和 API 37 隔离模拟器、真实 WebDAV 及隔离 fnOS FPK 验证，覆盖导入、离线恢复、冲突和小组件刷新。实体 Phone 与第三方桌面仍有待覆盖。[PR87](https://github.com/787x/DormPanel/pull/87) · [PR88](https://github.com/787x/DormPanel/pull/88) · [PR89](https://github.com/787x/DormPanel/pull/89)
- HA 兼容路径复用隔离 HA Core、模拟连接和 X08E 隔离包的专项验证。新版集成与生产 HAOS 的直连未在这轮验证。[PR90](https://github.com/787x/DormPanel/pull/90) · [PR91](https://github.com/787x/DormPanel/pull/91)
- 开关与天气复用 X08E 实机隔离包和定向测试，涵盖布局、触控、尺寸和配置恢复。开关测试使用模拟端点，实际 XMS01BXS 与真实 HA 联调尚未执行。实机使用 1.3× 字体且无预报时，4×3 卡片在极端温度区间可能轻微溢出。[PR92](https://github.com/787x/DormPanel/pull/92) · [PR93](https://github.com/787x/DormPanel/pull/93)

[完整代码对比 v0.1.12…v0.1.13](https://github.com/787x/DormPanel/compare/v0.1.12...v0.1.13)
