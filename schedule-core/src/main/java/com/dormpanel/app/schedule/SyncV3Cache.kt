package com.dormpanel.app.schedule

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Keep only metadata in memory; full business snapshots remain in content-addressed blobs. */
data class V3CacheEntry(val path: String, val hash: String, val size: Int, val type: Int,
    val datasetId: String, val epochId: String, val epochNumber: Int, val objectId: String,
    val descriptorHash: String, val refs: List<V3Ref>)
data class V3Evidence(val localContentVerified: Boolean, val observedThisRound: Boolean,
    val bytesRecheckedThisRound: Boolean, val closureVerifiedThisRound: Boolean)

/** Dedicated app-private directory, never ScheduleDatabase or the v2 checkpoint.
 * Immutable identity receipts survive missing blobs. Every hit revalidates bytes.
 * One owner per directory; private journal rename/fsync is the recoverable store (no Room change).
 */
class V3ObjectCache(private val directory: File, private val budget: V3Budget,
    private val afterBlob: () -> Unit = {}) {
    private val entries = linkedMapOf<String, V3CacheEntry>()
    private val expected = linkedMapOf<String, String>()
    private var ready = false
    init {
        require(directory.name == "sync-v3-cache-v1"); check(directory.isDirectory || directory.mkdirs())
        require(!Files.isSymbolicLink(directory.toPath())); syncDirectory(directory.parentFile); recover()
    }
    private fun syncDirectory(dir: File) {
        if (System.getProperty("os.name")?.startsWith("Windows") != true)
            java.nio.channels.FileChannel.open(dir.toPath(), java.nio.file.StandardOpenOption.READ).use { it.force(true) }
    }
    private fun atomic(name: String, bytes: ByteArray) {
        val target = File(directory, name)
        val temp = File(directory, ".${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            // Android/Linux: persist the rename's directory entry; JVM Windows does not expose directory fsync.
            syncDirectory(directory)
        } finally { temp.delete() }
    }
    private fun receiptName(path: String) = SyncV3Codec.hash(path.toByteArray(Charsets.US_ASCII)) + ".ref"
    private fun entry(path: String, hash: String, size: Int, o: V3Object) = V3CacheEntry(path, hash, size,
        o.type, o.datasetId, o.epochId, o.epochNumber, o.objectId, o.descriptorHash, o.refs.toList())
    private fun bytes(file: File, limit: Int): ByteArray {
        require(!Files.isSymbolicLink(file.toPath()) && file.isFile && file.length() <= limit)
        budget.charge(file.length(), 0)
        return file.inputStream().use { input ->
            val out = java.io.ByteArrayOutputStream(); val b = ByteArray(8192)
            while (true) { val n = input.read(b); if (n < 0) break; require(out.size().toLong() + n <= limit); out.write(b, 0, n) }
            out.toByteArray()
        }
    }
    @Synchronized fun recover(): List<V3CacheEntry> {
        ready = false
        entries.clear(); expected.clear()
        val files = mutableListOf<File>()
        Files.newDirectoryStream(directory.toPath()).use { listing ->
            for (path in listing) { budget.charge(0, 0); require(files.size < 4096); files += path.toFile() }
        }
        files.filter { it.name.endsWith(".ref") }.forEach { f ->
            val fields = bytes(f, 512).toString(Charsets.US_ASCII).split('\n'); require(fields.size == 2)
            val (path, hash) = fields; V3Paths.file(path); SyncV3Codec.hashValue(hash)
            require(f.name == receiptName(path)); expected[path] = hash
        }
        // Content-addressed orphans can be indexed; partial temp files are never trusted or removed.
        files.filter { it.name.endsWith(".dpv") }.forEach { f ->
            val raw = bytes(f, SyncV3Codec.MAX_BYTES); val hash = SyncV3Codec.hash(raw)
            require(f.name == "$hash.dpv"); val o = SyncV3Codec.decode(raw); val path = V3Paths.path(o)
            require(expected[path] == null || expected[path] == hash) { "v3 identity changed" }
            if (expected[path] == null) atomic(receiptName(path), "$path\n$hash".toByteArray(Charsets.US_ASCII))
            expected[path] = hash; entries[path] = entry(path, hash, raw.size, o)
        }
        ready = true; return entries.values.toList()
    }
    @Synchronized fun put(path: String, raw: ByteArray): V3CacheEntry {
        check(ready) { "Recover v3 cache before use" }
        budget.charge(raw.size.toLong(), 0); val o = V3Paths.match(path, raw); val hash = SyncV3Codec.hash(raw)
        require(expected[path] == null || expected[path] == hash) { "v3 identity changed" }
        // Receipt first: interrupted writes retain the expected identity even without trusted content.
        atomic(receiptName(path), "$path\n$hash".toByteArray(Charsets.US_ASCII)); expected[path] = hash
        val blob = File(directory, "$hash.dpv")
        if (blob.exists()) require(bytes(blob, SyncV3Codec.MAX_BYTES).contentEquals(raw)) else atomic(blob.name, raw)
        afterBlob()
        return entry(path, hash, raw.size, o).also { entries[path] = it }
    }
    @Synchronized fun get(path: String): ByteArray? {
        check(ready) { "Recover v3 cache before use" }
        V3Paths.file(path); val hash = expected[path] ?: return null
        val file = File(directory, "$hash.dpv"); if (!file.exists()) return null
        return bytes(file, SyncV3Codec.MAX_BYTES).also { require(SyncV3Codec.hash(it) == hash); V3Paths.match(path, it) }
    }
    fun load(path: String, store: V3ObjectStore): ByteArray? = get(path) ?: store.read(path)?.also { put(path, it) }
    fun index(): List<V3CacheEntry> = synchronized(this) { check(ready); entries.values.toList() }
}

/** Ephemeral, endpoint/round-scoped evidence. New rounds never inherit remote/closure flags from disk. */
class V3Observation(val endpoint: String, val round: String) {
    private val observed = mutableSetOf<String>(); private val rechecked = mutableSetOf<String>(); private val closed = mutableSetOf<String>()
    fun observe(path: String) { V3Paths.file(path); observed += path }
    fun recheck(path: String, remote: ByteArray, cachedHash: String) { V3Paths.match(path, remote); require(SyncV3Codec.hash(remote) == cachedHash); observed += path; rechecked += path }
    /** Called only by a future graph validator, never by a cache hit. */
    fun recordVerifiedClosure(paths: Set<String>) { paths.forEach(V3Paths::file); closed += paths }
    fun evidence(path: String, localVerified: Boolean) = V3Evidence(localVerified, path in observed, path in rechecked, path in closed)
}
