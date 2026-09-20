# DormPanel Development Guide

- Primary hardware is the Xiaomi Redmi XiaoAI Touchscreen Speaker Pro 8 (X08E): Android 9 / API 28, about 2 GB RAM, and a 1280x800 landscape display.
- Prefer long-running stability, low memory use, and near-zero idle CPU work over decorative animation.
- Keep the app native Kotlin using Android Views/XML unless the project deliberately changes direction.
- DormPanel coexists with Xiaomi's software. Do not replace or interfere with XiaoAI, MIUI Home, alarms, Bluetooth Mesh gateway features, or other original services.
- Home Assistant belongs behind a separate network/state layer when it is added; UI code must not own connection or protocol state.
- Normal dashboard and device controls must remain deterministic and must not depend on an LLM.
- Avoid unnecessary heavy dependencies, frameworks, background work, and device-specific Root/ADB behavior.
- Preserve Android 9 compatibility and verify changes against the 1280x800 landscape form factor.
# DormPanel 项目

DormPanel 是面向 Xiaomi Redmi 小爱触屏音箱 Pro 8（X08E）的轻量 Android 常驻桌面与智能家居控制应用。

## 产品定位

DormPanel 不是完整平板桌面、Home Assistant 客户端复刻或米家替代品，而是一套针对固定横屏智能终端优化的「桌面信息中心 + 快捷控制 + 轻量效率工具」。

目标设备当前为 X08E，主要环境为 Android 9、约 2GB RAM、8 英寸 1280×800 横屏，并需要长期常驻运行。

应用应与小米原生系统共存，而不是依赖删除或替换小米服务。小爱同学、Bluetooth Mesh 网关、原生闹钟、MIUI Home 和其他原厂能力原则上继续由小米系统负责。

## 核心目标

首页采用类似新版米家的可编辑卡片式布局。卡片可以添加、删除、拖动排列，并在卡片支持的预设尺寸之间调整大小。

计划支持的卡片包括但不限于：

时间与日期、天气、Home Assistant 灯光控制、传感器、场景、Todo、计时器、备忘、日历和课表。

首页之外提供可通过上、下、左、右手势进入的功能页。各方向目标应可配置，页面可包括：

应用列表、控制中心、完整日历/课表、完整家居控制等等。

Home Assistant 是主要智能家居数据与控制后端之一。优先使用 WebSocket 维护实时状态和执行常规控制，REST 仅作为必要补充。

普通设备控制应是确定性的，不依赖 LLM。AI/MCP 可以作为未来的高级自然语言入口，但不能成为开灯、关灯等基础功能的唯一链路。

## UX 原则

界面针对 1280×800 横屏和固定桌面/床头距离设计，不照搬手机 UI。

优先保证信息可以快速扫视、按钮容易触控、状态清晰、夜间不过亮，夜间/深色模式建议使用全黑背景。

首页保持低信息噪声。复杂历史数据、完整设置和低频操作放到二级页面或详情页。

设备离线、HA 断线和数据过期必须与正常状态有明显但不过度干扰的区别。

应用断网或 HA 不可用时，本地时间、日期和本地功能仍应工作。

## 技术方向

项目使用 Kotlin 原生 Android 开发，并优先选择适合 Android 9 和长期常驻场景的轻量实现。

首页卡片布局需要支持逻辑网格、不同尺寸卡片、拖拽、Resize、碰撞处理和持久化。

卡片系统应具备扩展能力，使新增卡片类型不需要不断修改首页核心布局逻辑。

Home Assistant 网络层与 UI 解耦。UI 不应直接管理 WebSocket 消息或维护第二份互相竞争的状态。

应用应支持 Mock/Fake 数据源，使 UI 和卡片引擎开发不依赖真实 HA 或 X08E。

## 性能与可靠性

目标设备性能有限，因此避免没有实际价值的持续动画、频繁轮询和过重依赖。

空闲时 CPU 占用应接近零。HA 状态主要依靠 WebSocket 推送而非轮询。

常驻运行稳定性优先于视觉特效。

对卡片布局、状态同步、WebSocket 重连等核心逻辑，应使用能够验证正确性的测试。

## 与 X08E 原系统的关系

DormPanel 默认作为普通常驻 Activity/App 运行，不应在没有明确需要时成为唯一 Launcher。

后续实机阶段计划支持开机后自动进入 DormPanel，同时保留返回 MIUI Home 和原生系统的入口。

任何与 Root、官改、ADB、自启有关的实现都不能默认牺牲小爱、Bluetooth Mesh 网关或其他原生能力。

设备侧行为在没有实机验证前不得视为已确认。