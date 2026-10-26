package com.dormpanel.app.schedule

import org.junit.Assert.*
import org.junit.Test

class ScheduleMigrationUiGuardTest {
    private class Store : ScheduleStore, ScheduleV2Store {
        var paused = false
        var refuseQueued = false
        var writes = 0
        var failLoad = false
        var data = ScheduleState(entries = listOf(TimetableEntry("existing", "原课程", 1, 480, 530, "")))
        override fun v2DatasetPaused() = paused
        override fun load(callback: (Result<ScheduleState>) -> Unit) = callback(if (failLoad) Result.failure(IllegalStateException("read failed")) else Result.success(data))
        override fun put(entry: TimetableEntry, callback: (Result<Unit>) -> Unit) {
            if (refuseQueued) { paused = true; callback(Result.failure(ScheduleDatasetWriteProtected())); return }
            writes++; data = data.copy(entries = data.entries + entry); callback(Result.success(Unit))
        }
        override fun put(event: CalendarEvent, callback: (Result<Unit>) -> Unit) { error("Unexpected event write") }
        override fun deleteEvent(id: String, callback: (Result<Unit>) -> Unit) { error("Unexpected delete") }
        override fun deleteEntry(id: String, callback: (Result<Unit>) -> Unit) { error("Unexpected delete") }
        override fun close() {}
        override fun v2Frame(): ScheduleV2Frame = error("unused")
        override fun v2Migrate(baseline: ScheduleSnapshot) = error("unused")
        override fun v2Start(root: ScheduleCommit, binding: Boolean) = error("unused")
        override fun v2Stage(bytes: ByteArray) = error("unused")
        override fun v2Attempt() = error("unused")
        override fun v2Observe(commits: Map<String, String>) = error("unused")
        override fun v2RenewPending() = error("unused")
        override fun v2Receipt(bytes: ByteArray) = error("unused")
        override fun v2Apply(plan: ScheduleV2Plan): List<ScheduleMerge.Conflict> = error("unused")
        override fun v2Resolve(choice: ScheduleMerge.Choice, expectedToken: String?) = error("unused")
    }
    @Test fun protectedMutationsNeverChangeOptimisticStateAndImportAlwaysCompletesCallback() {
        val store = Store(); val source = ScheduleSource(store); val original = source.state
        store.paused = true
        assertTrue(source.readOnly); assertTrue(source.editBlockReason!!.contains("换库"))
        assertFalse(source.saveEntry("幽灵课程", 2, 480, 530)); assertFalse(source.saveEvent("事件", 100, 200))
        source.deleteEntry("existing"); assertEquals(original, source.state); assertEquals(0, store.writes); assertFalse(source.error)
        val bytes = javaClass.getResourceAsStream("/schedule/wakeup.ics")!!.readBytes()
        val preview = IcsScheduleImporter().parse(ScheduleArtifact("test.ics", bytes))
        var imports = 0; source.import(preview, "test", null) { assertTrue(it.exceptionOrNull() is ScheduleDatasetWriteProtected); imports++ }
        var deletes = 0; source.deleteImport("existing") { assertTrue(it.exceptionOrNull() is ScheduleDatasetWriteProtected); deletes++ }
        assertEquals(1, imports); assertEquals(1, deletes); assertEquals(original, source.state)
        store.paused = false; assertNull(source.editBlockReason); assertTrue(source.saveEntry("恢复后", 2, 480, 530)); assertEquals(1, store.writes)
    }
    @Test fun guardBecomingDurableBetweenUiCheckAndWriteReloadsAcceptedRowsWithoutStorageError() {
        val store = Store(); val source = ScheduleSource(store); val original = source.state
        store.refuseQueued = true
        source.saveEntry("不可持久化的临时视图", 2, 480, 530)
        assertEquals(original, source.state); assertEquals(0, store.writes); assertFalse(source.error); assertTrue(source.readOnly)
    }
    @Test fun successfulLocalRetryDoesNotReleaseExistingMigrationProtection() {
        val store = Store(); val source = ScheduleSource(store); val original = source.state
        store.paused = true; store.failLoad = true; source.reload()
        store.failLoad = false; assertTrue(source.retryLocalRead())
        assertFalse(source.error); assertTrue(source.readOnly); assertFalse(source.editable)
        assertFalse(source.saveEntry("still protected", 2, 480, 530))
        assertEquals(original, source.state); assertEquals(0, store.writes)
        source.close()
    }
}
