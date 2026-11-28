package com.dormpanel.app.icloud

import biweekly.ICalendar
import biweekly.component.VEvent
import biweekly.io.text.ICalReader
import biweekly.property.ICalProperty
import biweekly.util.ICalDate
import com.github.mangstadt.vinnie.VObjectProperty
import com.github.mangstadt.vinnie.io.Context
import com.github.mangstadt.vinnie.io.SyntaxRules
import com.github.mangstadt.vinnie.io.VObjectDataListener
import com.github.mangstadt.vinnie.io.VObjectReader
import com.github.mangstadt.vinnie.io.Warning
import java.io.StringReader
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.TimeZone

internal fun readCloudEvents(text: String, window: CalendarWindow, budget: ExpansionBudget = ExpansionBudget()): List<CloudEvent> {
    try {
        checkCalendar(text.isNotEmpty())
        checkCalendarCapacity(text.length <= 262144)
        val input = text.removePrefix("\uFEFF")
        val count = validateCalendar(input)
        val calendar = ICalReader(input).use { reader ->
            reader.defaultTimezone = TimeZone.getTimeZone(window.zone)
            val value = reader.readNext() ?: invalidCalendar()
            checkCalendar(reader.warnings.all { it.code == 37 } && reader.readNext() == null)
            value
        }
        checkCalendar(calendar.events.size == count && calendar.method?.value?.uppercase(Locale.ROOT) in setOf(null, "PUBLISH"))
        val uids = calendar.events.groupBy { it.uid?.value?.takeIf { uid -> uid.isNotBlank() && uid.length <= 1024 } ?: invalidCalendar() }
        checkCalendar(uids.size == 1)
        val (uid, events) = uids.entries.single()
        return calendar.expand(uid, events, window, budget)
    } catch (e: CalDavFailure) {
        throw e
    } catch (e: Exception) {
        throw CalDavFailure(CalDavProblem.DATA).apply { stackTrace = e.stackTrace }
    }
}

private data class EventTime(val local: LocalDateTime, val zone: ZoneId, val dateOnly: Boolean, val embedded: TimeZone? = null) {
    val day: LocalDate? get() = if (dateOnly) local.toLocalDate() else null
    val instant: Instant by lazy(LazyThreadSafetyMode.NONE) { resolve(local) }
    val key: String get() = day?.toString() ?: instant.toString()

    fun at(day: LocalDate) = copy(local = day.atTime(local.toLocalTime()))
    fun localAt(instant: Instant): LocalDateTime = if (embedded == null) instant.atZone(zone).toLocalDateTime() else
        instant.plusMillis(embedded.getOffset(instant.toEpochMilli()).toLong()).atOffset(ZoneOffset.UTC).toLocalDateTime()

    fun localDate(instant: Instant) = localAt(instant).toLocalDate()
    fun originalLocal(reference: EventTime) =
        if (reference.dateOnly) reference.local else localAt(reference.instant)

    fun validLocalTime(): Boolean = dateOnly || if (embedded == null) zone.rules.getValidOffsets(local).isNotEmpty() else
        local.toInstant(ZoneOffset.UTC).toEpochMilli() == instant.toEpochMilli() + embedded.getOffset(instant.toEpochMilli())

    fun resolve(local: LocalDateTime): Instant {
        val timezone = embedded ?: return local.atZone(zone).toInstant()
        val nominal = local.toInstant(ZoneOffset.UTC).toEpochMilli()
        val before = timezone.getOffset(nominal - 2 * 86_400_000)
        val after = timezone.getOffset(nominal + 2 * 86_400_000)
        val first = nominal - before
        val second = nominal - after
        val firstValid = first + timezone.getOffset(first) == nominal
        val secondValid = second + timezone.getOffset(second) == nominal
        // Before a gap use the preceding offset. During an overlap use the first occurrence.
        return Instant.ofEpochMilli(when {
            firstValid && secondValid -> minOf(first, second)
            secondValid -> second
            else -> first
        })
    }
}

private fun ICalendar.timestamp(property: ICalProperty, value: ICalDate, deviceZone: ZoneId): EventTime {
    val raw = value.rawComponents ?: invalidCalendar()
    val date = LocalDate.of(raw.year, raw.month, raw.date)
    checkCalendar(date.year in 1..9999)
    val local = if (value.hasTime()) date.atTime(raw.hour, raw.minute, raw.second) else date.atStartOfDay()
    val assignment = timezoneInfo.getTimezone(property)
    val embedded = assignment?.takeIf { it.component != null }?.timeZone
    val zone = when {
        !value.hasTime() -> deviceZone
        raw.isUtc -> ZoneOffset.UTC
        embedded != null -> deviceZone
        assignment != null -> ZoneId.of(assignment.globalId ?: assignment.timeZone.id)
        timezoneInfo.isFloating(property) -> deviceZone
        else -> invalidCalendar()
    }
    return EventTime(local, zone, !value.hasTime(), embedded.takeIf { value.hasTime() && !raw.isUtc })
}

