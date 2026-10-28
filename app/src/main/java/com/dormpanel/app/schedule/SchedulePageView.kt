package com.dormpanel.app.schedule

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.core.view.doOnLayout
import androidx.core.view.doOnNextLayout
import androidx.core.view.doOnPreDraw
import com.dormpanel.app.appearance.*
import com.dormpanel.app.ui.PageInteraction
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

@SuppressLint("ViewConstructor")
class SchedulePageView(context: Context, private val source: ScheduleSource, private val session: ScheduleSession,
    private val appearance: AppearanceController, private val returnHome: () -> Unit,
    private val importTimetable: () -> Unit = {}, private val receiveTimetable: () -> Unit = {},
    private val manageTimetables: () -> Unit = {}, private val viewport: ScheduleViewport = ScheduleViewport()) : LinearLayout(context), PageInteraction, AppearanceAware {
    private val editors = ScheduleEditors(context, source, appearance)
    private val display = ScheduleDisplay(this, source, ::render)
    private var visible = false
    private val accents = mutableListOf<() -> Unit>()
    private val preferences = X08eSchedulePreferences(context)
    private var style = preferences.read()
    private val scrollOffsets get() = viewport.offsets
    private var grid: X08eWeekGrid? = null
    private var swipeContent: TimetableSwipeLayout? = null
    private var currentBody: TimetableBody? = null
    private var weekRange: TextView? = null
    private val displayCache=X08eDisplayCache()
    private var paintedTheme=appearance.state.themeMode
    private var settingsDialog: androidx.appcompat.app.AlertDialog? = null
    private fun tr(zh: String, en: String) = context.scheduleText(zh, en)
    private fun scroll(key: String, content: View, body: TimetableBody, initial: Int = 0, minuteAt: ((Int) -> Float)? = null) = ScrollView(context).apply {
        isFillViewport = false; addView(content)
        val saved = if (minuteAt != null) initial else scrollOffsets[key] ?: initial
        setOnScrollChangeListener { _: View, _: Int, y: Int, _: Int, _: Int ->
            if (body.active) { if (minuteAt != null) viewport.gridMinute = minuteAt(y) else scrollOffsets[key] = y }
        }
        body.captureViewport += {
            if (minuteAt != null) viewport.gridMinute = minuteAt(scrollY) else scrollOffsets[key] = scrollY
        }
        doOnLayout { scrollTo(0, saved.coerceAtMost((content.height - height).coerceAtLeast(0))) }
    }
    init { orientation = VERTICAL; setPadding(context.dp(20), context.dp(10), context.dp(20), context.dp(10)); render() }
    override fun shouldObservePageSwipe(event: MotionEvent) = false
    override fun onAttachedToWindow() { super.onAttachedToWindow(); display.visibility(isShown) }
    override fun onDetachedFromWindow() { displayCache.clear(); swipeContent?.cancelMotion(); display.visibility(false); settingsDialog?.dismiss(); editors.close(); super.onDetachedFromWindow() }
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); visible = isVisible; if(!isVisible) displayCache.clear(); display.visibility(isVisible) }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); if(visibility!=VISIBLE) displayCache.clear(); display.visibility(visible && visibility == VISIBLE) }
    private fun button(text: String, action: () -> Unit) = context.scheduleButton(text, action)
    private fun render() = renderPage(null)
    private fun renderPage(promoted: TimetableBody?) {
        weekRange=null
        if(promoted==null) displayCache.invalidate()
        currentBody?.active = false; currentBody = null
        swipeContent?.cancelMotion(); swipeContent = null
        grid?.setClockActive(false); grid = null
        removeAllViews(); accents.clear()
        val header = LinearLayout(context)
        header.addView(context.scheduleLabel(tr("日历与课表", "Schedule"), 26f), LayoutParams(0, context.dp(56), 1f))
        ScheduleMode.entries.forEach { mode ->
            val button = button(if (mode == ScheduleMode.CALENDAR) tr("日历", "Calendar") else tr("课表", "Timetable")) { session.mode = mode; render() }
            header.addView(button)
            accents += { if (session.mode == mode) button.setTextColor(PanelPalette.forMode(appearance.state.themeMode).accent) }
        }
        if (session.mode == ScheduleMode.TIMETABLE) header.addView(button(tr("显示设置", "Display")) {
            settingsDialog = context.scheduleStyleDialog(style, appearance) { next ->
                if (preferences.write(next)) { style = next; render(); true } else false
            }
        })
        header.addView(button(tr("今天", "Today")) { session.today(source.clock); render() })
        header.addView(button(tr("返回首页", "Home"), returnHome).apply {
            minWidth = context.dp(112); minimumWidth = context.dp(112)
            minHeight = context.dp(56); minimumHeight = context.dp(56)
            setPadding(context.dp(16),paddingTop,context.dp(16),paddingBottom)
        }, LayoutParams(LayoutParams.WRAP_CONTENT,context.dp(56)))
        addView(header)
        if (!source.ready || source.error || source.state.invalidRecords > 0) addView(context.scheduleLabel(
            if (source.error && source.retryingLocalRead && source.applyingSnapshot) "正在重新读取…已有数据保留。"
            else if (source.error) source.editBlockReason.orEmpty()
            else if (!source.ready) tr("正在读取本机课表…", "Loading local schedule…") else tr("部分无效记录已隐藏，原始记录保留。", "Some invalid stored records are hidden and preserved."), 16f))
        if (source.error) addView(button("重新读取本机课表") { source.retryLocalRead() }.apply {
            isEnabled = !source.applyingSnapshot
        })
        if (session.mode == ScheduleMode.CALENDAR) calendar() else timetable(promoted)
        paintAppearance(appearance.state, includeContent = false)
    }
    private fun calendar() {
        val body = LinearLayout(context)
        val month = context.scheduleColumn()
        val navigation = LinearLayout(context)
        navigation.addView(button("‹") { session.moveMonth(-1); render() }.apply { contentDescription = tr("上个月", "Previous month") })
        navigation.addView(context.scheduleLabel(session.month.format(DateTimeFormatter.ofPattern("LLLL yyyy", source.clock.locale())), 24f), LayoutParams(0, context.dp(56), 1f))
        navigation.addView(button("›") { session.moveMonth(1); render() }.apply { contentDescription = tr("下个月", "Next month") })
        month.addView(navigation)
        val weekdays = LinearLayout(context)
        ScheduleProjection.weekDays(source.clock.locale()).forEach { weekdays.addView(context.scheduleLabel(it.getDisplayName(TextStyle.SHORT, source.clock.locale()), 16f), LayoutParams(0, context.dp(40), 1f)) }
        month.addView(weekdays)
        ScheduleProjection.monthCells(session.month, source.clock.locale()).chunked(7).forEach { week ->
            val row = LinearLayout(context)
            week.forEach { date ->
                val day = button(date.dayOfMonth.toString()) { session.select(date); render() }.apply {
                    minWidth = 0; minimumWidth = 0; setPadding(0, 0, 0, 0)
                    contentDescription = "Select $date"
                }
                row.addView(day, LayoutParams(0, 0, 1f).apply { height = context.dp(48) })
                accents += {
                    val palette = PanelPalette.forMode(appearance.state.themeMode)
                    day.alpha = if (YearMonth.from(date) == session.month) 1f else .5f
                    day.backgroundTintList = null
                    day.background = GradientDrawable().apply {
                        cornerRadius = context.dp(8).toFloat()
                        setColor(if (date == session.selectedDate) android.graphics.Color.argb(45, android.graphics.Color.red(palette.accent), android.graphics.Color.green(palette.accent), android.graphics.Color.blue(palette.accent)) else android.graphics.Color.TRANSPARENT)
                        if (date == source.clock.today()) setStroke(context.dp(1), palette.accent)
                    }
                }
            }
            month.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        }
        body.addView(month, LayoutParams(0, LayoutParams.MATCH_PARENT, 1.05f))
        val agenda = context.scheduleColumn().apply { setPadding(context.dp(20), 0, 0, 0) }
        agenda.addView(context.scheduleLabel(scheduleDate(session.selectedDate), 24f))
        agenda.addView(button(tr("+ 添加日程", "+ Add event")) { editors.event(session.selectedDate) }.apply { isEnabled = source.editable })
        val events = context.scheduleColumn()
        val selected = ScheduleProjection.eventsOn(source.state, session.selectedDate, source.clock.zone())
        if (selected.isEmpty()) events.addView(context.scheduleLabel(tr("当天没有日程", "No events on this day"), 18f))
        selected.forEach { event ->
            val start = Instant.ofEpochMilli(event.start).atZone(source.clock.zone())
            val end = Instant.ofEpochMilli(event.end).atZone(source.clock.zone())
            val range = (if (start.toLocalDate() != session.selectedDate) "${scheduleDate(start.toLocalDate())} " else "") + context.scheduleTime(start.toInstant()) + " – " +
                (if (end.toLocalDate() != session.selectedDate) "${scheduleDate(end.toLocalDate())} " else "") + context.scheduleTime(end.toInstant())
            events.addView(button("$range\n${event.title}${if (event.note.isBlank()) "" else "\n${event.note}"}") { editors.event(session.selectedDate, event) }.apply {
                gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL; maxLines = 4; textSize = 18f
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        agenda.addView(ScrollView(context).apply { addView(events) }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        body.addView(agenda, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }
    private fun timetable(promoted: TimetableBody?) {
        warmDisplayData()
        val actions = LinearLayout(context)
        if (style.layout == X08eScheduleLayout.CLASSIC) {
            actions.addView(button(tr("+ 添加课程", "+ Add class")) { editors.entry() }.apply { isEnabled = source.editable })
            actions.addView(button(tr("导入课表", "Import timetable"), importTimetable).apply { isEnabled = source.editable })
            actions.addView(button(tr("接收课表", "Receive from phone/computer"), receiveTimetable).apply { isEnabled = source.editable })
            actions.addView(button(tr("课表管理", "Sources"), manageTimetables).apply { isEnabled = source.ready })
        } else actions.addView(button(tr("课表管理", "Manage")) {
            val options = arrayOf(tr("添加课程", "Add class"), tr("导入课表", "Import timetable"), tr("接收课表", "Receive timetable"), tr("来源与同步", "Sources and sync"))
            settingsDialog = androidx.appcompat.app.AlertDialog.Builder(context).setTitle(tr("课表管理", "Manage timetable"))
                .setItems(options) { _, i -> when(i) { 0 -> if (source.editable) editors.entry(); 1 -> if (source.editable) importTimetable(); 2 -> if (source.editable) receiveTimetable(); else -> manageTimetables() } }
                .setNegativeButton(tr("关闭", "Close"), null).show().also { it.matchActivityBrightness(); applyAppearanceTree(it.window!!.decorView, appearance.state) }
        })
        actions.addView(button("‹") { session.weekStart = session.weekStart.minusWeeks(1); render() }.apply { contentDescription = tr("上一周", "Previous week") })
        weekRange=context.scheduleLabel("${session.weekStart} – ${session.weekStart.plusDays(6)}",18f)
        actions.addView(weekRange,LayoutParams(0,context.dp(52),1f))
        actions.addView(button("›") { session.weekStart = session.weekStart.plusWeeks(1); render() }.apply { contentDescription = tr("下一周", "Next week") })
        actions.addView(button(tr("本周", "This week")) { session.today(source.clock); render() })
        addView(actions)
        val body = promoted ?: buildTimetable(session.weekStart)
        val swipe = TimetableSwipeLayout(context) { direction ->
            currentBody?.active=false
            // Keep the old detach/rebuild path solely for instrumentation A/B controls.
            val legacyControl=X08ePreviewTrace.cardOverride!=null
            val preview = (if(legacyControl) swipeContent?.takePreview() else swipeContent?.promotePreviewInPlace()) as? TimetableBody
            session.weekStart = session.weekStart.plusWeeks(direction.toLong())
            if(legacyControl || preview==null) renderPage(preview) else {
                currentBody=preview; preview.active=true; grid=preview.grid
                preview.captureViewport.forEach { it() }
                preview.grid?.clockAllowed=true
                weekRange?.text="${session.weekStart} – ${session.weekStart.plusDays(6)}"
                warmDisplayData()
            }
        }.apply {
            tag = "timetable-swipe"; swipeEnabled = style.swipeWeeks
            preparePreview = { direction -> warmDisplayData(); buildTimetable(session.weekStart.plusWeeks(direction.toLong()), deferred=true) }
        }
        swipeContent = swipe
        currentBody = body
        body.active = true; grid = body.grid
        if (promoted != null) body.captureViewport.forEach { it() }
        swipe.addView(body, FrameLayout.LayoutParams(-1,-1))
        addView(swipe, LayoutParams(-1,0,1f))
        body.grid?.clockAllowed = true
    }
    private inner class TimetableBody(val weekStart: LocalDate, val deferred: Boolean) : LinearLayout(context), AppearanceAware, TimetablePreviewContent {
        var active = false
        var grid: X08eWeekGrid? = null
        val accents = mutableListOf<() -> Unit>()
        val captureViewport = mutableListOf<() -> Unit>()
        private val pending=mutableListOf<Pair<View,()->Unit>>()
        private var preparing=false
        private var visibleLeft=0
        private var visibleRight=0
        fun prepareColumn(view: View, build: () -> Unit) { pending += view to build }
        override fun reveal(left: Int, right: Int): Boolean {
            visibleLeft=left; visibleRight=right
            val needed=pending.filter { (v,_) -> v.right>left && v.left<right }
            needed.forEach { entry -> pending.remove(entry); X08ePreviewTrace.section("visible-views") { entry.second() } }
            return needed.isNotEmpty()
        }
        private val fill=object : Runnable {
            override fun run() {
                if(!preparing || !isAttachedToWindow || pending.isEmpty()) return
                val next=pending.minBy { (v,_) -> when { v.right<=visibleLeft -> visibleLeft-v.right; v.left>=visibleRight -> v.left-visibleRight; else -> 0 } }
                pending.remove(next)
                X08ePreviewTrace.section("staged-views") { next.second() }
                if(pending.isNotEmpty()) postOnAnimationDelayed(this,16)
            }
        }
        override fun startPreparation() {
            if(deferred && pending.isNotEmpty()) {
                preparing=true
                // Never let a hidden-column batch run ahead of the first visible response.
                doOnPreDraw { post { if(preparing && isAttachedToWindow) postOnAnimationDelayed(fill,16) } }
            }
        }
        override fun cancelPreparation() { preparing=false; removeCallbacks(fill) }
        override fun onDetachedFromWindow() { cancelPreparation(); super.onDetachedFromWindow() }
        init { orientation = VERTICAL; tag = "x08e-page-$weekStart" }
        override fun applyAppearance(state: AppearanceState) = styleViews(state, includeGrid = true)
        fun styleViews(state: AppearanceState, includeGrid: Boolean) = X08ePreviewTrace.section("theme") {
            for (i in 0 until childCount) {
                val child=getChildAt(i)
                // A new grid has already styled its cards and clock in its constructor.
                if (!includeGrid && child is ScrollView && child.getChildAt(0) === grid) continue
                applyAppearanceTree(child,state)
            }
            accents.forEach { it() }
        }
    }
    private fun warmDisplayData() {
        if(!style.swipeWeeks || session.mode!=ScheduleMode.TIMETABLE || !source.ready || !isAttachedToWindow || !isShown) { displayCache.clear(); return }
        val config=resources.configuration
        displayCache.configure(source.state,source.clock.zone(),style,config.locales[0],
            "${resources.displayMetrics.widthPixels}:${resources.displayMetrics.heightPixels}:${config.densityDpi}:${config.fontScale}:$paintedTheme")
        displayCache.warm(session.weekStart)
    }
    private fun buildTimetable(weekStart: LocalDate, deferred: Boolean = false): TimetableBody {
        val body = TimetableBody(weekStart,deferred)
        val prepared=displayCache.get(weekStart) ?: X08ePreviewTrace.section("cache-miss") {
            X08eWeekDisplay.prepare(source.state,weekStart,source.clock.zone(),style,resources.configuration.locales[0]).also(displayCache::put)
        }
        val occurrences = prepared.items
        if (style.layout != X08eScheduleLayout.CLASSIC) {
            timeGrid(occurrences,body,prepared)
            body.styleViews(appearance.state, includeGrid = false)
            return body
        }
        val week = LinearLayout(context)
        (0L..6L).forEach { offset ->
            val date = weekStart.plusDays(offset)
            val day = date.dayOfWeek
            val column = context.scheduleColumn().apply { setPadding(context.dp(3), 0, context.dp(3), 0) }
            val adjustment = source.state.daysByDate[date.toString()]
            val suffix = when (adjustment?.mode) {
                "none" -> tr(" · 停课", " · No classes")
                "weekday" -> " →${DayOfWeek.of(adjustment.weekday!!).getDisplayName(TextStyle.SHORT, source.clock.locale())}"
                else -> ""
            }
            val headingText = "${day.getDisplayName(TextStyle.SHORT, source.clock.locale())} ${date.monthValue}/${date.dayOfMonth}$suffix"
            fun heading() = button(headingText) { if (body.active) editors.dayAdjustment(date) }.apply {
                textSize = 16f; minWidth = 0; minimumWidth = 0; setPadding(0, 0, 0, 0)
                contentDescription = tr("日期调整 $date", "Day adjustment $date${if (adjustment?.mode == "weekday") " uses ${DayOfWeek.of(adjustment.weekday!!)}" else ""}")
            }
            val headingSlot=FrameLayout(context).apply { minimumHeight=context.dp(48) }
            column.addView(headingSlot,LayoutParams(-1,LayoutParams.WRAP_CONTENT))
            val classes = context.scheduleColumn()
            val entries = prepared.days[offset.toInt()]
            val scroller=scroll("classic-$offset", classes, body)
            val saved=scrollOffsets["classic-$offset"] ?: 0
            val fill={
                val heading=heading()
                headingSlot.addView(heading,FrameLayout.LayoutParams(-1,LayoutParams.WRAP_CONTENT))
                val accent={ if(date==source.clock.today()) { heading.setTextColor(PanelPalette.forMode(appearance.state.themeMode).accent); heading.text="• $headingText" } }
                body.accents+=accent
                if(deferred) { applyAppearanceTree(heading,appearance.state); accent() }
                if(entries.isEmpty()) classes.addView(context.scheduleLabel("—",18f))
                entries.forEach { occurrence ->
                    val card=context.courseView(listOf(occurrence),source.state,style,source.clock.zone(),prepared.texts[occurrence]) { if(body.active) editors.classDetail(occurrence) }
                    if(deferred) applyAppearanceTree(card,appearance.state)
                    classes.addView(card,LayoutParams(-1,-2).apply { bottomMargin=context.dp(4) })
                }
                if(deferred) scroller.doOnNextLayout { scroller.scrollTo(0,saved.coerceAtMost((classes.height-scroller.height).coerceAtLeast(0))) }
            }
            if(deferred) body.prepareColumn(column,fill) else fill()
            column.addView(scroller, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
            week.addView(column, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
        body.addView(week, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        body.applyAppearance(appearance.state)
        return body
    }
    private fun timeGrid(items: List<ClassOccurrence>, body: TimetableBody, prepared: X08eWeekDisplay) {
        val content = X08eWeekGrid(context, items, body.weekStart, source, style, appearance, { if (body.active) editors.classDetail(it) }, { body.active },prepared,body.deferred)
        if(body.deferred) {
            content.preparations.forEach { (view,fill) -> body.prepareColumn(view,fill) }
            content.preparations.clear()
        }
        body.grid = content
        content.clockAllowed = false
        if (style.layout == X08eScheduleLayout.PERIODS && !content.usesPeriods) body.addView(context.scheduleLabel(
            tr("本周作息缺失或不一致，暂按实际时间显示。", "Period schedules are missing or differ this week. Showing actual time."), 16f))
        val headings = LinearLayout(context)
        headings.addView(context.scheduleLabel(if (content.usesPeriods) tr("节次", "Period") else tr("时间", "Time"), 16f), LayoutParams(context.dp(76), context.dp(56)))
        repeat(7) { day ->
            val date = body.weekStart.plusDays(day.toLong())
            val adjustment = source.state.daysByDate[date.toString()]
            val suffix = when (adjustment?.mode) {
                "none" -> tr(" · 停课", " · Off")
                "weekday" -> " →${DayOfWeek.of(adjustment.weekday!!).getDisplayName(TextStyle.SHORT, source.clock.locale())}"
                else -> ""
            }
            fun heading() = button("${date.dayOfWeek.getDisplayName(TextStyle.SHORT, source.clock.locale())} ${date.monthValue}/${date.dayOfMonth}$suffix") { if (body.active) editors.dayAdjustment(date) }.apply {
                minWidth = 0; minimumWidth = 0; textSize = 16f; setPadding(0, 0, 0, 0)
                contentDescription = tr("日期调整 $date", "Day adjustment $date")
            }
            val slot=FrameLayout(context)
            headings.addView(slot,LayoutParams(0,context.dp(56),1f))
            val fill={
                val heading=heading(); slot.addView(heading,FrameLayout.LayoutParams(-1,-1))
                val accent={ if(date==source.clock.today()) heading.setTextColor(PanelPalette.forMode(appearance.state.themeMode).accent) }
                body.accents+=accent
                if(body.deferred) { applyAppearanceTree(heading,appearance.state); accent() }
            }
            if(body.deferred) body.prepareColumn(slot,fill) else fill()
        }
        body.addView(headings)
        body.addView(scroll("grid", content, body, content.offset(viewport.gridMinute ?: 480f).coerceAtLeast(0), content::minuteAt), LayoutParams(-1, 0, 1f))
        val footer = LinearLayout(context)
        footer.addView(Space(context), LayoutParams(context.dp(76), context.dp(48)))
        repeat(7) { day ->
            val slot=FrameLayout(context)
            footer.addView(slot,LayoutParams(0,context.dp(48),1f))
            val fill={
                val button=button(tr("查看 ${content.dayCount(day)} 门", "View ${content.dayCount(day)}")) { if(body.active) content.openDay(day) }.apply {
                    minWidth=0; minimumWidth=0; textSize=14f; setPadding(0,0,0,0); isEnabled=content.dayCount(day)>0
                }
                slot.addView(button,FrameLayout.LayoutParams(-1,-1))
                if(body.deferred) applyAppearanceTree(button,appearance.state)
            }
            if(body.deferred) body.prepareColumn(slot,fill) else fill()
        }
        body.addView(footer)
    }
    override fun applyAppearance(state: AppearanceState) = paintAppearance(state, includeContent = true)
    private fun paintAppearance(state: AppearanceState, includeContent: Boolean) {
        if(paintedTheme!=state.themeMode) {
            paintedTheme=state.themeMode; displayCache.invalidate(); swipeContent?.cancelMotion(); warmDisplayData()
        }
        setBackgroundColor(PanelPalette.forMode(state.themeMode).background)
        for (index in 0 until childCount) {
            val child=getChildAt(index)
            if (includeContent || child !== swipeContent) applyAppearanceTree(child, state)
        }
        accents.forEach { it() }
        settingsDialog?.window?.decorView?.let { applyAppearanceTree(it, state) }
    }
}
