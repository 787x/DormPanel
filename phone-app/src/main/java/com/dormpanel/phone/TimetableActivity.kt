package com.dormpanel.phone

import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.dormpanel.app.schedule.*
import java.time.*
import java.time.temporal.ChronoUnit
import java.util.concurrent.Executors

/** Offline UI. The shared source owns all effective state and serialized database writes. */
class TimetableActivity : AppCompatActivity() {
    private lateinit var profiles: TermScheduleProfileStore
    internal lateinit var source: ScheduleSource
    internal lateinit var sharedSync: SharedScheduleController
    private var sharedDialog: android.app.AlertDialog? = null
    private var resumeCheckPending = true
    private lateinit var editors: PhoneEditors
    private lateinit var root: LinearLayout
    private var selected = LocalDate.now()
    private var weekView = true
    private var scroll: ScrollView? = null
    private var scrollOffset = 0
    private var renderedKey = ""
    override fun onPause() {
        getPreferences(MODE_PRIVATE).edit().putString("date", selected.toString()).putBoolean("weekView", weekView)
            .putInt("scroll", scroll?.scrollY ?: scrollOffset).apply()
        super.onPause()
    }
    private var activeTerm: String? = null
    private var replacement: String? = null
    internal var busy = false; private set
    private val worker = Executors.newSingleThreadExecutor()
    private val listener: () -> Unit = { render(); checkOnResume() }
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !source.readOnly && !source.applyingSnapshot) {
            val target = replacement
            background({
                val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: "timetable.ics"
                contentResolver.openInputStream(uri)?.use { input ->
                    TimetableImporter(profiles = profiles).parseOutcome(ScheduleArtifact.read(name, input, contentResolver.getType(uri)))
                } ?: throw ScheduleImportException("Cannot read this file.")
            }) { outcome -> handleParsed(outcome, target) }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selected = savedInstanceState?.getString("date")?.let(LocalDate::parse) ?: getPreferences(MODE_PRIVATE).getString("date", null)?.let(LocalDate::parse) ?: LocalDate.now()
        weekView = savedInstanceState?.getBoolean("weekView") ?: getPreferences(MODE_PRIVATE).getBoolean("weekView", true)
        scrollOffset = savedInstanceState?.getInt("scroll") ?: getPreferences(MODE_PRIVATE).getInt("scroll", 0)
        replacement = savedInstanceState?.getString("replacement")
        activeTerm = savedInstanceState?.getString("term") ?: getPreferences(MODE_PRIVATE).getString("term", null)
        profiles = TermScheduleProfileStore(this)
        source = ScheduleSource(ScheduleStores.create(this, profiles), profileResolver = profiles::get)
        profiles.editable = { source.ready && !source.readOnly && !source.applyingSnapshot && !isDestroyed }
        sharedSync = SharedScheduleController(this, source).also { it.changed = { render() } }
        editors = PhoneEditors(this, source) { !busy }
        root = scheduleColumn().apply { setPadding(dp(12), dp(8), dp(12), dp(8)) }
        setContentView(root)
        val dark = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        androidx.core.view.WindowInsetsControllerCompat(window, root).isAppearanceLightStatusBars = !dark
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(dp(12) + bars.left, dp(8) + bars.top, dp(12) + bars.right, dp(8) + bars.bottom)
            insets
        }
        source.subscribe(listener)
        render()
    }
    override fun onResume() { super.onResume(); resumeCheckPending = true; if (::root.isInitialized) { render(); checkOnResume() } }
    private fun checkOnResume() {
        if (resumeCheckPending && source.ready && ::sharedSync.isInitialized && source.state.sync != null && !sharedSync.busy) {
            resumeCheckPending = false; sharedSync.sync()
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("date", selected.toString()); outState.putString("replacement", replacement)
        outState.putBoolean("weekView", weekView); outState.putInt("scroll", scroll?.scrollY ?: scrollOffset)
        outState.putString("term", activeTerm); super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        sharedDialog?.dismiss(); sharedSync.close()
        source.unsubscribe(listener); editors.close(); source.close(); worker.shutdown()
        super.onDestroy()
    }
    private fun monday() = selected.minusDays((selected.dayOfWeek.value - 1).toLong())
    private fun row(parent: LinearLayout = root) = LinearLayout(this).also { parent.addView(it) }
    private fun action(row: LinearLayout, label: String, action: () -> Unit) {
        row.addView(scheduleButton(label, action).apply { minWidth = 0; minimumWidth = 0; minHeight = dp(36); minimumHeight = 0; textSize = 12f; setPadding(0, 0, 0, 0); isEnabled = source.ready && !busy && !source.applyingSnapshot },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }
    private fun render() {
        val key = "${monday()}:$weekView:${if (weekView) "" else selected}"
        if (renderedKey == key) scrollOffset = scroll?.scrollY ?: scrollOffset
        else if (renderedKey.isNotEmpty()) scrollOffset = 0
        renderedKey = key
        root.removeAllViews()
        if (source.state.sync != null && activeTerm !in source.state.profiles) activeTerm = source.state.sources.firstNotNullOfOrNull { it.resolvedTermKey() } ?: source.state.profiles.keys.firstOrNull()
        val profile = activeTerm?.let(profiles::get)
        if (resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE) root.addView(scheduleLabel(if (source.state.sync != null) "课程表 · 双向共享" else "课程表 · 离线", 24f))
        if (::sharedSync.isInitialized) {
            val stale = sharedSync.settings.lastSuccess.let { it > 0 && System.currentTimeMillis() - it > 86_400_000L }
            root.addView(scheduleLabel(sharedSync.status + if (stale) " · 数据过期，显示缓存" else "", 14f))
        }
        val week = profile?.let { ChronoUnit.WEEKS.between(it.week1Monday, monday()) + 1 }
        root.addView(scheduleLabel("${monday()} — ${monday().plusDays(6)} · " + if (week == null) "未选择学期" else "${profile.termKey} · 第 $week 教学周", 16f))
        row().also { bar ->
            action(bar, "‹ 上周") { selected = selected.minusWeeks(1); render() }
            action(bar, "今天") { selected = source.clock.today(); render() }
            action(bar, "下周 ›") { selected = selected.plusWeeks(1); render() }
            action(bar, "周视图") { weekView = true; render() }
            action(bar, "日视图") { weekView = false; render() }
        }
        val days = row().apply { if (weekView) setPadding(dp(32), 0, 0, 0) }
        val labels = listOf("一", "二", "三", "四", "五", "六", "日")
        repeat(7) { i ->
            val date = monday().plusDays(i.toLong())
            days.addView(scheduleButton("${labels[i]}\n${date.dayOfMonth}") { selected = date; weekView = false; render() }.apply {
                textSize = 12f; minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0; includeFontPadding = false; setPadding(0, 0, 0, 0)
                isSelected = date == selected
                if (date == selected) {
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.rgb(55, 85, 155))
                    setTextColor(android.graphics.Color.WHITE)
                }
                if (date == source.clock.today()) text = "●${labels[i]}\n${date.dayOfMonth}"
                contentDescription = "$date${if (date == source.clock.today()) " 今天" else ""}${if (date == selected) " 已选择" else ""}"
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        row().also { bar ->
            if (!source.readOnly) action(bar, "＋ 课程") { editors.entry() }
            if (!source.readOnly) action(bar, "日期调整") { editors.dayAdjustment(selected) }
            action(bar, "管理") { management() }
        }
        val list = scheduleColumn()
        scroll = ScrollView(this).apply { addView(list); isFillViewport = true }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        if (!source.ready) { list.addView(scheduleLabel(if (source.error) "读取失败，请重新打开应用。原有数据未清除。" else "正在读取课程…", 16f)); return }
        if (source.error || source.state.invalidRecords > 0) list.addView(scheduleLabel("存储异常或存在无效记录，请检查。更改可能尚未保存。", 16f))
        if (busy) list.addView(scheduleLabel("正在处理文件…", 16f))
        val projected = ScheduleProjection.week(source.state, monday(), source.clock.zone())
        if (weekView) {
            list.addView(PhoneWeekView(this, projected, monday(), source.clock.zone(), editors::classDetail))
            scroll?.post { scroll?.scrollTo(0, scrollOffset) }
            return
        }
        scroll?.post { scroll?.scrollTo(0, scrollOffset) }
        val adjustment = source.state.daysByDate[selected.toString()]
        list.addView(scheduleLabel("$selected${adjustment?.let { " · ${if (it.mode == "none") "无课" else "使用星期 ${it.weekday} 课表"}" }.orEmpty()}", 16f))
        val classes = projected.filter { it.start < selected.plusDays(1).atStartOfDay(source.clock.zone()).toInstant() && it.end > selected.atStartOfDay(source.clock.zone()).toInstant() }
        if (classes.isEmpty()) list.addView(scheduleLabel(if (source.readOnly) "今天没有课程。当前显示已缓存的共享课表；请在 X08E 修改并发布。" else "今天没有课程。可新增本地课程或从管理中导入 ICS / 湖北大学 CSV。", 18f))
        classes.forEach { item ->
            list.addView(scheduleButton(buildString {
                append(item.entry.title).append("\n")
                append(scheduleTime(item.start)).append(" – ").append(scheduleTime(item.end))
                item.periodLabel?.let { append(" · ").append(it) }
                if (item.entry.location.isNotBlank()) append("\n").append(item.entry.location)
                if (item.teacher.isNotBlank()) append(" · ").append(item.teacher)
                if (item.allWeeksEdited || item.thisWeekEdited) append("\n已应用本地修改")
            }) { editors.classDetail(item) }.apply { gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)) })
        }
    }
    private fun management() {
        if (source.applyingSnapshot) return
        AlertDialog.Builder(this).setTitle("本地数据管理").setItems(arrayOf("导入 ICS / 湖北大学 CSV", "导入来源（替换 / 删除）", "学期、教学周与节次", "WebDAV 共享课表（双向同步）")) { _, i ->
            when (i) { 0 -> chooseFile(null); 1 -> sources(); 2 -> termList(); 3 -> { sharedDialog = SharedScheduleDialog.show(this, source, sharedSync, false) } }
        }.setNegativeButton("关闭", null).show()
    }
    private fun chooseFile(target: String?) { replacement = target; picker.launch(arrayOf("*/*")) }
    private fun sources() {
        val items = source.state.sources
        if (items.isEmpty()) { message("还没有导入来源。"); return }
        AlertDialog.Builder(this).setTitle("导入来源").setItems(items.map { "${it.displayName} · ${it.occurrenceCount} 节" }.toTypedArray()) { _, i ->
            val item = items[i]
            AlertDialog.Builder(this).setTitle(item.displayName).setMessage("${item.filename}\n${item.timezones}\n替换会保留仍能匹配的本地修改。删除会清理该来源的关联修改。")
                .setPositiveButton("替换") { _, _ -> chooseFile(item.id) }
                .setNeutralButton("删除") { _, _ ->
                    AlertDialog.Builder(this).setMessage("删除 ${item.displayName} 及关联修改？").setPositiveButton("删除") { _, _ ->
                        source.deleteImport(item.id) { it.onFailure { e -> message(e.message.orEmpty()) } }
                    }.setNegativeButton("取消", null).show()
                }.setNegativeButton("取消", null).show()
        }.setNegativeButton("关闭", null).show()
    }
    private fun handleParsed(outcome: TimetableImporter.ParseOutcome, target: String?) {
        when (outcome) {
            is TimetableImporter.ParseOutcome.Ready -> preview(outcome.preview, target)
            is TimetableImporter.ParseOutcome.NeedsProfile -> editProfile(outcome.structure.termKey) { profile ->
                background({ resolveHubeiCsv(outcome.structure, profile, outcome.filename, outcome.kind, outcome.locator, outcome.rawDigest) }) { preview(it, target) }
            }
        }
    }
    private fun preview(preview: ImportPreview, target: String?) {
        val fields = scheduleColumn()
        val name = EditText(this).apply { hint = "来源名称"; setText(target?.let { id -> source.state.sources.firstOrNull { it.id == id }?.displayName } ?: preview.calendarName ?: preview.filename) }
        fields.addView(name)
        fields.addView(scheduleLabel(buildString {
            append("${preview.formatLabel ?: "ICS"}\n${preview.seriesCount} 个课程系列 · ${preview.occurrences.size} 个实例\n")
            append("${Instant.ofEpochMilli(preview.firstStart).atZone(source.clock.zone()).toLocalDate()} — ${Instant.ofEpochMilli(preview.lastEnd).atZone(source.clock.zone()).toLocalDate()}\n${preview.timezones}\n")
            preview.week1Monday?.let { append("第一周周一：$it\n${preview.phaseSummary}\n") }
            append(preview.warnings.joinToString("\n"))
        }, 16f))
        preview.occurrences.take(8).forEach { fields.addView(scheduleLabel("${it.title} · ${Instant.ofEpochMilli(it.start).atZone(source.clock.zone())} · ${it.location}", 14f)) }
        val dialog = AlertDialog.Builder(this).setTitle(if (target == null) "导入预览" else "替换预览")
            .setView(ScrollView(this).apply { addView(fields) }).setNegativeButton("取消", null).setPositiveButton("确认导入", null).create()
        dialog.show()
        dialog.getButton(-1).setOnClickListener {
            if (name.text.isBlank()) { name.error = "请输入来源名称"; return@setOnClickListener }
            dialog.getButton(-1).isEnabled = false
            source.import(preview, name.text.toString(), target) { result ->
                if (!isDestroyed) result.onSuccess { commit ->
                    preview.termKey?.let { selectTerm(it) }
                    selected = Instant.ofEpochMilli(commit.source.firstStart).atZone(source.clock.zone()).toLocalDate()
                    dialog.dismiss(); render()
                }.onFailure { dialog.getButton(-1).isEnabled = true; message(it.message.orEmpty()) }
            }
        }
    }
    private fun selectTerm(key: String) { activeTerm = key; getPreferences(MODE_PRIVATE).edit().putString("term", key).apply() }
    private fun termList() {
        val items = profiles.list()
        AlertDialog.Builder(this).setTitle("学期配置（选择以显示教学周）")
            .setItems((items.map { it.termKey } + "＋ 新学期").toTypedArray()) { _, i ->
                if (i == items.size) editProfile("") else {
                    val p = items[i]; selectTerm(p.termKey); render()
                    AlertDialog.Builder(this).setTitle(p.termKey).setMessage("第一周周一：${p.week1Monday}\n${p.timezone}\n\n${TermProfileForm.phasesToText(p)}")
                        .setPositiveButton("编辑") { _, _ -> editProfile(p.termKey) }
                        .setNeutralButton("删除 / 恢复预设") { _, _ ->
                            runCatching { profiles.remove(p.termKey); source.refreshProfiles() }
                                .onFailure { message(it.message ?: "无法删除学期，请先检查关联来源。") }
                            render()
                        }.setNegativeButton("关闭", null).show()
                }
            }.setNegativeButton("关闭", null).show()
    }
    private fun editProfile(key: String, done: ((TermScheduleProfile) -> Unit)? = null) {
        val existing = profiles.get(key)
        val fields = scheduleColumn()
        fun input(label: String, value: String, multiline: Boolean = false) = EditText(this).apply {
            hint = label; contentDescription = label; setText(value); minHeight = dp(48)
            if (multiline) { minLines = 6; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE }
            fields.addView(this)
        }
        fields.addView(scheduleLabel("填写学期、时区和第一周周一。阶段格式：日期一行，随后每行填写“节次 开始时间 结束时间”，可添加多个日期阶段。", 16f))
        val term = input("学期（如 2026-2027-1）", key).apply { isEnabled = key.isEmpty() }
        val zone = input("时区", existing?.timezone ?: "Asia/Shanghai")
        val monday = input("第一周周一 yyyy-MM-dd", existing?.week1Monday?.toString().orEmpty())
        val phases = input("分阶段节次", existing?.let(TermProfileForm::phasesToText) ?: "2026-08-31\n1 08:00 08:45\n2 08:55 09:40", true)
        val error = scheduleLabel("", 16f); fields.addView(error)
        val dialog = AlertDialog.Builder(this).setTitle("学期与节次").setView(ScrollView(this).apply { addView(fields) })
            .setNegativeButton("取消", null).setPositiveButton("保存", null).create()
        dialog.show()
        dialog.getButton(-1).setOnClickListener {
            runCatching {
                val p = TermProfileForm.parseProfileForm(term.text.toString(), zone.text.toString(), monday.text.toString(), phases.text.toString())
                profiles.put(p); source.refreshProfiles(); selectTerm(p.termKey); p
            }.onSuccess { dialog.dismiss(); render(); done?.invoke(it) }.onFailure { error.text = it.message }
        }
    }
    private fun message(text: String) { if (!isDestroyed) AlertDialog.Builder(this).setMessage(text).setPositiveButton("确定", null).show() }
    private fun <T> background(task: () -> T, done: (T) -> Unit) {
        if (busy) return
        busy = true; render()
        worker.execute {
            val result = runCatching(task)
            runOnUiThread { if (!isDestroyed) { busy = false; render(); result.onSuccess(done).onFailure { message(it.message ?: "处理失败，原有课程未改变。") } } }
        }
    }
}
