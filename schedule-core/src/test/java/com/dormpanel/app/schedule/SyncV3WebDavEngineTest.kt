package com.dormpanel.app.schedule

import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** Opt-in real HTTPS interoperability probe. Credentials live only in ignored local evidence. */
class SyncV3WebDavEngineTest {
    @Test fun receivesNodeRotatesAndPublishesKotlinEdit() {
        val path = System.getenv("DORMPANEL_V3_PROBE"); assumeTrue("isolated HTTPS probe not configured", path != null)
        val input = JSONObject(File(path!!).readText()); val config = input.getJSONObject("config"); val binding = input.getJSONObject("binding")
        val url = config.getString("url"); require(Regex("/pr54-engine-[0-9a-f-]{36}/$").containsMatchIn(url))
        val account = WebDavAccount(url, config.getString("username"), config.getString("password"))
        val root = Files.createTempDirectory("dp-pr54-https-").toFile(); val journal = V3Journal(root); val engine = V3Engine(journal, UUID.randomUUID().toString())
        engine.bind(SyncV3Codec.decode(requireNotNull(V3WebDavStore(account, url, V3Budget()).read(binding.getString("descriptorPath")))), binding.getString("descriptorHash"))
        repeat(5) { val budget = V3Budget(); val remote = V3WebDavStore(account, url, budget); try { engine.round(remote, remote, V3IndexedCache(root, budget), budget, 8) } catch (e: IllegalStateException) { if (!e.message.orEmpty().contains("initial discovery/validation incomplete")) throw e } }
        assertTrue(engine.local().entries.any { it.id == "node" })
        run {
            val budget = V3Budget(); val remote = V3WebDavStore(account, url, budget); val cache = V3IndexedCache(root, budget)
            val history = V3History(journal, cache, remote, binding.getString("descriptorPath"), binding.getString("descriptorHash"), budget).open()
            val baseline = journal.snapshot().getString("baselinePath"); history.verify(baseline)
            engine.rotate(history, remote, history.objectMetadata(baseline).epochId, listOf(baseline))
        }
        engine.edit(engine.local().copy(entries = engine.local().entries + TimetableEntry("kotlin", "Kotlin JVM", 2, 600, 660, "")))
        repeat(5) { val budget = V3Budget(); val remote = V3WebDavStore(account, url, budget); engine.round(remote, remote, V3IndexedCache(root, budget), budget, 8) }
        assertEquals(setOf("node", "kotlin"), engine.local().entries.map { it.id }.toSet())
    }
}
