package com.dormpanel.app.schedule

import android.content.Context
import com.dormpanel.app.icloud.CloudCalendarEvent
import com.dormpanel.app.icloud.CloudCalendarService
import com.dormpanel.app.icloud.CloudRange
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class CalendarAgendaItem(
    val start: Instant,
    val end: Instant,
    val local: CalendarEvent? = null,
    val remote: CloudCalendarEvent? = null,
) {
    val title get() = local?.title ?: remote?.event?.title.orEmpty().ifBlank { "无标题日程" }
    val source get() = remote?.calendar?.name ?: "本机"
    val color get() = remote?.let { it.calendar.color ?: 0xff8c72db.toInt() }
    val allDay get() = remote?.event?.firstDay != null
    val note get() = remote?.event?.location ?: local?.note.orEmpty()

    fun label(context: Context, day: LocalDate, zone: ZoneId, compact: Boolean = false): String {
        fun dateLabel(date: LocalDate) = if (compact) cardDate.format(date) else scheduleDate(date)
        val first = remote?.event?.firstDay ?: start.atZone(zone).toLocalDate()
        if (allDay) {
            val last = remote!!.event.endDay!!.minusDays(1)
            val allDayLabel = context.scheduleText("全天", "All day")
            if (first == last) return if (first == day) allDayLabel else "${dateLabel(first)} · $allDayLabel"
            return "${dateLabel(first)}–${dateLabel(last)} · $allDayLabel"
        }
        val last = end.atZone(zone).toLocalDate()
        val startLabel = (if (first == day) "" else "${dateLabel(first)} ") + context.scheduleTime(start)
        if ((compact && first == last) || end == start) return startLabel
        val endLabel = (if (last == day) "" else "${dateLabel(last)} ") + context.scheduleTime(end)
        return "$startLabel – $endLabel"
    }

    companion object {
        private val cardDate = DateTimeFormatter.ofPattern("M/d")
        fun local(event: CalendarEvent) = CalendarAgendaItem(Instant.ofEpochMilli(event.start), Instant.ofEpochMilli(event.end), local = event)
        fun remote(event: CloudCalendarEvent, zone: ZoneId) = CalendarAgendaItem(event.event.start(zone), event.event.end(zone), remote = event)
    }
}

internal fun calendarAgenda(source: ScheduleSource, cloud: CloudCalendarService?, date: LocalDate): List<CalendarAgendaItem> {
    val zone = source.clock.zone()
    return (ScheduleProjection.eventsOn(source.state, date, zone).map(CalendarAgendaItem::local) +
        cloud?.eventsOn(date, zone).orEmpty().map { CalendarAgendaItem.remote(it, zone) })
        .sortedWith(compareByDescending<CalendarAgendaItem> { it.allDay }.thenBy { it.start })
}

internal fun recentCloudRange(clock: ScheduleClock, selection: Set<String>?) =
    CloudRange.between(clock.today(), clock.today().plusDays(13), clock.zone(), selection)

internal fun recentAgenda(source: ScheduleSource, cloud: CloudCalendarService?, selection: Set<String>?, limit: Int): List<CalendarAgendaItem> {
    val clock = source.clock
    val now = clock.instant()
    val zone = clock.zone()
    val until = clock.today().plusDays(14).atStartOfDay(zone).toInstant()
    val recent = recentCloudRange(clock, selection)
    val remote = recent.months.flatMap { window ->
        val from = maxOf(window.start, now)
        val end = minOf(window.end, until)
        cloud?.events(CloudRange(setOf(window), selection)).orEmpty().filter { it.event.overlaps(from, end, zone) }
    }.distinctBy { Triple(it.calendar.id, it.event.uid, it.event.recurrence) }
    return (ScheduleProjection.upcomingEvents(source.state, clock, limit).map(CalendarAgendaItem::local) +
        remote.map { CalendarAgendaItem.remote(it, zone) })
        .sortedBy { it.start }.take(limit)
}

internal fun markedCalendarDays(source: ScheduleSource, cloud: CloudCalendarService?, days: List<LocalDate>) =
    days.groupBy(YearMonth::from).flatMap { (_, dates) ->
        val zone = source.clock.zone()
        val events = cloud?.events(CloudRange.between(dates.first(), dates.last(), zone)).orEmpty()
        dates.filter { day ->
            val from = day.atStartOfDay(zone).toInstant()
            val until = day.plusDays(1).atStartOfDay(zone).toInstant()
            ScheduleProjection.eventsOn(source.state, day, zone).isNotEmpty() || events.any { it.event.overlaps(from, until, zone) }
        }
    }.toSet()