private data class EventLength(val days: Long = 0, val seconds: Long = 0, val exactMillis: Long? = null) {
    fun end(start: EventTime): Instant = exactMillis?.let { start.instant.plusMillis(it) }
        ?: (if (days == 0L) start.instant else start.resolve(start.local.plusDays(days))).plusSeconds(seconds)
}

private fun ICalendar.length(event: VEvent, start: EventTime, zone: ZoneId): EventLength {
    checkCalendar(event.dateEnd == null || event.duration == null)
    event.dateEnd?.let {
        val end = timestamp(it, it.value ?: invalidCalendar(), zone)
        checkCalendar(end.dateOnly == start.dateOnly)
        return if (start.dateOnly) {
            val days = java.time.temporal.ChronoUnit.DAYS.between(start.day!!, end.day!!)
            checkCalendar(days > 0)
            EventLength(days = days)
        } else {
            val millis = end.instant.toEpochMilli() - start.instant.toEpochMilli()
            checkCalendar(millis > 0)
            EventLength(exactMillis = millis)
        }
    }
    val duration = event.duration?.value ?: return EventLength(days = if (start.dateOnly) 1 else 0)
    checkCalendar(!duration.isPrior && duration.toMillis() > 0 && (!start.dateOnly || !duration.hasTime()))
    val days = (duration.weeks ?: 0) * 7L + (duration.days ?: 0)
    val seconds = (duration.hours ?: 0) * 3600L + (duration.minutes ?: 0) * 60L + (duration.seconds ?: 0)
    return EventLength(days, seconds)
}

private fun ICalendar.start(event: VEvent, zone: ZoneId): EventTime {
    val property = event.dateStart ?: invalidCalendar()
    return timestamp(property, property.value ?: invalidCalendar(), zone)
}

private data class EventOverride(val id: EventTime, val event: VEvent)
private data class FutureEdit(val original: EventTime, val event: VEvent, val start: EventTime?, val length: EventLength?) {
    fun shifted(time: EventTime, master: EventTime): EventTime? = start?.let {
        // A shifted start is generated. The explicitly supplied anchor and detached
        // overrides are evaluated separately with the DATE-TIME gap interpretation.
        it.copy(local = it.local.plus(Duration.between(master.originalLocal(original), master.originalLocal(time))))
            .takeIf(EventTime::validLocalTime)
    }
}

