package com.dormpanel.app.schedule

import java.util.UUID

data class ScheduleV2Frame(val local: ScheduleState, val baseline: ScheduleCommit?, val frontier: List<String>,
    val writerId: String, val pending: ByteArray?, val attempted: Boolean, val conflict: ScheduleV2Plan?, val abandoned: Map<String, String> = emptyMap(), val unverifiable: Set<String> = emptySet())

/** Immutable inputs to a durable conflict decision. Branch order is commit UUID order, not priority. */
data class ScheduleV2Plan(val base: ScheduleCommit, val localBase: ScheduleCommit, val branches: List<ScheduleCommit>, private val sharedRegistry: SharedTypeRegistry = SharedTypes.registry()) {
    fun merge(local: ScheduleState, choice: ScheduleMerge.Choice? = null): ScheduleMerge.Result {
        val conflicts = linkedMapOf<String, ScheduleMerge.Conflict>()
        val details = mutableMapOf<String, LinkedHashSet<String>>()
        val truncated = mutableSetOf<String>()
        fun remember(c: ScheduleMerge.Conflict) {
            val old = conflicts[c.key]
            check(old != null || conflicts.size < 256) { "冲突条目超过本轮处理上限；本地修改和远端版本已保留" }
            conflicts[c.key] = if (old == null) c else old.copy(keys = old.keys + c.keys)
        }
        branches.forEachIndexed { i, a -> branches.drop(i + 1).forEachIndexed { offset, b ->
            ScheduleMerge.merge(base.state, a.state, b.state, maxConflicts = 256, sharedRegistry = sharedRegistry).conflicts.forEach { c ->
                remember(c)
                val text = c.description.replace("\n本地：", "\n远端版本 ${i + 1}：")
                    .replace("\n远端：", "\n远端版本 ${i + offset + 2}：").take(4000)
                c.keys.forEach { key ->
                    val kept = details.getOrPut(key) { linkedSetOf() }
                    if (kept.size < 8) kept += text else if (text !in kept) truncated += key
                }
            }
        } }
        var remote = branches.first().state
        branches.drop(1).forEach {
            val next = ScheduleMerge.merge(base.state, remote, it.state, ScheduleMerge.Choice.REMOTE, maxConflicts = 256, sharedRegistry = sharedRegistry)
            remote = next.state; next.conflicts.forEach(::remember)
        }
        val result = ScheduleMerge.merge(localBase.state, local, remote, choice, conflicts.values.flatMap { it.keys }.toSet(), maxConflicts = 256, sharedRegistry = sharedRegistry)
        val displayed = (result.conflicts + conflicts.values).groupBy { it.key }.values.map { rows -> rows.first().copy(keys=rows.flatMap { it.keys }.toSet()) }.map { c ->
            val related = c.keys.flatMap { details[it].orEmpty() }.distinct()
            if (related.isEmpty()) c else c.copy(description = c.description + "\n远端版本之间的差异：\n" + related.take(8).joinToString("\n") +
                if (related.size > 8 || c.keys.any { it in truncated }) "\n另有远端差异已完整保存；可暂不处理。" else "")
        }
        return ScheduleMerge.Result(result.state, displayed)
    }
    fun token(): String = digest(buildString {
        append(digest(ScheduleCommitCodec.encode(base))); append(digest(ScheduleCommitCodec.encode(localBase)))
        branches.sortedBy { it.commitId }.forEach { append(digest(ScheduleCommitCodec.encode(it))) }
    }.toByteArray(Charsets.US_ASCII))
    fun remoteState(): ScheduleState = branches.drop(1).fold(branches.first().state) { state, branch ->
        ScheduleMerge.merge(base.state, state, branch.state, ScheduleMerge.Choice.REMOTE, maxConflicts = 256, sharedRegistry = sharedRegistry).state
    }
}

