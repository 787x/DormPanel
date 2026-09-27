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
    val imported: List<ImportedClassOccurrence> = emptyList(),
    val dayAdjustments: List<DayAdjustment> = emptyList(), val classOverrides: List<ClassOverride> = emptyList(),
    val profiles: Map<String, TermScheduleProfile> = emptyMap()) {
    val importedIndex by lazy { ImportedOccurrenceIndex(imported) }
    val daysByDate by lazy { dayAdjustments.associateBy { it.date } }
    val editsByKey by lazy { classOverrides.associateBy { it.key } }
}
interface ScheduleStore {
    fun load(callback: (Result<ScheduleState>) -> Unit)
    fun put(event: CalendarEvent, callback: (Result<Unit>) -> Unit)
    fun put(entry: TimetableEntry, callback: (Result<Unit>) -> Unit)
    fun deleteEvent(id: String, callback: (Result<Unit>) -> Unit)
    fun deleteEntry(id: String, callback: (Result<Unit>) -> Unit)
    fun putDayAdjustment(item: DayAdjustment?, date: String, callback: (Result<Unit>) -> Unit) {
        callback(Result.failure(UnsupportedOperationException("Day adjustments unavailable")))
    }
    fun putClassOverride(item: ClassOverride?, key: String, callback: (Result<Unit>) -> Unit) {
        callback(Result.failure(UnsupportedOperationException("Class overrides unavailable")))
    }
    fun commitImport(source: ImportSource, occurrences: List<ImportedClassOccurrence>, replace: Boolean,
        callback: (Result<ImportCommit>) -> Unit) { callback(Result.failure(UnsupportedOperationException("Imports unavailable"))) }
    fun deleteImport(id: String, callback: (Result<Unit>) -> Unit) { callback(Result.failure(UnsupportedOperationException("Imports unavailable"))) }
    fun close()
}

/** Main-thread confined. Global identities belong to this boundary, never to dashboard cards.
 * Invalid disk rows are withheld with a visible warning, and remain intact for future recovery. */
