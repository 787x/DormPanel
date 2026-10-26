package com.dormpanel.app.schedule

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ScheduleReadFailureGuardTest {
    private class Store(var data: ScheduleState) : ScheduleStore {
        var failLoad = false
        var writes = 0
        var pendingApply: ((Result<Unit>) -> Unit)? = null
        override fun load(callback: (Result<ScheduleState>) -> Unit) = callback(
            if (failLoad) Result.failure(IllegalStateException("injected read failure")) else Result.success(data))
        override fun put(entry: TimetableEntry, callback: (Result<Unit>) -> Unit) {
            writes++; data = data.copy(entries = data.entries.filterNot { it.id == entry.id } + entry); callback(Result.success(Unit))
        }
        override fun put(event: CalendarEvent, callback: (Result<Unit>) -> Unit) { writes++; callback(Result.success(Unit)) }
        override fun deleteEvent(id: String, callback: (Result<Unit>) -> Unit) { writes++; callback(Result.success(Unit)) }
        override fun deleteEntry(id: String, callback: (Result<Unit>) -> Unit) { writes++; callback(Result.success(Unit)) }
        override fun putDayAdjustment(item: DayAdjustment?, date: String, callback: (Result<Unit>) -> Unit) { writes++; callback(Result.success(Unit)) }
        override fun putClassOverride(item: ClassOverride?, key: String, callback: (Result<Unit>) -> Unit) { writes++; callback(Result.success(Unit)) }
        override fun commitImport(source: ImportSource, occurrences: List<ImportedClassOccurrence>, replace: Boolean, callback: (Result<ImportCommit>) -> Unit) {
            writes++; callback(Result.failure(IllegalStateException("unexpected import")))
        }
        override fun deleteImport(id: String, callback: (Result<Unit>) -> Unit) { writes++; callback(Result.success(Unit)) }
        override fun applySnapshot(bytes: ByteArray, callback: (Result<Unit>) -> Unit) { writes++; pendingApply = callback }
        override fun close() {}
    }
    @Test fun failedReadRejectsAllBusinessWritesAndCallbacksWithoutOptimisticChanges() {
        val store = Store(SnapshotFixture.snapshot().state)
        val source = ScheduleSource(store)
        val before = source.state; val persisted = store.data
        var committed = 0; source.localCommitted = { committed++ }
        store.failLoad = true; source.reload()
        assertTrue(source.ready); assertTrue(source.error); assertTrue(source.readOnly); assertFalse(source.editable)
        assertTrue(source.editBlockReason!!.contains("暂不可编辑"))
        assertFalse(source.saveEntry("new", 1, 480, 530))
        assertFalse(source.saveEntry("changed", 2, 600, 645, id = before.entries.single().id))
        assertFalse(source.saveEvent("new", 100, 200))
        assertFalse(source.saveEvent("changed", 100, 200, id = before.events.single().id))
        source.deleteEntry(before.entries.single().id); source.deleteEvent(before.events.single().id)
        assertFalse(source.saveDayAdjustment(LocalDate.parse("2026-10-14"), "normal"))
        assertFalse(source.saveClassOverride(null, before.entries.single().id, null, ClassPatch(title = "changed")))
        val preview = IcsScheduleImporter().parse(ScheduleArtifact("test.ics", javaClass.getResourceAsStream("/schedule/wakeup.ics")!!.readBytes()))
        var callbacks = 0
        source.import(preview, "new", null) { assertTrue(it.exceptionOrNull() is ScheduleLocalReadFailed); callbacks++ }
        source.deleteImport(before.sources.single().id) { assertTrue(it.exceptionOrNull() is ScheduleLocalReadFailed); callbacks++ }
        source.applySnapshot(byteArrayOf(1)) { assertTrue(it.exceptionOrNull() is ScheduleLocalReadFailed); callbacks++ }
        source.refreshProfiles()
        assertEquals(3, callbacks); assertEquals(0, store.writes); assertEquals(0, committed)
        assertEquals(before, source.state); assertEquals(persisted, store.data)
        store.failLoad = false; source.reload()
        assertFalse(source.error); assertFalse(source.readOnly); assertTrue(source.editable); assertNull(source.editBlockReason)
        assertEquals(before, source.state)
        assertTrue(source.saveEntry("after recovery", 1, 480, 530))
        assertEquals(1, store.writes); assertEquals(1, committed)
        source.close()
    }
    @Test fun snapshotReloadSuccessClearsEarlierReadErrorAndKeepsApplyResultAccurate() {
        for (applySucceeds in listOf(true, false)) {
            val original = ScheduleState(entries = listOf(TimetableEntry("stable", "original", 1, 480, 530, "")))
            val store = Store(original); val source = ScheduleSource(store)
            var result: Result<Unit>? = null
            source.applySnapshot(byteArrayOf(1)) { result = it }
            store.failLoad = true; source.reload()
            assertTrue(source.error); assertEquals(original, source.state)
            store.failLoad = false
            if (applySucceeds) store.data = original.copy(entries = original.entries.map { it.copy(title = "confirmed snapshot") })
            store.pendingApply!!(if (applySucceeds) Result.success(Unit) else Result.failure(IllegalStateException("apply refused")))
            assertFalse(source.error); assertTrue(source.ready); assertTrue(source.editable)
            assertEquals(applySucceeds, result!!.isSuccess)
            assertEquals(store.data, source.state); assertEquals(1, store.writes)
            source.close()
        }
    }
    @Test fun snapshotReloadFailurePreservesCachedRowsAndBlocksWritesUntilReloadRecovery() {
        val original = ScheduleState(entries = listOf(TimetableEntry("stable", "original", 1, 480, 530, "")))
        val store = Store(original); val source = ScheduleSource(store)
        var result: Result<Unit>? = null
        source.applySnapshot(byteArrayOf(1)) { result = it }
        store.failLoad = true; store.pendingApply!!(Result.failure(IllegalStateException("apply refused")))
        assertTrue(result!!.isFailure); assertTrue(source.ready); assertTrue(source.error); assertTrue(source.readOnly)
        assertEquals(original, source.state); assertFalse(source.saveEntry("unconfirmed", 1, 480, 530)); assertEquals(1, store.writes)
        store.failLoad = false; source.reload()
        assertFalse(source.error); assertTrue(source.editable); assertEquals(original, source.state)
        source.close()
    }
}
