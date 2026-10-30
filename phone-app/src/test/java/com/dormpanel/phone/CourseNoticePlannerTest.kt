package com.dormpanel.phone

import com.dormpanel.app.schedule.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class CourseNoticePlannerTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val date = LocalDate.of(2026, 10, 28)
    private fun at(hour: Int, minute: Int = 0) = date.atTime(hour, minute).atZone(zone).toInstant()
    private val first = TimetableEntry("a", "数学", 3, 480, 600, "A101")
    private val second = TimetableEntry("b", "英语", 3, 660, 720, "B202")
    private fun plan(state: ScheduleState, now: Instant, mode: CourseNoticeMode = CourseNoticeMode.NEAR_CLASS) =
        CourseNoticePlanner.plan(state, now, zone, CourseNoticeOptions(mode, 15))

    @Test fun offByDefaultAndNextBoundary() {
        val p = plan(ScheduleState(entries = listOf(first)), at(7, 0), CourseNoticeMode.OFF)
        assertNull(p.course)
        assertEquals(at(7,45), p.nextWake)
    }
    @Test fun upcomingIsHiddenUntilLeadWindow() {
        val state = ScheduleState(entries = listOf(first))
        assertNull(plan(state, at(7,30)).course)
        val before = plan(state, at(7,45))
        assertEquals("a", before.course?.seriesId)
        assertTrue(before.imminent)
        assertEquals(at(8), before.nextWake)
        assertTrue(plan(state, at(9)).inProgress)
        assertEquals(at(10), plan(state, at(9)).nextWake)
        assertFalse(plan(state, at(10)).inProgress)
    }
    @Test fun alwaysModeDisplaysFutureAndHandlesGaps() {
        val state = ScheduleState(entries = listOf(first,second))
        assertEquals("a", plan(state, at(6), CourseNoticeMode.ALWAYS).course?.seriesId)
        assertEquals("b", plan(state, at(10,15), CourseNoticeMode.ALWAYS).course?.seriesId)
        assertNull(plan(state, at(10,15)).course)
    }
    @Test fun overlapAndOverridesUseEffectiveTimes() {
        val overlap = first.copy(id="c", title="提前下课", startMinute=540, endMinute=570)
        val state = ScheduleState(entries=listOf(first,overlap))
        assertEquals("c", plan(state, at(9,15)).course?.seriesId)
        assertEquals(at(9,30),plan(state, at(9,15)).nextWake)
        val edited = ScheduleState(entries=listOf(first), classOverrides=listOf(
            ClassOverride.of(null,"a",date.toString(),
                ClassPatch(title="改课",timingMode="custom",startMinute=720,endMinute=780))))
        assertEquals("改课",plan(edited,at(12,30)).course?.entry?.title)
        assertEquals(at(13),plan(edited,at(12,30)).nextWake)
    }
    @Test fun midnightAndNoClassesStillWake() {
        val p = plan(ScheduleState(),at(23,0))
        assertNull(p.course)
        assertEquals(date.plusDays(1).atStartOfDay(zone).toInstant(),p.nextWake)
    }
}
