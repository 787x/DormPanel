package com.dormpanel.phone

import com.dormpanel.app.schedule.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class WeekAxisTest {
    private val monday = LocalDate.of(2026,10,5)
    private val zone = ZoneId.of("Asia/Shanghai")
    @Test fun timeKeepsExactProportionsAndInvertsBeyondBounds() {
        val a = WeekAxis.time(480,1080)
        assertEquals(48f,a.y(540f)-a.y(480f),.001f)
        assertEquals(144f,a.y(720f)-a.y(540f),.001f)
        for (m in listOf(0f,480f,481f,720f,1440f)) assertEquals(m,a.minute(a.y(m)),.01f)
    }
    @Test fun eachDateUsesItsEffectivePhaseAndBreaksRemainInvertible() {
        val a = WeekAxis.periods(BuiltInProfiles.term2026,monday,zone,0,1440)!!
        val b = WeekAxis.periods(BuiltInProfiles.term2026,monday.plusDays(3),zone,0,1440)!!
        assertEquals(56f,a.y(915f)-a.y(870f),.001f)
        assertEquals(56f,b.y(885f)-b.y(840f),.001f)
        assertTrue(a.y(870f)-a.y(695f) <= 20.01f)
        assertTrue(a.y(870f) != b.y(870f))
        for (axis in listOf(a,b)) for (minute in 0..1440) {
            assertEquals(minute.toFloat(),axis.minute(axis.y(minute.toFloat())),.02f)
            if (minute > 0) assertTrue(axis.y(minute.toFloat()) > axis.y(minute-1f))
        }
    }
    @Test fun invalidMissingOverlappingOrWrongTimezoneNeverInventPeriods() {
        assertNull(WeekAxis.periods(null,monday,zone,480,1080))
        assertNull(WeekAxis.periods(BuiltInProfiles.term2026,LocalDate.of(2026,1,1),zone,480,1080))
        assertNull(WeekAxis.periods(BuiltInProfiles.term2026,monday,ZoneId.of("UTC"),480,1080))
        val bad = BuiltInProfiles.term2026.copy(phases=listOf(SchedulePhase(monday.minusWeeks(9),listOf(
            PeriodTime(1,LocalTime.of(8,0),LocalTime.of(9,0)), PeriodTime(2,LocalTime.of(8,30),LocalTime.of(10,0))))))
        assertNull(WeekAxis.periods(bad,monday,zone,480,1080))
    }
    @Test fun periodLabelsRetainActualPhaseNumbersAndClockBounds() {
        val profile=BuiltInProfiles.term2026
        for (date in listOf(monday,monday.plusDays(3))) {
            val axis=WeekAxis.periods(profile,date,zone,0,1440)!!
            assertEquals(profile.phaseOn(date)!!.periods.sortedBy { it.periodNumber }.map {
                WeekAxis.PeriodLabel(it.start.toSecondOfDay()/60,it.periodNumber,it.start,it.end)
            },axis.periodLabels)
        }
        assertNotEquals(WeekAxis.periods(profile,monday,zone,0,1440)!!.periodLabels,
            WeekAxis.periods(profile,monday.plusDays(3),zone,0,1440)!!.periodLabels)
        val seconds=profile.copy(phases=listOf(SchedulePhase(profile.week1Monday,listOf(
            PeriodTime(1,LocalTime.of(8,0,30),LocalTime.of(8,45,30))))))
        assertNull(WeekAxis.periods(seconds,monday,zone,480,1080))
    }
    @Test fun visibleBoundsApplyToBothAxesAndStillIncludeConfiguredPeriods() {
        val weekday = com.dormpanel.app.schedule.ClassOccurrence(TimetableEntry("a","weekday",1,480,1020),monday,
            monday.atTime(8,0).atZone(zone).toInstant(),monday.atTime(17,0).atZone(zone).toInstant())
        val weekend = weekday.copy(date=monday.plusDays(5),start=monday.plusDays(5).atTime(22,0).atZone(zone).toInstant(),
            end=monday.plusDays(6).atTime(0,30).atZone(zone).toInstant())
        val segments = WeekLayout.segments(listOf(weekday,weekend),monday,zone)
        val five = WeekLayout.visibleRange(segments,5); val seven = WeekLayout.visibleRange(segments,7)
        for (period in listOf(false,true)) {
            fun axis(bounds: Pair<Int,Int>) = if (period) WeekAxis.periods(BuiltInProfiles.term2026,monday,zone,bounds.first,bounds.second)!! else WeekAxis.time(bounds.first,bounds.second)
            val a = axis(five); val b = axis(seven)
            assertTrue(a.height < b.height)
            assertEquals(480f,a.points.first().first,.01f)
            assertEquals(if (period) 1295f else 1080f,a.points.last().first,.01f)
            assertEquals(600f,a.minute(a.y(600f)),.01f)
            assertEquals(600f,b.minute(b.y(600f)),.01f)
        }
    }
}