interface ScheduleV2Store {
    fun v2Migration(): ScheduleDatasetMigration? = null
    fun v2DatasetPaused(): Boolean = v2Migration() != null || v2DatasetInspection() != null
    fun v2DatasetResult(): String? = null
    fun v2DatasetInspection(): List<String>? = null
    fun v2BeginDatasetInspection(oldFolder: String, newFolder: String, datasetId: String) { error("此存储不支持持久暂停") }
    fun v2CancelDatasetInspection() { error("此存储不支持持久暂停") }
    fun v2Witness(): Map<String, String> = emptyMap()
    fun v2MigrationPaths(key: String, observed: List<String> = emptyList()): List<String> = observed
    fun v2PrepareMigration(journal: ScheduleDatasetMigration) { error("此存储不支持安全换库") }
    fun v2CommitMigration(id: String) { error("此存储不支持安全换库") }
    fun v2FinishMigration(id: String, cancel: Boolean = false) { error("此存储不支持安全换库") }
    fun v2RequireSyncAllowed() { check(!v2DatasetPaused()) { "换库保护已暂停自动同步与重试；请继续或取消换库" } }
    fun v2CheckTarget(identity: String) {}
    fun v2Unverifiable(ids: Set<String>) {}
    fun v2Frame(): ScheduleV2Frame
    fun v2Migrate(baseline: ScheduleSnapshot)
    fun v2Bind(remote: SharedScheduleRemote) { error("此存储不支持事务绑定") }
    fun v2Start(root: ScheduleCommit, binding: Boolean)
    fun v2Stage(bytes: ByteArray)
    fun v2Attempt()
    fun v2Observe(commits: Map<String, String>)
    fun v2RenewPending()
    fun v2Receipt(bytes: ByteArray)
    fun v2Apply(plan: ScheduleV2Plan): List<ScheduleMerge.Conflict>
    fun v2Resolve(choice: ScheduleMerge.Choice, expectedToken: String? = null)
}