private fun ICalendar.expand(uid: String, events: List<VEvent>, window: CalendarWindow, budget: ExpansionBudget): List<CloudEvent> {
    val zone = window.zone
    var master: VEvent? = null
    val overrides = linkedMapOf<String, EventOverride>()
    for (event in events) {
        checkCalendar(event.exceptionRules.isEmpty())
        val id = event.recurrenceId
        if (id == null) {
            checkCalendar(master == null)
            master = event
        } else {
            checkCalendar(event.recurrenceRule == null && event.recurrenceDates.isEmpty() && event.exceptionDates.isEmpty())
            val time = timestamp(id, id.value ?: invalidCalendar(), zone)
            checkCalendar(overrides.put(time.key, EventOverride(time, event)) == null)
        }
    }
    val rangeEdits = overrides.values.filter { it.event.recurrenceId.range != null }.map { (id, event) ->
        val start = if (event.cancelled()) null else start(event, zone).also { checkCalendar(it.dateOnly == id.dateOnly) }
        FutureEdit(id, event, start, start?.let { length(event, it, zone) })
    }.sortedBy { it.original.instant }
    checkCalendar(rangeEdits.isEmpty() || master != null)
    val masterStart = master?.takeUnless { it.cancelled() }?.let { start(it, zone) }
    val result = ArrayList<CloudEvent>()
    val text = java.util.IdentityHashMap<VEvent, Triple<String, String, String>>()
    fun add(event: VEvent, time: EventTime, length: EventLength, id: String?) {
        val start = time.instant
        val end = length.end(time)
        checkCalendar(end >= start)
        val (title, location, description) = text.getOrPut(event) {
            Triple(event.summary?.value.clean(512), event.location?.value.clean(512), event.description?.value.clean(4096))
        }
        val instance = CloudEvent(uid, id, title, location, description, start.toEpochMilli(), end.toEpochMilli(),
            time.day, time.day?.plusDays(length.days))
        if (instance.overlaps(window.start, window.end, zone)) {
            result.add(instance)
            checkCalendarCapacity(result.size <= MAX_INSTANCES)
        }
    }
    if (master != null && masterStart != null) {
        val first = masterStart
        checkCalendar(overrides.values.all { it.id.dateOnly == first.dateOnly })
        val length = length(master, first, zone)
        val excluded = master.exceptionDates.flatMap { property ->
            property.values.map { timestamp(property, it, zone).also { date -> checkCalendar(date.dateOnly == first.dateOnly) }.key }
        }.toSet()
        val included = linkedMapOf(first.key to first)
        master.recurrenceDates.forEach { property ->
            checkCalendar(property.periods.isEmpty())
            property.dates.forEach {
                val time = timestamp(property, it, zone)
                checkCalendar(time.dateOnly == first.dateOnly)
                included[time.key] = time
            }
        }
        checkCalendarCapacity(excluded.size + included.size <= MAX_INSTANCES)
        val rule = master.recurrenceRule?.value
        if (rule != null) {
            val until = rule.until?.let { value ->
                val raw = value.rawComponents ?: invalidCalendar()
                checkCalendar(value.hasTime() != first.dateOnly)
                val date = LocalDate.of(raw.year, raw.month, raw.date)
                val local = if (first.dateOnly) date.atStartOfDay() else date.atTime(raw.hour, raw.minute, raw.second)
                if (raw.isUtc) EventTime(local, ZoneOffset.UTC, false) else first.copy(local = local)
            }
            if (until != null) checkCalendar(until.instant >= first.instant)
            val recurrence = CalendarRecurrence(rule, first.local.toLocalDate())
            val lookback = length.days + length.seconds / 86400 + (length.exactMillis ?: 0) / 86_400_000 + 2
            var from = first.localDate(window.start).minusDays(lookback)
            var last = first.localDate(window.end).plusDays(1)
            // Map the display window back through each edit. Original recurrence IDs remain
            // in the master's time scale, including when the edited event uses another zone.
            for (edit in rangeEdits) {
                val target = edit.start ?: continue
                val editedLength = checkNotNull(edit.length)
                val lead = editedLength.days + editedLength.seconds / 86400 + (editedLength.exactMillis ?: 0) / 86_400_000 + 2
                val shift = Duration.between(first.originalLocal(edit.original), target.local)
                from = minOf(from, target.localAt(window.start).minus(shift).toLocalDate().minusDays(lead))
                last = maxOf(last, target.localAt(window.end).minus(shift).toLocalDate().plusDays(1))
            }
            val through = minOf(last, until?.let { first.localDate(it.instant).plusDays(1) } ?: LocalDate.MAX)
            for (date in recurrence.dates(from, through, budget) { first.at(it).validLocalTime() }) {
                val time = first.at(date)
                if (until != null && time.instant > until.instant) break
                included[time.key] = time
                checkCalendarCapacity(included.size <= MAX_INSTANCES)
            }
        }
        for ((key, time) in included) {
            if (key in excluded || key in overrides) continue
            val id = key.takeUnless { it == first.key }
            val editIndex = rangeEdits.binarySearchBy(time.instant) { it.original.instant }
            val edit = rangeEdits.getOrNull(if (editIndex >= 0) editIndex else -editIndex - 2)
            if (edit == null) add(master, time, length, id)
            else edit.shifted(time, first)?.let { add(edit.event, it, checkNotNull(edit.length), id) }
        }
    }
    if (master?.cancelled() != true) for ((id, override) in overrides) {
        val event = override.event
        if (!event.cancelled()) {
            val time = start(event, zone)
            val recurring = event.recurrenceId.value
            checkCalendar(recurring.hasTime() != time.dateOnly)
            add(event, time, length(event, time, zone), id.takeUnless { it == masterStart?.key })
        }
    }
    return result
}

private fun VEvent.cancelled() = status?.value.equals("CANCELLED", true)
private fun String?.clean(limit: Int) = orEmpty().replace("\r\n", "\n").replace('\r', '\n').take(limit)

