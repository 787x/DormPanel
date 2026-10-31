package com.dormpanel.phone

import com.dormpanel.app.schedule.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

/**
 * P1 life-cycle rules that do not need Android: the tracked session is a pure identity
 * and the persisted validity boundary is what keeps an expired tracking from lingering.
 */
class CourseNoticeTrackingLifecycleTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val date = LocalDate.of(2026, 10, 28)
    private fun at(hour: Int, minute: Int = 0) = date.atTime(hour, minute).atZone(zone).toInstant()
    private val first = TimetableEntry("a", "数学", 3, 480, 600, "A101")
    private val second = TimetableEntry("b", "英语", 3, 660, 720, "B202")

    @Test fun expiredSessionIsNotActiveAndIsCleanedByMatch() {
        val tracked = TrackedCourse(null, "a", at(8).toEpochMilli(), at(10).toEpochMilli())
        assertTrue(tracked.active(at(9)))
        assertFalse(tracked.active(at(10)))
        // The planner refuses an expired session the same way Coordinator's match() does.
        val state = ScheduleState(entries = listOf(first, second))
        val after = CourseNoticePlanner.plan(state, at(10, 5), zone,
            CourseNoticeOptions(CourseNoticeMode.NEAR_CLASS, 15), tracked)
        assertNull(after.tracked)
        assertFalse(after.tracking)
    }

    @Test fun cancelledOrMovedCourseDropsTrackingButKeepsOrdinaryNotice() {
        val tracked = TrackedCourse(null, "a", at(8).toEpochMilli(), at(10).toEpochMilli())
        val moved = ScheduleState(entries = listOf(first.copy(startMinute = 720, endMinute = 780), second))
        val plan = CourseNoticePlanner.plan(moved, at(12, 30), zone,
            CourseNoticeOptions(CourseNoticeMode.NEAR_CLASS, 15), tracked)
        assertNull(plan.tracked)
        assertFalse(plan.tracking)
        // The moved class is in progress at 12:30, so ordinary notices keep showing it,
        // but the explicit tracking session is gone.
        assertTrue(plan.inProgress)
        assertEquals("a", plan.course?.seriesId)
    }

    @Test fun ordinaryOffNeverPublishesTrackingEvenIfSessionExists() {
        val tracked = TrackedCourse(null, "a", at(8).toEpochMilli(), at(10).toEpochMilli())
        val plan = CourseNoticePlanner.plan(ScheduleState(entries = listOf(first, second)), at(9), zone,
            CourseNoticeOptions(CourseNoticeMode.OFF, 15), tracked)
        assertNull(plan.course)
        assertFalse(plan.tracking)
    }

    @Test fun persistedValidityBoundaryKeepsExpiredTrackingFromLingering() {
        // Mirrors Coordinator: an end-boundary is remembered, and a later retry that
        // crosses it fails closed instead of republishing a stale "tracking" frame.
        var saved: Long? = null
        var visible = false
        val validity = CourseNoticeValidity({ saved }, { saved = it }, { visible = false })
        validity.remember(at(10).toEpochMilli())
        visible = true
        validity.expire(at(10).toEpochMilli())
        assertFalse(visible)
        assertNull(saved)
    }
}