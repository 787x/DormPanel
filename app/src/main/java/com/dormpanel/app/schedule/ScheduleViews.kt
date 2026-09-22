package com.dormpanel.app.schedule

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.*
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.dormpanel.app.appearance.*
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

/** No repeating timer. Only attached, visible surfaces observe broadcasts and one future boundary. */
internal class ScheduleDisplay(private val view: View, private val source: ScheduleSource, private val render: () -> Unit) {
    private var active = false
    private val task = Runnable { refresh() }
    private val listener: () -> Unit = { refresh() }
    private val receiver = object : BroadcastReceiver() { override fun onReceive(context: Context?, intent: Intent?) = refresh() }
    fun visibility(visible: Boolean) {
        val next = visible && view.isAttachedToWindow && view.windowVisibility == View.VISIBLE
        if (next != active) {
            active = next
            if (active) {
                source.subscribe(listener)
                ContextCompat.registerReceiver(view.context, receiver, IntentFilter().apply {
                    addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
                    addAction(Intent.ACTION_DATE_CHANGED); addAction(Intent.ACTION_LOCALE_CHANGED)
                }, ContextCompat.RECEIVER_NOT_EXPORTED)
            } else {
                source.unsubscribe(listener); view.context.unregisterReceiver(receiver); view.removeCallbacks(task)
            }
        }
        if (active) refresh()
    }
    fun refresh() {
        view.removeCallbacks(task)
        render()
        if (active) view.postDelayed(task, (ScheduleProjection.nextBoundary(source.state, source.clock).toEpochMilli() - source.clock.instant().toEpochMilli()).coerceAtLeast(1))
    }
}

