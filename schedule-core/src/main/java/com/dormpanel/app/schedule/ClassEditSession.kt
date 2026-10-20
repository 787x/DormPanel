package com.dormpanel.app.schedule

import java.time.*

/** Editing baseline and sparse patch semantics shared by both native editors. */
class ClassEditSession(state: ScheduleState, val item: ClassOccurrence, val allWeeks: Boolean, private val zone: ZoneId) {
    val date = item.anchorDate
    val key = ClassOverride.key(item.sourceId, item.seriesId, if (allWeeks) null else date.toString())
    private val monday = date.minusDays((date.dayOfWeek.value - 1).toLong())
    private val withoutWeek = state.copy(classOverrides = state.classOverrides.filterNot {
            allWeeks && it.sourceId == item.sourceId && it.seriesId == item.seriesId && it.anchorDate != null
        })
    val draft = if (allWeeks) ScheduleProjection.academicWeek(withoutWeek, monday, zone)
            .firstOrNull { it.seriesId == item.seriesId && it.sourceId == item.sourceId &&
                it.imported?.id == item.imported?.id } ?: item else item
    private val lower = state.copy(classOverrides = state.classOverrides.filterNot {
            it.key == key || allWeeks && it.sourceId == item.sourceId && it.seriesId == item.seriesId && it.anchorDate != null
        })
    val baseline = ScheduleProjection.academicWeek(lower, date.minusDays((date.dayOfWeek.value - 1).toLong()), zone)
            .firstOrNull { it.seriesId == item.seriesId && it.sourceId == item.sourceId &&
                it.imported?.id == item.imported?.id && (allWeeks || it.anchorDate == date) } ?: draft
    val profile = ScheduleProjection.profile(state, item)
    val multiWeekday = item.sourceId != null && state.imported.filter {
        it.sourceId == item.sourceId && it.seriesId == item.seriesId
    }.map { Instant.ofEpochMilli(it.start).atZone(zone).dayOfWeek }.distinct().size > 1

    fun patch(title: String, location: String, teacher: String, note: String,
        selected: LocalDate, selectedWeekday: DayOfWeek, linked: Boolean,
        first: Int?, last: Int?, start: Int, end: Int): ClassPatch {
        val range = if (linked && profile != null && first != null && last != null)
            ScheduleProjection.linkedPeriodTimes(profile, selected, first, last) else null
        val baseRange = ScheduleProjection.periods(baseline.periodLabel)
        val patch = ClassPatch(
            title = title.trim().takeIf { it != baseline.entry.title },
            weekday = if (allWeeks && !multiWeekday) selectedWeekday.takeIf { it.value != baseline.academicDate.dayOfWeek.value }?.value else null,
            date = if (!allWeeks) selected.toString().takeIf { selected != baseline.academicDate } else null,
            periodStart = if (linked) first?.takeIf { it != baseRange?.first } else null,
            periodEnd = if (linked) last?.takeIf { it != baseRange?.second } else null,
            timingMode = if (linked != baseline.linked) if (linked) "linked" else "custom" else null,
            startMinute = if (!linked) start.takeIf { it != baseline.entry.startMinute || baseline.linked } else null,
            endMinute = if (!linked) end.takeIf { it != baseline.entry.endMinute || baseline.linked } else null,
            location = location.trim().takeIf { it != baseline.entry.location },
            teacher = teacher.trim().takeIf { it != baseline.teacher },
            note = note.trim().takeIf { it != baseline.note })
        val problem = when {
            title.isBlank() -> "Enter a course name."
            linked && (first == null || last == null || first > last || range == null) -> "The selected phase has no definition for those periods."
            linked && allWeeks && profile!!.phases.any { phase -> phase.period(first!!) == null || phase.period(last!!) == null } ->
                "Every term phase must define those periods for an all-weeks edit."
            end <= start && !(baseline.end.atZone(zone).toLocalDate() > baseline.date &&
                !linked && start == baseline.entry.startMinute && end == baseline.entry.endMinute) ->
                "End must be after start."
            !patch.valid(allWeeks) -> "Check the class fields and text lengths."
            else -> null
        }
        if (problem != null) throw ScheduleImportException(problem)
        return patch
    }
}
