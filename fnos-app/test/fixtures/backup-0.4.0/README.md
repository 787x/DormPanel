# 0.4.0 加密备份兼容夹具

`backup.dpbk` 是合并提交 `76dc937e8fc5d36f2d2ff104c2f3bbc75219edf5` 的 0.4.0 生产 `BackupManager.create()` 实际生成的 DPBK0001 密文，不是修改新归档的 appVersion，也不在测试时重新生成。生成环境为 fnOS x86_64 / 官方 Node 22.18.0 / 应用包用户；生产者 backup.mjs 已按统一换行与该提交逐字节核对。

生成时复用 `backup.test.mjs` 的 `fixture()`：四个合成工作区，含 attempted pending、capture 后编辑、abandoned、真实冲突、私有提交、虚构凭据及测试证书。口令为测试常量 `synthetic backup passphrase`。`expected.json` 保存当时 identity、catalog、etc 配置和四份完整工作区帧；所有内容仅用于独立临时安装，不含用户配置或凭据。

密文大小：18,275 字节。SHA-256：`df63c0d3525b4f419e26714d7396bd7a935ee46741e27ef8774eec334ebc7ca9`。

回归在 Linux 中直接验证旧密文并执行恢复，逐字节比较上述文件，检查恢复发布隔离，并分别修改 writerId、installationId 验证拒绝跨安装恢复。
