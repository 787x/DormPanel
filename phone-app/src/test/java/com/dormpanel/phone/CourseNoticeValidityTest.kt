package com.dormpanel.phone

import org.junit.Assert.*
import org.junit.Test

class CourseNoticeValidityTest {
    private class Harness {
        var now = 0L
        var saved: Long? = null
        var visible = false
        val timers = mutableListOf<Pair<Long, () -> Unit>>()
        val reads = mutableListOf<(Result<Long>) -> Unit>()
        var enabled = true
        fun guard() = CourseNoticeValidity({ saved }, { saved = it }, { visible = false })
        val validity = guard()
        var wake = 0L
        val machine = WidgetRefreshCoordinator<Long>(
            { enabled }, { ms, f -> timers.add(now + ms to f) }, { reads.add(it) },
            { result -> result.getOrNull()?.let { validity.remember(it); visible=true; wake=it } },
            {}, { wake=validity.retryAt(now) }, { validity.clear(); wake=0 })
        fun request() { validity.expire(now); machine.request() }
        fun advance(time: Long) {
            while (true) {
                val task=timers.filter { it.first<=time }.minByOrNull { it.first } ?: break
                timers.remove(task); now=task.first; task.second()
            }
            now=time
        }
        fun success(until: Long) { reads.last()(Result.success(until)) }
        fun fail() { reads.last()(Result.failure(IllegalStateException("Store unavailable"))) }
    }
    @Test fun failureWithinValidityRetainsFrameAndCannotReplaceEarlierBoundary() {
        val h=Harness(); h.request(); h.success(100_000)
        h.now=50_000; h.request(); h.fail()
        assertTrue(h.visible); assertEquals(100_000L,h.wake)
        h.now=100_000; h.request(); h.fail()
        assertFalse(h.visible); assertNull(h.saved); assertEquals(400_000L,h.wake)
    }
    @Test fun timeoutCrossingBoundaryExpiresThenSuccessRestoresLatestFrame() {
        val h=Harness(); h.request(); h.success(10_000)
        h.now=5_000; h.request(); h.advance(12_000)
        assertFalse(h.visible); assertNull(h.saved)
        h.request(); h.success(50_000)
        assertTrue(h.visible); assertEquals(50_000L,h.wake)
        h.reads[1](Result.success(15_000))
        assertEquals(50_000L,h.saved!!)
    }
    @Test fun offExpiryAndLateCallbackCannotRepublishOrReschedule() {
        val h=Harness(); h.request(); h.success(10_000)
        h.request(); h.enabled=false; h.now=10_000; h.request()
        h.reads.last()(Result.success(90_000)); h.advance(20_000)
        assertFalse(h.visible); assertNull(h.saved); assertEquals(0L,h.wake)
    }
    @Test fun reconstructedGuardUsesPersistedBoundaryAndUnknownDeadlineFailsClosed() {
        val h=Harness(); h.validity.remember(100)
        h.visible=true; h.guard().expire(99); assertTrue(h.visible)
        h.guard().expire(100); assertFalse(h.visible)
        h.visible=true; h.guard().expire(101); assertFalse(h.visible)
    }
}
