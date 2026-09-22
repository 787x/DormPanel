package com.dormpanel.app.schedule

import java.time.*
import java.time.temporal.WeekFields
import java.util.Locale
import java.util.UUID

interface ScheduleClock {
    fun instant(): Instant
    fun zone(): ZoneId
    fun locale(): Locale
    fun today(): LocalDate = instant().atZone(zone()).toLocalDate()
}
object DeviceScheduleClock : ScheduleClock {
    override fun instant(): Instant = Instant.now()
    override fun zone(): ZoneId = ZoneId.systemDefault()
    override fun locale(): Locale = Locale.getDefault()
}
data class CalendarEvent(val id: String, val title: String, val start: Long, val end: Long, val note: String = "") {
    fun valid() = id.isNotBlank() && title.isNotBlank() && end > start &&
        start >= -62135510400000L && end <= 253402214400000L
}
data class TimetableEntry(val id: String, val title: String, val day: Int, val startMinute: Int,
    val endMinute: Int, val location: String = "") {
    fun valid() = id.isNotBlank() && title.isNotBlank() && day in 1..7 &&
        startMinute in 0..1439 && endMinute in 1..1439 && endMinute > startMinute
}
data class ScheduleState(val events: List<CalendarEvent> = emptyList(), val entries: List<TimetableEntry> = emptyList(),
    val invalidRecords: Int = 0, val sources: List<ImportSource> = emptyList(),
    val imported: List<ImportedClassOccurrence> = emptyList()) {
    val importedIndex by lazy { ImportedOccurrenceIndex(imported) }
}
interface ScheduleStore {
    fun load(callback: (Result<ScheduleState>) -> Unit)
    fun put(event: CalendarEvent, callback: (Result<Unit>) -> Unit)
    fun put(entry: TimetableEntry, callback: (Result<Unit>) -> Unit)
    fun deleteEvent(id: String, callback: (Result<Unit>) -> Unit)
    fun deleteEntry(id: String, callback: (Result<Unit>) -> Unit)
    fun commitImport(source: ImportSource, occurrences: List<ImportedClassOccurrence>, replace: Boolean,
        callback: (Result<ImportCommit>) -> Unit) { callback(Result.failure(UnsupportedOperationException("Imports unavailable"))) }
    fun deleteImport(id: String, callback: (Result<Unit>) -> Unit) { callback(Result.failure(UnsupportedOperationException("Imports unavailable"))) }
    fun close()
}

/** Main-thread confined. Global identities belong to this boundary, never to dashboard cards.
 * Invalid disk rows are withheld with a visible warning, and remain intact for future recovery. */