class AppendOnlyScheduleSync(private val local: ScheduleV2Store, backend: CommitStore) {
    private var started = System.nanoTime()
    private var requests = 0
    private var transferred = 0L
    private fun budget(cost: Int) {
        requests += cost
        check(requests <= 512 && System.nanoTime() - started < 90_000_000_000L) { "本轮同步请求或时间预算已用尽；本地修改和回执已保留" }
    }
    private val remote = object : CommitStore {
        override val bindingKey get() = backend.bindingKey
        override fun list(): List<String> { budget(1); return backend.list() }
        override fun read(commitId: String): ByteArray? {
            budget(1)
            // Reserve a full response for a failed/interrupted stream; successful reads release the unused part.
            transferred += ScheduleCommitCodec.MAX_BYTES
            check(transferred <= 64L * 1024 * 1024) { "本轮同步读取预算超限；保留本地修改" }
            return backend.read(commitId).also { transferred -= ScheduleCommitCodec.MAX_BYTES - (it?.size ?: 0) }
        }
        override fun publish(bytes: ByteArray, allowCreate: Boolean) { budget(3); backend.publish(bytes, allowCreate) }
        override fun publishWithIntent(bytes: ByteArray, allowCreate: Boolean, beforeWrite: () -> Unit) {
            budget(3); backend.publishWithIntent(bytes, allowCreate, beforeWrite)
        }
    }
    fun synchronize(createDataset: String? = null): ScheduleSyncEngine.Outcome {
        local.v2RequireSyncAllowed()
        started = System.nanoTime(); requests = 0; transferred = 0
        remote.bindingKey?.let(local::v2CheckTarget)
        var frame = local.v2Frame()
        if (frame.baseline == null && frame.pending == null) {
            val dataset = requireNotNull(createDataset) { "尚未绑定 v2；旧绑定需要明确迁移" }
            check(remote.list().isEmpty()) { "目录已有提交；拒绝创建无关根" }
            local.v2Start(ScheduleCommit(dataset, UUID.randomUUID().toString(), frame.writerId, emptyList(), frame.local), false)
        }
        repeat(3) {
            frame = local.v2Frame()
            frame.pending?.let { bytes ->
                try {
                    remote.publishWithIntent(bytes, !frame.attempted) {
                        check(remote.list().size < 256) { "观察到的提交数量已达上限；保留本地修改" }
                        local.v2Attempt()
                    }
                } catch (_: CommitPathOccupied) { local.v2RenewPending(); return@repeat }
                local.v2Receipt(bytes)
                frame = local.v2Frame()
            }
            val baseline = requireNotNull(frame.baseline)
            // Own interrupted paths are quarantined explicitly; unrelated invalid files still stop the graph.
            val anomalies = mutableSetOf<String>()
            val knownAbandoned = frame.abandoned
            val observed = object : CommitStore by remote {
                override fun list(): List<String> {
                    val listed = remote.list()
                    val recovered = mutableSetOf<String>()
                    check(listed.distinct().size == listed.size && listed.size <= 256)
                    knownAbandoned.forEach { (id, expected) ->
                        val bytes = remote.read(id)
                        if (bytes == null || runCatching { ScheduleCommitCodec.decode(bytes) }.isFailure) anomalies += id
                        else {
                            check(digest(bytes) == expected) { "本机待确认文件变成不同的有效提交；拒绝采纳" }
                            recovered += id
                        }
                    }
                    return (listed.filter { it !in anomalies } + recovered).distinct()
                }
            }
            val graph = ScheduleCommitGraph(observed, baseline.datasetId)
            var verifiedGraph = false
            try {
                graph.load(frame.frontier, frame.unverifiable - frame.abandoned.keys)
                verifiedGraph = true
            } finally {
                val unresolved = if (verifiedGraph) graph.deferred.keys + anomalies else frame.unverifiable + graph.observed + anomalies
                local.v2Unverifiable(unresolved)
            }
            local.v2Observe(graph.commits.mapValues { digest(ScheduleCommitCodec.encode(it.value)) })
            val tips = graph.tips
            check(tips.size in 1..ScheduleCommitCodec.MAX_PARENTS) { "有效分支数量超限" }
            check(tips.sumOf { graph.sizes.getValue(it).toLong() } * tips.size <= 64L * 1024 * 1024) { "并发合并工作量超限；保留本地修改" }
            val covered = frame.frontier.all { old -> tips.any { old in graph.ancestors(it) } }
            check(covered) { "目录未观察到已确认历史；未完成同步" }
            val equivalent = tips.all { ScheduleMerge.same(graph.commits.getValue(tips.first()).state, graph.commits.getValue(it).state) }
            val common = if (equivalent) graph.commits.getValue(tips.first()).state else graph.baseline(tips)
            val localBase = if (equivalent && ScheduleMerge.same(baseline.state, common) || frame.frontier.toSet() == tips.toSet() || frame.frontier.all { old -> tips.all { old in graph.ancestors(it) } }) baseline.state
                else graph.baseline((tips + frame.frontier).distinct())
            val plan = ScheduleV2Plan(baseline.copy(state = common), baseline.copy(state = localBase), tips.map { graph.commits.getValue(it) })
            val found = local.v2Apply(plan)
            if (found.isNotEmpty()) return ScheduleSyncEngine.Outcome("存在冲突 · ${found.size} 项；本地修改已保存", found)
            frame = local.v2Frame()
            if (tips.all { ScheduleMerge.same(graph.commits.getValue(it).state, frame.local) }) {
                val unverifiable = (anomalies + graph.deferred.keys).size
                return ScheduleSyncEngine.Outcome(if (unverifiable == 0) "已检查观察到的版本 · 已发布；其他设备接收情况未知"
                    else "已发布有效课程 · $unverifiable 个文件暂不可验证/未确认；尚未完成全部版本同步", checkState = if (unverifiable == 0) "VERIFIED" else "PARTIAL")
            }
            check(graph.commits.size < 256) { "提交数量已达上限；保留本地修改" }
            val candidate = ScheduleCommit(baseline.datasetId, UUID.randomUUID().toString(), frame.writerId, tips, frame.local)
            local.v2Stage(ScheduleCommitCodec.encode(candidate))
        }
        return ScheduleSyncEngine.Outcome("有待上传修改 · 等待下次有界同步")
    }
}

