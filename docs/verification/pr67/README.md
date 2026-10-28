# PR67 Android 正式发布候选验收

日期：2026-10-28（Asia/Shanghai）。

基线：dd738cf3bc2474b498089cd9202b9b8a0b6cd18f（PR66）。
实际构建提交：f54002fad3a0a7bc5402192b3b3230fb8426d34b，构建时 trackedChanges 为空。本文是随后补录的证据。
冻结目录：artifacts/release/android-v0.1.10-candidate1；包含 APK、sidecar、SHA256SUMS.txt、build-record.json，无 HA ZIP / FPK。

| APK | 字节 | SHA-256 |
| --- | --- | --- |
| DormPanel-X08E-0.1.10.apk | 16753448 | 34314f5cd1af9cc7fcdcb418d6dbf24c53bc3d3dc694096157cf6d5343a9e844 |
| DormPanel-Phone-0.1.9.apk | 10524091 | cd7e6e12c601fa982e8f87d81b28bb1a8f491827f1ff6febc046d4b4e3a6d9d1 |

## 实际验证
- build-release.ps1 -AndroidOnly -SkipTests：两款 assembleRelease 及 release lint 成功。
- verify-release.ps1 -AndroidOnly：通过。X08E com.dormpanel.app / 0.1.10 / 13；Phone com.dormpanel.timetable / 0.1.9 / 11；均 minSdk 28 / targetSdk 37、非 debuggable、恰好一个批准签名。
- 批准证书 SHA-256：32d68b5c6ad0bfd1b87aa0d54514ef71c33838e7643eb038488af7ca2add17f7。
- 本地 pr58/candidate-3 旧正式 APK 与 GitHub v0.1.9 公开资产 digest 一致：X08E a14c75d88206482e69b8ce1411c672d8a5dc6736f3c7e152f723492ebe1895c4；Phone f424afa2108bd4e9a0574083d8d05dc79a35def7c117ec80ed6dbc4bc45a1320。两份旧包 apksigner 验证通过，证书与本轮一致。直接下载超时后取消，未使用空下载文件作证据。
- Python zipfile.testzip：全部条目 CRC 通过；hashlib 核对冻结 APK、AGP 精确输出、build-record 与 SHA256SUMS 一致。
- test-release-validation.ps1：39 项 mock 保护检查通过。
- test-release-artifacts.ps1：8 项真实负向检查通过，覆盖错误身份/哈希、无签名、错误证书、旧缓存和覆盖冻结目录。
- git diff --check 通过；复查远端 main 未变化、目标 tag 未使用。

## 边界与发布状态
未获日用设备安装授权，没有安装或改动设备数据。两端正式包原位升级、启动、课程/设置保留、页面导航、正式 MIUI 双小部件均未执行。复用 PR62–66 历史记录，不扩充为本轮正式包实机 PASS。没有重复全量矩阵、soak、fnOS 或 HA 验收。

PR67 已创建，等待审查与发布授权；未合并、未创建 tag / Release，Latest 仍为 v0.1.9。README 新下载链接为发布目标。
最终 tag 必须指向实际构建源码；若合并后重新构建，必须使用新候选目录与新哈希，不混入旧附件。
