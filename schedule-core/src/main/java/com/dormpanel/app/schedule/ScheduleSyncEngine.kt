package com.dormpanel.app.schedule

import java.util.UUID

class ScheduleHistoryPending : IllegalStateException("正在分段核实历史快照；进度已保存，可稍后继续同步")

/** Network work never holds a Room transaction. Every integration re-reads the current local rows. */
class ScheduleSyncEngine(private val store: ScheduleSyncStore, private val transport: SharedScheduleTransport,
    private val account: WebDavAccount, private val folder: String) {
    data class Legacy(val dataset: String, val revision: String?, val generation: Long,
        val pendingRevision: String? = null, val pendingHash: String? = null, val pendingGeneration: Long = 0)
    data class Outcome(val message: String, val conflicts: List<ScheduleMerge.Conflict> = emptyList())

    /** Bounded history traversal proves ancestry, including a committed but unacknowledged publication. */
    private fun history(remote: SharedScheduleRemote, revision: String, generation: Long): Pair<ByteArray, ScheduleSnapshot>? {
        var bytes = remote.bytes; var value = remote.snapshot
        store.historyCursor(remote.bytes)?.let { saved ->
            val cursor = ScheduleSnapshotCodec.decode(saved)
            if (cursor.generation >= generation) { bytes = saved; value = cursor }
        }
        repeat(256) {
            if (value.revision == revision) {
                check(value.generation == generation) { "Historical generation mismatch" }
                return bytes to value
            }
            if (value.generation <= generation) return null
            val parent = requireNotNull(value.parentRevision) { "Missing parent revision" }
            val previous = transport.historical(account, folder, value.datasetId, parent)
            check(previous.second.generation == value.generation - 1) { "Broken remote history" }
            bytes = previous.first; value = previous.second
        }
        store.saveHistoryCursor(remote.bytes, bytes)
        throw ScheduleHistoryPending()
    }
    private fun conflicts(items: List<ScheduleMerge.Conflict>) = Outcome("存在冲突 · ${items.size} 项；本地修改已保留", items)
    private fun publishInitial(dataset: String, local: ScheduleState) {
        val snapshot = ScheduleSnapshot(dataset, UUID.randomUUID().toString(), null, System.currentTimeMillis(), local, 1)
        val bytes = ScheduleSnapshotCodec.encode(snapshot)
        store.stagePublication(bytes, local)
        transport.publish(account, folder, bytes, null, null)
    }

    fun synchronize(createDataset: String? = null, legacy: Legacy? = null): Outcome {
        var initialDataset = createDataset
        var frame = store.syncFrame()
        if (frame.baseline == null && frame.pending == null && (legacy?.revision != null || legacy?.pendingRevision != null)) {
            val upgrade = requireNotNull(legacy)
            if (upgrade.revision == null && transport.head(account, folder) == null) {
                // PR31's very first publication never activated a head. Preserve its dataset ID.
                initialDataset = upgrade.dataset
            } else {
                val remote = transport.inspect(account, folder)
                check(remote.head.datasetId == upgrade.dataset) { "PR31 dataset 身份不一致；拒绝自动升级" }
                val pending = upgrade.pendingRevision?.let { history(remote, it, upgrade.pendingGeneration) }
                val old = if (pending != null) {
                    check(digest(pending.first) == upgrade.pendingHash) { "PR31 待确认回执不匹配" }; pending
                } else upgrade.revision?.let { history(remote, it, upgrade.generation) }
                check(old != null) { "无法证明 PR31 共同基线；请恢复原历史快照，禁止覆盖远端" }
                store.establishBaseline(old.first)
                frame = store.syncFrame()
            }
        }
        if (frame.baseline == null && frame.pending == null) {
            check(initialDataset != null) { "尚未绑定共享课表" }
            check(transport.head(account, folder) == null) { "Shared folder already contains a dataset; first publication refused" }
            publishInitial(initialDataset, frame.local)
        }
        // A CAS race restarts from durable local state and the new head, never retries old bytes.
        repeat(4) {
            frame = store.syncFrame()
            val pending = frame.pending
            if (pending != null && frame.baseline == null && transport.head(account, folder) == null) {
                // Initial upload did not activate a head. Its exact capture remains local.
                store.discardUncommittedPublication()
                // A fresh immutable filename captures any newer local edits. Recovery stays bounded.
                publishInitial(pending.datasetId, store.syncFrame().local)
                return@repeat
            }
            val remote = transport.inspect(account, folder)
            if (pending != null) {
                check(remote.head.datasetId == pending.datasetId) { "Pending publication dataset changed" }
                val committed = history(remote, pending.revision, pending.generation)
                if (committed != null) {
                    val found = store.integrateRemote(committed.first)
                    if (found.isNotEmpty()) return conflicts(found)
                } else {
                    val base = frame.baseline
                    check(base != null && history(remote, base.revision, base.generation) != null) {
                        "无法证明待确认发布结果；保留回执和本地修改"
                    }
                    store.discardUncommittedPublication()
                }
            }
            val found = store.integrateRemote(remote.bytes)
            if (found.isNotEmpty()) return conflicts(found)
            frame = store.syncFrame()
            if (!frame.dirty) return Outcome("已同步")
            val base = requireNotNull(frame.baseline)
            // A concurrent worker may have advanced Room; use only the ETag of the inspected head.
            if (base.revision != remote.head.revision) return@repeat
            val candidate = ScheduleSnapshot(base.datasetId, UUID.randomUUID().toString(), base.revision,
                System.currentTimeMillis(), frame.local, base.generation + 1)
            val bytes = ScheduleSnapshotCodec.encode(candidate)
            store.stagePublication(bytes, frame.local)
            try {
                transport.publish(account, folder, bytes, base.revision, remote.etag)
            } catch (_: SharedScheduleRace) { return@repeat }
            // Loop reconciles the receipt and any local edits saved during the upload.
        }
        return Outcome("有待上传修改 · 远端繁忙，等待重试")
    }
}
