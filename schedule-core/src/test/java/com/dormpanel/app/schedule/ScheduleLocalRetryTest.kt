package com.dormpanel.app.schedule

import org.junit.Assert.*
import org.junit.Test

class ScheduleLocalRetryTest {
    private class Store(var failed: Boolean = false, var delayed: Boolean = false) : ScheduleStore {
        val data = ScheduleState(entries = listOf(TimetableEntry("stable", "原课程", 1, 480, 530, "")))
        var reads = 0
        var pending: ((Result<ScheduleState>) -> Unit)? = null
        override fun load(callback: (Result<ScheduleState>) -> Unit) {
            reads++
            if (delayed) pending = callback else callback(if (failed) Result.failure(IllegalStateException("read failed")) else Result.success(data))
        }
        override fun put(event: CalendarEvent, callback: (Result<Unit>) -> Unit) = error("retry must not write")
        override fun put(entry: TimetableEntry, callback: (Result<Unit>) -> Unit) = error("retry must not write")
        override fun deleteEvent(id: String, callback: (Result<Unit>) -> Unit) = error("retry must not write")
        override fun deleteEntry(id: String, callback: (Result<Unit>) -> Unit) = error("retry must not write")
        override fun close() {}
    }
    @Test fun cachedFailureCanRetryAgainAndRapidClicksAdmitOnlyOnePendingRead() {
        val store = Store(); val source = ScheduleSource(store); val original = source.state
        store.failed = true; source.reload(); store.delayed = true
        val notifications = mutableListOf<Pair<Boolean, Boolean>>()
        source.subscribe { notifications += source.retryingLocalRead to source.applyingSnapshot }
        val count = store.reads
        assertTrue(source.retryLocalRead())
        assertTrue(source.retryingLocalRead); assertTrue(source.applyingSnapshot); assertFalse(source.editable)
        repeat(10) { assertFalse(source.retryLocalRead()) }
        assertEquals(count + 1, store.reads); assertEquals(original, source.state)
        store.pending!!(Result.failure(IllegalStateException("still failed")))
        assertFalse(source.retryingLocalRead); assertFalse(source.applyingSnapshot); assertTrue(source.error)
        assertEquals(original, source.state)
        store.failed = false; store.delayed = false
        assertTrue(source.retryLocalRead()); assertFalse(source.error); assertTrue(source.editable)
        assertEquals(original, source.state)
        assertFalse(source.retryLocalRead()); assertEquals(count + 2, store.reads)
        assertTrue(notifications.all { it.first }) // Completion is also local-only; no normal-ready echo.
        assertTrue(notifications.any { !it.second })
        source.close()
    }
    @Test fun initialFailureRecoversWithoutRestartAndPreservesTheStoredCourses() {
        val store = Store(failed = true); val source = ScheduleSource(store)
        assertFalse(source.ready); assertTrue(source.error)
        store.failed = false
        assertTrue(source.retryLocalRead()); assertTrue(source.ready); assertTrue(source.editable)
        assertEquals(store.data, source.state); assertEquals(2, store.reads)
        source.close()
    }
    @Test fun closedSourceDoesNotNotifyOldViewsWhenRetryReturns() {
        val store = Store(failed = true); val source = ScheduleSource(store)
        var notifications = 0; source.subscribe { notifications++ }
        store.delayed = true; source.retryLocalRead()
        val beforeClose = notifications
        source.close(); store.pending!!(Result.success(store.data))
        assertEquals(beforeClose, notifications); assertFalse(source.retryLocalRead())
    }
    @Test fun normalAndInitialLoadingStatesDoNotOfferManualRetry() {
        val store = Store(delayed = true); val source = ScheduleSource(store)
        assertFalse(source.error); assertFalse(source.retryLocalRead()); assertEquals(1, store.reads)
        store.pending!!(Result.success(store.data))
        assertFalse(source.retryLocalRead()); assertEquals(1, store.reads)
        source.close()
    }
}
