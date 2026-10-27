package com.dormpanel.phone

import com.dormpanel.app.schedule.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class CoursePresentationTest {
    private val profile = BuiltInProfiles.term2026
    private val date = LocalDate.of(2026,10,27)
    private val zone = ZoneId.of(profile.timezone)
    private fun item(day: LocalDate = date, first: Int = 3, last: Int = 4): ClassOccurrence {
        val times = ScheduleProjection.linkedPeriodTimes(profile,day,first,last)!!
        return ClassOccurrence(TimetableEntry("id","课程",2,595,695),day,times.first,times.second,
            periodLabel="[$first-$last 节]",linked=true)
    }
    @Test fun requiresLinkedExactTimesAndEffectiveProfile() {
        val original = item()
        assertEquals("3–4节",CoursePresentation.currentPeriods(original,profile))
        val custom = original.copy(linked=false,start=date.atTime(13,15).atZone(zone).toInstant(),end=date.atTime(14,45).atZone(zone).toInstant())
        assertNull(CoursePresentation.currentPeriods(custom,profile))
        assertNull(CoursePresentation.currentPeriods(original.copy(linked=false),profile))
        assertNull(CoursePresentation.currentPeriods(original,null))
        assertNull(CoursePresentation.currentPeriods(original.copy(start=original.start.plusSeconds(1)),profile))
        assertEquals(original.periodLabel,custom.periodLabel)
        assertTrue(CoursePresentation.detailPeriods(custom,profile)!!.startsWith("原始导入节次（非当前节次）"))
    }
    @Test fun datePhaseAndAcademicDateAreAuthoritative() {
        val summer = item(LocalDate.of(2026,10,7),5,6)
        val autumn = item(LocalDate.of(2026,10,8),5,6)
        assertEquals("5–6节",CoursePresentation.currentPeriods(summer,profile))
        assertEquals("5–6节",CoursePresentation.currentPeriods(autumn,profile))
        assertNull(CoursePresentation.currentPeriods(summer.copy(academicDate=autumn.academicDate),profile))
        assertEquals("5–6节",CoursePresentation.currentPeriods(autumn.copy(date=autumn.date.minusDays(1)),profile))
        assertNull(CoursePresentation.currentPeriods(autumn.copy(periodLabel="[6-5节]"),profile))
        assertNull(CoursePresentation.currentPeriods(autumn,profile.copy(phases=emptyList())))
        val missing = profile.copy(phases=profile.phases.map { it.copy(periods=it.periods.filterNot { p -> p.periodNumber==5 }) })
        assertNull(CoursePresentation.currentPeriods(autumn,missing))
    }
}