class WebDavCommitStore(private val account: WebDavAccount, folder: String,
    private val client: WebDavClient = WebDavClient()) : CommitStore {
    val directory = SharedScheduleTransport(client).folder(account, folder)
    override val bindingKey = digest(("WebDAV\n" + directory + "\n" + account.username).toByteArray(Charsets.UTF_8))
    private fun path(id: String): String {
        ScheduleCommitCodec.uuid(id)
        return client.url(directory).newBuilder().addPathSegment("commit-$id.dpc").build().toString()
    }
    override fun list(): List<String> = client.list(account, directory, strict = true).filter {
        it.url.trimEnd('/') != directory.trimEnd('/')
    }.map { item ->
        val target = client.url(item.url)
        val name = client.filename(target)
        require(!item.folder && name.matches(Regex("commit-[0-9a-f-]{36}\\.dpc"))) { "v2 目录混入未知文件" }
        val id = name.removePrefix("commit-").removeSuffix(".dpc")
        require(path(id) == item.url && (item.length == null || item.length in 0..ScheduleCommitCodec.MAX_BYTES.toLong())) { "Invalid commit path/size" }
        id
    }.also { require(it.distinct().size == it.size && it.size <= 256) { "目录重复或历史数量超限" } }
    override fun read(commitId: String): ByteArray? = client.readResource(account, path(commitId), ScheduleCommitCodec.MAX_BYTES)?.bytes
    override fun publish(bytes: ByteArray, allowCreate: Boolean) = publishWithIntent(bytes, allowCreate) {}
    override fun publishWithIntent(bytes: ByteArray, allowCreate: Boolean, beforeWrite: () -> Unit) {
        val c = ScheduleCommitCodec.decode(bytes)
        val existing = read(c.commitId)
        if (existing != null) {
            if (!existing.contentEquals(bytes)) throw CommitPathOccupied()
            ScheduleCommitCodec.decode(existing); return
        }
        if (!allowCreate) throw CommitPathOccupied()
        // UUIDv4 ownership is the concurrency primitive. Never retry PUT on this path after any outcome.
        beforeWrite()
        client.ordinaryPut(account, path(c.commitId), bytes)
        val verified = requireNotNull(read(c.commitId)) { "上传后无法回读；保留待确认提交" }
        check(verified.contentEquals(bytes)) { "上传回读不一致；未确认发布" }
        ScheduleCommitCodec.decode(verified)
    }
}

/** Explicit test directory, two concurrent writers, bounded eventual discovery. No v1 files. */
object AppendOnlyWebDavDiagnostic {
    fun run(account: WebDavAccount, testParent: String, client: WebDavClient = WebDavClient()): String {
        val parent = SharedScheduleTransport(client).folder(account, testParent)
        val dir = client.url(parent).newBuilder().addPathSegment("pr35-probe-${UUID.randomUUID()}").addPathSegment("").build().toString()
        client.makeCollection(account, dir)
        val store = WebDavCommitStore(account, dir, client)
        val dataset = UUID.randomUUID().toString()
        val objects = List(2) { ScheduleCommitCodec.encode(ScheduleCommit(dataset, UUID.randomUUID().toString(), UUID.randomUUID().toString(), emptyList(), ScheduleState())) }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try { pool.invokeAll(objects.map { bytes -> java.util.concurrent.Callable { store.publish(bytes) } }).forEach { it.get() } }
        finally { pool.shutdown() }
        val expected = objects.map { ScheduleCommitCodec.decode(it).commitId }.toSet()
        repeat(3) { attempt ->
            val listed = store.list().toSet()
            if (listed == expected) {
                objects.forEach { check(store.read(ScheduleCommitCodec.decode(it).commitId)!!.contentEquals(it)) }
                return "v2 独立测试目录：并发普通 PUT、GET/SHA-256、Depth:1 列举通过（第 ${attempt + 1} 次观察）；不代表双端 RC 验收"
            }
        }
        error("列举延迟或缺失：有界检查未发现全部测试文件；不能确认兼容")
    }
}
