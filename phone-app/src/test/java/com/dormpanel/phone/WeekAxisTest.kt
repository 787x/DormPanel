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
}
