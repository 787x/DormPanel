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
    private fun blocked(): Boolean {
        val reason = source.editBlockReason ?: return false
        Toast.makeText(context, reason, Toast.LENGTH_LONG).show(); return true
    }
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
            source.editBlockReason?.let { error.text = it; return@setOnClickListener }
            if (enabled() && !source.readOnly && !source.applyingSnapshot) { val message = save(); if (message == null) dialog.dismiss() else error.text = message }
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            source.editBlockReason?.let { error.text = it; return@setOnClickListener }
            if (enabled() && !source.applyingSnapshot) { delete?.invoke(); dialog.dismiss() }
        }
    }
    fun dayAdjustment(date: LocalDate) {
        if (!enabled() || !source.ready || blocked() || source.applyingSnapshot) return
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
            CoursePresentation.detailPeriods(item,ScheduleProjection.profile(source.state,item))?.let { append("\n").append(it) }
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
            if (blocked()) return@scheduleButton
            source.saveClassOverride(item.sourceId, item.seriesId, item.anchorDate, null); dialog.dismiss()
        })
        if (series && !source.readOnly) content.addView(context.scheduleButton("Reset all-weeks edit") {
            if (blocked()) return@scheduleButton
            source.saveClassOverride(item.sourceId, item.seriesId, null, null); dialog.dismiss()
        })
        if (item.imported == null && !source.readOnly) content.addView(context.scheduleButton("Delete recurring class") {
            if (blocked()) return@scheduleButton
            source.deleteEntry(item.seriesId); dialog.dismiss()
        })
        content.addView(context.scheduleButton("管理整门课") {
            dialog.dismiss(); courseManagement(item)
        })
        source.editBlockReason?.let { content.addView(context.scheduleLabel(it, 16f)) }
        track(dialog)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = source.editable
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { if (!blocked()) { dialog.dismiss(); editClass(item) } }
    }

    fun courseManagement(target: ClassOccurrence? = null) {
        val candidates = CourseBatch.candidates(source.state)
        if (target != null) {
            candidates.firstOrNull { it.sourceId == target.sourceId && target.seriesId in it.series }?.let { courseEditor(it) }
            return
        }
        val column = context.scheduleColumn()
        candidates.forEach { candidate ->
            val owner = source.state.sources.firstOrNull { it.id == candidate.sourceId }?.displayName ?: "本地课程"
            column.addView(context.scheduleButton("${candidate.originalTitle}\n${candidate.series.size} 个时段 · $owner · ${if (source.editable) "可编辑" else "只读"}") {
                courseEditor(candidate)
            }.apply {
                setTextColor(android.graphics.Color.BLACK)
                backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.rgb(
                    210 + (candidate.originalTitle.hashCode() and 15), 220, 235))
            })
        }
        if (candidates.isEmpty()) column.addView(context.scheduleLabel("暂无课程"))
        track(AlertDialog.Builder(context).setTitle("课程管理").setView(ScrollView(context).apply { addView(column) })
            .setNegativeButton("返回", null).create())
    }

    private fun courseEditor(candidate: CourseCandidate) {
        val expected = source.state
        val editableCandidate = if (candidate.sourceId == null) candidate.copy(series = expected.entries.map { it.id }) else candidate
        val occurrences = CourseBatch.occurrences(expected, editableCandidate, source.clock.zone(), source.clock.today())
        val representatives = occurrences.distinctBy { it.seriesId }
        val column = context.scheduleColumn()
        column.setPadding(context.dp(16), context.dp(8), context.dp(16), context.dp(8))
        column.addView(context.scheduleLabel("公共信息 · 勾选后才统一应用", 18f))
        fun common(label: String, values: List<String>): Pair<CheckBox, EditText> {
            val toggle = CheckBox(context).apply { text = "统一修改$label"; minHeight = context.dp(48) }
            val field = input(if (values.distinct().size > 1) "$label · 各时段不同" else label,
                values.distinct().singleOrNull().orEmpty(), label == "备注")
            column.addView(toggle); column.addView(field)
            return toggle to field
        }
        val title = common("课程名称", representatives.map { it.entry.title })
        val teacher = common("教师", representatives.map { it.teacher })
        val note = common("备注", representatives.map { it.note })
        val selected = linkedMapOf<String, CheckBox>()
        val drafts = linkedMapOf<String, ClassOverride>()
        representatives.forEachIndexed { index, item ->
            val key = ClassOverride.key(item.sourceId, item.seriesId, null)
            val check = CheckBox(context).apply { text = "时段 ${index + 1} · 参与本次修改"; isChecked = item.seriesId in candidate.series; minHeight = context.dp(48) }
            selected[item.seriesId] = check; column.addView(check)
            val dates = occurrences.filter { it.seriesId == item.seriesId }.map { it.date }.distinct().sorted()
            column.addView(context.scheduleLabel("实际日期：${dates.joinToString("、")}\n${item.periodLabel.orEmpty()} · ${context.scheduleTime(item.start)}–${context.scheduleTime(item.end)}\n教室：${item.entry.location} · 教师：${item.teacher}\n备注：${item.note}", 16f))
            column.addView(context.scheduleButton("编辑时段 ${index + 1}") {
                val state = expected.copy(classOverrides = expected.classOverrides.filterNot { it.key in drafts } + drafts.values)
                classEditor(item, true, state) { patch ->
                    drafts[key] = ClassOverride.of(item.sourceId, item.seriesId, null, patch)
                    check.text = "时段 ${index + 1} · 已修改草稿"
                }
            }.apply { isEnabled = source.editable })
        }
        val exceptions = expected.classOverrides.filter { it.sourceId == candidate.sourceId && it.seriesId in editableCandidate.series && it.anchorDate != null }
        column.addView(context.scheduleLabel(exceptions.joinToString("\n") {
            "指定周 ${it.anchorDate}：${it.patch()}（继续优先生效）"
        }, 14f))
        column.addView(context.scheduleButton("重新加载并查看当前课程（保留本页草稿）") {
            source.reload { result ->
                result.onSuccess { courseManagement() }.onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
            }
        })
        val error = context.scheduleLabel("指定周例外与日期调整继续生效；公共信息可能被指定周例外覆盖。", 16f)
        column.addView(error)
        val dialog = AlertDialog.Builder(context).setTitle(candidate.originalTitle)
            .setView(ScrollView(context).apply { addView(column) }).setNegativeButton("取消", null)
            .setPositiveButton("保存修改", null).create()
        track(dialog)
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        save.isEnabled = source.editable
        save.setOnClickListener {
            val changes = representatives.filter { selected[it.seriesId]?.isChecked == true }.map { item ->
                val key = ClassOverride.key(item.sourceId, item.seriesId, null)
                val old = drafts[key] ?: expected.editsByKey[key] ?: ClassOverride.of(item.sourceId, item.seriesId, null, ClassPatch())
                old.copy(title = if (title.first.isChecked) title.second.text.toString().trim() else old.title,
                    teacher = if (teacher.first.isChecked) teacher.second.text.toString().trim() else old.teacher,
                    note = if (note.first.isChecked) note.second.text.toString().trim() else old.note)
            }.filter { it != expected.editsByKey[it.key] && it.patch() != ClassPatch() }
            val valid = runCatching { CourseBatch.validate(source.state, expected, changes) }
            if (valid.isFailure) { error.text = valid.exceptionOrNull()?.message; return@setOnClickListener }
            val preview = changes.joinToString("\n\n") { change ->
                val old = expected.editsByKey[change.key] ?: ClassOverride.of(change.sourceId, change.seriesId, null, ClassPatch())
                val fields = listOf("名称" to (old.title to change.title), "教师" to (old.teacher to change.teacher),
                    "备注" to (old.note to change.note), "教室" to (old.location to change.location),
                    "星期" to (old.weekday to change.weekday), "节次起" to (old.periodStart to change.periodStart),
                    "节次止" to (old.periodEnd to change.periodEnd), "时间模式" to (old.timingMode to change.timingMode),
                    "开始" to (old.startMinute to change.startMinute), "结束" to (old.endMinute to change.endMinute))
                "时段 ${representatives.map { it.seriesId }.indexOf(change.seriesId) + 1}：" + fields.filter { it.second.first != it.second.second }
                    .joinToString("；") { "${it.first} → ${it.second.second ?: "继承原始值"}" }
            }
            track(AlertDialog.Builder(context).setTitle("变更预览 · ${changes.size} 个时段")
                .setMessage("$preview\n\n统一字段只应用于勾选时段。所有指定周例外继续生效，例外字段优先；未改字段与原始导入身份保留。")
                .setNegativeButton("继续编辑", null).setPositiveButton("确认保存") { _, _ ->
                    save.isEnabled = false; dialog.setCancelable(false)
                    source.saveClassOverrides(expected, changes) { result ->
                        save.isEnabled = true; dialog.setCancelable(true)
                        result.onSuccess { Toast.makeText(context, "已保存到本机", Toast.LENGTH_LONG).show(); dialog.dismiss() }
                            .onFailure { error.text = it.message ?: "保存失败，草稿保留" }
                    }
                }.create())
        }
    }

    private fun editClass(item: ClassOccurrence) {
        val scope = arrayOf("This week", "All weeks for this timetable item")
        track(AlertDialog.Builder(context).setTitle("Edit scope")
            .setItems(scope) { _, which -> classEditor(item, which == 1) }
            .setNegativeButton("Cancel", null).create())
    }

    private fun classEditor(item: ClassOccurrence, allWeeks: Boolean, draftState: ScheduleState = source.state, accept: ((ClassPatch) -> Unit)? = null) {
        if (!enabled() || !source.ready || blocked() || source.applyingSnapshot) return
        val session = ClassEditSession(draftState, item, allWeeks, source.clock.zone())
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
                if (accept != null) { accept(it); null }
                else if (source.saveClassOverride(item.sourceId, item.seriesId, if (allWeeks) null else date, it)) null
                else "Could not save class edit."
            }, onFailure = { it.message ?: "Invalid class edit." })
        }
    }
    fun entry(existing: TimetableEntry? = null) {
        if (!enabled() || !source.ready || blocked() || source.applyingSnapshot) return
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
