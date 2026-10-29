package com.dormpanel.app.schedule

import java.time.*

/** Editing baseline and sparse patch semantics shared by both native editors. */
class ClassEditSession(private val state: ScheduleState, val item: ClassOccurrence, val allWeeks: Boolean, private val zone: ZoneId) {
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
    }.map { Instant.ofEpochMilli(it.start).atZone(if (profile!=null) ZoneId.of(it.timezone) else zone).dayOfWeek }.distinct().size > 1

    private val effectiveDates by lazy {
        val weeks=state.imported.filter {it.sourceId==item.sourceId && it.seriesId==item.seriesId}.map {
            val d=Instant.ofEpochMilli(it.start).atZone(if(profile!=null) ZoneId.of(it.timezone) else zone).toLocalDate()
            d.minusDays(d.dayOfWeek.value-1L)
        }.toMutableSet()
        state.courseSlots.filter {it.sourceId==item.sourceId && it.enabled && (it.id==item.seriesId || it.replacesSeries==item.seriesId)}.forEach {slot ->
            slot.weeks.forEach {weeks.add(LocalDate.parse(slot.week1).plusWeeks(it-1L))}
        }
        val raw=state.copy(classOverrides=emptyList(),dayAdjustments=emptyList())
        weeks.flatMap {ScheduleProjection.academicWeek(raw,it,zone)}.filter {it.sourceId==item.sourceId && it.seriesId==item.seriesId}.map {it.academicDate}.distinct()
    }
    /** Preserve exact inheritance/clear intent when a form field was not changed. */
    fun preserveUntouched(original: ClassPatch, before: ClassPatch, after: ClassPatch) = ClassPatch(
        title = if (after.title == before.title) original.title else after.title,
        weekday = if (after.weekday == before.weekday) original.weekday else after.weekday,
        date = if (after.date == before.date) original.date else after.date,
        periodStart = if (after.periodStart == before.periodStart) original.periodStart else after.periodStart,
        periodEnd = if (after.periodEnd == before.periodEnd) original.periodEnd else after.periodEnd,
        timingMode = if (after.timingMode == before.timingMode) original.timingMode else after.timingMode,
        startMinute = if (after.startMinute == before.startMinute) original.startMinute else after.startMinute,
        endMinute = if (after.endMinute == before.endMinute) original.endMinute else after.endMinute,
        location = if (after.location == before.location) original.location else after.location,
        teacher = if (after.teacher == before.teacher) original.teacher else after.teacher,
        note = if (after.note == before.note) original.note else after.note)

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
            linked && allWeeks && (effectiveDates.isEmpty() || effectiveDates.any { original ->
                val target=if(multiWeekday) original else original.minusDays(original.dayOfWeek.value-1L).plusDays(selectedWeekday.value-1L)
                ScheduleProjection.linkedPeriodTimes(profile!!,target,first!!,last!!)==null
            }) -> "Cannot resolve selected periods on every actual course date; provide a reliable finite range."
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