class ScheduleSource(private val store: ScheduleStore, val clock: ScheduleClock = DeviceScheduleClock,
    private val profileResolver: (String) -> TermScheduleProfile? = { BuiltInProfiles.builtIn(it) }) {
    var state = ScheduleState(); private set
    var ready = false; private set
    var error = false; private set
    private var closed = false
    private val listeners = linkedSetOf<() -> Unit>()
    init {
        store.load { result -> if (!closed) {
            result.onSuccess { loaded ->
                state = ordered(loaded.copy(events = loaded.events.filter { it.valid() }, entries = loaded.entries.filter { it.valid() },
                    dayAdjustments = loaded.dayAdjustments.filter { it.valid() }, classOverrides = loaded.classOverrides.filter { it.valid() },
                    profiles = loaded.sources.mapNotNull { item -> item.resolvedTermKey()?.let { term ->
                        profileResolver(term)?.let { term to it }
                    } }.toMap(),
                    invalidRecords = loaded.invalidRecords + loaded.events.count { !it.valid() } + loaded.entries.count { !it.valid() } +
                        loaded.dayAdjustments.count { !it.valid() } + loaded.classOverrides.count { !it.valid() }))
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
        state = state.copy(entries = state.entries.filterNot { it.id == id },
            classOverrides = state.classOverrides.filterNot { it.sourceId == null && it.seriesId == id })
        store.deleteEntry(id, completion); refresh()
    }
    fun saveDayAdjustment(date: LocalDate, mode: String, weekday: Int? = null, label: String = ""): Boolean {
        val item = if (mode == "normal") null else DayAdjustment(date.toString(), mode, weekday, label.trim())
        if (closed || !ready || item != null && !item.valid() ||
            item != null && state.dayAdjustments.none { it.date == item.date } && state.dayAdjustments.size >= 1000) return false
        state = state.copy(dayAdjustments = state.dayAdjustments.filterNot { it.date == date.toString() } + listOfNotNull(item))
        store.putDayAdjustment(item, date.toString(), completion); refresh(); return true
    }
    fun saveClassOverride(sourceId: String?, seriesId: String, anchorDate: LocalDate?, patch: ClassPatch?): Boolean {
        val anchor = anchorDate?.toString()
        val key = ClassOverride.key(sourceId, seriesId, anchor)
        val item = patch?.let { ClassOverride.of(sourceId, seriesId, anchor, it) }
        val existingCharacters = state.classOverrides.filterNot { it.key == key }.sumOf { it.textCharacters().toLong() }
        if (closed || !ready || item != null && !item.valid() ||
            anchorDate != null && patch?.date != null &&
            runCatching { LocalDate.parse(patch.date).minusDays((LocalDate.parse(patch.date).dayOfWeek.value - 1).toLong()) }.getOrNull() !=
                anchorDate.minusDays((anchorDate.dayOfWeek.value - 1).toLong()) ||
            item != null && state.classOverrides.none { it.key == key } && state.classOverrides.size >= 5000 ||
            item != null && existingCharacters + item.textCharacters() > 2_000_000L) return false
        state = state.copy(classOverrides = state.classOverrides.filterNot { it.key == key } + listOfNotNull(item))
        store.putClassOverride(item, key, completion); refresh(); return true
    }
    fun import(preview: ImportPreview, name: String, targetId: String?, callback: (Result<ImportCommit>) -> Unit) {
        if (closed || !ready) return
        IcsScheduleImporter().commit(preview, store, name, targetId, clock.instant().toEpochMilli()) { result ->
            if (!closed) {
                result.onSuccess { committed -> if (!committed.unchanged) {
                    state = state.copy(sources = state.sources.filterNot { it.id == committed.source.id } + committed.source,
                        imported = state.imported.filterNot { it.sourceId == committed.source.id } + committed.occurrences,
                        profiles = state.profiles + listOfNotNull(committed.source.resolvedTermKey()?.let { term ->
                            profileResolver(term)?.let { term to it }
                        }).toMap())
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
                state = state.copy(sources = state.sources.filterNot { it.id == id }, imported = state.imported.filterNot { it.sourceId == id },
                    classOverrides = state.classOverrides.filterNot { it.sourceId == id })
                refresh()
            }
            callback(result)
        } }
    }
    fun close() { if (!closed) { closed = true; listeners.clear(); store.close() } }
}

enum class ScheduleMode { CALENDAR, TIMETABLE }
class ScheduleSession(clock: ScheduleClock, private val modeStore: ScheduleModeStore? = null) {
    var mode = modeStore?.read() ?: ScheduleMode.CALENDAR
        set(value) {
            if (field == value) return
            field = value
            modeStore?.write(value)
        }
    var selectedDate: LocalDate = clock.today()
    var month: YearMonth = YearMonth.from(selectedDate)
    var weekStart: LocalDate = selectedDate.minusDays((selectedDate.dayOfWeek.value - 1).toLong())
    fun today(clock: ScheduleClock) { selectedDate = clock.today(); month = YearMonth.from(selectedDate); weekStart = selectedDate.minusDays((selectedDate.dayOfWeek.value - 1).toLong()) }
    fun moveMonth(amount: Long) { month = month.plusMonths(amount) }
    fun select(date: LocalDate) { selectedDate = date; month = YearMonth.from(date) }
    // Rollover retains explicit selection; Today is the deterministic way to follow the new day.
}
/** One effective class. anchorDate identifies the composed day before a this-week move. */
data class ClassOccurrence(val entry: TimetableEntry, val date: LocalDate, val start: Instant, val end: Instant,
    val imported: ImportedClassOccurrence? = null, val anchorDate: LocalDate = date,
    val seriesId: String = imported?.seriesId ?: entry.id, val sourceId: String? = imported?.sourceId,
    val periodLabel: String? = imported?.periodLabel, val teacher: String = "", val note: String = "",
    val allWeeksEdited: Boolean = false, val thisWeekEdited: Boolean = false,
    val copiedFrom: LocalDate? = null, val linked: Boolean = false)
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
    private fun monday(date: LocalDate) = date.minusDays((date.dayOfWeek.value - 1).toLong())
    fun profile(state: ScheduleState, occurrence: ClassOccurrence): TermScheduleProfile? =
        state.sources.firstOrNull { it.id == occurrence.sourceId }?.resolvedTermKey()?.let { state.profiles[it] ?: BuiltInProfiles.builtIn(it) }
    fun periods(label: String?): Pair<Int, Int>? {
        val numbers = Regex("\\d+").findAll(label.orEmpty()).mapNotNull { it.value.toIntOrNull() }.toList()
        return if (numbers.isNotEmpty() && numbers.size <= 30 && numbers.first() in 1..30 && numbers.last() in 1..30)
            numbers.first() to numbers.last() else null
    }
    private fun patch(state: ScheduleState, value: ClassOccurrence, edit: ClassOverride, zone: ZoneId): ClassOccurrence? {
        val p = edit.patch()
        val targetDate = p.date?.let { LocalDate.parse(it) } ?: p.weekday?.let { monday(value.date).plusDays((it - 1).toLong()) } ?: value.date
        if (p.date != null && monday(targetDate) != monday(value.anchorDate)) return null
        val original = periods(value.periodLabel)
        val first = p.periodStart ?: original?.first
        val last = p.periodEnd ?: original?.second
        val linked = p.timingMode == "linked" || p.timingMode == null && value.linked
        val profile = profile(state, value)
        if (p.timingMode == "linked" && (profile == null || first == null || last == null)) return null
        val bounds = if (linked && profile != null && first != null && last != null) {
            val phase = profile.phaseOn(targetDate) ?: return null
            val start = phase.period(first) ?: return null
            val end = phase.period(last) ?: return null
            start.start.toSecondOfDay() / 60 to end.end.toSecondOfDay() / 60
        } else (p.startMinute ?: value.entry.startMinute) to (p.endMinute ?: value.entry.endMinute)
        val overnight = value.end.atZone(zone).toLocalDate() > value.date &&
            p.startMinute == null && p.endMinute == null && p.periodStart == null && p.periodEnd == null && p.timingMode == null
        if (bounds.second <= bounds.first && !overnight) return null
        val start = targetDate.atTime(LocalTime.ofSecondOfDay(bounds.first * 60L)).atZone(zone).toInstant()
        val end = targetDate.plusDays(if (overnight) 1 else 0)
            .atTime(LocalTime.ofSecondOfDay(bounds.second * 60L)).atZone(zone).toInstant()
        return value.copy(entry = value.entry.copy(title = p.title ?: value.entry.title, day = targetDate.dayOfWeek.value,
            startMinute = bounds.first, endMinute = bounds.second, location = p.location ?: value.entry.location),
            date = targetDate, start = start, end = end,
            periodLabel = if (p.periodStart != null || p.periodEnd != null)
                "[${(first!!..last!!).joinToString("-") { "%02d".format(it) }}节]" else value.periodLabel,
            teacher = p.teacher ?: value.teacher, note = p.note ?: value.note,
            allWeeksEdited = value.allWeeksEdited || edit.anchorDate == null,
            thisWeekEdited = value.thisWeekEdited || edit.anchorDate != null, linked = linked)
    }
    private fun onDate(value: ClassOccurrence, date: LocalDate, zone: ZoneId, anchor: LocalDate = value.anchorDate): ClassOccurrence {
        if (date == value.date) return value.copy(anchorDate = anchor)
        val start = date.atTime(LocalTime.ofSecondOfDay(value.entry.startMinute * 60L)).atZone(zone).toInstant()
        val endDate = date.plusDays(if (value.end.atZone(zone).toLocalDate() > value.date) 1 else 0)
        val end = endDate.atTime(LocalTime.ofSecondOfDay(value.entry.endMinute * 60L)).atZone(zone).toInstant()
        return value.copy(date = date, anchorDate = anchor, start = start, end = end,
            entry = value.entry.copy(day = date.dayOfWeek.value))
    }
    /** Base → all-weeks → target day composition → this-week. Source-day adjustments are never copied. */
    private fun academicWeek(state: ScheduleState, first: LocalDate, zone: ZoneId, applyThisWeek: Boolean = true): List<ClassOccurrence> {
        val monday = monday(first)
        val base = (0L..6L).flatMap { offset ->
            val date = monday.plusDays(offset)
            state.entries.filter { it.day == date.dayOfWeek.value }.map { entry ->
                val start = date.atTime(LocalTime.ofSecondOfDay(entry.startMinute * 60L)).atZone(zone).toInstant()
                val end = date.atTime(LocalTime.ofSecondOfDay(entry.endMinute * 60L)).atZone(zone).toInstant()
                ClassOccurrence(entry, date, start, end)
            }
        } + state.importedIndex.range(monday.atStartOfDay(zone).toInstant().toEpochMilli(),
            monday.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()).map { importedClass(it, zone) }
        val templates = base.mapNotNull { raw ->
            val value = raw.copy(linked = profile(state, raw) != null && periods(raw.periodLabel) != null)
            val edit = state.editsByKey[ClassOverride.key(value.sourceId, value.seriesId, null)]
            val withEdit = if (edit == null) value else patch(state, value, edit, zone)
            withEdit
        }
        val composed = (0L..6L).flatMap { offset ->
            val target = monday.plusDays(offset)
            val day = state.daysByDate[target.toString()]
            when (day?.mode) {
                "none" -> emptyList()
                "weekday" -> {
                    val sourceDate = monday.plusDays((day.weekday!! - 1).toLong())
                    templates.filter { it.date == sourceDate }.mapNotNull { template ->
                        var copied = onDate(template, target, zone, target).copy(copiedFrom = sourceDate)
                        val range = periods(copied.periodLabel)
                        val term = profile(state, copied)
                        if (copied.linked && range != null && term != null) {
                            copied = patch(state, copied, ClassOverride.of(copied.sourceId, copied.seriesId, null,
                                ClassPatch(periodStart = range.first, periodEnd = range.second, timingMode = "linked")), zone)
                                ?.copy(allWeeksEdited = template.allWeeksEdited, thisWeekEdited = template.thisWeekEdited)
                                ?: return@mapNotNull null
                        }
                        copied
                    }
                }
                else -> templates.filter { it.date == target }.map { onDate(it, target, zone) }
            }
        }
        if (!applyThisWeek) return composed.sortedBy { it.start }
        return composed.mapNotNull { value ->
            val edit = state.editsByKey[ClassOverride.key(value.sourceId, value.seriesId, value.anchorDate.toString())]
            val edited = if (edit == null) value else patch(state, value, edit, zone)
            edited?.takeUnless { state.daysByDate[it.date.toString()]?.mode == "none" }
        }.sortedWith(compareBy<ClassOccurrence> { it.start }.thenBy { it.entry.title }.thenBy { it.entry.id })
    }
    fun week(state: ScheduleState, first: LocalDate, zone: ZoneId): List<ClassOccurrence> {
        val start = first.atStartOfDay(zone).toInstant()
        val carry = academicWeek(state, first.minusWeeks(1), zone).filter { it.start < start && it.end > start }
        return (carry + academicWeek(state, first, zone)).sortedBy { it.start }
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
        val futureWeeks = state.importedIndex.upcoming(clock.instant().toEpochMilli(), 16).map {
            monday(Instant.ofEpochMilli(it.start).atZone(clock.zone()).toLocalDate())
        }
        val weeks = ((-1L..2L).map { monday(today).plusWeeks(it) } + futureWeeks).distinct()
        return weeks.flatMap { academicWeek(state, it, clock.zone()) }
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
