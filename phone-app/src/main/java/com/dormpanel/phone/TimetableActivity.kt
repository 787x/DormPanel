package com.dormpanel.phone

import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.dormpanel.app.schedule.*
import java.time.*
import java.time.temporal.ChronoUnit
import java.util.concurrent.Executors

/** Offline UI. The shared source owns all effective state and serialized database writes. */
class TimetableActivity : AppCompatActivity() {
    private val requestNoticePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            // Retain options for recovery after a later system permission grant.
            if (!isDestroyed) message("通知权限未开启，课程通知暂不显示；可在系统设置中授权。")
        }
        CourseNotificationCoordinator.request(this)
    }
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
    private var periodMode = false
    private var weekdaysOnly = false
    private var swipeDates = false
    private var swipeContent: TimetableSwipeLayout? = null
    private var currentPage: PhonePage? = null
    private fun moveDate(direction: Int) {
        selected = if (weekView) selected.plusWeeks(direction.toLong()) else selected.plusDays(direction.toLong())
        render()
    }
    private var weekAnchor = 480f
    private var weekGrid: PhoneWeekView? = null
    private var renderedWeek = true
    private var renderedDay = 0
    private fun captureScroll() {
        if (restoringScroll) return
        if (renderedWeek) weekGrid?.let { weekAnchor = it.minuteAt((scroll?.scrollY ?: 0)-it.top, renderedDay) }
        else scrollOffset = scroll?.scrollY ?: scrollOffset
    }
    private fun saveDisplayPreferences() {
        getPreferences(MODE_PRIVATE).edit().putBoolean("periodMode",periodMode).putBoolean("weekdaysOnly",weekdaysOnly)
            .putBoolean("swipeDates",swipeDates).apply()
    }
    private var restoringScroll = true
    private fun restoreScroll() {
        val view = scroll ?: return
        view.doOnLayout {
            if (scroll === view) {
                val offset = if (weekView) weekGrid?.let { it.top+it.offsetAt(weekAnchor,selected.dayOfWeek.value-1) } ?: 0 else scrollOffset
                view.scrollTo(0,offset)
                restoringScroll = false
            }
        }
    }
    override fun onPause() {
        swipeContent?.cancelMotion()
        captureScroll()
        getPreferences(MODE_PRIVATE).edit().putString("date", selected.toString()).putBoolean("weekView", weekView)
            .putInt("scroll", scrollOffset).putFloat("weekAnchor",weekAnchor).apply()
        super.onPause()
    }
    private var activeTerm: String? = null
    private var sharedStatusView: TextView? = null
    private var sharedRefreshButton: Button? = null
    private var replacement: String? = null
    internal var busy = false; private set
    private val worker = Executors.newSingleThreadExecutor()
    private val listener: () -> Unit = {
        render()
        if (source.retryingLocalRead) {
            if (source.ready && !source.error) resumeCheckPending = false
        } else checkOnResume()
    }
    class IcsRequest : androidx.lifecycle.ViewModel() {
        var bytes: ByteArray? = null
        var choosing = false
        @Volatile var writing = false; private set
        val outcome = androidx.lifecycle.MutableLiveData<String?>()
        private val executor = Executors.newSingleThreadExecutor()
        fun write(resolver: android.content.ContentResolver, uri: android.net.Uri, captured: ByteArray) {
            if (writing) return
            writing = true
            executor.execute {
                val result = runCatching {
                    try {
                        (resolver.openOutputStream(uri,"wt") ?: error("无法打开目标文件")).use { it.write(captured); it.flush() }
                    } catch (e: Exception) {
                        runCatching { android.provider.DocumentsContract.deleteDocument(resolver,uri) }
                        throw IllegalStateException("文件保存失败；已尝试删除不完整文件，请核对目标位置。${e.message}",e)
                    }
                }
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    writing = false
                    outcome.value = result.fold({ "ICS 文件已保存。" }, { it.message ?: "文件保存失败" })
                }
            }
        }
        override fun onCleared() { executor.shutdown() }
    }
    private var shownIcsOutcome: String? = null
    private var icsOutcomeDialog: AlertDialog? = null
    private val icsRequest by lazy { androidx.lifecycle.ViewModelProvider(this)[IcsRequest::class.java] }
    private var pendingIcs: ByteArray?
        get() = icsRequest.bytes
        set(value) { icsRequest.bytes=value }
    private val saveIcs = registerForActivityResult(ActivityResultContracts.CreateDocument("text/calendar")) { uri ->
        val bytes = pendingIcs
        icsRequest.choosing=false
        resumeCheckPending=false
        pendingIcs = null
        if (uri != null) {
            if (bytes == null) {
                message("导出请求已失效，未写入文件。请重新预览后保存，并检查选择器是否创建了空文件。")
            } else icsRequest.write(applicationContext.contentResolver,uri,bytes)
        }
    }
    private fun exportCalendar() {
        if (icsRequest.writing || icsRequest.choosing || icsRequest.outcome.value != null) { message("请先完成当前文件保存。"); return }
        if (!source.ready || source.error || source.applyingSnapshot) { message("课表尚未成功读取，请稍后重试。"); return }
        val layout=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(8),dp(20),dp(8)) }
        val from=EditText(this).apply { hint="开始日期 YYYY-MM-DD"; setText(source.clock.today().toString()) }
        val until=EditText(this).apply { hint="结束日期 YYYY-MM-DD"; setText(source.clock.today().plusDays(27).toString()) }
        val zone=EditText(this).apply { hint="IANA 时区"; setText(source.clock.zone().id) }
        val events=CheckBox(this).apply { text="包含日历事件" }
        layout.addView(TextView(this).apply { text="开始日期（含）" }); layout.addView(from)
        layout.addView(TextView(this).apply { text="结束日期（含）" }); layout.addView(until)
        layout.addView(TextView(this).apply { text="IANA 时区" }); layout.addView(zone); layout.addView(events)
        val exportOptions = mutableListOf<android.view.View>(from,until,zone,events)
        listOf("本周" to 6L,"未来四周" to 27L).forEach { (label,days) -> layout.addView(Button(this).apply { exportOptions.add(this); text=label; setOnClickListener { val today=source.clock.today();val first=if(days==6L) today.minusDays((today.dayOfWeek.value-1).toLong()) else today;from.setText(first.toString());until.setText(first.plusDays(days).toString()) } }) }
        val dialog=AlertDialog.Builder(this).setTitle("导出 ICS（最多 366 天）").setView(ScrollView(this).apply { addView(layout) }).setNegativeButton("取消",null).setPositiveButton("预览",null).create()
        var previewEpoch = 0
        var previewRunning = false
        fun processing(running: Boolean) {
            previewRunning = running
            exportOptions.forEach { it.isEnabled = !running }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                isEnabled = !running
                text = if (running) "正在生成预览…" else "预览"
            }
        }
        dialog.setOnDismissListener { ++previewEpoch }
        fun validPreview(ticket: Int) = ticket == previewEpoch && dialog.isShowing && !isDestroyed && !isFinishing
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (previewRunning) return@setOnClickListener
            if(!source.ready || source.error || source.applyingSnapshot) { message("课表尚未成功读取"); return@setOnClickListener }
            processing(true)
            val start=from.text.toString();val end=until.text.toString();val z=zone.text.toString();val include=events.isChecked
            val ticket = ++previewEpoch
            source.readExportSnapshot { captured -> runOnUiThread {
                if (!validPreview(ticket)) return@runOnUiThread
                captured.onFailure { processing(false); message(it.message ?: "课表读取失败") }.onSuccess { snapshot ->
                    val generatedAt = source.clock.instant()
                    worker.execute {
                        val generated = runCatching { IcsScheduleExporter.export(snapshot,LocalDate.parse(start),LocalDate.parse(end),ZoneId.of(z),include,generatedAt) }
                        runOnUiThread {
                            if (validPreview(ticket)) generated.onFailure { processing(false); message(it.message ?: "导出失败") }.onSuccess { result ->
                dialog.dismiss()
                AlertDialog.Builder(this).setTitle("导出预览").setMessage("$start 至 $end\n时区 $z\n课程 ${result.courseCount} · 日历事件 ${result.eventCount}\n${result.bytes.size} 字节\n基于本机已保存课表的此预览快照，不代表其他设备已接收。\n用于外部日历交换，不保留课程分组、颜色、来源及同步历史，不是完整备份。超过 256 事件或 1 MiB 时可能无法重新导入当前 DormPanel。")
                    .setNegativeButton("取消",null).setPositiveButton("保存文件") { _,_ -> pendingIcs=result.bytes;icsRequest.choosing=true;saveIcs.launch("DormPanel-$start-$end.ics") }.show()
                            }
                        }
                    }
                }
            } }

        } }; dialog.show()
    }
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
        icsRequest.choosing = icsRequest.choosing || savedInstanceState?.getBoolean("icsChoosing",false)==true
        selected = savedInstanceState?.getString("date")?.let(LocalDate::parse) ?: getPreferences(MODE_PRIVATE).getString("date", null)?.let(LocalDate::parse) ?: LocalDate.now()
        if (intent?.action == "com.dormpanel.phone.OPEN_TODAY") selected = LocalDate.now()
        weekView = savedInstanceState?.getBoolean("weekView") ?: getPreferences(MODE_PRIVATE).getBoolean("weekView", true)
        scrollOffset = savedInstanceState?.getInt("scroll") ?: getPreferences(MODE_PRIVATE).getInt("scroll", 0)
        if (savedInstanceState==null) applyWidgetNavigation(intent)
        weekAnchor = savedInstanceState?.getFloat("weekAnchor") ?: getPreferences(MODE_PRIVATE).getFloat("weekAnchor",480f)
        periodMode = getPreferences(MODE_PRIVATE).getBoolean("periodMode",false)
        weekdaysOnly = getPreferences(MODE_PRIVATE).getBoolean("weekdaysOnly",false)
        swipeDates = getPreferences(MODE_PRIVATE).getBoolean("swipeDates",false)
        replacement = savedInstanceState?.getString("replacement")
        activeTerm = savedInstanceState?.getString("term") ?: getPreferences(MODE_PRIVATE).getString("term", null)
        profiles = TermScheduleProfileStore(this)
        source = ScheduleSource(ScheduleStores.create(this, profiles), profileResolver = profiles::get)
        profiles.editable = { source.editable && !isDestroyed }
        sharedSync = SharedScheduleController(this, source).also { it.changed = { updateSharedStatus() } }
        editors = PhoneEditors(this, source) { !busy }
        editors.restoreDraft(lastCustomNonConfigurationInstance as? PhoneEditors.CourseDraft)
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
        icsRequest.outcome.observe(this) { result ->
            if (result != null && shownIcsOutcome != result) {
                shownIcsOutcome = result
                icsOutcomeDialog = AlertDialog.Builder(this).setMessage(result).setCancelable(false).setPositiveButton("确定") { _,_ ->
                    icsRequest.outcome.value = null; shownIcsOutcome = null
                }.show()
            }
        }
    }
    private fun applyWidgetNavigation(intent: android.content.Intent?) {
        if (intent?.action != "com.dormpanel.phone.OPEN_COLLECTION") return
        selected = runCatching { LocalDate.parse(intent.getStringExtra("widgetDate")) }.getOrNull() ?: LocalDate.now()
        weekView = intent.getBooleanExtra("widgetWeek",false)
    }
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == "com.dormpanel.phone.OPEN_TODAY") { selected = LocalDate.now(); render() }
        if (intent.action == "com.dormpanel.phone.OPEN_COLLECTION") { applyWidgetNavigation(intent); render() }
    }
    private val clockChanges = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) { render() }
    }
    override fun onStart() {
        super.onStart()
        androidx.core.content.ContextCompat.registerReceiver(this,clockChanges,android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_DATE_CHANGED)
            addAction(android.content.Intent.ACTION_TIME_CHANGED)
            addAction(android.content.Intent.ACTION_TIMEZONE_CHANGED)
        },androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        weekGrid?.setClockActive(true)
    }
    override fun onStop() {
        weekGrid?.setClockActive(false)
        unregisterReceiver(clockChanges)
        super.onStop()
    }
    override fun onResume() { super.onResume(); CourseNotificationCoordinator.request(this); resumeCheckPending = true; if (::root.isInitialized) { render(); checkOnResume() } }
    private fun checkOnResume() {
        if (resumeCheckPending && !icsRequest.choosing && !icsRequest.writing && source.ready && !source.error && !source.retryingLocalRead && ::sharedSync.isInitialized && source.state.sync != null && !sharedSync.busy) {
            resumeCheckPending = false; sharedSync.sync()
        }
    }
    override fun onRetainCustomNonConfigurationInstance(): Any? = if(::editors.isInitialized) editors.captureDraft() else null
    override fun onSaveInstanceState(outState: Bundle) {
        captureScroll()
        outState.putBoolean("icsChoosing",icsRequest.choosing)
        outState.putFloat("weekAnchor",weekAnchor)
        outState.putString("date", selected.toString()); outState.putString("replacement", replacement)
        outState.putBoolean("weekView", weekView); outState.putInt("scroll", scrollOffset)
        outState.putString("term", activeTerm); super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        icsOutcomeDialog?.dismiss(); icsOutcomeDialog = null
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
    private fun render() = renderPage(null)
    private fun renderPage(promoted: PhonePage?) {
        currentPage?.active = false
        swipeContent?.cancelMotion()
        swipeContent = null
        captureScroll()
        restoringScroll = true
        weekGrid = null
        renderedWeek = weekView; renderedDay = selected.dayOfWeek.value-1
        root.removeAllViews()
        sharedStatusView = null
        sharedRefreshButton = null
        if (source.state.sync != null && activeTerm !in source.state.profiles) activeTerm = source.state.sources.firstNotNullOfOrNull { it.resolvedTermKey() } ?: source.state.profiles.keys.firstOrNull()
        // A failed load must render cached data without querying the unreadable catalog again.
        val profile = if (source.error) activeTerm?.let(source.state.profiles::get) else activeTerm?.let(profiles::get)
        val dark = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        root.addView(scheduleLabel("",12f).apply {
            sharedStatusView = this
            setOnClickListener { sharedDialog = SharedScheduleDialog.show(this@TimetableActivity,source,sharedSync,false) }
        })
        updateSharedStatus()
        // Above either view and the first-load early return, so failure never hides retry.
        if (source.error) {
            root.addView(scheduleLabel(if (source.retryingLocalRead && source.applyingSnapshot) "正在重新读取…已有数据保留。" else source.editBlockReason.orEmpty(), 16f))
            root.addView(scheduleButton("重新读取本机课表") { source.retryLocalRead() }.apply {
                isEnabled = !source.applyingSnapshot
            })
        }
        val week = profile?.let { ChronoUnit.WEEKS.between(it.week1Monday, monday()) + 1 }
        val heading = row()
        heading.addView(scheduleLabel("${if (week == null) "课程表" else "第 $week 教学周"} · ${monday().monthValue}/${monday().dayOfMonth}–${monday().plusDays(6).monthValue}/${monday().plusDays(6).dayOfMonth}",16f).apply {
            tag = "week-range"; maxLines = if (landscape && resources.configuration.fontScale <= 1.3f) 1 else 2
            if (landscape) { textSize = 12f; ellipsize = android.text.TextUtils.TruncateAt.END }
            contentDescription = "${profile?.termKey.orEmpty()} · ${monday()} — ${monday().plusDays(6)}"
        }, if (landscape) LinearLayout.LayoutParams(dp(180),-2) else LinearLayout.LayoutParams(0,-2,1f))
        heading.addView(scheduleButton("刷新") { sharedSync.sync() }.apply {
            tag = "refresh-sync"; contentDescription = "立即同步共享课表"
            minimumWidth = dp(48); minWidth = dp(48); textSize = 12f
            minHeight = dp(48); minimumHeight = dp(48); setPadding(dp(4),0,dp(4),0)
            sharedRefreshButton = this
        })
        updateSharedStatus()
        heading.addView(scheduleButton("管理") { management() }.apply { minimumWidth = dp(48); minWidth = dp(48); textSize = 12f; minHeight = dp(48); minimumHeight = dp(48); setPadding(dp(4),0,dp(4),0) })
        (if (landscape) heading else row()).also { bar ->
            val modeStart = bar.childCount
            action(bar,if (weekView) "‹ 上周" else "‹ 前一天") { moveDate(-1) }
            action(bar,"今天") { selected = source.clock.today(); render() }
            action(bar,if (weekView) "下周 ›" else "后一天 ›") { moveDate(1) }
            action(bar,"周视图") { weekView = true; render() }
            (bar.getChildAt(modeStart+3) as TextView).apply { isSelected = weekView; if (weekView) setTypeface(typeface,android.graphics.Typeface.BOLD); alpha = if (weekView) 1f else .6f }
            action(bar,"日视图") { weekView = false; render() }
            (bar.getChildAt(modeStart+4) as TextView).apply { isSelected = !weekView; if (!weekView) setTypeface(typeface,android.graphics.Typeface.BOLD); alpha = if (!weekView) 1f else .6f }
        }
        if (weekView) (if (landscape) heading else row()).also { bar ->
            action(bar,if (periodMode) "按节次 ▾" else "按时间 ▾") {
                AlertDialog.Builder(this).setTitle("周视图时间轴").setSingleChoiceItems(arrayOf("按实际时间","按节次 · 压缩课间"),if (periodMode) 1 else 0) { dialog,i ->
                    periodMode = i == 1; saveDisplayPreferences(); dialog.dismiss(); render()
                }.setNegativeButton("关闭",null).show()
            }
            action(bar,if (landscape) { if (weekdaysOnly) "五天 ↔" else "七天 ↔" } else if (weekdaysOnly) "五天 · 切换七天" else "七天 · 切换五天") { weekdaysOnly = !weekdaysOnly; saveDisplayPreferences(); render() }
            action(bar,if (landscape || source.readOnly) "更多" else "＋ / 更多") { courseActions() }
        } else (if (landscape) heading else row()).also { bar ->
            if (!source.readOnly) action(bar,"＋ 课程") { editors.entry() }
            if (!source.readOnly) action(bar,"日期调整") { editors.dayAdjustment(selected) }
        }
        val body = promoted ?: buildPage(selected, profile)
        val swipe = TimetableSwipeLayout(this) { direction ->
            val preview = swipeContent?.takePreview() as? PhonePage
            selected = if (weekView) selected.plusWeeks(direction.toLong()) else selected.plusDays(direction.toLong())
            renderPage(preview)
        }.apply {
            tag = "timetable-swipe"; swipeEnabled = swipeDates
            preparePreview = { direction ->
                captureScroll()
                buildPage(if (weekView) selected.plusWeeks(direction.toLong()) else selected.plusDays(direction.toLong()), profile)
            }
        }
        swipeContent = swipe
        currentPage = body
        scroll = body.scroller; weekGrid = body.grid
        body.active = true
        swipe.addView(body, FrameLayout.LayoutParams(-1,-1))
        root.addView(swipe, LinearLayout.LayoutParams(-1,0,1f))
        body.grid?.setClockActive(true)
        if (promoted == null) restoreScroll() else restoringScroll = false
    }
    /** No source/session mutations: a preview is the same layout with an inactive interaction gate. */
    private inner class PhonePage(val date: LocalDate) : LinearLayout(this@TimetableActivity) {
        var active = false
        lateinit var scroller: ScrollView
        var grid: PhoneWeekView? = null
        init { orientation = VERTICAL; tag = "phone-page-$date" }
    }
    private fun buildPage(date: LocalDate, profile: TermScheduleProfile?): PhonePage {
        val body = PhonePage(date)
        val pageMonday = date.minusDays((date.dayOfWeek.value-1).toLong())
        val dark = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val days = row(body).apply { if (weekView) setPadding(dp(32), 0, 0, 0) }
        val labels = listOf("一", "二", "三", "四", "五", "六", "日")
        repeat(if (weekView && weekdaysOnly) 5 else 7) { i ->
            val date = pageMonday.plusDays(i.toLong())
            days.addView(scheduleButton("${labels[i]}\n${date.dayOfMonth}") { if (body.active) { selected = date; weekView = false; render() } }.apply {
                textSize = 12f; minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0; includeFontPadding = false; setPadding(0, 0, 0, 0)
                isSelected = date == body.date
                if (if (weekView) date == source.clock.today() else date == body.date) {
                    backgroundTintList = android.content.res.ColorStateList.valueOf(if (dark) 0xff263e53.toInt() else 0xffdceafa.toInt())
                    setTextColor(if (dark) android.graphics.Color.WHITE else android.graphics.Color.rgb(30,55,80))
                }
                if (date == source.clock.today()) {
                    setTypeface(typeface,android.graphics.Typeface.BOLD)
                } else if (date == body.date) {
                    setTypeface(typeface,android.graphics.Typeface.BOLD)
                    if (weekView) text = "${labels[i]} ·\n${date.dayOfMonth}"
                }
                if (date == source.clock.today()) text = "●${labels[i]}\n${date.dayOfMonth}"
                if (source.state.daysByDate.containsKey(date.toString())) text = "$text *"
                contentDescription = "$date${if (date == source.clock.today()) " 今天" else ""}${if (date == body.date) " 已选择" else ""}"
            }, LinearLayout.LayoutParams(0, dp(maxOf(if (landscape) 40 else 48,(resources.configuration.fontScale*30).toInt()+8)), 1f))
        }
        val list = scheduleColumn()
        val pageScroll = ScrollView(this).apply { tag = "timetable-scroll"; addView(list); isFillViewport = true }
        body.scroller = pageScroll
        body.addView(pageScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val anchor = weekAnchor
        val dayOffset = scrollOffset
        pageScroll.doOnLayout {
            if (!body.active) pageScroll.scrollTo(0, if (weekView) body.grid?.let { it.top+it.offsetAt(anchor,date.dayOfWeek.value-1) } ?: 0 else dayOffset)
        }
        if (!source.ready) { if (!source.error) list.addView(scheduleLabel("正在读取课程…", 16f)); return body }
        if (!source.error && source.state.invalidRecords > 0) list.addView(scheduleLabel("存在无效记录，请检查。", 16f))
        if (busy) list.addView(scheduleLabel("正在处理文件…", 16f))
        val projected = ScheduleProjection.week(source.state, pageMonday, source.clock.zone())
        if (weekView) {
            val effectiveProfile = profile ?: projected.mapNotNull { ScheduleProjection.profile(source.state,it) }.distinct().singleOrNull()
            val grid = PhoneWeekView(this,projected,pageMonday,source.clock.zone(),{ if (body.active) editors.classDetail(it) },
                if (weekdaysOnly) 5 else 7,periodMode,effectiveProfile, { body.active }, CourseColors.snapshot(this,source.state)) { ScheduleProjection.profile(source.state,it) }
            body.grid = grid
            grid.setClockActive(false)
            if (periodMode && !grid.usesPeriods) {
                // Keep the grid origin aligned with scroll anchors: explanation lives above the scroller.
                val note = scheduleLabel("当前按时间显示：节次模式需要本周各日作息一致、有效且无重叠，起止为整分钟，时区与设备一致。点管理选择学期。",12f)
                body.addView(note,body.indexOfChild(pageScroll))
            }
            list.addView(grid)
            return body
        }
        val adjustment = source.state.daysByDate[date.toString()]
        list.addView(scheduleLabel("$date${adjustment?.let { " · ${if (it.mode == "none") "无课" else "使用星期 ${it.weekday} 课表"}" }.orEmpty()}", 16f))
        val classes = projected.filter { it.start < date.plusDays(1).atStartOfDay(source.clock.zone()).toInstant() && it.end > date.atStartOfDay(source.clock.zone()).toInstant() }
        if (classes.isEmpty() && !source.error) list.addView(scheduleLabel(if (source.readOnly) source.editBlockReason ?: "课表正在验证或受迁移保护；本地数据保留，请从共享课表入口继续恢复。" else "今天没有课程。可新增本地课程或从管理中导入 ICS / 湖北大学 CSV、XLS。", 18f))
        val colors = if (!source.error) CourseColors.snapshot(this,source.state) else null
        classes.forEach { item ->
            list.addView(scheduleButton(buildString {
                append(item.entry.title).append("\n")
                append(scheduleTime(item.start)).append(" – ").append(scheduleTime(item.end))
                CoursePresentation.currentPeriods(item,ScheduleProjection.profile(source.state,item))?.let { append(" · ").append(it) }
                if (item.entry.location.isNotBlank()) append("\n").append(item.entry.location)
                if (item.teacher.isNotBlank()) append(" · ").append(item.teacher)
                if (item.allWeeksEdited || item.thisWeekEdited) append("\n已应用本地修改")
            }) { if (body.active) editors.classDetail(item) }.apply { backgroundTintList = android.content.res.ColorStateList.valueOf(CourseColors.background(colors?.rgb(item) ?: CourseColors.palette[0], resources.configuration.uiMode and 48 == 32)); setTextColor(CourseColors.ink(resources.configuration.uiMode and 48 == 32)); gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)) })
        }
        return body
    }
    private fun updateSharedStatus() {
        val p = sharedSync.presentation
        sharedRefreshButton?.apply {
            isEnabled = SharedAction.SYNC in p.actions && !sharedSync.busy && source.ready && !source.applyingSnapshot && !source.error && !busy
            text = if(sharedSync.busy) "同步中" else "刷新"
        }
        sharedStatusView?.apply {
            text = p.summary()
            visibility = if (p.conflict || p.recovery || p.transition || p.confirmationRejected ||
                (p.ready && p.mode == SharedMode.UNKNOWN) || (p.bound && (p.result !in setOf("UNCHECKED","OBSERVED","PUBLISHED","VERIFIED") && !p.busy || p.confirming || p.receipting))) android.view.View.VISIBLE else android.view.View.GONE
        }
    }
    private fun courseActions() {
        val labels = if (source.readOnly) arrayOf("管理") else arrayOf("＋ 课程","日期调整","管理")
        AlertDialog.Builder(this).setItems(labels) { _,i -> when(labels[i]) {
            "＋ 课程" -> editors.entry(); "日期调整" -> editors.dayAdjustment(selected); else -> management()
        } }.setNegativeButton("关闭",null).show()
    }
    private fun management() {
        if (source.applyingSnapshot) return
        swipeContent?.cancelMotion()
        AlertDialog.Builder(this).setTitle("本地数据管理").setItems(arrayOf("导入 ICS / 湖北大学 CSV、XLS", "导入来源（替换 / 删除）", "学期、教学周与节次", "WebDAV 共享课表（双向同步）", "课表显示设置", "课程通知", "课程管理", "导出 ICS")) { _, i ->
            if (i < 3 && source.error) { message(source.editBlockReason.orEmpty()); return@setItems }
            when (i) { 0 -> chooseFile(null); 1 -> sources(); 2 -> termList(); 3 -> { sharedDialog = SharedScheduleDialog.show(this, source, sharedSync, false) }; 4 -> displaySettings(); 5 -> noticeSettings(); 6 -> editors.courseManagement(); 7 -> exportCalendar() }
        }.setNegativeButton("关闭", null).show()
    }
    private fun displaySettings() {
        val toggle = CheckBox(this).apply {
            text = "横划切换日期"; isChecked = swipeDates; minHeight = dp(48)
            setPadding(dp(24),dp(12),dp(24),dp(12))
        }
        AlertDialog.Builder(this).setTitle("课表显示设置").setView(toggle)
            .setMessage("日视图切换前后一天，周视图切换前后一周。仅保存在本机。")
            .setPositiveButton("保存") { _,_ -> swipeDates = toggle.isChecked; saveDisplayPreferences(); render() }
            .setNegativeButton("取消",null).show()
    }
    private fun noticeSettings() {
        val original = CourseNoticeSettings.read(this)
        val labels = arrayOf("关闭（默认）", "全天显示当前 / 下一节课", "仅课前和上课中")
        val layout = scheduleColumn().apply { setPadding(dp(20), dp(12), dp(20), dp(8)) }
        val group = RadioGroup(this)
        var selected = original.mode.ordinal
        labels.forEachIndexed { i, label ->
            group.addView(RadioButton(this).apply {
                text = label
                id = android.view.View.generateViewId()
                minHeight = dp(44)
                setOnClickListener { selected = i }
            })
        }
        group.check(group.getChildAt(selected).id)
        layout.addView(group)
        layout.addView(scheduleLabel("课前开始显示", 14f))
        val lead = Spinner(this).apply {
            adapter = ArrayAdapter(this@TimetableActivity, android.R.layout.simple_spinner_dropdown_item,
                arrayOf("提前 5 分钟", "提前 15 分钟", "提前 30 分钟"))
            setSelection(listOf(5, 15, 30).indexOf(original.leadMinutes).coerceAtLeast(0))
        }
        layout.addView(lead)
        val privacy = CheckBox(this).apply {
            text = "锁屏隐藏课程详情"
            isChecked = original.hideOnLockscreen
        }
        layout.addView(privacy)
        layout.addView(scheduleLabel("通知仅使用本地课表，无需持续后台服务。省电或厂商限制可能让切换延迟。", 12f))

        // ---- P1: 主动跟踪当前课程（实验）----
        val tracking = CourseLiveTracking.read(this)
        val trackingActive = tracking?.active(Instant.now()) == true
        val effectiveTracking = tracking?.takeIf { trackingActive }
        val trackedTitle = effectiveTracking?.let { session ->
            source.state.let { state ->
                ScheduleProjection.occurrences(state, source.clock)
                    .firstOrNull { it.sourceId == session.sourceId && it.seriesId == session.seriesId &&
                        it.start.toEpochMilli() == session.start && it.end.toEpochMilli() == session.end }?.entry?.title
            } ?: "已结束或已调整的课程"
        }
        val trackingText = effectiveTracking?.let { session ->
            "正在跟踪：$trackedTitle · 预计 ${timeText(Instant.ofEpochMilli(session.end))} 结束\nAndroid 16+ 会尝试申请 Live Updates 提升，系统可能只显示普通通知。"
        } ?: "未在跟踪课程。可对正在进行的课手动开启实时跟踪。"
        layout.addView(scheduleLabel(trackingText, 13f))
        val trackButton = scheduleButton(if (trackingActive) "结束跟踪" else "跟踪当前课程（实验）") {
            // Read again at tap time so a stale label never drives the action.
            if (CourseLiveTracking.read(this)?.active(Instant.now()) == true) {
                CourseLiveTracking.stop(this)
                CourseNotificationCoordinator.request(this)
                message("已结束跟踪。")
            } else startTrackingCurrent()
        }
        layout.addView(trackButton)
        layout.addView(scheduleButton("查看通知能力诊断") { notificationDiagnostics() })

        AlertDialog.Builder(this).setTitle("课程通知").setView(ScrollView(this).apply { addView(layout) })
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val options = CourseNoticeOptions(
                    CourseNoticeMode.entries[selected],
                    listOf(5, 15, 30)[lead.selectedItemPosition],
                    privacy.isChecked
                )
                CourseNoticeSettings.write(this, options)
                // Turning notifications off must also end an explicitly tracked session.
                if (options.mode == CourseNoticeMode.OFF) CourseLiveTracking.stop(this)
                CourseNotificationCoordinator.request(this)
                if (options.mode != CourseNoticeMode.OFF &&
                    android.os.Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestNoticePermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            }.show()
    }

    private fun timeText(instant: Instant): String =
        java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(instant)

    /** Only an in-progress course from a successfully loaded local timetable can be tracked. */
    private fun startTrackingCurrent() {
        if (CourseNoticeSettings.read(this).mode == CourseNoticeMode.OFF) {
            message("课程通知当前为关闭状态。请先在“课程通知”中开启普通通知，再开始跟踪。")
            return
        }
        if (!source.ready || source.error || source.retryingLocalRead) {
            message("本地课表尚未成功读取，暂不能开始跟踪。")
            return
        }
        val now = source.clock.instant()
        val current = ScheduleProjection.occurrences(source.state, source.clock)
            .filter { it.start <= now && it.end > now }
            .minWithOrNull(compareBy<ClassOccurrence> { it.end }.thenBy { it.start }.thenBy { it.entry.id })
        if (current == null) {
            message("当前没有正在进行的课程。只能主动跟踪上课中的课程。")
            return
        }
        AlertDialog.Builder(this).setTitle("跟踪当前课程（实验）")
            .setMessage("开始跟踪「${current.entry.title}」，直到本节课结束。Android 16+ 将尝试申请 Live Updates 提升；系统可能只显示普通通知。课程表不是官方典型 Live Updates 场景，不保证获得提升。")
            .setPositiveButton("开始跟踪") { _, _ ->
                if (CourseLiveTracking.start(this, current, now)) {
                    CourseNotificationCoordinator.request(this)
                    if (android.os.Build.VERSION.SDK_INT >= 33 &&
                        checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        requestNoticePermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                } else message("课程已经结束，请刷新课表再试。")
            }.setNegativeButton("取消", null).show()
    }

    private fun notificationDiagnostics() {
        worker.execute {
            val report = runCatching { PhoneNotificationDiagnostics.report(applicationContext) }
                .getOrElse { "通知能力检查失败：${it.javaClass.simpleName}" }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                val dialog = AlertDialog.Builder(this).setTitle("手机通知能力诊断")
                    .setMessage(report)
                    .setPositiveButton("确定", null)
                    .setNeutralButton("系统通知设置") { _, _ ->
                        val intent = android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
                        runCatching { startActivity(intent) }
                    }
                if (android.os.Build.VERSION.SDK_INT >= 36) {
                    dialog.setNegativeButton("实时更新权限") { _, _ ->
                        val intent = android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
                        runCatching { startActivity(intent) }
                    }
                }
                dialog.show()
            }
        }
    }

    private fun chooseFile(target: String?) {
        source.editBlockReason?.let { message(it); return }
        if (!source.editable) return
        replacement = target; picker.launch(arrayOf("*/*"))
    }
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
            source.editBlockReason?.let { name.error = it; return@setOnClickListener }
            if (!source.editable) return@setOnClickListener
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
    internal var profileSaveStage = "idle"; private set
    private fun editProfile(key: String, done: ((TermScheduleProfile) -> Unit)? = null) {
        profileSaveStage = "editing"
        val existing = profiles.get(key)
        val fields = scheduleColumn()
        fun input(label: String, value: String, multiline: Boolean = false) = EditText(this).apply {
            hint = label; contentDescription = label; setText(value); minHeight = dp(48)
            if (multiline) {
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 6; maxLines = 6; isVerticalScrollBarEnabled = true
            }
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
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.show()
        dialog.getButton(-1).setOnClickListener {
            profileSaveStage = "clicked"
            runCatching {
                val p = TermProfileForm.parseProfileForm(term.text.toString(), zone.text.toString(), monday.text.toString(), phases.text.toString())
                profileSaveStage = "parsed"; profiles.put(p); profileSaveStage = "stored"; source.refreshProfiles(); selectTerm(p.termKey); p
            }.onSuccess { profileSaveStage = "callback"; dialog.dismiss(); render(); done?.invoke(it); profileSaveStage = "done" }.onFailure { profileSaveStage = "failed: ${it.message}"; error.text = it.message?.takeIf { message -> message.isNotBlank() } ?: "保存失败，请检查学期配置"; error.requestRectangleOnScreen(android.graphics.Rect(0,0,error.width,error.height), true) }
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
