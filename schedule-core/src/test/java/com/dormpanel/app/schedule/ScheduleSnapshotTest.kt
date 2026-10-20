package com.dormpanel.app.schedule

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.time.LocalDate
import java.time.ZoneId

class ScheduleSnapshotTest {
    @Test fun fullRoundTripPreservesIdentityOverridesAndProjection() {
        val original = SnapshotFixture.snapshot()
        val restored = ScheduleSnapshotCodec.decode(ScheduleSnapshotCodec.encode(original))
        assertEquals(original, restored)
        assertEquals(ScheduleProjection.week(original.state, LocalDate.parse("2026-10-12"), ZoneId.of("Asia/Shanghai")),
            ScheduleProjection.week(restored.state, LocalDate.parse("2026-10-12"), ZoneId.of("Asia/Shanghai")))
        assertEquals("stable-source", restored.state.imported.single().sourceId)
        assertEquals("stable-instance", restored.state.imported.single().id)
        assertEquals("stable-series", restored.state.classOverrides.first().seriesId)
        assertEquals("", restored.state.classOverrides.first().location)
    }
    @Test fun corruptIncompleteUnknownAndOversizedEnvelopesFailClosed() {
        val good = ScheduleSnapshotCodec.encode(SnapshotFixture.snapshot())
        for (length in listOf(0, 12, 43, good.size - 1)) assertThrows(Exception::class.java) { ScheduleSnapshotCodec.decode(good.copyOf(length)) }
        val unknown = good.copyOf(); ByteBuffer.wrap(unknown).putInt(4, 99)
        assertThrows(Exception::class.java) { ScheduleSnapshotCodec.decode(unknown) }
        val bad = good.copyOf(); bad[60] = (bad[60].toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { ScheduleSnapshotCodec.decode(bad) }
        assertThrows(Exception::class.java) { ScheduleSnapshotCodec.decode(good + byteArrayOf(0)) }
        assertThrows(Exception::class.java) { ScheduleSnapshotCodec.decode(ByteArray(ScheduleSnapshotCodec.MAX_BYTES + 1)) }
    }
    @Test fun identityConflictsDanglingSourcesAndRecordLimitsFailWithoutTruncating() {
        val s = SnapshotFixture.snapshot(); val v = s.state
        val invalid = listOf(
            v.copy(entries = v.entries + v.entries), v.copy(imported = v.imported + v.imported),
            v.copy(sources = emptyList()), v.copy(profiles = emptyMap()),
            v.copy(classOverrides = listOf(v.classOverrides.first().copy(key = "wrong"))),
            v.copy(sources = listOf(v.sources.single().copy(occurrenceCount = 2))),
            v.copy(events = List(10001) { CalendarEvent("$it", "Event", 1, 2) }),
            v.copy(imported = v.imported.map { it.copy(timezone = "unknown-zone") }),
            v.copy(sources = v.sources.map { it.copy(locator = "https://user:secret@example.org/file") }))
        invalid.forEach { state -> assertThrows(Exception::class.java) { ScheduleSnapshotCodec.encode(s.copy(state = state)) } }
        val large = s.copy(state = v.copy(events = List(10000) { CalendarEvent("$it", "Event", 1, 2) }))
        assertEquals(10000, ScheduleSnapshotCodec.decode(ScheduleSnapshotCodec.encode(large)).state.events.size)
    }
    @Test fun oversizedTextExportFailsInsteadOfTruncating() {
        val s = SnapshotFixture.snapshot()
        assertThrows(Exception::class.java) { ScheduleSnapshotCodec.encode(s.copy(state = s.state.copy(events = listOf(CalendarEvent("a", "x".repeat(1_000_001), 1, 2))))) }
    }
    @Test fun catalogAndUnmatchedImportedEditsRemainLossless() {
        val s = SnapshotFixture.snapshot()
        val orphan = ClassOverride.of("stable-source", "previous-series", null, ClassPatch(note = "preserved for later replacement"))
        val value = s.copy(state = s.state.copy(classOverrides = s.state.classOverrides + orphan))
        assertEquals(value, ScheduleSnapshotCodec.decode(ScheduleSnapshotCodec.encode(value)))
    }
}
