# DormPanel Android v0.1.12

本次 Android 正式版包含 X08E **0.1.12 / versionCode 15** 和 Phone **0.1.11 / versionCode 13**，最低 Android 9（API 28），targetSdk 37。HA Integration 沿用 0.1.2，fnOS FPK 沿用 0.8.2 Pre-release，均单独获取。

## X08E

- 天气卡片支持和风天气与 Home Assistant 多来源预报。每张卡片可独立选择地点和来源，同源卡片共享请求与缓存。
- 点击卡片打开双栏天气中心，可搜索、收藏和切换城市，查看实时指标、小时走势、10 天预报、预警和按需加载的降水、空气质量等明细。离线地图支持选点。
- 在控制中心打开统一扫码配置，用手机填写 HA、WebDAV、和风凭据及天气地点，再回到 X08E 核对并确认。扫码门户使用局域网 HTTP，请在可信网络使用。和风设置支持生成 Ed25519 JWT 密钥、加密保存私钥并导出公钥及指纹，用于在和风控制台注册。
- 控制中心改为紧凑双栏，共享滚动区域，服务状态可完整换行。一屏能放下时可从内容区上滑回首页，内容溢出时可从提示区域上滑返回。
- 新版天气卡片优化温度区间、预报日期和指标排版，随尺寸调整信息密度。新增经典天气卡片，可与新版同时使用。
- 课表三种布局均可在「课表管理」旁一键同步。按钮会显示本次结果，失败后可重试；反馈反映当前设备已验证的进度，多设备接收需分别确认。

## Phone

- 本地课程常驻通知默认关闭。在「管理 → 课程通知」开启后，可查看当前或下一节课程与课前、课中倒计时，并设置锁屏显示内容。
- 在同一入口手动开始跟踪正在进行的课程，并可随时结束。本节结束后自动停止跟踪。
- Android 16+ 可为主动跟踪实验性请求系统 Live Updates。实际提升展示取决于系统支持、资格与用户许可，Android 16 或 HyperOS 均可能只显示普通通知。DormPanel 使用 Android 公开 API，小米超级岛官方接入不在本次范围内。
- 通知诊断可查看权限、系统开关和渠道状态、跟踪会话、调度、失败记录和最近的提升请求。

## 下载与原位升级

1. 在 [GitHub Release](https://github.com/787x/DormPanel/releases/tag/v0.1.12) 下载适合设备的 APK 和 `SHA256SUMS.txt`。音箱使用 `DormPanel-X08E-0.1.12.apk`，手机使用 `DormPanel-Phone-0.1.11.apk`。
2. 在 PowerShell 用 `Get-FileHash -Algorithm SHA256 .\DormPanel-X08E-0.1.12.apk` 核对清单中同名文件的 SHA-256。手机包请替换命令中的文件名。哈希一致后再传到设备。
3. 使用系统安装器或 `adb install -r <APK 文件>` 覆盖安装。包名和既有正式签名保持一致，versionCode 分别由 14 升到 15、由 12 升到 13。保留已安装应用及其数据，避免卸载、清数据、重置 Keystore 或重建同步身份。安装会保留现有同步绑定。

使用和风天气前请自行配置有效凭据，并在天气检查页关注本机请求预算和账户配额。缓存过期、限流和网络不可用时，请参考页面状态与最近更新时间。

## 验收依据

- Phone 复用 PR76 的通知调度、有效期及 Android 9 仪器验证，以及 PR77 的 89 项 JVM 测试和 MIX 2S / API 33 隔离包会话验证。Android 16 实际提升和 HyperOS 展示仍未实测。[PR76](https://github.com/787x/DormPanel/pull/76) · [PR77](https://github.com/787x/DormPanel/pull/77)
- 天气复用 Repository、独立卡片、天气中心和统一扫码的定向 JVM 与 X08E 隔离包验证，涵盖缓存、来源切换、预报交互、软键盘、Keystore、JWT 和扫码门户。记录中的天气中心月相可见性断言在基线可复现。[PR78](https://github.com/787x/DormPanel/pull/78) · [PR79](https://github.com/787x/DormPanel/pull/79) · [PR80](https://github.com/787x/DormPanel/pull/80) · [PR81](https://github.com/787x/DormPanel/pull/81)
- X08E 复用 Android 9、1280×800 的控制中心触控与手势、两种天气卡片的尺寸和布局验证，以及课表同步 9 项 JVM 和 6 项隔离实机用例。[PR82](verification/pr82-control-center.md) · [PR83](verification/pr83-x08e-weather.md) · [PR84](verification/pr84-x08e-weather.md) · [PR85](https://github.com/787x/DormPanel/pull/85)
