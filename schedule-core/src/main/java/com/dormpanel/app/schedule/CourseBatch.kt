package com.dormpanel.app.schedule

import java.time.*

data class CourseCandidate(val sourceId: String?, val originalTitle: String, val series: List<String>)
object CourseBatch {
    fun candidates(state: ScheduleState): List<CourseCandidate> =
        state.imported.groupBy { it.sourceId to it.title }.map { (key, rows) ->
            CourseCandidate(key.first, key.second, rows.map { it.seriesId }.distinct().sorted())
        } + state.entries.map { CourseCandidate(null, it.title, listOf(it.id)) }

    fun occurrences(state: ScheduleState, candidate: CourseCandidate, zone: ZoneId, today: LocalDate): List<ClassOccurrence> {
        val dates = if (candidate.sourceId == null) listOf(today) else state.imported.filter {
            it.sourceId == candidate.sourceId && it.seriesId in candidate.series
        }.map { Instant.ofEpochMilli(it.start).atZone(zone).toLocalDate() }
        return dates.map { it.minusDays(it.dayOfWeek.value - 1L) }.distinct().flatMap {
            ScheduleProjection.academicWeek(state, it, zone)
        }.filter { it.sourceId == candidate.sourceId && it.seriesId in candidate.series }
            .distinctBy { it.seriesId to it.imported?.id }
    }

    fun validate(current: ScheduleState, expected: ScheduleState, items: List<ClassOverride>) {
        check(current.entries.toSet() == expected.entries.toSet() && current.sources.toSet() == expected.sources.toSet() &&
            current.imported.toSet() == expected.imported.toSet() && current.classOverrides.toSet() == expected.classOverrides.toSet() &&
            current.dayAdjustments.toSet() == expected.dayAdjustments.toSet() && current.profiles == expected.profiles &&
            current.sync == expected.sync) { "课表或活动版本已变化；草稿保留，请重新加载后比较" }
        require(items.isNotEmpty() && items.map { it.key }.distinct().size == items.size)
        items.forEach { item ->
            require(item.valid() && item.anchorDate == null && item.key == ClassOverride.key(item.sourceId, item.seriesId, null))
            check(if (item.sourceId == null) current.entries.any { it.id == item.seriesId } else
                current.sources.any { it.id == item.sourceId } && current.imported.any { it.sourceId == item.sourceId && it.seriesId == item.seriesId }) { "来源或时段已不存在" }
        }
        val combined = current.classOverrides.filterNot { old -> items.any { it.key == old.key } } + items
        require(combined.size <= 5000 && combined.sumOf { it.textCharacters().toLong() } <= 2_000_000L) { "课程覆盖超过存储预算" }
    }
}
