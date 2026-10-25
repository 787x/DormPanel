package com.dormpanel.app.schedule

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
internal class V3CacheCorruptException : IllegalStateException("v3 cache hash")

/** v2 direct-path persistent index. Opening never enumerates receipts or decodes old snapshots.
 * No cache deletion or GC exists. Missing content does not erase a durable identity witness. */
class V3IndexedCache(root: File, internal val budget: V3Budget, private val requireRemoteReadback: Boolean = false) {
    var contentReads = 0L; private set
    private val directory = File(root, "sync-v3-index-v2")
    init { require(!java.nio.file.Files.isSymbolicLink(directory.toPath())) }
    private fun location(path: String): File {
        V3Paths.file(path); val key = SyncV3Codec.hash(path.toByteArray(Charsets.US_ASCII))
        return File(File(File(directory, key.take(2)), key.substring(2, 4)), key.substring(4))
    }
    fun blob(path: String) = File(location(path), "object.dpv")
    @Synchronized fun witness(path: String): JSONObject? {
        val file = File(location(path), "witness.json"); if (!file.exists()) return null
        return JSONObject(V3Atomic.read(file, 32768, budget).toString(Charsets.UTF_8)).also {
            require(it.getInt("version") == 2 && it.getString("path") == path); SyncV3Codec.hashValue(it.getString("hash")); it.getJSONArray("pins")
        }
    }
    @Synchronized fun put(path: String, raw: ByteArray) {
        V3Paths.match(path, raw); val hash = SyncV3Codec.hash(raw); val old = witness(path)
        require(old == null || old.getString("hash") == hash) { "v3 identity changed" }; budget.charge(raw.size.toLong(), 0)
        val receipt = JSONObject().put("version", 2).put("path", path).put("hash", hash).put("pins", old?.getJSONArray("pins") ?: JSONArray())
        V3Atomic.write(File(location(path), "witness.json"), receipt.toString().toByteArray(Charsets.UTF_8))
        val blob = blob(path)
        if (blob.exists()) require(get(path)?.contentEquals(raw) == true) { "v3 cache corrupt; preserve witness" }
        else V3Atomic.write(blob, raw)
    }
    @Synchronized fun get(path: String): ByteArray? {
        val receipt = witness(path) ?: return null; val blob = blob(path); if (!blob.exists()) return null
        return V3Atomic.read(blob, SyncV3Codec.MAX_BYTES, budget).also { contentReads++; if (SyncV3Codec.hash(it) != receipt.getString("hash")) throw V3CacheCorruptException(); V3Paths.match(path, it) }
    }
    fun load(path: String, remote: V3ObjectStore, expectedHash: String? = null): ByteArray? {
        if(requireRemoteReadback) {
            val raw=requireNotNull(remote.read(path)) { "候选祖先缺失" }
            require(expectedHash==null || SyncV3Codec.hash(raw)==expectedHash) { "候选祖先哈希变化" }
            put(path,raw);return raw
        }
        var expected = expectedHash
        val hit = try { get(path) } catch (e: V3CacheCorruptException) {
            val w = requireNotNull(witness(path)); require(expected == null || w.getString("hash") == expected) { "v3 cache witness identity changed" }; expected = w.getString("hash")
            java.nio.file.Files.move(blob(path).toPath(), File(location(path), "damaged-${java.util.UUID.randomUUID()}.dpv").toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE); V3Atomic.sync(location(path)); null
        }
        if (hit != null) { require(expected == null || SyncV3Codec.hash(hit) == expected) { "v3 reference identity changed" }; return hit }
        return remote.read(path)?.also { require(expected == null || SyncV3Codec.hash(it) == expected) { "v3 reference identity changed" }; put(path, it) }
    }
    @Synchronized fun pin(path: String, owner: String) {
        require(owner.isNotEmpty() && owner.length <= 160); val w = requireNotNull(witness(path)); val pins = w.getJSONArray("pins")
        if ((0 until pins.length()).none { pins.getString(it) == owner }) pins.put(owner)
        require(pins.length() <= 128); V3Atomic.write(File(location(path), "witness.json"), w.toString().toByteArray(Charsets.UTF_8))
    }
}
