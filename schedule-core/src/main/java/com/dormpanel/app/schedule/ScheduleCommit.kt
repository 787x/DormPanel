package com.dormpanel.app.schedule

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.UUID

/** Portable immutable object; see docs/sync-v2-protocol.md for the complete wire contract. */
data class ScheduleCommit(val datasetId: String, val commitId: String, val writerId: String,
    val parents: List<String>, val state: ScheduleState) {
    fun snapshot() = ScheduleSnapshot(datasetId, commitId, null, 1, state.copy(sync = null), 1)
}

object ScheduleCommitCodec {
    const val MAX_BYTES = ScheduleSnapshotCodec.MAX_BYTES + 8192
    const val MAX_PARENTS = 64
    fun uuid(s: String) { require(runCatching { UUID.fromString(s).toString() == s }.getOrDefault(false)) { "Invalid v2 UUID" } }
    private fun canonical(s: ScheduleState) = s.copy(sync = null, events = s.events.sortedBy { it.id },
        entries = s.entries.sortedBy { it.id }, sources = s.sources.sortedBy { it.id }, imported = s.imported.sortedBy { it.id },
        dayAdjustments = s.dayAdjustments.sortedBy { it.date }, classOverrides = s.classOverrides.sortedBy { it.key },
        profiles = s.profiles.toSortedMap().mapValues { (_, p) -> p.copy(phases = p.phases.sortedBy { it.effectiveFrom }.map {
            phase -> phase.copy(periods = phase.periods.sortedBy { it.periodNumber })
        }) })
    fun encode(c: ScheduleCommit): ByteArray {
        uuid(c.datasetId); uuid(c.commitId); uuid(c.writerId)
        require(c.parents.size <= MAX_PARENTS && c.parents.distinct().size == c.parents.size && c.commitId !in c.parents)
        c.parents.forEach(::uuid)
        val snapshot = ScheduleSnapshotCodec.encode(c.snapshot().copy(state = canonical(c.state)))
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeInt(0x44504332); out.writeInt(2)
            listOf(c.datasetId, c.commitId, c.writerId).forEach { out.write(it.toByteArray(Charsets.US_ASCII)) }
            out.writeInt(c.parents.size); c.parents.sorted().forEach { out.write(it.toByteArray(Charsets.US_ASCII)) }
            out.writeInt(snapshot.size); out.write(snapshot)
        }
        val body = buffer.toByteArray()
        return body + MessageDigest.getInstance("SHA-256").digest(body)
    }
    fun decode(bytes: ByteArray): ScheduleCommit {
        require(bytes.size in 200..MAX_BYTES) { "v2 commit size limit" }
        val body = bytes.copyOfRange(0, bytes.size - 32)
        require(MessageDigest.isEqual(bytes.copyOfRange(bytes.size - 32, bytes.size), MessageDigest.getInstance("SHA-256").digest(body))) { "v2 checksum failed" }
        return DataInputStream(body.inputStream()).use { input ->
            require(input.readInt() == 0x44504332 && input.readInt() == 2) { "Unsupported commit protocol" }
            fun id(): String = ByteArray(36).also(input::readFully).toString(Charsets.US_ASCII).also(::uuid)
            val dataset = id(); val commit = id(); val writer = id()
            val n = input.readInt(); require(n in 0..MAX_PARENTS)
            val parents = List(n) { id() }; require(parents == parents.distinct().sorted() && commit !in parents)
            val length = input.readInt(); require(length == input.available() && length <= ScheduleSnapshotCodec.MAX_BYTES)
            val s = ScheduleSnapshotCodec.decode(ByteArray(length).also(input::readFully))
            require(s.datasetId == dataset && s.revision == commit && s.parentRevision == null && s.generation == 1L && s.createdAt == 1L)
            ScheduleCommit(dataset, commit, writer, parents, s.state).also {
                require(encode(it).contentEquals(bytes)) { "Noncanonical v2 object" }
            }
        }
    }
}

interface CommitStore {
    val bindingKey: String? get() = null
    fun list(): List<String>
    fun read(commitId: String): ByteArray?
    /** Must never replace a different object. Success means full readback verification. */
    fun publish(bytes: ByteArray, allowCreate: Boolean = true)
    /** Persist write intent before starting a write; GET failures alone must not imply an attempted PUT. */
    fun publishWithIntent(bytes: ByteArray, allowCreate: Boolean, beforeWrite: () -> Unit) {
        beforeWrite(); publish(bytes, allowCreate)
    }
}

class CommitPathOccupied : IllegalStateException("提交路径已有不同内容；已保留异常文件，需要新身份重试")

