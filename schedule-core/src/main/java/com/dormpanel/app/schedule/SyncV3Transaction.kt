package com.dormpanel.app.schedule

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.channels.FileChannel
import java.util.Base64
import java.util.UUID

/** Private, single-owner durable documents. Network never holds this monitor or a Room transaction. */
internal object V3Atomic {
    fun sync(dir: File) {
        if (System.getProperty("os.name")?.startsWith("Windows") != true)
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }
    fun write(file: File, raw: ByteArray) {
        val parent = requireNotNull(file.parentFile)
        val missing = generateSequence(parent) { it.parentFile }.takeWhile { !it.exists() }.toList()
        check(parent.isDirectory || parent.mkdirs())
        val temp = File(parent, ".${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temp).use { it.write(raw); it.fd.sync() }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            sync(parent)
            missing.forEach { sync(requireNotNull(it.parentFile)) }
        } finally { temp.delete() }
    }
    fun read(file: File, limit: Int, budget: V3Budget? = null): ByteArray {
        require(!Files.isSymbolicLink(file.toPath()) && file.isFile && file.length() <= limit)
        budget?.charge(file.length(), 0)
        return file.inputStream().use { input ->
            val out = java.io.ByteArrayOutputStream(); val b = ByteArray(8192)
            while (true) { val n = input.read(b); if (n < 0) break; require(out.size().toLong() + n <= limit); out.write(b, 0, n) }; out.toByteArray()
        }
    }
}

/** Separate isolated state adapter: course state, capture and receipt share one atomic document.
 * Existing Room/v2 bindings are not touched. PR55 must supply the production Room adapter. */
class V3Journal(private val directory: File) {
    internal val networkGate = Any()
    private var failed = false
    private val file = File(directory, "v3-engine-v1.json")
    private var frame = if (file.exists()) JSONObject(V3Atomic.read(file, 48 * 1024 * 1024).toString(Charsets.UTF_8)) else JSONObject().put("version", 1)
    init { require(frame.getInt("version") == 1) }
    @Synchronized fun snapshot() = JSONObject(frame.toString())
    @Synchronized fun transaction(change: (JSONObject) -> Unit) {
        check(!failed) { "v3 journal requires reopen after persistence failure" }
        val next = snapshot(); change(next); val raw = next.toString().toByteArray(Charsets.UTF_8)
        require(raw.size <= 48 * 1024 * 1024) { "v3 journal budget" }
        try { V3Atomic.write(file, raw) } catch (e: Exception) { failed = true; throw e }; frame = next
    }
}

/** Deliberately separate from PR53's test-only create callback. Exactly one physical attempt. */
interface V3PublicationStore {
    fun read(path: String): ByteArray?
    fun putOnce(path: String, raw: ByteArray)
    fun prepare(scope: String)
}

class V3Publisher(private val journal: V3Journal, private val remote: V3PublicationStore) {
    fun stage(path: String, raw: ByteArray, capture: JSONObject) {
        V3Paths.match(path, raw)
        journal.transaction { s ->
            require(!s.has("pending") || s.getJSONObject("pending").getString("phase") == "RECEIPTED")
            s.put("pending", JSONObject().put("path", path).put("hash", SyncV3Codec.hash(raw))
                .put("bytes", Base64.getEncoder().encodeToString(raw)).put("capture", JSONObject(capture.toString()))
                .put("phase", "STAGED").put("attempts", 0))
        }
    }
    fun resume(): String {
        val p = journal.snapshot().getJSONObject("pending"); val path = p.getString("path")
        val raw = Base64.getDecoder().decode(p.getString("bytes")); require(SyncV3Codec.hash(raw) == p.getString("hash")); V3Paths.match(path, raw)
        val phase = p.getString("phase"); if (phase in listOf("READBACK_CONFIRMED", "RECEIPTED")) return phase
        val old = remote.read(path)
        if (old != null) {
            require(old.contentEquals(raw)) { "v3 identity anomaly; pending retained" }
            journal.transaction { it.getJSONObject("pending").put("phase", "READBACK_CONFIRMED") }; return "READBACK_CONFIRMED"
        }
        if (phase != "STAGED") { journal.transaction { it.getJSONObject("pending").put("phase", "UNCERTAIN") }; return "UNCERTAIN" }
        journal.transaction { it.getJSONObject("pending").put("phase", "ATTEMPTED").put("attempts", p.getInt("attempts") + 1) }
        try {
            remote.putOnce(path, raw); require(remote.read(path)?.contentEquals(raw) == true) { "v3 uncertain readback" }
            journal.transaction { it.getJSONObject("pending").put("phase", "READBACK_CONFIRMED") }; return "READBACK_CONFIRMED"
        } catch (e: Exception) {
            journal.transaction { it.getJSONObject("pending").put("phase", "UNCERTAIN").put("error", e.message.orEmpty().take(1024)) }; throw e
        }
    }
    fun receipt(apply: (JSONObject, JSONObject) -> Unit) {
        journal.transaction { s -> val p = s.getJSONObject("pending"); require(p.getString("phase") == "READBACK_CONFIRMED"); apply(s, p.getJSONObject("capture")); p.put("phase", "RECEIPTED") }
    }
}
