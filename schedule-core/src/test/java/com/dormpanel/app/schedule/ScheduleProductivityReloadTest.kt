package com.dormpanel.app.schedule

import org.junit.Assert.*
import org.junit.Test

class ScheduleProductivityReloadTest {
    private class Store(var state: ScheduleState) : ScheduleStore {
        var queued = false
        var complete: ((Result<ScheduleState>) -> Unit)? = null
        override fun load(callback: (Result<ScheduleState>) -> Unit) {
            if (queued) complete = callback else callback(Result.success(state))
        }
        override fun put(event: CalendarEvent, callback: (Result<Unit>) -> Unit) = error("Unexpected write")
        override fun put(entry: TimetableEntry, callback: (Result<Unit>) -> Unit) = error("Unexpected write")
        override fun deleteEvent(id: String, callback: (Result<Unit>) -> Unit) = error("Unexpected write")
        override fun deleteEntry(id: String, callback: (Result<Unit>) -> Unit) = error("Unexpected write")
        override fun close() {}
    }

    @Test fun efficiencyChangesAndReceiptUseStatusChannelWhileCoursesUseNormalRefresh() {
        val original = ScheduleState(
            entries = listOf(TimetableEntry("x", "课程", 1, 480, 540)),
            sync = SnapshotIdentity("dataset", "base"), sharedRecords = emptyList()
        )
        val store = Store(original)
        val source = ScheduleSource(store)
        var fullRefreshes = 0
        val states = mutableListOf<SnapshotIdentity?>()
        val status = { states.add(source.state.sync); Unit }
        source.subscribe { fullRefreshes++ }
        source.subscribeSyncReload(status)
        val displayed = source.state.entries
        val memo = SharedProductivity.encode(ProductivityRecord.Memo("00000000-0000-0000-0000-000000000001", "备忘", "正文"))
        store.state = original.copy(sharedRecords = listOf(memo))
        source.reload()
        assertSame(displayed, source.state.entries)
        assertEquals(listOf(memo), source.state.sharedRecords)
        store.state = store.state.copy(sync = SnapshotIdentity("dataset", "base", pending = true))
        source.reload()
        store.state = store.state.copy(sync = SnapshotIdentity("dataset", "receipt"))
        source.reload()
        assertEquals(listOf(original.sync, SnapshotIdentity("dataset", "base", true), store.state.sync), states)
        assertEquals(0, fullRefreshes)

        store.state = store.state.copy(entries = displayed.map { it.copy(title = "新课程") })
        source.reload()
        assertEquals(1, fullRefreshes)
        assertEquals("新课程", source.state.entries.single().title)
        store.state = store.state.copy(sync = SnapshotIdentity("other-dataset", "initial"))
        source.reload()
        assertEquals(2, fullRefreshes)

        source.unsubscribeSyncReload(status)
        source.reload()
        assertEquals(3, states.size)
        store.queued = true
        source.reload()
        source.close()
        store.complete!!(Result.success(store.state))
        assertEquals(2, fullRefreshes)
        assertEquals(3, states.size)
    }
}
