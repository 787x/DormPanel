# DormPanel X08E v0.1.14 — 天气卡片 AQI

本次更新为 **X08E 普通天气卡片和紧凑天气卡片**加入实时 AQI，并调整 2×3 和 3×3 的显示布局。

**本次发布版本：**

- **X08E：v0.1.14**

支持 Android 9 及以上版本。

---

## ✨ 本次更新亮点

### 🌦️ 1. 天气卡片显示实时 AQI

- 普通天气与紧凑天气卡片的所有可选尺寸，均可显示和风天气返回的有效实时 AQI。
- **3×3**：天气状况上移，AQI 位于天气状况和湿度之间。
- **2×3**：天气状况保留原显示空间，今日温度范围靠左，AQI 同行靠右。

进入首页编辑模式即可调整已有卡片的大小，地点和天气来源会保留。

### 🧩 2. Demo 桌面可直接查看两种 AQI 布局

新建 Demo 桌面并排提供 3×3 天气卡片和 2×3 紧凑天气卡片，使用固定示例数据。可以通过首页编辑入口调整尺寸。

---


## 🏠 原有功能继续保留

DormPanel 仍然是一套为 X08E 固定横屏设备优化的**桌面信息中心、快捷控制与效率工具**，而不是替换小米原生系统。

继续支持：

- **可编辑卡片桌面**：自由添加、移动和调整卡片尺寸。
- **Home Assistant 智能家居控制**：查看设备状态、控制灯光及执行场景。
- **日历与课程表**：周视图、课程管理、课表导入和共享同步。
- **效率工具**：Todo、备忘录、计时器等。
- **应用管理**：打开已安装应用及使用现有 APK 安装功能。
- **系统快捷控制**：亮度、音量、屏幕常亮、黑屏等。

DormPanel 继续与 MIUI Home、小爱同学及小米原有服务共存，不要求删除或替换系统组件。

---

## 📦 下载与升级

| 设备 | 本次版本 | 下载 |
|---|---|---|
| 小米 Redmi 小爱触屏音箱 Pro 8（X08E） | v0.1.14 | [DormPanel-X08E-0.1.14.apk](https://github.com/787x/DormPanel/releases/download/v0.1.14/DormPanel-X08E-0.1.14.apk) |

**升级方法**

1. 下载适合设备的 APK。
2. 建议通过 [SHA256SUMS.txt](https://github.com/787x/DormPanel/releases/download/v0.1.14/SHA256SUMS.txt) 核对文件完整性。
3. 使用系统安装器覆盖安装，或使用 `adb install -r` 原位升级。

本次发布继续使用现有正式签名。X08E 的 versionCode 为 19，支持从 v0.1.13（16）、RC1（17）和 RC2（18）原位升级。正常覆盖安装会保留已有应用数据、桌面布局、天气配置及课表同步绑定。

**请勿先卸载旧版或清除应用数据。** 卸载或清理数据可能造成配置、密钥及同步身份丢失。

### 配套组件

- Android Phone：[v0.1.13](https://github.com/787x/DormPanel/releases/tag/v0.1.13)
- Home Assistant Integration：[v0.1.3](https://github.com/787x/DormPanel/releases/tag/ha-v0.1.3)
- fnOS DormPanel Schedule Manager：[v0.8.3](https://github.com/787x/DormPanel/releases/tag/fpk-v0.8.3)

## ℹ️ 使用说明与注意事项

- 更新 APK 会保留原有的同步绑定。已有 Sync v2 或旧版 Sync v3（DPV3/3）的用户，如需共享 Todo 和备忘，请按共享管理流程显式迁移，并保留原目录和历史。启用共享 Todo 前请确认各参与端兼容此功能，旧版可能无法向含 Todo 的数据集写入。
- HA 侧边栏、概览卡片和 X08E 原生设置控制需要配套新版 HA 集成。fnOS 网页和 CLI/MCP 的 Todo 与备忘功能也需要配套新版 FPK。现有公开配套版本见上文。
- 本次核验了 X08E 正式 APK 的包名、版本、签名及 SHA-256。天气布局、缩放和同源订阅复用在 X08E 隔离测试包中通过 4 项实机测试，使用固定天气数据。[PR #97](https://github.com/787x/DormPanel/pull/97) · [PR #98](https://github.com/787x/DormPanel/pull/98)

---

**完整更新：** [v0.1.13 → v0.1.14 代码对比](https://github.com/787x/DormPanel/compare/v0.1.13...v0.1.14)

感谢使用 DormPanel！
