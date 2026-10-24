package com.dormpanel.app.schedule

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.UUID

/** Read-only v2 evidence verifier. Never calls synchronize/stage/publish, including at 256. */
data class ScheduleV3MigrationPreview(val transactionId: String, val sourceFolder: String, val sourceKey: String,
    val frame: ScheduleV2Frame, val hashes: Map<String, String>, val tips: List<String>, val merged: ScheduleState) {
    fun evidence(): JSONObject {
        val baseline = requireNotNull(frame.baseline)
        fun snapshot(state: ScheduleState) = Base64.getEncoder().encodeToString(ScheduleSnapshotCodec.encode(baseline.snapshot().copy(state = state.copy(sync = null))))
        return JSONObject().put("transactionId", transactionId).put("sourceProtocol", "DPC2")
            .put("sourceFolder", sourceFolder).put("sourceKey", sourceKey).put("sourceDatasetId", baseline.datasetId)
            .put("sourceWriterId", frame.writerId)
            .put("sourceBaseline", Base64.getEncoder().encodeToString(ScheduleCommitCodec.encode(baseline)))
            .put("sourceFrontier", JSONArray(frame.frontier)).put("sourceHashes", JSONObject(hashes))
            .put("sourceTips", JSONArray(tips)).put("sourceLocal", snapshot(frame.local)).put("merged", snapshot(merged))
            .put("businessHash", SyncV3Codec.businessHash(merged)).put("rawHash", SyncV3Codec.hash(SyncV3Codec.stateBytes(merged))).put("independentBackupVerified", false)
            .put("unknownWritersStopped", false).put("serverWriteProhibited", false).put("sourceHistoryRetained", true)
    }
    companion object {
        fun restore(e: JSONObject): ScheduleV3MigrationPreview {
            fun state(key: String) = ScheduleSnapshotCodec.decode(Base64.getDecoder().decode(e.getString(key))).state
            fun strings(key: String) = e.getJSONArray(key).let { a -> (0 until a.length()).map(a::getString) }
            val hashes = e.getJSONObject("sourceHashes").let { o -> o.keys().asSequence().associateWith(o::getString) }
            val frame = ScheduleV2Frame(state("sourceLocal"), ScheduleCommitCodec.decode(Base64.getDecoder().decode(e.getString("sourceBaseline"))),
                strings("sourceFrontier"), e.getString("sourceWriterId"), null, false, null)
            return ScheduleV3MigrationPreview(e.getString("transactionId"), e.getString("sourceFolder"), e.getString("sourceKey"), frame, hashes, strings("sourceTips"), state("merged"))
        }
    }
}

object ScheduleV3Migration {
    fun inspect(frame: ScheduleV2Frame, known: Map<String, String>, remote: CommitStore, sourceFolder: String,
        transactionId: String = UUID.randomUUID().toString()): ScheduleV3MigrationPreview {
        check(frame.baseline != null && frame.frontier.isNotEmpty()) { "源 v2 基线或前沿缺失" }
        check(frame.pending == null && !frame.attempted && frame.conflict == null && frame.abandoned.isEmpty() && frame.unverifiable.isEmpty()) {
            "待确认 PUT、冲突或不可验证历史尚未恢复；本地修改已保留"
        }
        val key = requireNotNull(remote.bindingKey) { "源访问身份不可验证" }
        val graph = ScheduleCommitGraph(remote, frame.baseline.datasetId).load((known.keys + frame.frontier).distinct())
        check(graph.deferred.isEmpty() && graph.tips.isNotEmpty()) { "源历史不完整，停止迁移" }
        val hashes = graph.commits.mapValues { digest(ScheduleCommitCodec.encode(it.value)) }
        check(known.all { (id, hash) -> hashes[id] == hash }) { "源已知提交哈希变化" }
        check(frame.frontier.all { old -> graph.tips.any { old in graph.ancestors(it) } }) { "源前沿不完整" }
        // v2 may persist a synthetic aggregate baseline under a frontier commit's identity.
        // Prove its business state from the saved frontier, rather than pretending those
        // synthetic bytes were an immutable remote commit.
        val oldBranches = frame.frontier.map { graph.commits.getValue(it) }
        val oldBase = frame.baseline.copy(state = graph.baseline(frame.frontier))
        check(ScheduleMerge.same(ScheduleV2Plan(oldBase, oldBase, oldBranches).remoteState(), frame.baseline.state)) { "源本地基线不能由已验证前沿重建" }
        val tips = graph.tips
        val equivalent = tips.all { ScheduleMerge.same(graph.commits.getValue(tips.first()).state, graph.commits.getValue(it).state) }
        val base = if (equivalent) graph.commits.getValue(tips.first()).state else graph.baseline(tips)
        val localBase = if (equivalent && ScheduleMerge.same(frame.baseline.state, base) || frame.frontier.toSet() == tips.toSet() ||
            frame.frontier.all { old -> tips.all { old in graph.ancestors(it) } }) frame.baseline.state else graph.baseline((tips + frame.frontier).distinct())
        val result = ScheduleV2Plan(frame.baseline.copy(state = base), frame.baseline.copy(state = localBase), tips.map { graph.commits.getValue(it) }).merge(frame.local)
        check(result.conflicts.isEmpty()) { "源与未发布本地修改存在真实冲突；双方内容保留，停止迁移" }
        return ScheduleV3MigrationPreview(transactionId, sourceFolder, key, frame, hashes, tips, result.state)
    }
    fun unchanged(previous: ScheduleV3MigrationPreview, current: ScheduleV3MigrationPreview) {
        check(previous.sourceKey == current.sourceKey && previous.hashes == current.hashes && previous.tips == current.tips &&
            previous.frame.frontier == current.frame.frontier && previous.frame.baseline == current.frame.baseline &&
            SyncV3Codec.stateBytes(previous.frame.local).contentEquals(SyncV3Codec.stateBytes(current.frame.local)) &&
            SyncV3Codec.stateBytes(previous.merged).contentEquals(SyncV3Codec.stateBytes(current.merged))) {
            "迁移预览后源或本地状态变化；保留源与目标，禁止切换"
        }
    }
}
