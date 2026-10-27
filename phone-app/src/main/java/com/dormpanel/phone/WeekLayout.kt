package com.dormpanel.phone

import com.dormpanel.app.schedule.ClassOccurrence
import java.time.*

/** Display-only clipping; every segment retains the projected instance used by the editor. */
internal data class WeekSegment(val day: Int, val start: Int, val end: Int, val item: ClassOccurrence)
internal object WeekLayout {
    fun segments(items: List<ClassOccurrence>, monday: LocalDate, zone: ZoneId): List<WeekSegment> =
        (0..6).flatMap { day ->
            val date = monday.plusDays(day.toLong())
            val from = date.atStartOfDay(zone).toInstant()
            val until = date.plusDays(1).atStartOfDay(zone).toInstant()
            items.filter { it.start < until && it.end > from }.map { item ->
                val start = if (item.start <= from) 0 else item.start.atZone(zone).toLocalTime().toSecondOfDay() / 60
                val end = if (item.end >= until) 1440 else (item.end.atZone(zone).toLocalTime().toSecondOfDay() + 59) / 60
                WeekSegment(day, start, end.coerceAtLeast(start + 1), item)
            }.sortedWith(compareBy<WeekSegment> { it.start }.thenBy { it.end }.thenBy { it.item.sourceId.orEmpty() }
                .thenBy { it.item.seriesId }.thenBy { it.item.anchorDate }.thenBy { it.item.entry.id })
        }
    // Group only genuine instant overlap, independently of minute/pixel rounding and touch padding.
    fun groups(segments: List<WeekSegment>): List<List<WeekSegment>> {
        val groups = mutableListOf<MutableList<WeekSegment>>()
        var bottom: Instant? = null
        segments.sortedBy { it.item.start }.forEach { segment ->
            if (bottom == null || segment.item.start >= bottom) { groups.add(mutableListOf()); bottom = segment.item.end }
            groups.last().add(segment)
            bottom = maxOf(bottom!!,segment.item.end)
        }
        return groups
    }
    fun visibleRange(segments: List<WeekSegment>, dayCount: Int): Pair<Int,Int> {
        val visible = segments.filter { it.day in 0 until dayCount }
        return minOf(480,visible.minOfOrNull { it.start } ?: 480)/60*60 to
            maxOf(1080,visible.maxOfOrNull { it.end } ?: 1080)
    }
}

internal data class WeekHitArea(val group: List<WeekSegment>, val top: Int, val bottom: Int)
internal object WeekTouch {
    /** Body wins; blank-space candidates stay in stable time order and retain their overlap groups. */
    fun hit(areas: List<WeekHitArea>, y: Float, padding: Int): List<WeekHitArea> {
        areas.firstOrNull { y >= it.top && y < it.bottom }?.let { return listOf(it) }
        return areas.filter { y >= it.top-padding && y <= it.bottom+padding }
    }
}
