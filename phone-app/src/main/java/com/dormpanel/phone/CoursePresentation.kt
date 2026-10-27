package com.dormpanel.phone

import com.dormpanel.app.schedule.*

/** Original periodLabel is provenance, not proof of the effective timing. Never writes course data. */
internal object CoursePresentation {
    fun currentPeriods(item: ClassOccurrence, profile: TermScheduleProfile?): String? {
        if (!item.linked || profile == null || profile.validate() != null) return null
        val range = ScheduleProjection.periods(item.periodLabel) ?: return null
        if (range.first > range.second) return null
        val phase = profile.phaseOn(item.academicDate) ?: return null
        val periods = (range.first..range.second).map { phase.period(it) ?: return null }
        if (periods.zipWithNext().any { (a,b) -> a.end > b.start }) return null
        val bounds = ScheduleProjection.linkedPeriodTimes(profile,item.academicDate,range.first,range.second) ?: return null
        if (bounds.first != item.start || bounds.second != item.end) return null
        return if (range.first == range.second) "${range.first}节" else "${range.first}–${range.second}节"
    }
    fun detailPeriods(item: ClassOccurrence, profile: TermScheduleProfile?): String? =
        currentPeriods(item,profile)?.let { "当前节次：$it" }
            ?: item.periodLabel?.takeIf { it.isNotBlank() }?.let { "原始导入节次（非当前节次）：$it" }
}