/** Reject malformed content before the decoder can normalize it or drop a duplicate property. */
private fun validateCalendar(text: String): Int {
    val stack = mutableListOf<String>()
    var calendars = 0
    var versions = 0
    var events = 0
    var properties = 0
    var zones = 0
    var observances = 0
    val zoneIds = hashSetOf<String>()
    val componentProperties = hashSetOf<String>()
    VObjectReader(StringReader(text), SyntaxRules.iCalendar()).use { reader ->
        reader.parse(object : VObjectDataListener {
            override fun onComponentBegin(name: String, context: Context) {
                checkCalendar(when (stack.lastOrNull()) {
                    null -> name == "VCALENDAR" && ++calendars == 1
                    "VCALENDAR" -> (name == "VEVENT" && ++events <= MAX_INSTANCES) || (name == "VTIMEZONE" && ++zones <= 4)
                    "VEVENT" -> name == "VALARM"
                    "VTIMEZONE" -> (name == "STANDARD" || name == "DAYLIGHT") && ++observances <= 48
                    else -> false
                })
                if (name in setOf("VEVENT", "STANDARD", "DAYLIGHT")) componentProperties.clear()
                stack.add(name)
            }

            override fun onComponentEnd(name: String, context: Context) {
                checkCalendar(stack.lastOrNull() == name && context.unfoldedLine.trim().equals("END:$name", true))
                if (name == "STANDARD" || name == "DAYLIGHT") checkCalendar(componentProperties.containsAll(observanceRequired))
                stack.removeAt(stack.lastIndex)
            }

            override fun onProperty(property: VObjectProperty, context: Context) {
                checkCalendar(stack.isNotEmpty() && ++properties <= 16000 && property.value.length <= 16384)
                val name = property.name.uppercase(Locale.ROOT)
                when (stack.last()) {
                    "VEVENT" -> {
                        if (name in singletonProperties) checkCalendar(componentProperties.add(name))
                        if (name == "RECURRENCE-ID") {
                            val range = property.parameters.get("RANGE").orEmpty()
                            checkCalendar(range.isEmpty() || range.size == 1 && range.single().equals("THISANDFUTURE", true))
                        }
                        if (name == "RRULE") validateRule(property.value, false)
                        if (name == "STATUS") checkCalendar(property.value.uppercase(Locale.ROOT) in eventStatuses)
                    }
                    "VTIMEZONE" -> if (name == "TZID") checkCalendar(property.value.isNotBlank() && zoneIds.add(property.value))
                    "STANDARD", "DAYLIGHT" -> {
                        if (name in singletonProperties || name in setOf("TZOFFSETFROM", "TZOFFSETTO"))
                            checkCalendar(componentProperties.add(name))
                        if (name == "RRULE") validateRule(property.value, true)
                        if (name == "RDATE") checkCalendar(property.value.length <= 1024)
                        checkCalendar(name != "EXRULE" && name != "EXDATE")
                    }
                }
            }

            override fun onVersion(value: String, context: Context) {
                checkCalendar(stack.lastOrNull() == "VCALENDAR" && value == "2.0" && ++versions == 1)
            }

            override fun onWarning(warning: Warning, property: VObjectProperty?, thrown: Exception?, context: Context) {
                invalidCalendar()
            }
        })
    }
    checkCalendar(calendars == 1 && versions == 1 && events > 0 && zones == zoneIds.size && stack.isEmpty())
    return events
}

private fun validateRule(value: String, timezone: Boolean) {
    val fields = value.uppercase(Locale.ROOT).split(';').map {
        val parts = it.split('=', limit = 2)
        checkCalendar(parts.size == 2 && parts[1].isNotEmpty() && parts[1].length <= 384)
        parts[0] to parts[1]
    }
    val rules = fields.toMap()
    checkCalendar(rules.size == fields.size)
    if (timezone) {
        // Bound embedded observance rules before biweekly evaluates timezone transitions.
        checkCalendar(rules["FREQ"] == "YEARLY" && rules.keys.all { it in timezoneRules })
        checkCalendar(rules["BYMONTH"]?.toIntOrNull() in 1..12)
        checkCalendar(rules["BYDAY"]?.matches(timezoneDay) != false)
        val monthDay = rules["BYMONTHDAY"]
        checkCalendar(monthDay == null || monthDay.split(',').let { it.size <= 7 && it.all { day -> day.toIntOrNull() in 1..31 } })
    }
}

private val timezoneRules = setOf("FREQ", "BYMONTH", "BYDAY", "BYMONTHDAY", "UNTIL")
private val timezoneDay = Regex("[+-]?[1-5]?(MO|TU|WE|TH|FR|SA|SU)")
private val observanceRequired = setOf("DTSTART", "TZOFFSETFROM", "TZOFFSETTO")
private val eventStatuses = setOf("TENTATIVE", "CONFIRMED", "CANCELLED")
private val singletonProperties = setOf("DTSTART", "DTEND", "DURATION", "SUMMARY", "LOCATION", "DESCRIPTION", "STATUS", "UID", "RECURRENCE-ID", "RRULE")
