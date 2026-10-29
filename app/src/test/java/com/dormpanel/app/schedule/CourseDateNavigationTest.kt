package com.dormpanel.app.schedule

import org.junit.Assert.*
import org.junit.Test
import java.time.*
import java.util.Locale

class CourseDateNavigationTest {
    private val clock=object : ScheduleClock {
        override fun instant()=Instant.parse("2026-12-27T00:00:00Z")
        override fun zone()=ZoneId.of("Asia/Shanghai")
        override fun locale()=Locale.CHINA
    }
    @Test fun explicitTimetableDateResetsWeekWithoutChangingCalendarSelectionContract() {
        val session=ScheduleSession(clock)
        val sunday=LocalDate.of(2026,12,27)
        val old=session.weekStart
        session.select(sunday.plusDays(1))
        assertEquals(old,session.weekStart)
        for(date in listOf(sunday.plusDays(1),LocalDate.of(2027,1,1),LocalDate.of(2027,2,1),sunday)) {
            session.weekStart=LocalDate.of(2020,1,6)
            session.openTimetableDate(date)
            assertEquals(date,session.selectedDate)
            assertEquals(YearMonth.from(date),session.month)
            assertEquals(date.minusDays(date.dayOfWeek.value-1L),session.weekStart)
            assertEquals(ScheduleMode.TIMETABLE,session.mode)
        }
    }
}
