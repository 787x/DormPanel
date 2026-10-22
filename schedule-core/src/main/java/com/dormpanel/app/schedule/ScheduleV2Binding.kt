package com.dormpanel.app.schedule

/** Read-only first-binding inspection. Uses the same bounded DAG verifier as normal sync. */
object ScheduleV2Binding {
    fun inspect(backend: CommitStore, previous: SharedScheduleRemote? = null): SharedScheduleRemote {
        var requests = 0
        var bytesRead = 0L
        val started = System.nanoTime()
        fun budget() {
            check(++requests <= 512 && System.nanoTime() - started < 90_000_000_000L) { "绑定检查预算已用尽，请重新预览" }
        }
        val remote = object : CommitStore by backend {
            override fun list(): List<String> { budget(); return backend.list() }
            override fun read(commitId: String): ByteArray? {
                budget()
                bytesRead += ScheduleCommitCodec.MAX_BYTES
                check(bytesRead <= 64L * 1024 * 1024) { "绑定检查读取预算超限" }
                return backend.read(commitId).also { bytesRead -= ScheduleCommitCodec.MAX_BYTES - (it?.size ?: 0) }
            }
        }
        if (previous != null) check(previous.bindingKey == backend.bindingKey) { "服务器、目录或账号已变化，请重新预览" }
        val ids = remote.list()
        check(ids.isNotEmpty() && ids.size <= 256 && ids.distinct().size == ids.size) { "尚无 v2 数据集或历史数量超限" }
        val dataset = previous?.head?.datasetId ?: ids.take(8).firstNotNullOfOrNull { id ->
            remote.read(id)?.let { runCatching { ScheduleCommitCodec.decode(it) }.getOrNull() }
        }?.datasetId ?: error("前八个文件均无法验证；请检查目录，保留本地数据")
        val graph = ScheduleCommitGraph(remote, dataset).load(previous?.witness?.keys?.toList().orEmpty(), previous?.unverifiable.orEmpty())
        check(graph.tips.size == 1) { "首次绑定发现并发分支；请先在已有编辑端处理合并，再刷新预览" }
        val c = graph.commits.getValue(graph.tips.single())
        val bytes = ScheduleSnapshotCodec.encode(c.snapshot())
        val result = SharedScheduleRemote(SharedScheduleHead(c.datasetId, c.commitId, digest(bytes)), null, bytes,
            c.snapshot(), ScheduleCommitCodec.encode(c), graph.commits.mapValues { digest(ScheduleCommitCodec.encode(it.value)) },
            graph.deferred.keys.toSet(), backend.bindingKey)
        if (previous != null) check(previous.head == result.head && previous.witness == result.witness &&
            previous.unverifiable == result.unverifiable && previous.commitBytes?.contentEquals(result.commitBytes!!) == true) {
            "远端身份、提交或历史状态已变化；本地未绑定，请重新预览"
        }
        return result
    }
}
