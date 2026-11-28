# X08E 的 AirPlay 音频接收

在控制中心的音量区域开启 AirPlay。接收器默认关闭。设置可修改局域网显示名称，并单独配置开机自动启动。在 iPhone 或 iPad 控制中心选择 AirPlay 音频输出，在 macOS 的声音输出中选择接收器。连接双方需要位于可互访的局域网，访客网络的客户端隔离可能阻止发现和播放。

返回 MIUI Home、切换页面或删除卡片后，已开启的服务继续运行。可从控制中心或前台服务通知关闭。首页卡片选择器的「系统」分类提供 AirPlay 音乐卡片，可在有足够空间时调整为带封面、曲目信息和可用控制的尺寸。进度来自发送端的 RTP 进度报告。DACP 控制取决于发送端提供的服务，收到控制请求的结果不会直接改写播放状态。

接收能力为 AirPlay 音频的 RAOP/NTP 路径，采用 UxPlay 的会话处理和 Android 音频后端。LAN 内的设备均可发起连接。当前版本未提供经过验证的 PIN 认证，亦不包含视频镜像、视频播放、AirPlay 2 分组或多房间同步。

## 从源码构建

X08E 实机报告 Android 9（API 28）与 `armeabi-v7a,armeabi`。APK 包含 `armeabi-v7a`、`arm64-v8a` 与 `x86_64` 三种原生库。OpenSSL、libplist、Oboe 以及仅启用 ALAC/AAC 解码的 FFmpeg 从源码静态编译，链接进接收器。Oboe 使用 Android 公共音频后端。

- 需要 x86_64 Linux，Windows 开发机可使用默认 WSL 发行版。Linux 中安装 Bash、CMake 3.22 以上、Ninja、GNU Make、Perl、Python 3.12 以上、patch、flock。Gradle 和 Android SDK 仍按仓库项目配置使用，可运行于 Windows。
- 安装 Linux Android NDK `27.0.12077973`（r27）。默认路径为 `~/dormpanel-native/android-ndk-r27`。Linux 可设置 `DORMPANEL_AIRPLAY_NDK`，Windows 可通过 `WSLENV` 传入一个 Linux 路径。以 Windows 主机为例，可用 `wsl --exec bash -lc 'mkdir -p ~/dormpanel-native'` 创建缓存目录，再在 WSL 中解压官方 NDK。
- 运行 `./gradlew :app:assembleX08eTest` 或 Windows 的 `gradlew.bat :app:assembleX08eTest`。APK 的打包任务会生成对应 ABI 的原生库。首次构建需要下载锁定的源码。仅需编译 Kotlin 或运行 JVM 单元测试时，无需构建原生库。
- 如需单独编译 32 位库，可在仓库根目录运行 `bash app/src/main/airplay/build.sh app/build/generated/airplay/armeabi-v7a armeabi-v7a`。构建缓存默认位于 Linux 的 `~/dormpanel-native`，可用 `DORMPANEL_AIRPLAY_WORK` 指定。所有下载的源码归档均先验证 SHA-256。

[依赖锁](../app/src/main/airplay/sources.json) 包含确切提交、源码地址和 SHA-256。Android 接收器为 GPL-3.0，UxPlay 和随附的库遵循原项目许可证。DormPanel 的适配、上游源码锁及应用补丁的步骤均随该提交发布。APK 的 `assets/airplay/` 附带许可证和源码锁。分发 APK 时一并提供对应 DormPanel 提交和锁定的依赖源码。 [HomeView 源码清单](https://github.com/wegooo-cell/x08A-HomeView-Pro8/blob/HEAD/android-launcher/third_party/AirPlay-SOURCE.json) · [Android 接收器对应源码](https://github.com/jqssun/android-airplay-server/tree/a46cca0a6a1274e9e33c0e750f0f1c1eb1d881ea) · [UxPlay 对应源码](https://github.com/FDH2/UxPlay/tree/df67c212a433cf6dda3676dd40c097900d24e645)

## 定向验证

```shell
./gradlew :app:testDebugUnitTest --tests com.dormpanel.app.airplay.AirPlayMetadataTest
./gradlew :app:assembleX08eTest :app:assembleX08eTestAndroidTest -Pdormpanel.testBuildType=x08eTest -Pdormpanel.testSuffix=.airplaytest
```

使用隔离包 `com.dormpanel.app.airplaytest` 安装并运行 `AirPlayAndroidTest` 与 `ControlCenterAndroidTest`。服务测试需要 Wi-Fi 或有线局域网。用普通发送端验证时，从系统音频输出选中接收器，播放、暂停、恢复、断开后再次连接。可用 `adb shell dumpsys audio` 检查焦点，用 `adb shell dumpsys media.audio_flinger` 检查活动输出轨道，以 `adb shell dumpsys servicediscovery` 核对 DNS-SD 注册和注销。

2026 年 11 月 28 日在 1280×800 的 X08E 实机执行了验证。JVM 单元测试 5 项、X08E 仪器测试 8 项通过。实机回应局域网 mDNS 查询，兼容测试端通过 RAOP 发送 ALAC、曲目信息、封面和进度，并执行 FLUSH、恢复、TEARDOWN 与重连。观察到 44.1 kHz AAudio 活动轨道、返回 MIUI Home 后的背景输出、暂停和断开后的焦点释放，以及 Wi-Fi 关闭和恢复时的注销与重新注册。DACP 通过 X08E 上的测试端验证了发现、状态确认、请求头和下一首命令。

本轮使用协议测试端，尚未使用 iPhone、iPad、Mac 核对系统输出选择或各机型的控制能力，也未执行真实冷启动和长时间连续播放测试。