/** Editors own drafts only. All commands go through ScheduleSource and run only on explicit Save/Delete. */
internal class ScheduleEditors(private val context: Context, private val source: ScheduleSource,
    private val appearance: AppearanceController, private val enabled: () -> Boolean = { true }) {
    private val dialogs = mutableListOf<android.content.DialogInterface>()
    private val themed = mutableListOf<android.app.Dialog>()
    private val appearanceListener: (AppearanceState) -> Unit = { themeAll() }
    private fun themeAll() { themed.forEach { dialog -> dialog.window?.decorView?.let {
        it.setBackgroundColor(PanelPalette.forMode(appearance.state.themeMode).surface); applyAppearanceTree(it, appearance.state)
    } } }
    fun close() { dialogs.toList().forEach { it.dismiss() }; dialogs.clear(); themed.clear(); appearance.removeListener(appearanceListener) }
    private fun track(dialog: android.app.Dialog) {
        dialogs += dialog; themed += dialog
        appearance.addListener(appearanceListener)
        dialog.setOnDismissListener { dialogs.remove(dialog); themed.remove(dialog); if (dialogs.isEmpty()) appearance.removeListener(appearanceListener) }
        dialog.show(); themeAll()
    }
    private fun input(hint: String, value: String, multiline: Boolean = false) = EditText(context).apply {
        this.hint = hint; contentDescription = hint; textSize = 20f; minHeight = context.dp(48); setText(value)
        inputType = android.text.InputType.TYPE_CLASS_TEXT or if (multiline) android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE else android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        maxLines = if (multiline) 3 else 1
    }
    private fun editor(title: String, fields: LinearLayout, delete: (() -> Unit)?, save: () -> String?) {
        val error = context.scheduleLabel("", 16f)
        fields.addView(error)
        fields.setPadding(context.dp(20), context.dp(8), context.dp(20), context.dp(8))
        val dialog = AlertDialog.Builder(context).setTitle(title).setView(ScrollView(context).apply { addView(fields) })
            .setNegativeButton("Cancel", null).setPositiveButton("Save", null)
            .apply { if (delete != null) setNeutralButton("Delete", null) }.create()
        dialogs += dialog
        val appearanceUpdate: (AppearanceState) -> Unit = { state -> dialog.window?.decorView?.let {
            it.setBackgroundColor(PanelPalette.forMode(state.themeMode).surface); applyAppearanceTree(it, state)
        } }
        dialog.setOnDismissListener { dialogs.remove(dialog); appearance.removeListener(appearanceUpdate) }
        dialog.show(); appearance.addListener(appearanceUpdate); appearanceUpdate(appearance.state)
        dialog.window?.setLayout(minOf(context.dp(900), context.resources.displayMetrics.widthPixels - context.dp(48)), ViewGroup.LayoutParams.WRAP_CONTENT)
        listOf(-1, -2, -3).forEach { dialog.getButton(it)?.minHeight = context.dp(48) }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (enabled()) { val message = save(); if (message == null) dialog.dismiss() else error.text = message }
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener { if (enabled()) { delete?.invoke(); dialog.dismiss() } }
    }
    fun event(date: LocalDate, existing: CalendarEvent? = null) {
        if (!enabled() || !source.ready) return
        val editingZone = source.clock.zone()
        var start = existing?.let { Instant.ofEpochMilli(it.start).atZone(editingZone).toLocalDateTime() } ?: date.atTime(9, 0)
        var end = existing?.let { Instant.ofEpochMilli(it.end).atZone(editingZone).toLocalDateTime() } ?: start.plusHours(1)
        val title = input("Event title", existing?.title.orEmpty())
        val note = input("Note (optional)", existing?.note.orEmpty(), true)
        val fields = context.scheduleColumn(); fields.addView(title)
        fun dateTimeRow(label: String, get: () -> LocalDateTime, set: (LocalDateTime) -> Unit) {
            val row = LinearLayout(context)
            val dateButton = context.scheduleButton("") {}.apply { contentDescription = "Event ${label.lowercase(java.util.Locale.ROOT)} date" }
            val timeButton = context.scheduleButton("") {}.apply { contentDescription = "Event ${label.lowercase(java.util.Locale.ROOT)} time" }
            fun update() { dateButton.text = "$label · ${scheduleDate(get().toLocalDate())}"; timeButton.text = context.scheduleTime(get().hour * 60 + get().minute) }
            dateButton.setOnClickListener {
                val value = get()
                track(DatePickerDialog(context, { _, y, m, d -> set(LocalDate.of(y, m + 1, d).atTime(get().toLocalTime())); update() }, value.year, value.monthValue - 1, value.dayOfMonth))
            }
            timeButton.setOnClickListener { val value = get(); track(TimePickerDialog(context, { _, h, m -> set(get().withHour(h).withMinute(m)); update() }, value.hour, value.minute, android.text.format.DateFormat.is24HourFormat(context))) }
            update(); row.addView(dateButton, LinearLayout.LayoutParams(0, context.dp(52), 2f)); row.addView(timeButton, LinearLayout.LayoutParams(0, context.dp(52), 1f)); fields.addView(row)
        }
        dateTimeRow("Start", { start }, { start = it }); dateTimeRow("End", { end }, { end = it }); fields.addView(note)
        editor(if (existing == null) "Add event" else "Edit event", fields, existing?.let { { source.deleteEvent(it.id) } }) {
            val zone = source.clock.zone()
            // A DST gap is invalid input, not an invitation to silently move the appointment.
            if (zone != editingZone) "Timezone changed. Cancel and reopen the editor to use the new local times."
            else if (zone.rules.getValidOffsets(start).isEmpty() || zone.rules.getValidOffsets(end).isEmpty()) "This local time does not exist in the current timezone."
            else {
                // Preserve the exact offset of an unchanged event during a repeated DST hour.
                val startMillis = if (existing != null && start == Instant.ofEpochMilli(existing.start).atZone(zone).toLocalDateTime()) existing.start else start.atZone(zone).toInstant().toEpochMilli()
                val endMillis = if (existing != null && end == Instant.ofEpochMilli(existing.end).atZone(zone).toLocalDateTime()) existing.end else end.atZone(zone).toInstant().toEpochMilli()
                when {
                    title.text.isBlank() -> "Enter a title."
                    endMillis <= startMillis -> "End must be after start."
                    source.saveEvent(title.text.toString(), startMillis, endMillis, note.text.toString(), existing?.id) -> null
                    else -> "Event unavailable. Cancel and reopen the editor."
                }
            }
        }
    }
    fun entry(existing: TimetableEntry? = null) {
        if (!enabled() || !source.ready) return
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
