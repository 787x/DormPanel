package com.dormpanel.app.schedule

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.json.JSONObject
import java.io.File
import java.util.Base64
import java.util.UUID

/** All writes are inside a random instrumentation-private directory or the explicit PR54 probe. */
class SyncV3EngineAndroidTest {
    private fun id() = UUID.randomUUID().toString()
    @Test fun indexedColdOpenBeyond4096FilesAndDurablePendingRecovery() {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "pr54-${id()}")
        var last: V3Object? = null
        repeat(21) {
            val cache = V3IndexedCache(root, V3Budget())
            repeat(100) { val o = V3Object(1, id(), id(), id()); cache.put(V3Paths.path(o), SyncV3Codec.encode(o)); last = o }
        }
        val cache = V3IndexedCache(root, V3Budget(maxBytes = 2048)); val o = last!!; val path = V3Paths.path(o)
        assertArrayEquals(SyncV3Codec.encode(o), cache.get(path)); assertTrue(cache.blob(path).delete()); assertNull(cache.get(path))
        assertThrows(Exception::class.java) { cache.put(path, SyncV3Codec.encode(o.copy(writerId = id()))) }
        val journal = V3Journal(root); var content: ByteArray? = null; var puts = 0
        val remote = object : V3PublicationStore {
            override fun prepare(scope: String) {}
            override fun read(path: String) = content
            override fun putOnce(path: String, raw: ByteArray) { puts++; content = raw; error("injected lost response") }
        }
        val publisher = V3Publisher(journal, remote); publisher.stage(path, SyncV3Codec.encode(o), JSONObject().put("capture", "local-edit")); assertThrows(Exception::class.java) { publisher.resume() }
        val reopened = V3Journal(root); assertEquals("READBACK_CONFIRMED", V3Publisher(reopened, remote).resume()); assertEquals(1, puts)
    }
    @Test fun isolatedHttpsReceivesAndPublishesWithRestart() {
        val arguments = InstrumentationRegistry.getArguments(); val encoded = arguments.getString("v3Probe"); assumeTrue("explicit isolated probe required", encoded != null)
        val probe = JSONObject(String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)); val config = probe.getJSONObject("config"); val binding = probe.getJSONObject("binding")
        val url = config.getString("url"); require(Regex("/pr54-engine-[0-9a-f-]{36}/$").containsMatchIn(url)); val account = WebDavAccount(url, config.getString("username"), config.getString("password"))
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "pr54-https-${id()}"); val journal = V3Journal(root); val writer = id(); val engine = V3Engine(journal, writer)
        engine.bind(SyncV3Codec.decode(requireNotNull(V3WebDavStore(account, url, V3Budget()).read(binding.getString("descriptorPath")))), binding.getString("descriptorHash"))
        repeat(8) { val budget = V3Budget(); val remote = V3WebDavStore(account, url, budget); try { engine.round(remote, remote, V3IndexedCache(root, budget), budget, 8) } catch (e: IllegalStateException) { if (!e.message.orEmpty().contains("initial discovery incomplete")) throw e } }
        assertTrue(engine.local().entries.any { it.id == "kotlin" }); assertTrue(engine.local().entries.any { it.id == "node" })
        val device = "android-" + arguments.getString("v3Device", "test")
        engine.edit(engine.local().copy(entries = engine.local().entries.filter { it.id != device } + TimetableEntry(device, "$device ${id()}", 4, 720, 780, "")))
        val reopened = V3Engine(V3Journal(root), writer)
        repeat(4) { val budget = V3Budget(); val remote = V3WebDavStore(account, url, budget); reopened.round(remote, remote, V3IndexedCache(root, budget), budget, 8) }
        assertTrue(reopened.local().entries.any { it.id == device })
    }
}
