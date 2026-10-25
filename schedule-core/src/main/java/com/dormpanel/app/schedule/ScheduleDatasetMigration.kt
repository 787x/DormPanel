package com.dormpanel.app.schedule

import org.json.JSONObject
import java.util.Base64

class ScheduleDatasetWriteProtected : IllegalStateException("换库保护或首次加入验证已暂停课程编辑和导入；请在共享课表入口继续恢复，或取消尚未创建目标的迁移。")

/** Durable journal lives in the same Room transaction as checkpoints, never in preferences. */
data class ScheduleDatasetMigration(val id: String, val phase: String, val oldFolder: String, val newFolder: String,
    val source: SharedScheduleRemote, val target: SharedScheduleRemote, val writerId: String) {
    fun bytes(): ByteArray = JSONObject().put("id", id).put("phase", phase).put("oldFolder", oldFolder).put("newFolder", newFolder)
        .put("source", remoteJson(source)).put("target", remoteJson(target)).put("writerId", writerId).toString().toByteArray()
    companion object {
        private fun remoteJson(r: SharedScheduleRemote) = JSONObject().put("commit", Base64.getEncoder().encodeToString(requireNotNull(r.commitBytes)))
            .put("bindingKey", r.bindingKey).put("witness", JSONObject(r.witness))
        private fun remote(j: JSONObject): SharedScheduleRemote {
            val bytes = Base64.getDecoder().decode(j.getString("commit")); val c = ScheduleCommitCodec.decode(bytes)
            val hashes = j.getJSONObject("witness").let { o -> o.keys().asSequence().associateWith(o::getString) }
            val snapshot = ScheduleSnapshotCodec.encode(c.snapshot())
            return SharedScheduleRemote(SharedScheduleHead(c.datasetId, c.commitId, digest(snapshot)), null, snapshot, c.snapshot(), bytes, hashes, emptySet(), j.getString("bindingKey"))
        }
        fun decode(bytes: ByteArray): ScheduleDatasetMigration = JSONObject(String(bytes, Charsets.UTF_8)).let {
            ScheduleDatasetMigration(it.getString("id"), it.getString("phase"), it.getString("oldFolder"), it.getString("newFolder"), remote(it.getJSONObject("source")), remote(it.getJSONObject("target")), it.getString("writerId"))
        }
    }
}

/** Read-only, bounded verifier shared by preview, confirmation and resume. */
object ScheduleDatasetSwitch {
    fun clean(frame: ScheduleV2Frame) {
        check(frame.baseline != null && frame.pending == null && !frame.attempted && frame.conflict == null && frame.abandoned.isEmpty() && frame.unverifiable.isEmpty()) { "待确认上传、冲突或历史异常尚未恢复" }
        check(ScheduleMerge.same(frame.local, frame.baseline.state)) { "有未发布本地修改，禁止换库" }
    }
    fun inspect(local: ScheduleV2Store, old: CommitStore, target: CommitStore, previous: ScheduleDatasetMigration? = null): Pair<SharedScheduleRemote, SharedScheduleRemote> {
        val frame = local.v2Frame(); clean(frame)
        check(old.bindingKey != null && target.bindingKey != null && old.bindingKey != target.bindingKey) { "新旧目录必须不同" }
        val baseline = requireNotNull(frame.baseline)
        // Use all persistent witnesses as required paths, including PROPFIND omissions.
        val known = local.v2Witness()
        val graph = ScheduleCommitGraph(old, baseline.datasetId)
        try { graph.load((known.keys + frame.frontier).distinct(), frame.unverifiable) }
        finally { if (previous == null) local.v2Unverifiable(frame.unverifiable + graph.observed - graph.commits.keys) }
        check(graph.deferred.isEmpty() && graph.tips.isNotEmpty()) { "旧库历史不完整" }
        val hashes = graph.commits.mapValues { digest(ScheduleCommitCodec.encode(it.value)) }
        check(known.all { (id, sha) -> hashes[id] == sha }) { "旧库已知哈希变化" }
        if (previous == null) local.v2Observe(hashes)
        check(graph.tips.toSet() == frame.frontier.toSet() && graph.tips.all { ScheduleMerge.same(graph.commits.getValue(it).state, frame.local) }) { "旧库前沿变化，请同步并重新归档准备候选" }
        val tip = graph.commits.getValue(graph.tips.first())
        val bytes = ScheduleCommitCodec.encode(tip); val snapshot = ScheduleSnapshotCodec.encode(tip.snapshot())
        val source = SharedScheduleRemote(SharedScheduleHead(tip.datasetId, tip.commitId, digest(snapshot)), null, snapshot, tip.snapshot(), bytes, hashes, emptySet(), old.bindingKey)
        val next = ScheduleV2Binding.inspect(target, previous?.target)
        val root = ScheduleCommitCodec.decode(requireNotNull(next.commitBytes))
        check(root.datasetId != baseline.datasetId && root.parents.isEmpty() && next.witness.size == 1 && next.unverifiable.isEmpty()) { "目标必须是独立数据集的单个完整根" }
        check(ScheduleMerge.same(frame.local, root.state)) { "源、目标全部业务字段或稳定身份不等价" }
        if (previous != null) check(source.witness == previous.source.witness && source.head == previous.source.head && source.bindingKey == previous.source.bindingKey) { "旧源已变化，预览过期；保留两库并重新检查" }
        return source to next
    }
}
