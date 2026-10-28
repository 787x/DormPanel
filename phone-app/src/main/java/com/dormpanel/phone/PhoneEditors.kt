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
    data class CourseDraft(val candidate: CourseCandidate, val expected: ScheduleState, val slots: List<CourseSlot>,
        val overrides: Map<String,ClassOverride>, val selected: Set<String>, val color: Int?, val common: List<Pair<Boolean,String>>, val copied: Set<String>)
    private var captureCourse: (() -> CourseDraft)? = null
    fun captureDraft(): CourseDraft? = captureCourse?.invoke()
    fun restoreDraft(draft: CourseDraft?) {
        if(draft==null) return
        var restored=false
        val listener=object: () -> Unit {
            override fun invoke() { if(!restored && source.ready && !source.error) { restored=true;source.unsubscribe(this);courseEditor(draft.candidate,draft) } }
        }
        source.subscribe(listener);listener()
    }
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
        val managedSlot = source.state.courseSlots.any { it.id==item.entry.id }
        val info = buildString {
            append(scheduleDate(item.date)).append(" · ").append(item.date.dayOfWeek)
            append("\n").append(context.scheduleTime(item.start)).append(" – ").append(context.scheduleTime(item.end))
            if(item.end.atZone(source.clock.zone()).toLocalDate()!=item.date) append("（结束日期：").append(item.end.atZone(source.clock.zone()).toLocalDate()).append("）")
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
        if (item.sourceId == null && !managedSlot && !source.readOnly) content.addView(context.scheduleButton("Delete recurring class") {
            if (blocked()) return@scheduleButton
            source.deleteEntry(item.seriesId); dialog.dismiss()
        })
        content.addView(context.scheduleButton("管理整门课") {
            dialog.dismiss(); courseManagement(item)
        })
        source.editBlockReason?.let { content.addView(context.scheduleLabel(it, 16f)) }
        track(dialog)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = source.editable
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { if (!blocked()) { dialog.dismiss(); if(managedSlot) courseManagement(item) else editClass(item) } }
    }

    fun courseManagement(target: ClassOccurrence? = null) {
        if (!source.ready || source.error) { Toast.makeText(context,"本地课程尚未成功读取，请恢复读取后重试",Toast.LENGTH_LONG).show(); return }
        val candidates = CourseBatch.candidates(source.state)
        if (target != null) {
            candidates.firstOrNull { it.sourceId == target.sourceId && target.colorSeriesId in it.series }?.let { courseEditor(it) }
            return
        }
        val column = context.scheduleColumn()
        fun countColumns(widthPx: Int): Int {
            val widthDp = widthPx / context.resources.displayMetrics.density
            return if (widthDp >= 300 * context.resources.configuration.fontScale) 2 else 1
        }
        var columns = countColumns(context.resources.displayMetrics.widthPixels - context.dp(64))
        fun renderList() {
            column.removeAllViews()
            if (!source.ready || source.error) { column.addView(context.scheduleLabel("本地课程读取失败，请关闭并恢复读取")); return }
            if (source.state.sharedRecords != null) column.addView(context.scheduleButton("预览迁入 PR68 本机颜色") { migrateColors() })
            val current = CourseBatch.candidates(source.state)
            val colors = CourseColors.snapshot(context,source.state)
            val dark = context.resources.configuration.uiMode and 48 == 32
            var row = LinearLayout(context)
            current.forEachIndexed { index, candidate ->
                if (index % columns == 0) { row = LinearLayout(context); column.addView(row) }
                val owner = source.state.sources.firstOrNull { it.id == candidate.sourceId }?.displayName ?: "本地课程"
                val titles = candidate.series.map { series ->
                    source.state.editsByKey[ClassOverride.key(candidate.sourceId, series, null)]?.title ?: candidate.originalTitle
                }.distinct()
                val displayed = titles.singleOrNull() ?: "${candidate.originalTitle}（各时段名称不同）"
                val original = if (displayed != candidate.originalTitle) "\n原始名称：${candidate.originalTitle}" else ""
                row.addView(context.scheduleButton("$displayed$original\n${candidate.series.size + SharedCourseSchedule.slots(source.state).count { it.sourceId==candidate.sourceId && it.ownerSeries in candidate.series && it.replacesSeries==null }} 个时段 · $owner\n${if (source.editable) "可编辑" else "只读"}") {
                    courseEditor(candidate)
                }.apply {
                    setTextColor(CourseColors.ink(dark))
                    val content = text.toString()
                    val split = content.indexOf('\n').coerceAtLeast(0)
                    text = android.text.SpannableString(content).apply {
                        setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD),0,split,0)
                        setSpan(android.text.style.RelativeSizeSpan(.75f),split,length,0)
                    }
                    setPadding(context.dp(12),context.dp(16),context.dp(12),context.dp(16))
                    minHeight = context.dp(112)
                    gravity = android.view.Gravity.CENTER
                    backgroundTintList = android.content.res.ColorStateList.valueOf(CourseColors.background(colors.rgb(colors.key(candidate)!!),dark))
                }, LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(context.dp(3),context.dp(3),context.dp(3),context.dp(3)) })
            }
            if (current.size % columns != 0) row.addView(View(context),LinearLayout.LayoutParams(0,1,1f))
            if (current.isEmpty()) column.addView(context.scheduleLabel("暂无课程"))
        }
        column.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val actual = countColumns(right-left)
            if (actual != columns) { columns = actual; column.post { renderList() } }
        }
        renderList()
        val dialog = AlertDialog.Builder(context).setTitle("课程管理").setView(ScrollView(context).apply { addView(column) })
            .setNegativeButton("返回", null).create()
        track(dialog)
        val listener: () -> Unit = { renderList() }
        source.subscribe(listener)
        dialog.setOnDismissListener { source.unsubscribe(listener); dialogs.remove(dialog) }
    }

    private fun migrateColors() {
        val preview = runCatching { SharedColorMigration.preview(context, source.state) }.getOrElse {
            Toast.makeText(context, it.message, Toast.LENGTH_LONG).show(); return
        }
        val decisions = mutableMapOf<String, SharedColorMigration.Decision>()
        val column = context.scheduleColumn()
        column.addView(context.scheduleLabel("本机设置 ${preview.previousRgb.size} 门；自动色视觉变化 ${preview.automaticVisualChanges.size} 门。已有共享色默认保留。确认前逐门选择；取消或失败保留旧配置。", 16f))
        val names=CourseBatch.candidates(source.state).associate { SharedCourseColors.identity(source.state,it.sourceId,it.series.first()) to it.originalTitle }
        fun setting(record: SharedRecord): String = if(record.schemaVersion!=1) "不支持的版本 ${record.schemaVersion}（原始值只读）" else if(record.payload==listOf(0.toByte())) "自动" else "#%06X".format(record.payload.drop(1).fold(0) { n,v -> (n shl 8) or (v.toInt() and 255) })
        preview.previousRgb.forEach { (id, rgb) ->
            val existing = source.state.sharedRecords.orEmpty().any { it.typeId == "course.color" && it.recordId == id }
            column.addView(context.scheduleLabel("${names[id] ?: id.take(12)} · 本机 #%06X · 新自动 #%06X · ${if (existing) "已有共享设置" else "只有本机设置"}${if(id in preview.automaticVisualChanges) " · 自动颜色有视觉变化" else ""}".format(rgb,preview.automaticRgb.getValue(id)),14f))
        }
        preview.conflicts.forEach { conflict ->
            column.addView(context.scheduleLabel(conflict.courseTitles.joinToString(" / ")+"\n共享：${setting(conflict.shared)}；本机：${setting(conflict.local)}",18f))
            val group = RadioGroup(context)
            listOf("保留共享颜色" to SharedColorMigration.Decision.KEEP_SHARED,
                "使用本机颜色（覆盖共享设置）" to SharedColorMigration.Decision.USE_LOCAL,
                "跳过" to SharedColorMigration.Decision.SKIP).forEach { (label, decision) ->
                group.addView(RadioButton(context).apply {
                    id = View.generateViewId(); text = label
                    setOnCheckedChangeListener { _, checked -> if (checked) decisions[conflict.recordId] = decision }
                })
            }
            column.addView(group)
        }
        val status = context.scheduleLabel("",14f); column.addView(status)
        val dialog = AlertDialog.Builder(context).setTitle("预览迁入本机颜色")
            .setView(ScrollView(context).apply { addView(column) }).setNegativeButton("取消",null)
            .setPositiveButton("确认迁入",null).create()
        track(dialog)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if(source.syncStore==null) return@setOnClickListener
            val approved = runCatching { SharedColorMigration.decide(preview,decisions) }
            if (approved.isFailure) { status.text = approved.exceptionOrNull()?.message; return@setOnClickListener }
            val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            button.isEnabled = false
            source.saveSharedRecords(approved.getOrThrow(),preview.expectedHash) { result ->
                button.isEnabled = true
                result.onSuccess { receipt -> TodayWidgetUpdates.request(context); dialog.dismiss(); Toast.makeText(context,receipt.message,Toast.LENGTH_LONG).show() }
                    .onFailure { status.text = it.message ?: "迁入失败，选择与旧配置保留";if(it is ScheduleCommitUnconfirmed) {button.isEnabled=false;source.refresh()} }
            }
        }
    }

    private fun courseEditor(candidate: CourseCandidate, restored: CourseDraft? = null) {
        val expected = restored?.expected ?: source.state
        var editableCandidate = candidate
        var occurrences = CourseBatch.editingOccurrences(expected.copy(sharedRecords=expected.sharedRecords?.filterNot { it.typeId=="course.schedule" && it.schemaVersion==1 }), editableCandidate, source.clock.zone(), source.clock.today())
        val representatives = occurrences.distinctBy { it.seriesId }.map { ClassEditSession(expected.copy(dayAdjustments = emptyList()), it, true, source.clock.zone()).draft }.toMutableList()
        val column = context.scheduleColumn()
        column.setPadding(context.dp(16), context.dp(8), context.dp(16), context.dp(8))
        val copiedSlots=restored?.copied.orEmpty().toMutableSet()
        val scheduleDrafts = (restored?.slots ?: SharedCourseSchedule.slots(expected)).toMutableList()
        fun draftRecords(): List<SharedRecord>? = expected.sharedRecords?.filterNot { it.typeId == "course.schedule" && it.schemaVersion == 1 }?.plus(scheduleDrafts.map(SharedCourseSchedule::record))
        val colorSnapshot = CourseColors.snapshot(context,expected)
        val colorKey = colorSnapshot.key(candidate) ?: return
        val sharedFormat = expected.sharedRecords != null
        val unsupportedColor = sharedFormat && SharedCourseColors.display(expected,candidate.sourceId,candidate.series.first()).status=="UNSUPPORTED"
        val originalColor = colorSnapshot.manual(colorKey)
        var colorDraft = if(restored!=null) restored.color else originalColor
        val colorButton = context.scheduleButton("") {}
        fun refreshColor() {
            val dark = context.resources.configuration.uiMode and 48 == 32
            colorButton.text = "课程颜色 · ${if (colorDraft == null) "自动" else "手动"}\n${candidate.originalTitle}"
            colorButton.backgroundTintList = android.content.res.ColorStateList.valueOf(CourseColors.background(colorSnapshot.rgb(colorKey,colorDraft),dark))
            colorButton.setTextColor(CourseColors.ink(dark))
        }
        refreshColor()
        if(unsupportedColor) {colorButton.text="共享颜色版本不受支持（只读，保留原始记录）";colorButton.isEnabled=false}
        colorButton.setOnClickListener {
            track(CourseColorPicker.show(context, colorSnapshot.rgb(colorKey,colorDraft), colorDraft, candidate.originalTitle, colorSnapshot.rgb(colorKey,null)) { colorDraft = it; refreshColor() })
        }
        column.addView(colorButton)
        column.addView(context.scheduleLabel(if (sharedFormat) "共享颜色与课程修改在同一事务保存到本机，发布状态请查看同步页面。旧本机颜色尚未自动迁入。" else "颜色仅保存在本机，应用于整门课程；与时段勾选无关。",14f))
        val colorError = context.scheduleLabel("",14f)
        column.addView(colorError)
        fun saveColor(): Boolean {
            if (sharedFormat || colorDraft == originalColor) return true
            return runCatching {
                check(source.ready && !source.error) { "本地课程读取失败，颜色草稿保留" }
                check(CourseColors.snapshot(context,source.state).identity.active.contains(colorKey)) { "课程已不存在，颜色草稿保留" }
                CourseColors.save(context,colorKey,colorDraft)
                source.refresh()
            }.onFailure {
                colorError.text = it.message ?: "颜色保存失败，草稿保留"
                Toast.makeText(context,colorError.text,Toast.LENGTH_LONG).show()
            }.isSuccess
        }
        column.addView(context.scheduleLabel("公共信息 · 勾选后才统一应用", 18f))
        val editedCommon = mutableSetOf<EditText>()
        val commonStatus = linkedMapOf<EditText, TextView>()
        var updatingCommon = false
        fun common(label: String, values: List<String>): Pair<CheckBox, EditText> {
            val toggle = CheckBox(context).apply { text = "统一修改$label"; minHeight = context.dp(48) }
            val field = input(if (values.distinct().size > 1) "$label · 各时段不同" else label,
                values.distinct().singleOrNull().orEmpty(), label == "备注")
            field.contentDescription = label
            val status = context.scheduleLabel("", 14f)
            commonStatus[field] = status
            field.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { if (!updatingCommon) editedCommon.add(field) }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
            column.addView(toggle); column.addView(field); column.addView(status)
            return toggle to field
        }
        val title = common("课程名称", representatives.map { it.entry.title })
        val teacher = common("教师", representatives.map { it.teacher })
        val note = common("备注", representatives.map { it.note })
        val selected = linkedMapOf<String, CheckBox>()
        val drafts = linkedMapOf<String, ClassOverride>().apply { restored?.overrides?.let(::putAll) }
        val resetDraftViews = linkedMapOf<String, () -> Unit>()
        val commonScope = context.scheduleLabel("", 14f)
        column.addView(commonScope)
        fun updateCommon() {
            val participants = representatives.withIndex().filter { selected[it.value.seriesId]?.isChecked == true }
            commonScope.text = "统一字段仅应用：" + participants.joinToString("、") { "时段 ${it.index + 1}" }.ifEmpty { "未选择时段" } +
                "；未勾选时段不修改公共字段。"
            val state = CourseBatch.applyOverrides(expected, drafts.values.toList())
            val rows = CourseBatch.editingOccurrences(state, editableCandidate, source.clock.zone(), source.clock.today())
            val values = participants.mapNotNull { participant -> rows.firstOrNull { it.seriesId == participant.value.seriesId } }
            fun refresh(pair: Pair<CheckBox, EditText>, label: String, texts: List<String>) {
                val distinct = texts.distinct()
                val protected = pair.second in editedCommon || pair.first.isChecked
                pair.second.hint = if (distinct.size > 1) "$label · 各时段不同" else label
                commonStatus[pair.second]?.text = "$label：" + when { texts.isEmpty() -> "未选择时段"; distinct.size > 1 -> "各时段不同"; else -> "参与时段一致" } +
                    if (pair.first.isChecked) "；统一草稿已保留，将应用于上述时段" else if (protected) "；保留已输入草稿，尚未启用统一修改" else ""
                if (!protected) pair.second.setText(distinct.singleOrNull().orEmpty())
            }
            updatingCommon = true
            try {
                refresh(title, "课程名称", values.map { it.entry.title })
                refresh(teacher, "教师", values.map { it.teacher })
                refresh(note, "备注", values.map { it.note })
            } finally { updatingCommon = false }
        }
        listOf(title, teacher, note).forEach { it.first.setOnCheckedChangeListener { _, _ -> updateCommon() } }
        val slots = context.scheduleColumn()
        column.addView(slots)
        fun addSlot(item: ClassOccurrence, initiallySelected: Boolean) {
            val index = representatives.indexOfFirst { it.seriesId == item.seriesId }
            val key = ClassOverride.key(item.sourceId, item.seriesId, null)
            val check = CheckBox(context).apply { text = "时段 ${index + 1} · 参与本次修改"; isChecked = initiallySelected; minHeight = context.dp(48) }
            selected[item.seriesId] = check; slots.addView(check)
            check.setOnCheckedChangeListener { _, _ -> updateCommon() }
            val slotOccurrences = occurrences.filter { it.seriesId == item.seriesId }
            val dates = slotOccurrences.map { it.date }.distinct().sorted()
            fun summary(rows: List<ClassOccurrence>, display: ClassOccurrence): String {
                val actualDates = rows.map { it.date }.distinct().sorted()
                val weekdays = actualDates.map { it.dayOfWeek }.distinct().sortedBy { it.value }
                    .joinToString("、") { it.getDisplayName(java.time.format.TextStyle.FULL, source.clock.locale()) }
                val weeks = ScheduleProjection.profile(expected, item)?.let { profile ->
                    rows.map { Math.floorDiv(it.academicDate.toEpochDay() - profile.week1Monday.toEpochDay(), 7L) + 1 }
                        .distinct().sorted().joinToString("、", prefix = "第", postfix = "周")
                }
                return "$weekdays · ${weeks ?: if (item.sourceId == null) "本地每周课程" else "${actualDates.size} 次基线安排"}\n" +
                    "编辑基线日期：${actualDates.take(5).joinToString("、")}${if (actualDates.size > 5) " 等 ${actualDates.size} 次" else ""}\n" +
                    "${display.periodLabel.orEmpty()} · ${context.scheduleTime(display.start)}–${context.scheduleTime(display.end)}\n" +
                    "教室：${display.entry.location} · 教师：${display.teacher}\n备注：${display.note}"
            }
            val details = context.scheduleLabel(summary(slotOccurrences, item), 16f)
            slots.addView(details)
            resetDraftViews[key] = { details.text = summary(slotOccurrences, item); check.text = "时段 ${index + 1} · 参与本次修改"; updateCommon() }
            if (dates.size > 5) slots.addView(context.scheduleButton("查看全部 ${dates.size} 次基线日期") {
                val state = CourseBatch.applyOverrides(expected, drafts.values.toList()).copy(dayAdjustments = emptyList())
                val actual = CourseBatch.editingOccurrences(state, editableCandidate, source.clock.zone(), source.clock.today()).filter { it.seriesId == item.seriesId }
                track(AlertDialog.Builder(context).setTitle("时段 ${index + 1} · 编辑基线日期")
                    .setMessage(actual.map { it.date }.distinct().sorted().joinToString("\n"))
                    .setPositiveButton("关闭", null).create())
            })
            slots.addView(context.scheduleButton("编辑时段 ${index + 1}") {
                val state = CourseBatch.applyOverrides(expected, drafts.values.toList()).copy(dayAdjustments = emptyList())
                classEditor(item, true, state) { patch ->
                    drafts[key] = ClassOverride.of(item.sourceId, item.seriesId, null, patch)
                    check.isChecked = true
                    check.text = "时段 ${index + 1} · 已修改草稿，已纳入保存"
                    val future = CourseBatch.applyOverrides(expected, drafts.values.toList()).copy(dayAdjustments = emptyList())
                    val rows = CourseBatch.editingOccurrences(future, editableCandidate, source.clock.zone(), source.clock.today()).filter { it.seriesId == item.seriesId }
                    val display = rows.firstOrNull()?.let { ClassEditSession(future, it, true, source.clock.zone()).draft }
                    if (display != null) details.text = summary(rows, display)
                    updateCommon()
                }
            }.apply { isEnabled = source.editable })
            slots.addView(context.scheduleButton("恢复时段 ${index + 1} 全部继承") {
                drafts[key] = ClassOverride.of(item.sourceId, item.seriesId, null, ClassPatch())
                check.isChecked = true; check.text = "时段 ${index + 1} · 恢复继承草稿，已纳入保存"
                val state = CourseBatch.applyOverrides(expected, drafts.values.toList())
                val rows = CourseBatch.editingOccurrences(state, editableCandidate, source.clock.zone(), source.clock.today()).filter { it.seriesId == item.seriesId }
                rows.firstOrNull()?.let { details.text = summary(rows, it) }
                updateCommon()
            }.apply { isEnabled = source.editable })
        }
        representatives.toList().forEach { addSlot(it, true) }
        if (sharedFormat) {
            val arrangements=context.scheduleColumn(); column.addView(arrangements)
            fun editArrangement(original: CourseSlot, accept: (CourseSlot) -> Unit) {
                val fields=context.scheduleColumn()
                fun field(label: String, value: String): EditText = input(label,value,label=="备注").also { fields.addView(context.scheduleLabel(label,14f));fields.addView(it) }
                val name=field("名称",original.title);val location=field("教室",original.location)
                val teacherField=field("教师",original.teacher);val noteField=field("备注",original.note)
                val week1=field("第1周周一 YYYY-MM-DD",original.week1)
                val weeks=field("有效周次（逗号分隔，1–104）",original.weeks.joinToString(","))
                val upper=field("单双周选择上限（明确指定）",original.weeks.maxOrNull()?.toString().orEmpty())
                val finiteConsent=CheckBox(context).apply {text="明确将原无限重复课程改为所选有限周；范围外不再上课"}
                if(original.sourceId==null && original.replacesSeries!=null) fields.addView(finiteConsent)
                if(original.sourceId==null) fields.addView(context.scheduleLabel("新增安排必须明确指定有限周次。替换原无限重复课程会取消范围外所有重复课程，不是等价替换。",14f))
                listOf("单周" to 1,"双周" to 2,"全部周" to 0).forEach { (label,parity) -> fields.addView(context.scheduleButton(label) {
                    val limit=upper.text.toString().toIntOrNull()
                    if(limit==null || limit !in 1..104) {Toast.makeText(context,"请先明确指定周次上限",Toast.LENGTH_LONG).show();return@scheduleButton}
                    weeks.setText((1..limit).filter { parity==0 || it%2==parity%2 }.joinToString(","))
                }) }
                val weekday=field("星期 1–7",original.weekday.toString())
                fun time(minute: Int) = "%02d:%02d".format(minute/60,minute%60)
                val start=field("开始时间 HH:mm",time(original.startMinute));val end=field("结束时间 HH:mm（早于开始表示跨午夜）",time(original.endMinute))
                val a=field("起始节次（0 使用自定义时间）",original.periodStart.toString());val z=field("结束节次",original.periodEnd.toString())
                val enabled=CheckBox(context).apply { text="启用此安排";isChecked=original.enabled };fields.addView(enabled)
                val status=context.scheduleLabel("",14f);fields.addView(status)
                val editor=AlertDialog.Builder(context).setTitle("编辑安排草稿").setView(ScrollView(context).apply { addView(fields) })
                    .setNegativeButton("取消",null).setPositiveButton("保留草稿",null).create();track(editor)
                editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    runCatching {
                        val next=original.copy(title=name.text.toString(),location=location.text.toString(),teacher=teacherField.text.toString(),note=noteField.text.toString(),
                            week1=week1.text.toString(),weeks=weeks.text.toString().split(",","，").filter { it.isNotBlank() }.map { it.trim().toInt() }.distinct().sorted(),
                            weekday=weekday.text.toString().toInt(),startMinute=LocalTime.parse(start.text.toString()).toSecondOfDay()/60,endMinute=LocalTime.parse(end.text.toString()).toSecondOfDay()/60,
                            periodStart=a.text.toString().toInt(),periodEnd=z.text.toString().toInt(),enabled=enabled.isChecked)
                        require(next.weeks.isNotEmpty()) {"请明确选择有效周次，不能使用隐式默认范围"}
                        require(original.sourceId!=null || original.replacesSeries==null || finiteConsent.isChecked) {"请明确确认无限重复改为有限范围的影响"}
                        SharedCourseSchedule.record(next);accept(next);editor.dismiss()
                    }.onFailure { status.text=it.message ?: "安排无效，草稿保留"; status.post {status.requestRectangleOnScreen(android.graphics.Rect(0,0,status.width,status.height),false)} }
                }
            }
            fun renderArrangements() {
                arrangements.removeAllViews()
                arrangements.addView(context.scheduleLabel("完整安排 · 新增时段独立保存身份；导入时段停用保留原始数据。既有全周覆盖与指定周例外继续优先。",16f))
                scheduleDrafts.filter { it.sourceId==candidate.sourceId && it.ownerSeries in candidate.series }.toList().forEach { slot ->
                    arrangements.addView(context.scheduleLabel("${slot.title} · 星期${slot.weekday} · 第${slot.weeks.joinToString(",")}周\n${slot.startMinute}–${slot.endMinute} 分 · ${slot.location} · ${if(slot.enabled) "启用" else "停用"}",16f))
                    arrangements.addView(context.scheduleButton("编辑安排") { editArrangement(slot) { next -> scheduleDrafts[scheduleDrafts.indexOf(slot)]=next;renderArrangements() } })
                    arrangements.addView(context.scheduleButton("复制安排") { editArrangement(slot.copy(id=java.util.UUID.randomUUID().toString(),replacesSeries=null)) { next -> copiedSlots.add(next.id);scheduleDrafts.add(next);renderArrangements() } })
                    arrangements.addView(context.scheduleButton(if(slot.replacesSeries==null) "删除新增安排" else "停用导入/原有安排") {
                        track(AlertDialog.Builder(context).setTitle("确认停止显示此安排？").setMessage("原始导入数据保留。指定周例外不会应用于不相关的新时段。")
                            .setNegativeButton("取消",null).setPositiveButton("确认") { _,_ ->
                                if(slot.replacesSeries==null) scheduleDrafts.remove(slot) else scheduleDrafts[scheduleDrafts.indexOf(slot)]=slot.copy(enabled=false)
                                renderArrangements()
                            }.create())
                    })
                }
            }
            renderArrangements()
            column.addView(context.scheduleButton("移除此课程的全部共享安排规则（恢复原始基线）") {
                track(AlertDialog.Builder(context).setTitle("移除安排规则？").setMessage("独立新增安排将删除，替换规则将移除并恢复原始导入/重复课程。此操作尚需保存预览确认；之后才可删除或替换来源。")
                    .setNegativeButton("取消",null).setPositiveButton("加入草稿") { _,_ -> scheduleDrafts.removeAll {it.sourceId==candidate.sourceId && it.ownerSeries in candidate.series};renderArrangements() }.create())
            })
            fun initialSlot(series: String, replace: Boolean): CourseSlot {
                val rows=occurrences.filter { it.seriesId==series }
                val row=rows.first()
                check(rows.map { Triple(it.academicDate.dayOfWeek,it.entry.startMinute,it.entry.endMinute) }.distinct().size==1) { "系列原有时间不一致，请先明确处理覆盖关系" }
                val profile=ScheduleProjection.profile(expected,row)
                val monday=profile?.week1Monday ?: row.academicDate.minusDays(row.academicDate.dayOfWeek.value-1L)
                val weeks=if(candidate.sourceId==null) emptyList() else rows.map { (Math.floorDiv(it.academicDate.toEpochDay()-monday.toEpochDay(),7L)+1).toInt() }.distinct().sorted()
                return CourseSlot(java.util.UUID.randomUUID().toString(),candidate.sourceId,series,if(replace) series else null,monday.toString(),weeks,row.academicDate.dayOfWeek.value,
                    row.entry.startMinute,row.entry.endMinute,if(row.linked) ScheduleProjection.periods(row.periodLabel)?.first ?: 0 else 0,if(row.linked) ScheduleProjection.periods(row.periodLabel)?.second ?: 0 else 0,true,row.entry.title,row.entry.location,row.teacher,row.note)
            }
            column.addView(context.scheduleButton("新增安排") {
                runCatching { initialSlot(candidate.series.first(),false) }.onSuccess { initial -> editArrangement(initial) { next -> scheduleDrafts.add(next);renderArrangements() } }
                    .onFailure { Toast.makeText(context,it.message,Toast.LENGTH_LONG).show() }
            })
            column.addView(context.scheduleButton("隐藏整门课程全部安排") {
                track(AlertDialog.Builder(context).setTitle("隐藏全部相关安排？")
                    .setMessage("仅停止课表显示；保留手动课程身份和导入来源。所有修改仍需预览后原子保存。")
                    .setNegativeButton("取消",null).setPositiveButton("加入草稿") { _,_ ->
                        runCatching {
                            val replacements=candidate.series.map { series -> scheduleDrafts.find { it.sourceId==candidate.sourceId && it.replacesSeries==series } ?: initialSlot(series,true) }
                            scheduleDrafts.removeAll { it.sourceId==candidate.sourceId && it.ownerSeries in candidate.series }
                            scheduleDrafts.addAll(replacements.map { it.copy(enabled=false) });renderArrangements()
                        }.onFailure { Toast.makeText(context,it.message,Toast.LENGTH_LONG).show() }
                    }.create())
            })
            candidate.series.forEach { series -> column.addView(context.scheduleButton("管理原时段周次 / 复制 · $series") {
                val existing=scheduleDrafts.find { it.sourceId==candidate.sourceId && it.replacesSeries==series }
                runCatching { existing ?: initialSlot(series,true) }.onSuccess { initial -> editArrangement(initial) { next ->
                    if(existing!=null) scheduleDrafts.remove(existing)
                    scheduleDrafts.add(next);renderArrangements()
                } }.onFailure { Toast.makeText(context,it.message,Toast.LENGTH_LONG).show() }
            }) }
        } else column.addView(context.scheduleLabel("精确周次、新增、复制和停用安排需要已显式启用 DPV3/4 扩展格式的数据集。本页不会升级或切换绑定。",14f))

        updateCommon()
        if (candidate.sourceId == null) column.addView(context.scheduleButton("添加其他本地课程") {
            val others = expected.entries.filter { it.id !in editableCandidate.series }
            if (others.isEmpty()) { Toast.makeText(context, "没有其他本地课程", Toast.LENGTH_SHORT).show(); return@scheduleButton }
            val chosen = BooleanArray(others.size)
            track(AlertDialog.Builder(context).setTitle("显式添加本次编辑的条目")
                .setMultiChoiceItems(others.map { "${it.title} · 星期${it.day} · ${context.scheduleTime(it.startMinute)}" }.toTypedArray(), chosen) { _, index, value -> chosen[index] = value }
                .setNegativeButton("取消", null).setPositiveButton("添加条目") { _, _ ->
                    val added = others.filterIndexed { index, _ -> chosen[index] }.map { it.id }
                    editableCandidate = editableCandidate.copy(series = editableCandidate.series + added)
                    occurrences = CourseBatch.editingOccurrences(expected, editableCandidate, source.clock.zone(), source.clock.today())
                    added.forEach { id ->
                        val row = occurrences.firstOrNull { it.seriesId == id } ?: return@forEach
                        val item = ClassEditSession(expected.copy(dayAdjustments = emptyList()), row, true, source.clock.zone()).draft
                        representatives.add(item); addSlot(item, false)
                    }
                    updateCommon()
                }.create())
        })
        val affectedWeeks = occurrences.map { it.anchorDate.minusDays(it.anchorDate.dayOfWeek.value - 1L) }.toSet()
        val dayEffects = expected.dayAdjustments.filter { adjustment ->
            val date = LocalDate.parse(adjustment.date); date.minusDays(date.dayOfWeek.value - 1L) in affectedWeeks
        }
        if (dayEffects.isNotEmpty()) column.addView(context.scheduleLabel("日期调整继续影响最终课表：\n" + dayEffects.joinToString("\n") {
            "${it.date}：${if (it.mode == "none") "无课（仍可编辑系列）" else "使用星期${it.weekday}课表"}"
        }, 16f))
        val exceptions = expected.classOverrides.filter { it.sourceId == candidate.sourceId && it.seriesId in editableCandidate.series && it.anchorDate != null }
        column.addView(context.scheduleLabel(exceptions.joinToString("\n") {
            "指定周 ${it.anchorDate} 有单独修改，继续优先生效；保存前显示最终结果"
        }, 14f))
        val error = context.scheduleLabel("指定周例外与日期调整继续生效；公共信息可能被指定周例外覆盖。", 16f)
        column.addView(error)
        val dialog = AlertDialog.Builder(context).setTitle(candidate.originalTitle)
            .setView(ScrollView(context).apply { addView(column) }).setNegativeButton("取消", null)
            .setPositiveButton("保存修改", null).create()
        track(dialog)
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        save.isEnabled = source.ready && !source.error && !unsupportedColor && (!sharedFormat || source.editable)
        column.addView(context.scheduleButton("放弃草稿并重新加载") {
            track(AlertDialog.Builder(context).setTitle("放弃未保存草稿？")
                .setMessage("重新读取成功后关闭本页，按最新数据重新进入课程；本页所有未保存修改将被放弃。")
                .setNegativeButton("保留草稿", null).setPositiveButton("放弃并重新加载") { _, _ ->
                    save.isEnabled = false
                    source.reload { result ->
                        result.onSuccess {
                            dialog.dismiss()
                            val fresh = CourseBatch.candidates(source.state).firstOrNull { it.sourceId == candidate.sourceId && it.series.any { id -> id in candidate.series } }
                            if (fresh != null) courseEditor(fresh)
                            else Toast.makeText(context, "课程已不存在，旧草稿已关闭", Toast.LENGTH_LONG).show()
                        }.onFailure { error.text = "重新读取失败，旧草稿保留；暂不可保存" }
                    }
                }.create())
        })
        restored?.let { old ->
            selected.forEach { (series,check) -> check.isChecked=series in old.selected }
            listOf(title,teacher,note).zip(old.common).forEach { (field,value) -> field.first.isChecked=value.first;field.second.setText(value.second) }
        }
        captureCourse = { CourseDraft(candidate,expected,scheduleDrafts.toList(),drafts.toMap(),selected.filterValues { it.isChecked }.keys,
            colorDraft,listOf(title,teacher,note).map { it.first.isChecked to it.second.text.toString() },copiedSlots.toSet()) }
        dialog.setOnDismissListener { captureCourse=null;dialogs.remove(dialog) }
        var businessSaved = false
        column.addView(context.scheduleButton("重试本地读取（保留草稿）") {
            save.isEnabled = false
            source.reload { result ->
                save.isEnabled = result.isSuccess
                error.text = if (result.isSuccess) {
                    if (businessSaved) "业务已保存；颜色草稿保留，请保存颜色。" else "已恢复读取，草稿保留；保存时继续核对课程版本。"
                } else "读取失败，业务和颜色草稿保留。"
            }
        })
        save.setOnClickListener {
            if (businessSaved) { if (saveColor()) dialog.dismiss(); return@setOnClickListener }
            val excluded = drafts.filter { (_, edit) -> selected[edit.seriesId]?.isChecked != true && edit != expected.editsByKey[edit.key] }
            if (excluded.isNotEmpty()) {
                track(AlertDialog.Builder(context).setTitle("存在未参与保存的已编辑时段")
                    .setMessage(excluded.values.joinToString("、") { "时段 ${representatives.indexOfFirst { item -> item.seriesId == it.seriesId } + 1}" } + "已有草稿，请决定是否纳入保存。")
                    .setNegativeButton("返回检查", null)
                    .setNeutralButton("放弃这些草稿") { _, _ -> excluded.keys.forEach { drafts.remove(it); resetDraftViews[it]?.invoke() }; save.performClick() }
                    .setPositiveButton("纳入保存") { _, _ -> excluded.values.forEach { selected[it.seriesId]?.isChecked = true }; save.performClick() }.create())
                return@setOnClickListener
            }
            val changes = representatives.filter { selected[it.seriesId]?.isChecked == true }.map { item ->
                val key = ClassOverride.key(item.sourceId, item.seriesId, null)
                val old = drafts[key] ?: expected.editsByKey[key] ?: ClassOverride.of(item.sourceId, item.seriesId, null, ClassPatch())
                old.copy(title = if (title.first.isChecked) title.second.text.toString().trim() else old.title,
                    teacher = if (teacher.first.isChecked) teacher.second.text.toString().trim() else old.teacher,
                    note = if (note.first.isChecked) note.second.text.toString().trim() else old.note)
            }.filter { it != expected.editsByKey[it.key] && (expected.editsByKey[it.key] != null || it.patch() != ClassPatch()) }
            if (changes.isEmpty() && !sharedFormat) {
                if (colorDraft == originalColor) {
                    Toast.makeText(context,"没有修改",Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                } else if (saveColor()) dialog.dismiss()
                return@setOnClickListener
            }
            if (!source.editable) { error.text = "当前课表只读，业务草稿保留"; return@setOnClickListener }
            val recordResult=runCatching { draftRecords()?.map { record ->
                if(record.typeId=="course.schedule" && record.schemaVersion==1) {
                    val slot=SharedCourseSchedule.decode(record)
                    if(slot.sourceId==candidate.sourceId && slot.ownerSeries in candidate.series && selected[slot.ownerSeries]?.isChecked==true)
                        SharedCourseSchedule.record(slot.copy(title=if(title.first.isChecked) title.second.text.toString().trim() else slot.title,
                            teacher=if(teacher.first.isChecked) teacher.second.text.toString().trim() else slot.teacher,
                            note=if(note.first.isChecked) note.second.text.toString().trim() else slot.note)) else record
                } else record
            } }
            if(recordResult.isFailure) {error.text=recordResult.exceptionOrNull()?.message ?: "安排无效，草稿保留";return@setOnClickListener}
            val finalRecords=recordResult.getOrThrow()
            val colorEdit=if(colorDraft==originalColor) CourseColorEdit.Unchanged else CourseColorEdit.Set(colorDraft)
            if(sharedFormat && changes.isEmpty() && finalRecords.orEmpty().toSet()==expected.sharedRecords.orEmpty().toSet() && colorEdit==CourseColorEdit.Unchanged) {Toast.makeText(context,"没有修改",Toast.LENGTH_SHORT).show();return@setOnClickListener}
            val valid = runCatching { if (sharedFormat) CourseBatch.withColor(source.state, expected, changes, candidate, colorEdit, finalRecords) else CourseBatch.validate(source.state, expected, changes) }
            if (valid.isFailure) { error.text = valid.exceptionOrNull()?.message; return@setOnClickListener }
            val preview = changes.joinToString("\n\n") { change ->
                val old = expected.editsByKey[change.key] ?: ClassOverride.of(change.sourceId, change.seriesId, null, ClassPatch())
                val fields = listOf("名称" to (old.title to change.title), "教师" to (old.teacher to change.teacher),
                    "备注" to (old.note to change.note), "教室" to (old.location to change.location),
                    "星期" to (old.weekday to change.weekday), "节次起" to (old.periodStart to change.periodStart),
                    "节次止" to (old.periodEnd to change.periodEnd), "时间模式" to (old.timingMode to change.timingMode),
                    "开始" to (old.startMinute to change.startMinute), "结束" to (old.endMinute to change.endMinute))
                "时段 ${representatives.map { it.seriesId }.indexOf(change.seriesId) + 1}：" + if (change.patch().isEmpty()) "移除全周覆盖，恢复全部继承（指定周例外保留）" else fields.filter { it.second.first != it.second.second }
                    .joinToString("；") { "${it.first} → ${it.second.second ?: "继承原始值"}" }
            }
            val commonSummary = listOf(title to "课程名称", teacher to "教师", note to "备注")
                .filter { it.first.first.isChecked }
                .joinToString("；") { "${it.second} → ${it.first.second.text.toString().trim().ifEmpty { "（清空）" }}" }
                .ifEmpty { "未统一修改公共信息" }
            val oldSlots=expected.courseSlots.associateBy { it.id }
            val newSlots=finalRecords.orEmpty().filter {it.typeId=="course.schedule" && it.schemaVersion==1}.map(SharedCourseSchedule::decode).associateBy {it.id}
            val scheduleChanges=(oldSlots.keys+newSlots.keys).filter {oldSlots[it]!=newSlots[it]}.joinToString("\n") { id ->
                "$id："+when {newSlots[id]==null -> "删除新增安排";oldSlots[id]==null -> if(id in copiedSlots) "复制安排" else "新增安排";newSlots[id]?.enabled==false -> "停用安排";else -> "修改安排"}
            }
            val exceptionPreview = scheduleChanges+"\n"+CourseBatch.exceptionPreview(expected, changes, source.clock.zone()) +
                (if(sharedFormat) "\n"+CourseBatch.schedulePreview(expected,CourseBatch.withColor(expected,expected,changes,candidate,colorEdit,finalRecords),candidate,source.clock.zone(),source.clock.today()) else "") +
                if (sharedFormat) "\n安排最终草稿：\n" + scheduleDrafts.filter { it.sourceId==candidate.sourceId && it.ownerSeries in candidate.series }.joinToString("\n") {
                    "${it.id}：${if(it.enabled) "启用" else "停用"}；第${it.weeks.joinToString(",")}周；星期${it.weekday}；${it.startMinute}–${it.endMinute}分；${it.periodStart}–${it.periodEnd}节；${it.location}"
                } else ""
            track(AlertDialog.Builder(context).setTitle(if(sharedFormat) "课程保存预览" else "变更预览 · ${changes.size} 个时段")
                .setMessage("颜色：${if (colorDraft == null) "自动" else "#%06X".format(colorDraft)}；${if (sharedFormat) "共享设置" else "本机设置"}\n\n统一信息：$commonSummary\n\n各时段实际差异：\n$preview\n\n$exceptionPreview\n\n${commonScope.text}所有指定周例外继续生效，例外字段优先；未改字段与原始导入身份保留。")
                .setNegativeButton("继续编辑", null).setPositiveButton("确认保存") { _, _ ->
                    save.isEnabled = false; dialog.setCancelable(false)
                    val complete: (Result<ClassBatchSaveReceipt>) -> Unit = { result ->
                        save.isEnabled = source.editable; dialog.setCancelable(true)
                        result.onSuccess { receipt ->
                            businessSaved = true
                            if (!saveColor()) { error.text = "业务修改已提交；颜色保存失败，请重试颜色保存"; return@onSuccess }
                            dialog.dismiss()
                            if (receipt.refreshed) Toast.makeText(context, receipt.message, Toast.LENGTH_LONG).show()
                            else track(AlertDialog.Builder(context).setTitle("修改已提交，刷新失败")
                                .setMessage(receipt.message).setNegativeButton("关闭", null)
                                .setPositiveButton("恢复读取") { _, _ -> source.retryLocalRead() }.create())
                        }
                            .onFailure {
                                error.text = it.message ?: "保存失败，草稿保留"
                                if (it is ScheduleCommitUnconfirmed) {
                                    source.refresh()
                                    track(AlertDialog.Builder(context).setTitle("保存结果尚待确认").setMessage(it.message)
                                        .setPositiveButton("知道了", null).create())
                                }
                            }
                    }
                    if (sharedFormat) source.saveCourseEdit(expected, changes, candidate, colorEdit, finalRecords, complete)
                    else source.saveClassOverrides(expected, changes, complete)
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
        val normalizedBefore = if (accept != null) runCatching { session.patch(draft.entry.title, draft.entry.location,
            draft.teacher, draft.note, selectedDate, selectedWeekday, draft.linked,
            existingRange?.first, existingRange?.second, start, end) }.getOrNull() else null

        val firstPeriod = input("First period", existingRange?.first?.toString().orEmpty())
        val lastPeriod = input("Last period", existingRange?.second?.toString().orEmpty())
        val fields = context.scheduleColumn()
        fields.addView(title)
        val dateButton = context.scheduleButton("Date · $selectedDate") {}
        val days = DayOfWeek.entries
        val weekdaySpinner = Spinner(context).apply {
            contentDescription = "Class weekday"
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
                if (accept != null) {
                    val original = draftState.editsByKey[session.key]?.patch() ?: ClassPatch()
                    accept(normalizedBefore?.let { before -> session.preserveUntouched(original, before, it) } ?: it); null
                }
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
