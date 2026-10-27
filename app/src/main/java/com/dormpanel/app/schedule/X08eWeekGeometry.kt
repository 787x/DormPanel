package com.dormpanel.app.schedule

import java.time.*

/** Display-only geometry. Coordinates always come from the final projected instants. */
internal data class X08eSegment(val day: Int, val start: Float, val end: Float, val item: ClassOccurrence)
internal class X08eWeekAxis(val points: List<Pair<Float, Float>>, val labels: List<Pair<Float, String>>) {
    private fun interpolate(value: Float, points: List<Pair<Float, Float>>): Float {
        val i = points.indexOfFirst { it.first >= value }.let { if (it < 0) points.lastIndex else it.coerceAtLeast(1) }
        val (a, x) = points[i - 1]; val (b, y) = points[i]
        return x + (value - a) / (b - a) * (y - x)
    }
    fun y(minute: Float) = interpolate(minute, points)
    fun minute(y: Float) = interpolate(y, points.map { it.second to it.first })
    val height get() = points.last().second
}

internal object X08eWeekGeometry {
    fun segments(items: List<ClassOccurrence>, week: LocalDate, zone: ZoneId): List<X08eSegment> =
        (0..6).flatMap { day ->
            val date = week.plusDays(day.toLong())
            val from = date.atStartOfDay(zone).toInstant(); val until = date.plusDays(1).atStartOfDay(zone).toInstant()
            items.filter { it.start < until && it.end > from }.map {
                X08eSegment(day, if (it.start <= from) 0f else it.start.atZone(zone).toLocalTime().toSecondOfDay() / 60f,
                    if (it.end >= until) 1440f else it.end.atZone(zone).toLocalTime().toSecondOfDay() / 60f, it)
            }.sortedBy { it.start }
        }

    /** Connected overlap groups use exact instants, never touch padding or rounded pixels. */
    fun groups(items: List<X08eSegment>): List<List<X08eSegment>> {
        val result = mutableListOf<MutableList<X08eSegment>>()
        var end: Instant? = null
        items.sortedBy { it.item.start }.forEach {
            if (end == null || it.item.start >= end) result.add(mutableListOf())
            result.last().add(it); end = maxOf(end ?: it.item.end, it.item.end)
        }
        return result
    }
    /** Reuse a lane only after the preceding course has really ended. */
    fun lanes(items: List<X08eSegment>): List<List<X08eSegment>> {
        val lanes = mutableListOf<MutableList<X08eSegment>>()
        items.sortedBy { it.item.start }.forEach { segment ->
            val lane = lanes.firstOrNull { it.last().item.end <= segment.item.start }
                ?: mutableListOf<X08eSegment>().also { lanes += it }
            lane += segment
        }
        return lanes
    }
    fun range(segments: List<X08eSegment>): Pair<Int, Int> =
        (minOf(480, segments.minOfOrNull { it.start.toInt() } ?: 480) / 60 * 60) to
            minOf(1440, kotlin.math.ceil(maxOf(1200f, segments.maxOfOrNull { it.end } ?: 1200f) / 60).toInt() * 60)
    fun time(first: Int, last: Int) = X08eWeekAxis(listOf(first.toFloat() to 0f, last.toFloat() to (last - first) * 1.6f),
        (first..last step 60).map { it.toFloat() to timeLabel(it) })
    fun timeLabel(minute: Int): String = if (minute == 1440) "24:00" else "%02d:%02d".format(java.util.Locale.ROOT, minute / 60, minute % 60)

    /** A shared gutter is valid only when EVERY relevant source/day has the same period table.
     * Custom-time/local courses still use this invertible real-minute transform. */
    fun periods(state: ScheduleState, items: List<ClassOccurrence>, week: LocalDate, zone: ZoneId,
        first: Int, last: Int): X08eWeekAxis? {
        val profiles = items.mapNotNull { ScheduleProjection.profile(state, it) }.distinct()
        if (profiles.isEmpty() || items.any { it.sourceId != null && ScheduleProjection.profile(state, it) == null }) return null
        if (profiles.any { it.validate() != null || ZoneId.of(it.timezone) != zone }) return null
        val tables = profiles.flatMap { p -> (0..6).map { p.phaseOn(week.plusDays(it.toLong()))?.periods?.sortedBy { it.periodNumber } ?: return null } }
        val table = tables.first()
        if (tables.any { it != table } || table.zipWithNext().any { (a, b) -> a.end > b.start }) return null
        val start = minOf(first.toFloat(), table.first().start.toSecondOfDay() / 60f)
        val end = maxOf(last.toFloat(), table.last().end.toSecondOfDay() / 60f)
        val points = mutableListOf(start to 0f); val labels = mutableListOf<Pair<Float, String>>()
        var previous = start; var y = 0f
        table.forEach { p ->
            val a = p.start.toSecondOfDay() / 60f; val b = p.end.toSecondOfDay() / 60f
            if (a > previous) { y += minOf(36f, maxOf(12f, (a - previous) * .6f)); points += a to y }
            labels += a to "${p.periodNumber}\n${p.start}\n${p.end}"
            y += 72f; points += b to y; previous = b
        }
        if (end > previous) points += end to y + maxOf(24f, (end - previous) * .6f)
        return X08eWeekAxis(points, labels)
    }
    fun currentPeriods(item: ClassOccurrence, profile: TermScheduleProfile?): IntRange? {
        if (!item.linked || profile == null || profile.validate() != null) return null
        val (first, last) = ScheduleProjection.periods(item.periodLabel) ?: return null
        if (first > last) return null
        val phase = profile.phaseOn(item.academicDate) ?: return null
        val periods = (first..last).map { phase.period(it) ?: return null }
        if (periods.zipWithNext().any { (a,b) -> a.end > b.start }) return null
        val bounds = ScheduleProjection.linkedPeriodTimes(profile, item.academicDate, first, last) ?: return null
        return (first..last).takeIf { bounds.first == item.start && bounds.second == item.end }
    }
    fun colorIndex(item: ClassOccurrence) = Math.floorMod((item.sourceId.orEmpty() + "|" + item.seriesId).hashCode(), 6)
}