/** Bounded, fully verified observed graph. Listing is an observation, never a global head. */
class ScheduleCommitGraph(private val remote: CommitStore, val dataset: String) {
    val commits = linkedMapOf<String, ScheduleCommit>()
    val sizes = mutableMapOf<String, Int>()
    val deferred = linkedMapOf<String, String>()
    val observed = linkedSetOf<String>()
    private var bytesRead = 0L
    private val visiting = mutableSetOf<String>()
    fun load(required: List<String> = emptyList(), revisit: Set<String> = emptySet()): ScheduleCommitGraph {
        val listed = remote.list()
        check(listed.size <= 256 && listed.distinct().size == listed.size) { "提交数超限或目录重复；未完成同步" }
        val discovery = (listed + revisit).distinct().sorted()
        check(discovery.size <= 256) { "已观察的提交路径数量超限" }
        observed += discovery
        discovery.forEach { visit(it, false) }
        required.distinct().sorted().forEach { visit(it, true) }
        check(commits.values.count { it.parents.isEmpty() } <= 1) { "多个无关根提交；拒绝猜测数据身份" }
        return this
    }
    private fun visit(id: String, required: Boolean) {
        ScheduleCommitCodec.uuid(id)
        observed += id
        check(observed.size <= 256) { "历史遍历超限" }
        check(id !in visiting) { "Cyclic commit history" }
        if (id in commits) return
        if (id in deferred) { require(!required) { "已引用祖先暂不可验证；拒绝合并" }; return }
        check(commits.size + visiting.size < 256) { "历史遍历超限；保留本地修改" }
        visiting += id
        val bytes = try { remote.read(id) } catch (error: java.io.IOException) {
            if (required || error is WebDavException && !error.reason.startsWith("Network")) throw error
            deferred[id] = "暂时无法读取"; visiting -= id; return
        }
        if (bytes == null) {
            require(!required) { "提交或祖先缺失；未完成同步" }
            deferred[id] = "已观察路径无法读取"; visiting -= id; return
        }
        bytesRead += bytes.size
        check(bytesRead <= 32L * 1024 * 1024) { "单轮历史字节数超限" }
        if (bytes.size >= 8 && String(bytes, 0, 3, Charsets.US_ASCII) == "DPC")
            require(java.nio.ByteBuffer.wrap(bytes, 4, 4).int == 2) { "不支持的提交协议；停止应用" }
        val value = try { ScheduleCommitCodec.decode(bytes) } catch (error: Exception) {
            val intactEnvelope = bytes.size >= 200 && java.security.MessageDigest.isEqual(
                bytes.copyOfRange(bytes.size - 32, bytes.size), java.security.MessageDigest.getInstance("SHA-256").apply { update(bytes, 0, bytes.size - 32) }.digest())
            if (required || intactEnvelope) throw error
            deferred[id] = "内容暂不可验证"; visiting -= id; return
        }
        check(value.commitId == id && value.datasetId == dataset) { "提交身份或 dataset 不匹配" }
        value.parents.forEach { visit(it, true) }
        visiting -= id; commits[id] = value; sizes[id] = bytes.size
    }
    fun ancestors(id: String): Set<String> {
        val found = mutableSetOf<String>()
        fun walk(v: String) { if (found.add(v)) commits.getValue(v).parents.forEach(::walk) }
        walk(id); return found
    }
    val tips: List<String> get() = (commits.keys - commits.values.flatMap { it.parents }.toSet()).sorted()
    fun baseline(ids: List<String>): ScheduleState {
        require(ids.isNotEmpty())
        val common = ids.map(::ancestors).reduce { a, b -> a intersect b }
        val maximal = common.filter { c -> common.none { it != c && c in ancestors(it) } }.sorted()
        check(maximal.isNotEmpty()) { "无法证明共同基线" }
        val state = commits.getValue(maximal.first()).state
        check(maximal.all { ScheduleMerge.same(state, commits.getValue(it).state) }) { "多重共同基线歧义；保留全部版本" }
        return state
    }
    fun mergeTips(): ScheduleMerge.Result {
        check(tips.isNotEmpty())
        check(tips.size <= ScheduleCommitCodec.MAX_PARENTS)
        val first = commits.getValue(tips.first()).state
        if (tips.all { ScheduleMerge.same(first, commits.getValue(it).state) }) return ScheduleMerge.Result(first, emptyList())
        val base = baseline(tips)
        check(tips.sumOf { sizes.getValue(it).toLong() } * tips.size <= 64L * 1024 * 1024) { "并发合并工作量超限" }
        val identity = commits.getValue(tips.first()).copy(state = base)
        return ScheduleV2Plan(identity, identity, tips.map { commits.getValue(it) }).merge(base)
    }
}
