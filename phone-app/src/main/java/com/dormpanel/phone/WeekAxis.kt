package com.dormpanel.phone

import com.dormpanel.app.schedule.TermScheduleProfile
import java.time.*

/** Pure display transform. Every real minute, including breaks and out-of-hours, remains invertible. */
internal class WeekAxis private constructor(val points: List<Pair<Float, Float>>, val labels: List<Pair<Int, String>>,
    val periodLabels: List<PeriodLabel> = emptyList()) {
    data class PeriodLabel(val minute: Int, val number: Int, val start: LocalTime, val end: LocalTime)
    fun y(minute: Float): Float = interpolate(minute, points)
    fun minute(y: Float): Float = interpolate(y, points.map { it.second to it.first })
    val height get() = points.last().second
    companion object {
        private fun interpolate(value: Float, points: List<Pair<Float, Float>>): Float {
            val i = points.indexOfFirst { it.first >= value }.let { if (it < 0) points.lastIndex else if (it == 0) 1 else it }
            val (a, x) = points[i - 1]; val (b, y) = points[i]
            return x + (value - a) / (b - a) * (y - x)
        }
        fun time(first: Int, last: Int) = WeekAxis(listOf(first.toFloat() to 0f, last.toFloat() to (last-first)*.8f),
            (first..last step 60).map { it to "%02d:%02d".format(it/60,it%60) })
        fun periods(profile: TermScheduleProfile?, date: LocalDate, zone: ZoneId, first: Int, last: Int): WeekAxis? {
            if (profile == null || profile.validate() != null || ZoneId.of(profile.timezone) != zone) return null
            val periods = profile.phaseOn(date)?.periods?.sortedBy { it.periodNumber } ?: return null
            // The narrow three-line label is HH:mm. Never silently round a subminute timetable.
            if (periods.any { it.start.second != 0 || it.start.nano != 0 || it.end.second != 0 || it.end.nano != 0 }) return null
            if (periods.zipWithNext().any { (a,b) -> a.end > b.start }) return null
            val start = minOf(first, periods.first().start.toSecondOfDay()/60)
            val end = maxOf(last, (periods.last().end.toSecondOfDay()+59)/60)
            val points = mutableListOf(start.toFloat() to 0f)
            var previous = start; var y = 0f
            val labels = mutableListOf<Pair<Int,String>>()
            periods.forEach { p ->
                val a = p.start.toSecondOfDay()/60; val b = (p.end.toSecondOfDay()+59)/60
                if (a > previous) { y += if (previous == start && points.size == 1) (a-previous)*.35f else minOf(20f, maxOf(8f,(a-previous)*.35f)); points += a.toFloat() to y }
                labels += a to "${p.periodNumber}"
                y += 56f; points += b.toFloat() to y; previous = b
            }
            if (end > previous) points += end.toFloat() to y+(end-previous)*.35f
            return WeekAxis(points, labels, periods.map { PeriodLabel(it.start.toSecondOfDay()/60, it.periodNumber, it.start, it.end) })
        }
    }
}
