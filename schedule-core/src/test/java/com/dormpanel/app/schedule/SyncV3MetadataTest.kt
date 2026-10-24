package com.dormpanel.app.schedule

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.UUID

class SyncV3MetadataTest {
    private fun dir() = Files.createTempDirectory("v3-pages-").toFile()
    private fun id() = UUID.randomUUID().toString()
    @Test fun pagesBeyondOldLimitsAndBoundedColdOpen() {
        val root = dir(); var j = V3Journal(root)
        repeat(1100) { batch -> j.meta.atomic { repeat(64) { val key = (batch * 64 + it).toString(); val row = JSONObject().put("path", key).put("padding", "x".repeat(768)); j.meta.put("scope", key, row); j.meta.put("observed", key, row) } } }
        assertEquals(70400L, j.meta.count("scope")); j.close(); j = V3Journal(root); assertTrue(j.meta.reads <= 2)
        var cursor = 0L; var total = 0
        while (true) { val page = j.meta.page("observed", cursor, 64); if (page.isEmpty()) break; total += page.size; cursor = page.last().seq }
        assertEquals(70400, total); val reads = j.meta.reads; val writes = j.meta.writes
        j.transaction { it.put("revision", 1) }; assertTrue(j.meta.reads - reads < 4); assertTrue(j.meta.writes - writes < 4); j.close()
    }
    @Test fun indexAndControlRollbackOrCommitTogether() {
        val root = dir(); var j = V3Journal(root)
        assertThrows(Exception::class.java) { j.transaction { j.meta.put("node", "candidate", JSONObject().put("hash", "original")); it.put("receipt", "candidate"); error("before commit") } }
        assertNull(j.meta.get("node", "candidate")); assertFalse(j.snapshot().has("receipt"))
        j.meta.fault = { phase -> if (phase == "after-commit") error("commit response lost") }
        assertThrows(Exception::class.java) { j.transaction { j.meta.put("node", "candidate", JSONObject().put("hash", "original")); it.put("receipt", "candidate") } }
        assertThrows(Exception::class.java) { j.transaction { it.remove("receipt") } }; j.close(); j = V3Journal(root)
        assertEquals("candidate", j.snapshot().getString("receipt")); assertEquals("original", j.meta.get("node", "candidate")!!.getString("hash")); j.close()
    }
    @Test fun ownerAndLegacyRejection() {
        val root = dir(); val first = V3Journal(root); first.transaction { it.put("local", "saved") }
        assertThrows(Exception::class.java) { V3Journal(root) }; first.close(); V3Journal(root).use { assertEquals("saved", it.snapshot().getString("local")) }
        val old = dir(); File(old, "v3-engine-v1.json").writeText("{\"version\":1}"); assertThrows(Exception::class.java) { V3Journal(old) }; assertEquals("{\"version\":1}", File(old, "v3-engine-v1.json").readText())
    }
    @Test fun corruptBlobIsPreservedAndRecoveredByOriginalHash() {
        val root = dir(); val budget = V3Budget(); val cache = V3IndexedCache(root, budget); val o = V3Object(1, id(), id(), id()); val raw = SyncV3Codec.encode(o); val path = V3Paths.path(o)
        cache.put(path, raw); cache.blob(path).writeText("damaged"); assertThrows(Exception::class.java) { cache.get(path) }
        val remote = object : V3ObjectStore { override fun read(path: String) = raw; override fun list(scope: String) = emptyList<String>(); override fun createForIsolatedTest(path: String, bytes: ByteArray, beforeWriteIntent: () -> Unit) = error("forbidden") }
        assertArrayEquals(raw, cache.load(path, remote, SyncV3Codec.hash(raw))); assertEquals(SyncV3Codec.hash(raw), cache.witness(path)!!.getString("hash")); assertTrue(cache.blob(path).parentFile!!.listFiles()!!.any { it.name.startsWith("damaged-") })
    }
    @Test fun localSnapshotReferencesDoNotDuplicateLargePayloadsInControlJournal() {
        val root = dir(); var j = V3Journal(root); val id = "00000000-0000-0000-0000-000000000001"; val note = "\u0000".repeat(1000000)
        val state = ScheduleState(events = (0 until 9).map { CalendarEvent(it.toString(), "event", 0, 1000, note) })
        val raw = SyncV3Codec.encode(V3Object(2, id, id, id, id, 1, "1".repeat(64), state = state)); val value = java.util.Base64.getEncoder().encodeToString(raw)
        j.transaction { s -> listOf("local", "baseline", "capture", "left", "right").forEach { s.put(it, value) } }; assertEquals(1L, j.meta.count("snapshot"))
        val writes = j.meta.writes; j.transaction { it.put("phase", "control-only") }; assertEquals(1L, j.meta.writes - writes)
        j.close(); j = V3Journal(root); assertEquals(value, j.snapshot().getString("local")); assertEquals(value, j.snapshot().getString("right")); j.close()
    }
    @Test fun unboundLocalEditsIgnoreExpiredNetworkBudgetAndBindingPreservesData() {
        val root = dir(); val j = V3Journal(root); val e = V3Engine(j, id()); val first = ScheduleState(entries = listOf(TimetableEntry("saved", "first", 1, 480, 540, "")))
        e.edit(first); repeat(160) { n -> e.edit(first.copy(entries = first.entries.map { it.copy(title = n.toString()) })) }
        val expired = V3Budget(maxMillis = 0); j.meta.budget = expired; e.edit(first); assertSame(expired, j.meta.budget); assertEquals("first", e.local().entries.single().title)
        val d = V3Object(1, id(), id(), id()); val hash = SyncV3Codec.hash(SyncV3Codec.encode(d)); assertThrows(Exception::class.java) { e.bind(d, hash, ScheduleState()) }; assertFalse(j.snapshot().has("binding"))
        e.bind(d, hash); assertEquals("saved", e.local().entries.single().id); assertTrue(j.snapshot().getBoolean("unbasedLocal")); j.close()
    }
    private class Kill : Error()
    @Test fun sharedCrashOracleKeepsCaptureLocalStateAndExactPutCounts() {
        val scenarios = JSONArray(File("src/test/resources/sync-v3/recovery-scenarios.json").readText())
        repeat(scenarios.length()) { n ->
            val scenario = scenarios.getJSONObject(n); val target = scenario.getString("phase"); val root = dir(); var journal = V3Journal(root)
            val o = V3Object(1, id(), id(), id()); val raw = SyncV3Codec.encode(o); val path = V3Paths.path(o); var content: ByteArray? = null; var puts = 0
            fun boundary(phase: String) { if (phase == target) throw Kill() }
            journal.transaction { it.put("local", "saved") }
            journal.meta.fault = { phase -> if (phase == "after-commit") journal.meta.get("state", "frame")?.optJSONObject("pending")?.getString("phase")?.let(::boundary) }
            val remote = object : V3PublicationStore {
                override fun prepare(scope: String) {}
                override fun read(path: String): ByteArray? { if (content != null) { boundary("READBACK_BEFORE"); val b = content; boundary("READBACK_AFTER"); return b }; return null }
                override fun putOnce(path: String, bytes: ByteArray) { puts++; content = bytes; boundary("PUT_DONE"); if (target == "UNCERTAIN") error("lost response") }
            }
            try { val p = V3Publisher(journal, remote); p.stage(path, raw, JSONObject().put("exactCapture", "saved")); p.resume(); p.receipt { s, _ -> s.put("applied", s.optInt("applied") + 1) } } catch (_: Kill) {}
            journal.close(); journal = V3Journal(root); assertEquals("saved", journal.snapshot().getString("local")); assertEquals(java.util.Base64.getEncoder().encodeToString(raw), journal.snapshot().getJSONObject("pending").getString("bytes"))
            val recovery = object : V3PublicationStore { override fun prepare(scope: String) {}; override fun read(path: String) = content; override fun putOnce(path: String, raw: ByteArray) { puts++; content = raw } }
            val publisher = V3Publisher(journal, recovery); val phase = publisher.resume(); if (phase == "READBACK_CONFIRMED") publisher.receipt { s, _ -> s.put("applied", s.optInt("applied") + 1) }
            if (target == "ATTEMPTED") assertEquals("UNCERTAIN", phase)
            assertEquals(target, scenario.getInt("puts"), puts); assertEquals(target, scenario.getInt("applied"), journal.snapshot().optInt("applied")); assertEquals("saved", journal.snapshot().getJSONObject("pending").getJSONObject("capture").getString("exactCapture")); journal.close()
        }
    }
}
