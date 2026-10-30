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
        assertEquals(date.plusDays(1).atStartOfDay(zone).toInstant(), p.nextWake)
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

    @Test fun invisibleOverlapBoundariesDoNotWakeButEarlierEndingCourseDoes() {
        val long = first.copy(id="long", startMinute=510, endMinute=630)
        val short = first.copy(id="short", startMinute=540, endMinute=570)
        val state = ScheduleState(entries=listOf(first,long,short))
        assertEquals(at(9), plan(state,at(8)).nextWake)
        assertEquals("short",plan(state,at(9)).course?.seriesId)
        assertEquals(at(9,30),plan(state,at(9)).nextWake)
        assertEquals("a",plan(state,at(9,30)).course?.seriesId)
        assertEquals(at(10),plan(state,at(9,30)).nextWake)
        assertEquals("long",plan(state,at(10)).course?.seriesId)
        assertEquals(plan(state,at(9)),plan(state.copy(entries=state.entries.reversed()),at(9)))
    }
    @Test fun allLeadOptionsAndExactEnd() {
        for (lead in listOf(5,15,30)) {
            val state=ScheduleState(entries=listOf(first))
            fun p(now: Instant)=CourseNoticePlanner.plan(state,now,zone,
                CourseNoticeOptions(CourseNoticeMode.NEAR_CLASS,lead))
            val boundary=at(8).minusSeconds(lead*60L)
            assertNull(p(boundary.minusMillis(1)).course)
            assertEquals(boundary,p(boundary.minusMillis(1)).nextWake)
            assertTrue(p(boundary).imminent)
            assertTrue(p(at(8)).inProgress)
            assertFalse(p(at(10)).inProgress)
        }
    }
    @Test fun importedCourseContinuesAcrossMidnightAndWeek() {
        val start=LocalDate.of(2026,10,25).atTime(23,50).atZone(zone).toInstant()
        val end=start.plusSeconds(7200)
        val row=ImportedClassOccurrence("night","source","series",null,start.toEpochMilli(),
            "Night",start.toEpochMilli(),end.toEpochMilli(),zone.id,"Room","",null)
        val state=ScheduleState(imported=listOf(row))
        val midnight=LocalDate.of(2026,10,26).atStartOfDay(zone).toInstant()
        assertEquals(midnight,plan(state,start).nextWake)
        assertTrue(plan(state,midnight).inProgress)
        assertEquals(end,plan(state,midnight).nextWake)
        assertNull(plan(state,end).course)
        val utc=CourseNoticePlanner.plan(state,midnight,ZoneId.of("UTC"),CourseNoticeOptions(CourseNoticeMode.ALWAYS))
        assertTrue(utc.inProgress)
        assertEquals(end,utc.nextWake)
    }
}
