# X08E 天气服务

## 配置和检查

从首页下滑进入控制中心，选择「天气服务与地点」。打开「和风服务配置」，输入和风控制台分配的专属 API Host。JWT 模式填写凭据 ID、开发者 ID、项目 ID 和 Ed25519 PKCS#8 私钥。已有 API Key 的用户可以切换认证方式。保存后可测试已保存的凭据。测试使用已配置的第一个和风地点，或使用北京的公开示例坐标发出一次实时天气请求。该请求计入本机预算。

通过「添加地点」设置名称、经纬度和 IANA 时区，例如 `Asia/Shanghai`。已连接的 Home Assistant 天气实体会出现在同一表单中，可以为地点同时启用和风和多个 HA 来源。地点列表支持编辑、删除、切换检查来源及清除缓存。历史天气只接受明确的和风 Location ID。输入经纬度时会保留原始精度。

城市或区县搜索显示行政区划、同名候选和数据归因。选中后使用 GeoAPI 提供的时区，有缺失时填写时区。官方缓存指南指出 GeoAPI 的数据来自多个许可范围不同的提供方，未明确授权逐字段永久保存。本阶段将选中的 GeoAPI 地点用于当前应用进程，搜索结果及其选中地点的地理字段无磁盘写入。用户可以独立保存自行提供的名称、坐标、时区和已知 Location ID。[城市搜索](https://dev.qweather.com/docs/api/geoapi/city-lookup/) · [缓存指南](https://dev.qweather.com/docs/best-practices/cache/) · [使用限制](https://dev.qweather.com/docs/terms/restriction/)

检查界面打开后订阅实时天气和小时、每日预报。选择数据类型并点「请求」可检查其他数据块。关闭检查界面即释放该界面的订阅。归因和许可随各数据块展示，天气预警与 AQI 的来源声明完整显示。[和风注明来源](https://dev.qweather.com/docs/terms/attribution/)

## 接口和单位

| 数据 | 和风接口 | 请求参数 |
| --- | --- | --- |
| 地点 | `/geo/v2/city/lookup` | `location`、`number`、`lang` |
| 实时 | `/weather/v1/current/{lat}/{lon}` | `lang` |
| 小时 | `/weather/v1/hourly/{lat}/{lon}` | `hours` 1–240、`lang` |
| 每日 | `/weather/v1/daily/{lat}/{lon}` | `days` 1–10、`lang` |
| 预警 | `/weatheralert/v1/current/{lat}/{lon}` | `lang` |
| 空气质量 | `/airquality/v1/{current,hourly,daily}/{lat}/{lon}` | `lang` |
| 分钟降水 | `/v7/minutely/5m` | `location`、`lang` |
| 生活指数 | `/v7/indices/{1d,3d}` | `location`、逗号分隔的 `type`、`lang` |
| 天文 | `/v7/astronomy/{sun,moon}` | `location`、`date`，月相另有 `lang` |
| 历史天气 | `/v7/historical/weather` | Location ID、`date`、`unit=m`、`lang` |

天气参数以十进制点格式化，坐标在请求时四舍五入至两位小数。v1 路径使用纬度、经度顺序，v7 位置参数使用逗号分隔的经度、纬度。坐标、日期及 API 参数影响共享缓存键，名称仅用于展示。天文支持当地今天起 60 天，历史支持当地昨天起 10 天。分钟降水在支持区域提供约两小时的五分钟累计量。[和风 API 文档](https://dev.qweather.com/docs/api/)

天气量统一为 °C、m/s、hPa、m、mm、mm/h，百分比为 0–100。空气污染物浓度保留上游单位和 AQI 标准。缺失和不合法数值为空。天气代码保留原值。带偏移量的时间转换为 `Instant`，日历日期按地点时区计算。历史接口给出的无偏移时间作为当地日期和钟表时间保存，以保留夏令时歧义。[和风单位](https://dev.qweather.com/docs/resource/unit/)

## 安全和持久化

和风连接仅接受专属 `qweatherapi.com` 主机名或对应的 HTTPS 地址。OkHttp 验证 TLS，关闭重定向和连接失败自动重试。API Key 使用 `X-QW-Api-Key` 请求头。JWT 使用 Ed25519，签发时回拨 30 秒，总有效期 15 分钟，提前 60 秒换发。签名使用独立 Java EdDSA 实现，在 Android 9 上可运行。凭据与其绑定的主机和配置 ID 一起通过 Android Keystore AES-GCM 加密保存，凭据文件排除在 Android 备份和设备迁移之外。解密失败会显示重新配置提示。错误提示使用本地分类及状态码，省略凭据和服务端原始错误正文。[官方认证](https://dev.qweather.com/docs/configuration/authentication/) · [API Host](https://dev.qweather.com/docs/configuration/api-host/)

用户地点与关系使用独立设置文件。天气快照使用应用私有目录中的原子文件，支持重启回读，磁盘最多保留 128 份，每份最多约 2 MiB。和风配置换代与 HA 端点、认证身份共同隔离缓存，已撤销请求的回调会被拒绝。已有布局、卡片与 Room 数据结构沿用原路径。

## 刷新和预算

| 数据块 | 默认新鲜期 |
| --- | --- |
| 实时天气 | 15 分钟 |
| 小时预报 | 45 分钟 |
| 每日及半日预报 | 2 小时 |
| 预警 | 10 分钟，单条有效期更短时先失效 |
| 实时、小时、每日 AQI | 45 分钟、1 小时、8 小时 |
| 分钟降水 | 5 分钟 |
| 生活指数 | 6 小时 |
| 天文与历史 | 按日期区分，24 小时新鲜期，历史只按需加载 |

每日预报、每日 AQI、生活指数也会在地点当地午夜到期。自动刷新仅对有需求的块调度，HTTP 并发上限为两个，核心请求优先。每个键合并在途请求。后台或面板黑屏会停止新系统的订阅与刷新任务，恢复前台时按缓存状态刷新。断网、超时和服务错误会保留上次天气结果，并受退避与新鲜周期约束。预警超过缓存有效期或来源失败时隐藏当前预警，取消和被取代的消息不会计入有效预警。授权和能力拒绝会停止该块自动尝试，429 遵从 `Retry-After` 并增加退避。[缓存建议](https://dev.qweather.com/docs/best-practices/cache/) · [请求优化](https://dev.qweather.com/docs/best-practices/optimize-requests/)

本机预算按 UTC 月份计数，默认 3000 次，可设置。达到 80% 时将剩余额度留给核心天气，达到总上限时停止和风请求。测试连接及搜索计入预算。本机统计记录交给 HTTP 客户端的请求，含随后失败或取消的请求，仅覆盖当前设备。服务配置界面显示最近成功和最近错误。

HA 当前天气从现有实体存储读取，并跟随原连接的状态事件。同一实体和预报类型共享 `weather/subscribe_forecast`，订阅失败或无初始事件时回退 `weather.get_forecasts`。原版天气卡片保留 Preferred weather 及原全局投影，与新消费者共享同类型的 HA 预报订阅。和风配置及数据与 HA/Demo 后端模式独立。[HA 天气实体](https://developers.home-assistant.io/docs/core/entity/weather/) · [HA 预报订阅实现](https://github.com/home-assistant/core/blob/dev/homeassistant/components/weather/websocket_api.py)

## PR2 / PR3 接入

从 `DashboardBackend.weatherRepository` 获取同一个 Repository。通过 `places.places`、`places.get(id)`、`places.observe` 读取地点配置。卡片可保存地点 ID 与明确的 `WeatherSourceRef`，以便不同卡片独立选择来源。切换绑定时关闭旧订阅，并用新地点和来源调用 `observe`。地点或绑定移除会通知当前观察者。

向 `observe(placeId, source, WeatherQuery(...), automatic, listener)` 传入所需数据块。默认只自动刷新核心块。长按详情或天气中心可以在可见时订阅专项数据，或对订阅调用 `refresh()`。使用 `capabilities` 读默认请求范围的已知状态，使用各 `WeatherSnapshot` 显示请求特定范围的状态、数据、来源、上游时间、获取时间及过期时间。预警模型将当前有效、即将生效和撤销消息分别暴露。消费者应在界面移除、卡片换绑或详情关闭时调用 `close()`。这些 API 在主线程调用，网络与磁盘工作由内部执行器负责。