class ScheduleSource(private val store: ScheduleStore, val clock: ScheduleClock = DeviceScheduleClock) {
    var state = ScheduleState(); private set
    var ready = false; private set
    var error = false; private set
    private var closed = false
    private val listeners = linkedSetOf<() -> Unit>()
    init {
        store.load { result -> if (!closed) {
            result.onSuccess { loaded ->
                state = ordered(loaded.copy(events = loaded.events.filter { it.valid() }, entries = loaded.entries.filter { it.valid() },
                    invalidRecords = loaded.invalidRecords + loaded.events.count { !it.valid() } + loaded.entries.count { !it.valid() }))
                ready = true
            }.onFailure { error = true }
            refresh()
        } }
    }
    fun subscribe(listener: () -> Unit) { if (!closed) listeners.add(listener) }
    fun unsubscribe(listener: () -> Unit) { listeners.remove(listener) }
    fun refresh() { if (!closed) listeners.toList().forEach { it() } }
    private fun ordered(value: ScheduleState) = value.copy(
        events = value.events.sortedWith(compareBy<CalendarEvent> { it.start }.thenBy { it.title }.thenBy { it.id }),
        entries = value.entries.sortedWith(compareBy<TimetableEntry> { it.day }.thenBy { it.startMinute }.thenBy { it.title }.thenBy { it.id }))
    private val completion: (Result<Unit>) -> Unit = { if (!closed && it.isFailure) { error = true; refresh() } }
    fun saveEvent(title: String, start: Long, end: Long, note: String = "", id: String? = null): Boolean {
        val event = CalendarEvent(id ?: UUID.randomUUID().toString(), title.trim(), start, end, note.trim())
        if (closed || !ready || !event.valid() || (id != null && state.events.none { it.id == id })) return false
        state = ordered(state.copy(events = state.events.filterNot { it.id == event.id } + event))
        store.put(event, completion); refresh(); return true
    }
    fun saveEntry(title: String, day: Int, start: Int, end: Int, location: String = "", id: String? = null): Boolean {
        val entry = TimetableEntry(id ?: UUID.randomUUID().toString(), title.trim(), day, start, end, location.trim())
        if (closed || !ready || !entry.valid() || (id != null && state.entries.none { it.id == id })) return false
        state = ordered(state.copy(entries = state.entries.filterNot { it.id == entry.id } + entry))
        store.put(entry, completion); refresh(); return true
    }
    fun deleteEvent(id: String) {
        if (closed || !ready || state.events.none { it.id == id }) return
        state = state.copy(events = state.events.filterNot { it.id == id }); store.deleteEvent(id, completion); refresh()
    }
    fun deleteEntry(id: String) {
        if (closed || !ready || state.entries.none { it.id == id }) return
        state = state.copy(entries = state.entries.filterNot { it.id == id }); store.deleteEntry(id, completion); refresh()
    }
    fun import(preview: ImportPreview, name: String, targetId: String?, callback: (Result<ImportCommit>) -> Unit) {
        if (closed || !ready) return
        IcsScheduleImporter().commit(preview, store, name, targetId, clock.instant().toEpochMilli()) { result ->
            if (!closed) {
                result.onSuccess { committed -> if (!committed.unchanged) {
                    state = state.copy(sources = state.sources.filterNot { it.id == committed.source.id } + committed.source,
                        imported = state.imported.filterNot { it.sourceId == committed.source.id } + committed.occurrences)
                    refresh()
                } }
                callback(result)
            }
        }
    }
    fun deleteImport(id: String, callback: (Result<Unit>) -> Unit) {
        if (closed || !ready) return
        store.deleteImport(id) { result -> if (!closed) {
            result.onSuccess {
                state = state.copy(sources = state.sources.filterNot { it.id == id }, imported = state.imported.filterNot { it.sourceId == id })
                refresh()
            }
            callback(result)
        } }
    }
    fun close() { if (!closed) { closed = true; listeners.clear(); store.close() } }
}

enum class ScheduleMode { CALENDAR, TIMETABLE }
class ScheduleSession(clock: ScheduleClock) {
    var mode = ScheduleMode.CALENDAR
    var selectedDate: LocalDate = clock.today()
    var month: YearMonth = YearMonth.from(selectedDate)
    var weekStart: LocalDate = ScheduleProjection.weekStart(selectedDate, clock.locale())
    fun today(clock: ScheduleClock) { selectedDate = clock.today(); month = YearMonth.from(selectedDate); weekStart = ScheduleProjection.weekStart(selectedDate, clock.locale()) }
    fun moveMonth(amount: Long) { month = month.plusMonths(amount) }
    fun select(date: LocalDate) { selectedDate = date; month = YearMonth.from(date) }
    // Rollover retains explicit selection; Today is the deterministic way to follow the new day.
}
/** entry is presentation-only for imports; imported identifies read-only detail instead of the weekly editor. */
data class ClassOccurrence(val entry: TimetableEntry, val date: LocalDate, val start: Instant, val end: Instant,
    val imported: ImportedClassOccurrence? = null)
