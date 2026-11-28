package com.dormpanel.app.icloud

import biweekly.util.Frequency
import biweekly.util.Recurrence
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

internal class ExpansionBudget(private val checkRound: () -> Unit = {}) {
    private var dateChecks = 0L
    fun scan(days: Long) {
        dateChecks += days
        checkRound()
        checkCalendarCapacity(dateChecks <= 180_000)
    }
}

/** RFC 5545 day-level rules. Times are resolved separately using the event's timezone. */
internal class CalendarRecurrence(private val rule: Recurrence, private val first: LocalDate) {
    private val frequency = rule.frequency
    private val interval = (rule.interval ?: 1).toLong()
    private val weekStart = DayOfWeek.valueOf(rule.workweekStarts?.name ?: "MONDAY")
    private val byDay = rule.byDay.map { DayOfWeek.valueOf(it.day.name) to it.num }
    private val byMonthDay = rule.byMonthDay.toSet()
    private val byMonth = rule.byMonth.toSet()
    private val bySetPosition = rule.bySetPos.toSet()
    private val periodUnit = when (frequency) {
        Frequency.DAILY -> ChronoUnit.DAYS
        Frequency.WEEKLY -> ChronoUnit.WEEKS
        Frequency.MONTHLY -> ChronoUnit.MONTHS
        Frequency.YEARLY -> ChronoUnit.YEARS
        else -> invalidCalendar()
    }
    private val firstPeriod = when (frequency) {
        Frequency.WEEKLY -> first.with(TemporalAdjusters.previousOrSame(weekStart))
        Frequency.MONTHLY -> first.withDayOfMonth(1)
        Frequency.YEARLY -> first.withDayOfYear(1)
        else -> first
    }

    init {
        checkCalendar(interval in 1..366 && (rule.count == null || rule.count in 1..180_000) &&
            (rule.count == null || rule.until == null))
        checkCalendar(rule.byHour.isEmpty() && rule.byMinute.isEmpty() && rule.bySecond.isEmpty() &&
            rule.byWeekNo.isEmpty() && rule.byYearDay.isEmpty() && rule.xRules.isEmpty())
        checkCalendar(byMonth.all { it in 1..12 } && byMonthDay.all { it != 0 && it in -31..31 } &&
            bySetPosition.all { it != 0 && it in -366..366 } && byDay.size <= 53)
        checkCalendar(bySetPosition.isEmpty() || byDay.isNotEmpty() || byMonthDay.isNotEmpty() || byMonth.isNotEmpty())
        checkCalendar(frequency != Frequency.WEEKLY || byMonthDay.isEmpty())
        checkCalendar(byDay.all { (_, ordinal) ->
            ordinal == null || (frequency in setOf(Frequency.MONTHLY, Frequency.YEARLY) && ordinal != 0 && ordinal in -53..53)
        })
    }

    fun dates(from: LocalDate, through: LocalDate, budget: ExpansionBudget, validTime: (LocalDate) -> Boolean): Sequence<LocalDate> = sequence {
        // DTSTART and the recurrence rule must describe the same first occurrence.
        checkCalendar(first in datesInPeriod(firstPeriod, budget))
        val skip = if (rule.count == null) (periodUnit.between(firstPeriod, from).coerceAtLeast(0) / interval) * interval else 0L
        var period = firstPeriod.plus(skip, periodUnit)
        var count = 0
        while (period <= through) {
            val candidates = datesInPeriod(period, budget)
            for (day in candidates) {
                if (day < first) continue
                if (day > through) return@sequence
                if (day != first && !validTime(day)) continue
                if (rule.count != null && ++count > rule.count!!) return@sequence
                if (day >= from) yield(day)
            }
            period = period.plus(interval, periodUnit)
        }
    }

    private fun datesInPeriod(period: LocalDate, budget: ExpansionBudget): List<LocalDate> {
        val days = ChronoUnit.DAYS.between(period, period.plus(1, periodUnit))
        budget.scan(days)
        val dates = (0 until days).map { period.plusDays(it) }.filter(::matches)
        if (bySetPosition.isEmpty()) return dates
        return bySetPosition.mapNotNull { dates.getOrNull(if (it > 0) it - 1 else dates.size + it) }.distinct().sorted()
    }

    private fun matches(date: LocalDate): Boolean {
        if (byMonth.isNotEmpty() && date.monthValue !in byMonth) return false
        if (byMonthDay.isNotEmpty() && !matchesIndex(date.dayOfMonth, date.lengthOfMonth(), byMonthDay)) return false
        if (byDay.isNotEmpty()) return byDay.any { (weekday, ordinal) ->
            if (weekday != date.dayOfWeek) false
            else if (ordinal == null) true
            else {
                val wholeYear = frequency == Frequency.YEARLY && byMonth.isEmpty()
                val index = if (wholeYear) date.dayOfYear else date.dayOfMonth
                val days = if (wholeYear) date.lengthOfYear() else date.lengthOfMonth()
                ordinal == (index - 1) / 7 + 1 || ordinal == -((days - index) / 7 + 1)
            }
        }
        return when (frequency) {
            Frequency.WEEKLY -> date.dayOfWeek == first.dayOfWeek
            Frequency.MONTHLY -> byMonthDay.isNotEmpty() || date.dayOfMonth == first.dayOfMonth
            Frequency.YEARLY -> byMonthDay.isNotEmpty() ||
                (date.dayOfMonth == first.dayOfMonth && (byMonth.isNotEmpty() || date.month == first.month))
            else -> true
        }
    }
}

private fun matchesIndex(value: Int, length: Int, positions: Set<Int>) = value in positions || value - length - 1 in positions
