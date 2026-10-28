package com.dormpanel.app.schedule

import java.time.*

data class CourseCandidate(val sourceId: String?, val originalTitle: String, val series: List<String>)
object CourseBatch {
    fun candidates(state: ScheduleState): List<CourseCandidate> {
        val series = state.imported.groupBy { it.sourceId to it.seriesId }
        val regular = series.filterValues { rows -> rows.map { it.title }.distinct().size == 1 }
            .entries.groupBy { it.key.first to it.value.first().title }.map { (key, rows) ->
                CourseCandidate(key.first, key.second, rows.map { it.key.second }.sorted())
            }
        val ambiguous = series.filterValues { rows -> rows.map { it.title }.distinct().size > 1 }.map { (key, rows) ->
            CourseCandidate(key.first, "原始名称不一致：" + rows.map { it.title }.distinct().sorted().joinToString(" / "), listOf(key.second))
        }
        return regular + ambiguous + state.entries.map { CourseCandidate(null, it.title, listOf(it.id)) }
    }

    fun occurrences(state: ScheduleState, candidate: CourseCandidate, zone: ZoneId, today: LocalDate): List<ClassOccurrence> {
        val dates = if (candidate.sourceId == null) listOf(today) else state.imported.filter {
            it.sourceId == candidate.sourceId && it.seriesId in candidate.series
        }.map { Instant.ofEpochMilli(it.start).atZone(if (state.sources.firstOrNull { source -> source.id == it.sourceId }?.resolvedTermKey() != null)
            ZoneId.of(it.timezone) else zone).toLocalDate() }
        return dates.map { it.minusDays(it.dayOfWeek.value - 1L) }.distinct().flatMap {
            ScheduleProjection.academicWeek(state, it, zone)
        }.filter { it.sourceId == candidate.sourceId && it.seriesId in candidate.series }
            .distinctBy { Triple(it.seriesId, it.imported?.id, it.anchorDate) }
    }

    /** Enumerate editable identities before day composition and specified-week exceptions. */
    fun editingOccurrences(state: ScheduleState, candidate: CourseCandidate, zone: ZoneId, today: LocalDate) =
        occurrences(state.copy(dayAdjustments = emptyList(), classOverrides = state.classOverrides.filter { it.anchorDate == null }), candidate, zone, today)

    fun validate(current: ScheduleState, expected: ScheduleState, items: List<ClassOverride>) {
        check(current.entries.toSet() == expected.entries.toSet() && current.sources.toSet() == expected.sources.toSet() &&
            current.imported.toSet() == expected.imported.toSet() && current.classOverrides.toSet() == expected.classOverrides.toSet() &&
            current.dayAdjustments.toSet() == expected.dayAdjustments.toSet() && current.profiles == expected.profiles &&
            current.sync == expected.sync) { "课表或活动版本已变化；草稿保留，请放弃草稿并重新加载" }
        require(items.isNotEmpty()) { "没有实际修改，请先选择时段并编辑字段" }
        require(items.map { it.key }.distinct().size == items.size) { "重复的时段修改" }
        items.forEach { item ->
            require(item.valid() && item.anchorDate == null && item.key == ClassOverride.key(item.sourceId, item.seriesId, null))
            check(if (item.sourceId == null) current.entries.any { it.id == item.seriesId } else
                current.sources.any { it.id == item.sourceId } && current.imported.any { it.sourceId == item.sourceId && it.seriesId == item.seriesId }) { "来源或时段已不存在" }
        }
        val combined = current.classOverrides.filterNot { old -> items.any { it.key == old.key } } + items
        require(combined.size <= 5000 && combined.sumOf { it.textCharacters().toLong() } <= 2_000_000L) { "课程覆盖超过存储预算" }
        val future = current.copy(classOverrides = combined.filter { it.anchorDate == null }, dayAdjustments = emptyList())
        val zone = ZoneId.systemDefault()
        items.forEach { edit ->
            val candidate = CourseCandidate(edit.sourceId, "", listOf(edit.seriesId))
            val raw = current.copy(classOverrides = emptyList(), dayAdjustments = emptyList())
            occurrences(raw, candidate, zone, LocalDate.now(zone)).forEach { base ->
                val session = ClassEditSession(raw, base, true, zone)
                val draft = session.draft
                val range = ScheduleProjection.periods(draft.periodLabel)
                val weekday = DayOfWeek.of(edit.weekday ?: draft.academicDate.dayOfWeek.value)
                val selected = draft.academicDate.minusDays(draft.academicDate.dayOfWeek.value - 1L).plusDays(weekday.value - 1L)
                require(!session.multiWeekday || edit.weekday == null) { "多星期系列不能统一移动星期" }
                session.patch(edit.title ?: draft.entry.title, edit.location ?: draft.entry.location,
                    edit.teacher ?: draft.teacher, edit.note ?: draft.note, selected, weekday,
                    when (edit.timingMode) { "linked" -> true; "custom" -> false; else -> draft.linked },
                    edit.periodStart ?: range?.first, edit.periodEnd ?: range?.second,
                    edit.startMinute ?: draft.entry.startMinute, edit.endMinute ?: draft.entry.endMinute)
            }
            val occurrences = occurrences(future, candidate, zone, LocalDate.now(zone))
            require(occurrences.isNotEmpty()) { "无法解析此时段的有效课程" }
            occurrences.forEach { occurrence ->
                val session = ClassEditSession(future, occurrence, true, zone)
                val draft = session.draft
                val range = ScheduleProjection.periods(draft.periodLabel)
                session.patch(draft.entry.title, draft.entry.location, draft.teacher, draft.note,
                    draft.academicDate, draft.academicDate.dayOfWeek, draft.linked,
                    range?.first, range?.second, draft.entry.startMinute, draft.entry.endMinute)
            }
        }

    }
    /** Final projected exception values, using the same composition as the timetable. */
    fun exceptionPreview(state: ScheduleState, items: List<ClassOverride>, zone: ZoneId): String {
        val future = state.copy(classOverrides = state.classOverrides.filterNot { old -> items.any { it.key == old.key } } + items)
        return state.classOverrides.filter { week -> week.anchorDate != null && items.any {
            it.sourceId == week.sourceId && it.seriesId == week.seriesId
        } }.joinToString("\n") { week ->
            val anchor = LocalDate.parse(week.anchorDate)
            val occurrence = ScheduleProjection.academicWeek(future, anchor.minusDays(anchor.dayOfWeek.value - 1L), zone)
                .firstOrNull { it.sourceId == week.sourceId && it.seriesId == week.seriesId && it.anchorDate == anchor }
            if (occurrence == null) "$anchor：指定周例外保留；当周无可显示课程"
            else "$anchor 指定周最终显示：${occurrence.entry.title}；教师：${occurrence.teacher.ifEmpty { "（空）" }}；" +
                "备注：${occurrence.note.ifEmpty { "（空）" }}；教室：${occurrence.entry.location}；" +
                "${occurrence.date} ${occurrence.start.atZone(zone).toLocalTime()}–${occurrence.end.atZone(zone).toLocalTime()}"
        }
    }

}
