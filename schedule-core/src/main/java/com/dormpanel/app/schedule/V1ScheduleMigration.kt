package com.dormpanel.app.schedule

/** Read-only v1 remote verification. No conditional writes, diagnostics or head mutation. */
object V1ScheduleMigration {
    fun prepare(store: ScheduleSyncStore, transport: SharedScheduleTransport, account: WebDavAccount,
        folder: String, legacy: ScheduleSyncEngine.Legacy): ScheduleSnapshot {
        var frame = store.syncFrame()
        check(frame.conflict == null) { "先处理旧协议已保存的冲突；迁移尚未开始" }
        val remote = transport.inspect(account, folder)
        val dataset = frame.baseline?.datasetId ?: frame.pending?.datasetId ?: legacy.dataset
        check(dataset == remote.snapshot.datasetId) { "旧绑定 dataset 不一致；拒绝自动迁移" }
        val target = frame.baseline?.revision ?: legacy.revision ?: frame.pending?.revision ?: legacy.pendingRevision
        check(target != null) { "无法证明旧共同基线" }
        val chain = linkedMapOf<String, Pair<ByteArray, ScheduleSnapshot>>()
        var cursor = remote.bytes to remote.snapshot
        for (step in 0 until 256) {
            chain[cursor.second.revision] = cursor
            if (cursor.second.revision == target) break
            check(step < 255) { "旧历史遍历超限；迁移停止" }
            val parent = requireNotNull(cursor.second.parentRevision) { "旧共同基线不在远端历史中" }
            val previous = transport.historical(account, folder, dataset, parent)
            check(previous.second.generation + 1 == cursor.second.generation) { "旧历史父版本关系损坏" }
            cursor = previous
        }
        val proof = requireNotNull(chain[target]) { "旧历史遍历超限；迁移停止" }
        frame.baseline?.let { check(ScheduleSnapshotCodec.encode(it).contentEquals(proof.first)) { "旧共同基线内容改变" } }
        if (frame.baseline == null && legacy.revision != null) check(proof.second.generation == legacy.generation) { "PR31 基线代数不符" }
        store.archiveMigrationRecovery(proof.second)
        val pending = frame.pending
        if (pending != null) {
            chain[pending.revision]?.let { committed ->
                check(ScheduleSnapshotCodec.encode(pending).contentEquals(committed.first)) { "旧待确认发布内容改变" }
                check(store.integrateRemote(committed.first).isEmpty()) { "旧回执重放存在冲突；已保留" }
            } ?: run {
                check(frame.baseline != null) { "无法确认旧首次发布结果" }
                // A complete verified chain reaches the baseline without this candidate.
                store.discardUncommittedPublication()
            }
        } else if (legacy.pendingRevision != null && chain[legacy.pendingRevision] != null) {
            val receipt = chain.getValue(legacy.pendingRevision)
            check(digest(receipt.first) == legacy.pendingHash && receipt.second.generation == legacy.pendingGeneration) { "PR31 待确认回执不符" }
            if (frame.baseline == null) store.establishBaseline(receipt.first)
        }
        frame = store.syncFrame()
        if (frame.baseline == null) store.establishBaseline(proof.first)
        check(store.integrateRemote(remote.bytes).isEmpty()) { "旧远端有冲突；本地与冲突已保留，迁移停止" }
        return requireNotNull(store.syncFrame().baseline)
    }
}
