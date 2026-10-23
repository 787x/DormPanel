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
    // Minimum touch height participates in collision grouping so short classes remain reachable.
    fun groups(segments: List<WeekSegment>): List<List<WeekSegment>> {
        val groups = mutableListOf<MutableList<WeekSegment>>()
        var bottom = -1
        segments.forEach { segment ->
            if (groups.isEmpty() || segment.start >= bottom) { groups.add(mutableListOf()); bottom = -1 }
            groups.last().add(segment)
            bottom = maxOf(bottom, segment.end, segment.start + 24)
        }
        return groups
    }
}
