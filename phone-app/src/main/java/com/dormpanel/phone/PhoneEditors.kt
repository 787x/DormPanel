package com.dormpanel.phone

import com.dormpanel.app.schedule.*

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.*
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date

internal fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()
internal fun Context.scheduleTime(instant: Instant): String = android.text.format.DateFormat.getTimeFormat(this).format(Date.from(instant))
internal fun Context.scheduleTime(minute: Int): String = LocalTime.ofSecondOfDay(minute * 60L).format(DateTimeFormatter.ofPattern(
    android.text.format.DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), if (android.text.format.DateFormat.is24HourFormat(this)) "Hm" else "hm")))
internal fun scheduleDate(date: LocalDate): String = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
internal fun Context.scheduleButton(label: String, action: () -> Unit) = Button(this).apply {
    text = label; textSize = 16f; isAllCaps = false; minHeight = context.dp(48); setOnClickListener { action() }
}
internal fun Context.scheduleLabel(label: String, size: Float = 20f) = TextView(this).apply {
    text = label; textSize = size; setPadding(context.dp(8), context.dp(6), context.dp(8), context.dp(6))
}
internal fun Context.scheduleColumn() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

internal class PhoneEditors(private val context: Context, private val source: ScheduleSource,
    private val enabled: () -> Boolean = { true }) {
    private val dialogs = mutableListOf<android.content.DialogInterface>()
    fun close() { dialogs.toList().forEach { it.dismiss() }; dialogs.clear() }
    private fun track(dialog: android.app.Dialog) {
        dialogs += dialog
        dialog.setOnDismissListener { dialogs.remove(dialog) }
        dialog.show()
    }
    private fun input(hint: String, value: String, multiline: Boolean = false) = EditText(context).apply {
        this.hint = hint; contentDescription = hint; textSize = 16f; minHeight = context.dp(48); setText(value)
        inputType = android.text.InputType.TYPE_CLASS_TEXT or if (multiline) android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE else android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        maxLines = if (multiline) 3 else 1
    }
    private fun editor(title: String, fields: LinearLayout, delete: (() -> Unit)?,
        deleteLabel: String = "Delete", save: () -> String?) {
        val error = context.scheduleLabel("", 16f)
        fields.addView(error)
        fields.setPadding(context.dp(20), context.dp(8), context.dp(20), context.dp(8))
        val dialog = AlertDialog.Builder(context).setTitle(title).setView(ScrollView(context).apply { addView(fields) })
            .setNegativeButton("Cancel", null).setPositiveButton("Save", null)
            .apply { if (delete != null) setNeutralButton(deleteLabel, null) }.create()
        dialogs += dialog
        dialog.setOnDismissListener { dialogs.remove(dialog) }
        dialog.show()
        dialog.window?.setLayout(minOf(context.dp(900), context.resources.displayMetrics.widthPixels - context.dp(24)), ViewGroup.LayoutParams.WRAP_CONTENT)
        listOf(-1, -2, -3).forEach { dialog.getButton(it)?.minHeight = context.dp(48) }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (enabled() && !source.readOnly && !source.applyingSnapshot) { val message = save(); if (message == null) dialog.dismiss() else error.text = message }
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener { if (enabled() && !source.readOnly && !source.applyingSnapshot) { delete?.invoke(); dialog.dismiss() } }
    }
    fun dayAdjustment(date: LocalDate) {
        if (!enabled() || !source.ready || source.readOnly || source.applyingSnapshot) return
        val current = source.state.daysByDate[date.toString()]
        val choices = listOf("Normal", "No classes", "Use another weekday")
        val fields = context.scheduleColumn()
        val mode = Spinner(context).apply {
            contentDescription = "Day adjustment mode"
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, choices)
            setSelection(when (current?.mode) { "none" -> 1; "weekday" -> 2; else -> 0 })
        }
        val days = DayOfWeek.entries.filter { it != date.dayOfWeek }
        val weekday = Spinner(context).apply {
            contentDescription = "Replacement weekday"
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item,
                days.map { it.name.lowercase().replaceFirstChar(Char::uppercase) })
            setSelection(days.indexOfFirst { it.value == current?.weekday }.coerceAtLeast(0))
        }
        val label = input("Reason (optional)", current?.label.orEmpty())
        fields.addView(context.scheduleLabel("${scheduleDate(date)} · ${date.dayOfWeek}", 18f))
        fields.addView(mode); fields.addView(weekday); fields.addView(label)
        weekday.visibility = if (mode.selectedItemPosition == 2) View.VISIBLE else View.GONE
        mode.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                weekday.visibility = if (position == 2) View.VISIBLE else View.GONE
            }
        }
        editor("Day adjustment", fields, current?.let { { source.saveDayAdjustment(date, "normal") } }, "Reset day to normal") {
            val chosen = when (mode.selectedItemPosition) { 1 -> "none"; 2 -> "weekday"; else -> "normal" }
            if (source.saveDayAdjustment(date, chosen, if (chosen == "weekday") days[weekday.selectedItemPosition].value else null,
                    label.text.toString())) null else "Could not save day adjustment."
        }
    }

    fun classDetail(item: ClassOccurrence) {
        if (!enabled() || !source.ready || source.applyingSnapshot) return
        val owner = source.state.sources.firstOrNull { it.id == item.sourceId }
        val info = buildString {
            append(scheduleDate(item.date)).append(" · ").append(item.date.dayOfWeek)
            append("\n").append(context.scheduleTime(item.start)).append(" – ").append(context.scheduleTime(item.end))
            item.periodLabel?.let { append("\n").append(it) }
            if (item.entry.location.isNotBlank()) append("\nLocation: ").append(item.entry.location)
            if (item.teacher.isNotBlank()) append("\nTeacher: ").append(item.teacher)
            if (item.note.isNotBlank()) append("\nNote: ").append(item.note)
            append("\n\n").append(if (owner == null) "Local recurring class" else "Imported from ${owner.displayName}")
            if (item.imported != null) append("\nSource timezone: ").append(item.imported?.timezone)
            if (item.copiedFrom != null) append("\nCopied from ${item.copiedFrom}")
            if (item.thisWeekEdited || item.allWeeksEdited) append("\nLocal edit active")
            if (owner != null) append("\nSource data stays unchanged; local overrides can be applied.")
            if (item.imported?.description?.isNotBlank() == true) append("\n\nSource information:\n").append(item.imported?.description)
        }
        val content = context.scheduleColumn().apply { addView(context.scheduleLabel(info, 18f)) }
        val weekly = source.state.editsByKey[ClassOverride.key(item.sourceId, item.seriesId, item.anchorDate.toString())] != null
        val series = source.state.editsByKey[ClassOverride.key(item.sourceId, item.seriesId, null)] != null
        val dialog = AlertDialog.Builder(context).setTitle(item.entry.title)
            .setView(ScrollView(context).apply { addView(content) })
            .setNegativeButton("Close", null).setPositiveButton("Edit", null).create()
        if (weekly && !source.readOnly) content.addView(context.scheduleButton("Reset this week") {
            source.saveClassOverride(item.sourceId, item.seriesId, item.anchorDate, null); dialog.dismiss()
        })
        if (series && !source.readOnly) content.addView(context.scheduleButton("Reset all-weeks edit") {
            source.saveClassOverride(item.sourceId, item.seriesId, null, null); dialog.dismiss()
        })
        if (item.imported == null && !source.readOnly) content.addView(context.scheduleButton("Delete recurring class") {
            source.deleteEntry(item.seriesId); dialog.dismiss()
        })
        track(dialog)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = !source.readOnly
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { dialog.dismiss(); editClass(item) }
    }

    private fun editClass(item: ClassOccurrence) {
        val scope = arrayOf("This week", "All weeks for this timetable item")
        track(AlertDialog.Builder(context).setTitle("Edit scope")
            .setItems(scope) { _, which -> classEditor(item, which == 1) }
            .setNegativeButton("Cancel", null).create())
    }

    private fun classEditor(item: ClassOccurrence, allWeeks: Boolean) {
        if (!enabled() || !source.ready || source.readOnly || source.applyingSnapshot) return
        val session = ClassEditSession(source.state, item, allWeeks, source.clock.zone())
        val date = session.date
        val draft = session.draft
        val profile = session.profile
        val title = input("Course name", draft.entry.title)
        val location = input("Location", draft.entry.location)
        val teacher = input("Teacher", draft.teacher)
        val note = input("User note", draft.note, true)
        var selectedDate = draft.academicDate
        var selectedWeekday = if (allWeeks && draft.copiedFrom != null) requireNotNull(draft.copiedFrom).dayOfWeek else draft.academicDate.dayOfWeek
        var start = draft.entry.startMinute
        var end = draft.entry.endMinute
        val existingRange = ScheduleProjection.periods(draft.periodLabel)
        val firstPeriod = input("First period", existingRange?.first?.toString().orEmpty())
        val lastPeriod = input("Last period", existingRange?.second?.toString().orEmpty())
        val fields = context.scheduleColumn()
        fields.addView(title)
        val dateButton = context.scheduleButton("Date · $selectedDate") {}
        val days = DayOfWeek.entries
        val weekdaySpinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item,
                days.map { it.name.lowercase().replaceFirstChar(Char::uppercase) })
            setSelection(days.indexOf(selectedWeekday))
        }
        val multiWeekday = session.multiWeekday
        if (allWeeks) {
            weekdaySpinner.isEnabled = !multiWeekday
            fields.addView(context.scheduleLabel(if (multiWeekday) "Weekday is fixed for this multi-day series" else "Weekday", 17f))
            fields.addView(weekdaySpinner)
        } else {
            fields.addView(dateButton)
        }
        val timing = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item,
                if (profile == null) listOf("Custom time") else listOf("Linked periods", "Custom time"))
            setSelection(if (profile != null && draft.linked) 0 else if (profile != null) 1 else 0)
        }
        fields.addView(timing)
        if (profile != null) {
            fields.addView(context.scheduleLabel("Period range · first / last", 16f))
            fields.addView(firstPeriod); fields.addView(lastPeriod)
        } else if (draft.periodLabel != null) fields.addView(context.scheduleLabel("Source periods: ${draft.periodLabel} (no term profile)", 16f))
        val row = LinearLayout(context)
        val startButton = context.scheduleButton("") {}
        val endButton = context.scheduleButton("") {}
        fun targetDate(): LocalDate = if (allWeeks) date.minusDays((date.dayOfWeek.value - 1).toLong())
            .plusDays((days[weekdaySpinner.selectedItemPosition].value - 1).toLong()) else selectedDate
        fun linkedBounds(): Pair<Int, Int>? {
            val first = firstPeriod.text.toString().toIntOrNull() ?: return null
            val last = lastPeriod.text.toString().toIntOrNull() ?: return null
            val times = ScheduleProjection.linkedPeriodTimes(profile ?: return null, targetDate(), first, last) ?: return null
            val zone = source.clock.zone()
            return (times.first.atZone(zone).toLocalTime().toSecondOfDay() / 60) to
                (times.second.atZone(zone).toLocalTime().toSecondOfDay() / 60)
        }
        fun updateTimes() {
            firstPeriod.isEnabled = profile != null && timing.selectedItemPosition == 0
            lastPeriod.isEnabled = firstPeriod.isEnabled
            if (profile != null && timing.selectedItemPosition == 0) linkedBounds()?.let { start = it.first; end = it.second }
            startButton.text = "Start · ${context.scheduleTime(start)}"
            endButton.text = "End · ${context.scheduleTime(end)}"
        }
        dateButton.setOnClickListener {
            track(DatePickerDialog(context, { _, y, m, d ->
                val picked = LocalDate.of(y, m + 1, d)
                val monday = date.minusDays((date.dayOfWeek.value - 1).toLong())
                if (picked in monday..monday.plusDays(6)) { selectedDate = picked; dateButton.text = "Date · $picked"; updateTimes() }
                else Toast.makeText(context, "Choose a date in this Monday–Sunday week", Toast.LENGTH_SHORT).show()
            }, selectedDate.year, selectedDate.monthValue - 1, selectedDate.dayOfMonth))
        }
        fun pickTime(button: Button, current: Int, setter: (Int) -> Unit) {
            track(TimePickerDialog(context, { _, h, m ->
                setter(h * 60 + m)
                if (profile != null) timing.setSelection(1)
                updateTimes()
            }, current / 60, current % 60, android.text.format.DateFormat.is24HourFormat(context)))
        }
        startButton.setOnClickListener { pickTime(startButton, start) { start = it } }
        endButton.setOnClickListener { pickTime(endButton, end) { end = it } }
        row.addView(startButton, LinearLayout.LayoutParams(0, context.dp(52), 1f))
        row.addView(endButton, LinearLayout.LayoutParams(0, context.dp(52), 1f))
        fields.addView(row); fields.addView(location); fields.addView(teacher); fields.addView(note)
        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updateTimes() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        }
        firstPeriod.addTextChangedListener(watcher); lastPeriod.addTextChangedListener(watcher)
        timing.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) { updateTimes() }
        }
        weekdaySpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedWeekday = days[position]; updateTimes()
            }
        }
        updateTimes()
        editor(if (allWeeks) "Edit all weeks" else "Edit this week", fields, null) {
            val linked = profile != null && timing.selectedItemPosition == 0
            val selected = targetDate()
            val patch = runCatching { session.patch(title.text.toString(), location.text.toString(),
                teacher.text.toString(), note.text.toString(), selected, selectedWeekday, linked,
                firstPeriod.text.toString().toIntOrNull(), lastPeriod.text.toString().toIntOrNull(), start, end) }
            patch.fold(onSuccess = {
                if (source.saveClassOverride(item.sourceId, item.seriesId, if (allWeeks) null else date, it)) null
                else "Could not save class edit."
            }, onFailure = { it.message ?: "Invalid class edit." })
        }
    }
    fun entry(existing: TimetableEntry? = null) {
        if (!enabled() || !source.ready || source.readOnly || source.applyingSnapshot) return
        val title = input("Class title", existing?.title.orEmpty())
        val location = input("Location (optional)", existing?.location.orEmpty())
        val days = ScheduleProjection.weekDays(source.clock.locale())
        val day = Spinner(context).apply {
            minimumHeight = context.dp(48)
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, days.map { it.getDisplayName(java.time.format.TextStyle.FULL, source.clock.locale()) })
            setSelection(days.indexOf(DayOfWeek.of(existing?.day ?: source.clock.today().dayOfWeek.value)))
        }
        var start = existing?.startMinute ?: 540; var end = existing?.endMinute ?: 600
        val fields = context.scheduleColumn(); fields.addView(title); fields.addView(day)
        val row = LinearLayout(context)
        fun time(label: String, get: () -> Int, set: (Int) -> Unit) {
            val button = context.scheduleButton("$label · ${context.scheduleTime(get())}") {}
            button.setOnClickListener { track(TimePickerDialog(context, { _, h, m -> set(h * 60 + m); button.text = "$label · ${context.scheduleTime(get())}" }, get() / 60, get() % 60, android.text.format.DateFormat.is24HourFormat(context))) }
            row.addView(button, LinearLayout.LayoutParams(0, context.dp(52), 1f))
        }
        time("Start", { start }, { start = it }); time("End", { end }, { end = it }); fields.addView(row); fields.addView(location)
        editor(if (existing == null) "Add class" else "Edit class", fields, existing?.let { { source.deleteEntry(it.id) } }) {
            when {
                title.text.isBlank() -> "Enter a title."
                end <= start -> "End must be after start on the same day."
                source.saveEntry(title.text.toString(), days[day.selectedItemPosition].value, start, end, location.text.toString(), existing?.id) -> null
                else -> "Class unavailable. Cancel and reopen the editor."
            }
        }
    }
}