enum class ClassStatus { CURRENT, NEXT_TODAY, NO_MORE_TODAY }
data class TimetableCardContent(val status: ClassStatus, val occurrences: List<ClassOccurrence>)
object ScheduleProjection {
    fun weekStart(date: LocalDate, locale: Locale): LocalDate = date.minusDays((date.dayOfWeek.value - weekDays(locale).first().value + 7L) % 7)
    private fun importedClass(item: ImportedClassOccurrence, zone: ZoneId): ClassOccurrence {
        val start = Instant.ofEpochMilli(item.start).atZone(zone)
        val end = Instant.ofEpochMilli(item.end).atZone(zone)
        return ClassOccurrence(TimetableEntry(item.id, item.title, start.dayOfWeek.value,
            start.hour * 60 + start.minute, end.hour * 60 + end.minute, item.location), start.toLocalDate(),
            start.toInstant(), end.toInstant(), item)
    }
    fun week(state: ScheduleState, first: LocalDate, zone: ZoneId): List<ClassOccurrence> {
        val manual = (0L..6L).flatMap { offset ->
            val date = first.plusDays(offset)
            state.entries.filter { it.day == date.dayOfWeek.value }.map { entry ->
                ClassOccurrence(entry, date, date.atTime(LocalTime.ofSecondOfDay(entry.startMinute * 60L)).atZone(zone).toInstant(),
                    date.atTime(LocalTime.ofSecondOfDay(entry.endMinute * 60L)).atZone(zone).toInstant())
            }
        }
        val imported = state.importedIndex.range(first.atStartOfDay(zone).toInstant().toEpochMilli(),
            first.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()).map { importedClass(it, zone) }
        return (manual + imported).sortedBy { it.start }
    }
    fun weekDays(locale: Locale): List<DayOfWeek> {
        val first = WeekFields.of(locale).firstDayOfWeek
        return (0L..6L).map { first.plus(it) }
    }
    fun monthCells(month: YearMonth, locale: Locale): List<LocalDate> {
        val first = month.atDay(1)
        val offset = (first.dayOfWeek.value - weekDays(locale).first().value + 7) % 7
        return (0L..41L).map { first.minusDays(offset.toLong()).plusDays(it) }
    }
    fun eventsOn(state: ScheduleState, date: LocalDate, zone: ZoneId): List<CalendarEvent> {
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return state.events.filter { it.start < end && it.end > start }
    }
    fun upcomingEvents(state: ScheduleState, clock: ScheduleClock, limit: Int): List<CalendarEvent> =
        state.events.filter { it.end > clock.instant().toEpochMilli() }.take(limit)
    fun occurrences(state: ScheduleState, clock: ScheduleClock): List<ClassOccurrence> {
        val today = clock.today()
        val manual = (0L..7L).flatMap { offset ->
            val date = today.plusDays(offset)
            state.entries.filter { it.day == date.dayOfWeek.value }.map { entry ->
                ClassOccurrence(entry, date, date.atTime(LocalTime.ofSecondOfDay(entry.startMinute * 60L)).atZone(clock.zone()).toInstant(),
                    date.atTime(LocalTime.ofSecondOfDay(entry.endMinute * 60L)).atZone(clock.zone()).toInstant())
            }
        }
        val importedToday = state.importedIndex.range(clock.instant().toEpochMilli(), today.plusDays(1).atStartOfDay(clock.zone()).toInstant().toEpochMilli())
        val importedFuture = state.importedIndex.upcoming(today.plusDays(1).atStartOfDay(clock.zone()).toInstant().toEpochMilli(), 4)
        return (manual + (importedToday + importedFuture).distinctBy { it.id }.map { importedClass(it, clock.zone()) })
            .filter { it.end > clock.instant() && it.end > it.start }
            .sortedWith(compareBy<ClassOccurrence> { it.start }.thenBy { it.entry.title }.thenBy { it.entry.id })
    }
    fun cardLimit(columns: Int, rows: Int) = if (rows == 1) 1 else if (columns >= 3) 4 else 2
    fun timetableCard(state: ScheduleState, clock: ScheduleClock, columns: Int, rows: Int): TimetableCardContent {
        val all = occurrences(state, clock)
        val today = all.filter { it.date == clock.today() || (it.start <= clock.instant() && it.end > clock.instant()) }
        val status = when {
            today.any { it.start <= clock.instant() } -> ClassStatus.CURRENT
            today.isNotEmpty() -> ClassStatus.NEXT_TODAY
            else -> ClassStatus.NO_MORE_TODAY
        }
        return TimetableCardContent(status, (if (rows > 1 && today.isEmpty()) all else today).take(cardLimit(columns, rows)))
    }
    /** A single visible-view wakeup at the next actual presentation boundary, including DST midnight. */
    fun nextBoundary(state: ScheduleState, clock: ScheduleClock): Instant {
        val now = clock.instant()
        return (listOf(clock.today().plusDays(1).atStartOfDay(clock.zone()).toInstant()) +
            state.events.flatMap { listOf(Instant.ofEpochMilli(it.start), Instant.ofEpochMilli(it.end)) } +
            occurrences(state, clock).flatMap { listOf(it.start, it.end) }).filter { it > now }.minOrNull()!!
    }
}
