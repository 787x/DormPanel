package com.dormpanel.app.schedule

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ScheduleV3MigrationTest {
    private fun id() = UUID.randomUUID().toString()
    private class Remote : CommitStore {
        val objects = linkedMapOf<String, ByteArray>(); var writes = 0
        override val bindingKey = "1".repeat(64)
        override fun list() = objects.keys.toList()
        override fun read(commitId: String) = objects[commitId]
        override fun publish(bytes: ByteArray, allowCreate: Boolean) { writes++; error("migration must never publish v2") }
        fun add(c: ScheduleCommit) { objects[c.commitId] = ScheduleCommitCodec.encode(c) }
    }
    private fun fixture(count: Int = 1): Pair<Remote, ScheduleV2Frame> {
        val raw = javaClass.getResourceAsStream("/sync-v3/commit-complex.dpv")!!.readBytes()
        val state = SyncV3Codec.decode(raw).state!!; val r = Remote(); val dataset = id(); val writer = id()
        var tip = ScheduleCommit(dataset, id(), writer, emptyList(), state); r.add(tip)
        repeat(count - 1) { tip = tip.copy(commitId = id(), parents = listOf(tip.commitId)); r.add(tip) }
        return r to ScheduleV2Frame(state, tip, listOf(tip.commitId), writer, null, false, null)
    }
    @Test fun full256HistoryMigratesAllRawCollectionsWithoutAnotherV2Commit() {
        val (r, f) = fixture(256)
        val p = ScheduleV3Migration.inspect(f, r.objects.mapValues { digest(it.value) }, r, "https://isolated.test/")
        assertEquals(256, p.hashes.size); assertArrayEquals(SyncV3Codec.stateBytes(f.local), SyncV3Codec.stateBytes(p.merged))
        assertEquals(0, r.writes); assertFalse(p.evidence().getBoolean("independentBackupVerified"))
        assertEquals(SyncV3Codec.businessHash(f.local), p.evidence().getString("businessHash"))
    }
    @Test fun retainsUnpublishedLocalAndRemoteIndependentEditsButStopsTrueConflict() {
        val (r, f) = fixture(); val entry = TimetableEntry("new-local", "local", 1, 480, 540, "")
        val local = f.local.copy(entries = f.local.entries + entry)
        val remote = f.baseline!!.copy(commitId = id(), parents = f.frontier, state = f.local.copy(events = f.local.events + CalendarEvent("remote", "remote", 1000, 2000)))
        r.add(remote)
        val p = ScheduleV3Migration.inspect(f.copy(local = local), emptyMap(), r, "isolated")
        assertTrue(p.merged.entries.any { it.id == entry.id }); assertTrue(p.merged.events.any { it.id == "remote" })
        val conflict = remote.copy(commitId = id(), parents = listOf(remote.commitId), state = remote.state.copy(entries = remote.state.entries + entry.copy(title = "remote"))); r.add(conflict)
        assertThrows(Exception::class.java) { ScheduleV3Migration.inspect(f.copy(local = local), emptyMap(), r, "isolated") }
        assertEquals(0, r.writes)
    }
    @Test fun pendingUnknownAncestorKnownHashChangeAndPreviewChangeAreRejected() {
        val (r, f) = fixture(); val p = ScheduleV3Migration.inspect(f, emptyMap(), r, "isolated")
        assertThrows(Exception::class.java) { ScheduleV3Migration.inspect(f.copy(pending = byteArrayOf(1), attempted = true), emptyMap(), r, "isolated") }
        assertThrows(Exception::class.java) { ScheduleV3Migration.inspect(f, mapOf(f.frontier.single() to "0".repeat(64)), r, "isolated") }
        val next = f.baseline!!.copy(commitId = id(), parents = f.frontier); r.add(next)
        val changed = ScheduleV3Migration.inspect(f, emptyMap(), r, "isolated")
        assertThrows(Exception::class.java) { ScheduleV3Migration.unchanged(p, changed) }
        r.add(next.copy(commitId = id(), parents = listOf(id())))
        assertThrows(Exception::class.java) { ScheduleV3Migration.inspect(f, emptyMap(), r, "isolated") }
    }
}
