package com.dormpanel.app.schedule

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class SyncV3RoomAndroidTest {
    private fun id() = UUID.randomUUID().toString()
    private fun adapter(root: File, writable: () -> Unit) = V3RoomAdapter(root, {
        Room.databaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, V3RoomDatabase::class.java, File(root, "v3-state-v2.sqlite").absolutePath)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build()
    }, writable)
    private fun course(key: String) = TimetableEntry(key, key, 1, 480, 540, "")
    @Test fun roomCoursesReceiptCaptureAndIndexSurviveReopen() {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "pr54-room-${id()}"); var allowed = true
        var a = adapter(root) { check(allowed) { "denied" } }; val writer = id(); var engine = V3Engine(a.journal, writer)
        val objects = linkedMapOf<String, ByteArray>(); val puts = mutableMapOf<String, Int>(); var inject = false
        val remote = object : V3ObjectStore, V3PublicationStore {
            override fun prepare(scope: String) { assertFalse(a.database.inTransaction()) }
            override fun read(path: String): ByteArray? { assertFalse(a.database.inTransaction()); return objects[path] }
            override fun list(scope: String) = objects.keys.filter { it.startsWith(scope) }.map { p -> val tail = p.removePrefix(scope); scope + if ('/' in tail) tail.substringBefore('/') + "/" else tail }.distinct().sorted()
            override fun putOnce(path: String, raw: ByteArray) {
                assertFalse(a.database.inTransaction()); puts[path] = (puts[path] ?: 0) + 1; assertFalse(objects.containsKey(path)); objects[path] = raw
                if (inject) { inject = false; engine.edit(ScheduleState(entries = listOf(course("captured"), course("during")))); error("lost response") }
            }
            override fun createForIsolatedTest(path: String, bytes: ByteArray, beforeWriteIntent: () -> Unit) = error("test primitive forbidden")
        }
        a.database.dao().checkpoint(ScheduleSyncCheckpoint(1, byteArrayOf(1, 2, 3)))
        engine.initialize(remote); repeat(3) { val b = V3Budget(); try { engine.round(remote, remote, V3IndexedCache(root, b), b, 32) } catch (e: IllegalStateException) { if (!e.message.orEmpty().contains("incomplete")) throw e } }
        engine.edit(ScheduleState(entries = listOf(course("captured")))); inject = true
        run { val b = V3Budget(); assertThrows(Exception::class.java) { engine.round(remote, remote, V3IndexedCache(root, b), b, 8) } }
        val pending = a.journal.snapshot().getJSONObject("pending").getString("path"); assertEquals(setOf("captured", "during"), a.database.dao().entries().map { it.id }.toSet()); a.close()
        a = adapter(root) { check(allowed) }; engine = V3Engine(a.journal, writer)
        repeat(3) { val b = V3Budget(); engine.round(remote, remote, V3IndexedCache(root, b), b, 16) }
        assertEquals(1, puts[pending]); assertEquals(setOf("captured", "during"), engine.local().entries.map { it.id }.toSet()); assertNotNull(a.journal.meta.get("node", pending))
        assertArrayEquals(byteArrayOf(1, 2, 3), a.database.dao().checkpoint()!!.payload)
        val revision = a.journal.snapshot().getLong("revision"); a.journal.meta.fault = { if (it == "before-commit") error("injected rollback") }
        assertThrows(Exception::class.java) { engine.edit(ScheduleState(entries = listOf(course("rollback")))) }; a.journal.meta.fault = null
        assertEquals(revision, a.journal.snapshot().getLong("revision")); assertEquals(setOf("captured", "during"), a.database.dao().entries().map { it.id }.toSet())
        allowed = false; assertThrows(Exception::class.java) { engine.edit(ScheduleState()) }; allowed = true; a.close()
    }
    @Test fun committedRoomRowsAndIndexRecoverAfterAcknowledgementFailure() {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "pr54-room-commit-${id()}"); var a = adapter(root) {}
        val o = V3Object(2, id(), id(), id(), id(), 1, "1".repeat(64), state = ScheduleState(entries = listOf(course("saved"))))
        val local = java.util.Base64.getEncoder().encodeToString(SyncV3Codec.encode(o))
        a.journal.meta.fault = { if (it == "after-commit") error("commit acknowledgement lost") }
        assertThrows(Exception::class.java) { a.journal.transaction { s -> a.journal.meta.put("node", "candidate", JSONObject().put("hash", "exact")); s.put("local", local).put("receipt", "candidate") } }; a.close()
        a = adapter(root) {}; assertEquals("candidate", a.journal.snapshot().getString("receipt")); assertEquals("exact", a.journal.meta.get("node", "candidate")!!.getString("hash")); assertEquals("saved", a.database.dao().entries().single().id); a.close()
    }
}
