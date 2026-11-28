package com.dormpanel.app.schedule

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import com.dormpanel.app.R
import com.dormpanel.app.appearance.*
import com.dormpanel.app.dashboard.card.*
import com.dormpanel.app.dashboard.catalog.*
import com.dormpanel.app.dashboard.model.*
import com.dormpanel.app.icloud.CardCalendars
import com.dormpanel.app.icloud.CloudCalendarDialogs
import com.dormpanel.app.icloud.CloudCalendarService
import com.dormpanel.app.icloud.stripe
import java.time.Instant

class ScheduleCatalog(private val context: Context) : CardCatalog {
    override val candidates = listOf("calendar" to "Calendar", "timetable" to "Timetable", "schedule_today" to context.getString(R.string.schedule_today), "schedule_recent" to context.getString(R.string.schedule_recent)).map { (key, label) ->
        CardAddCandidate(key, CardCategory.PRODUCTIVITY, key, label, "Local offline schedule", "{}")
    }
    override fun addListener(listener: (List<CardAddCandidate>) -> Unit) = listener(candidates)
    override fun removeListener(listener: (List<CardAddCandidate>) -> Unit) = Unit
}
class ScheduleCardProvider(override val typeKey: String, private val source: ScheduleSource,
    private val appearance: AppearanceController, private val cloudCalendar: CloudCalendarService? = null) : DashboardCardProvider {
    init { require(typeKey == "calendar" || typeKey == "timetable") }
    override val displayMetadata = CardDisplayMetadata(typeKey, "", if (typeKey == "calendar") R.string.schedule_calendar else R.string.schedule_timetable, R.string.productivity_local)
    override val sizePolicy = ExplicitCardSizePolicy(listOf(CardSize(2, 1), CardSize(2, 2), CardSize(3, 2)))
    override val defaultSize = CardSize(2, 2)
    override fun createView(context: Context): View = ScheduleCardView(context, appearance, source, typeKey, cloudCalendar)
    override fun bind(view: View, card: PlacedCard, interactions: CardInteractionScope) = (view as ScheduleCardView).bind(card, interactions)
}
@SuppressLint("ViewConstructor")
class ScheduleCardView(context: Context, appearance: AppearanceController, private val source: ScheduleSource,
    private val kind: String, private val cloudCalendar: CloudCalendarService? = null) : DashboardCardView(context, appearance) {
    private val heading = label(18f, true)
    private val status = label(18f, true)
    private val content = column()
    private val editors = ScheduleEditors(context, source, appearance) { interactions.enabled }
    private val cloudDialogs = CloudCalendarDialogs(context, appearance)
    private var settings = CardCalendars()
    private var cloudBoundary: Instant? = null
    private val display = ScheduleDisplay(this, source, ::refresh) {
        val local = ScheduleProjection.nextBoundary(source.state, source.clock)
        cloudBoundary?.let { minOf(it, local) } ?: local
    }
    private val renderCloud = Runnable { if (visible && windowVisibility == VISIBLE) display.refresh() }
    private val cloudListener: () -> Unit = { removeCallbacks(renderCloud); postOnAnimation(renderCloud) }
    private var visible = false
    init { heading.maxLines = 1; status.maxLines = 1; addView(heading); addView(status); addView(content) }
    override fun onBound(previous: PlacedCard?) {
        if (kind == "calendar" && previous?.configurationJson != card.configurationJson) settings = CardCalendars.decode(card.configurationJson)
        if (!interactions.enabled) cloudDialogs.close()
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        cloudCalendar?.addListener(cloudListener)
        display.visibility(isShown)
    }
    override fun onDetachedFromWindow() {
        cloudCalendar?.removeListener(cloudListener)
        cloudCalendar?.watch(this, null)
        removeCallbacks(renderCloud)
        display.visibility(false)
        editors.close()
        cloudDialogs.close()
        super.onDetachedFromWindow()
    }
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        visible = isVisible
        if (!isVisible) cloudCalendar?.watch(this, null)
        display.visibility(isVisible)
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) cloudCalendar?.watch(this, null)
        display.visibility(visible && visibility == VISIBLE)
    }
    override fun render() {
        val clock = source.clock
        val cloudConnected = cloudCalendar?.connected == true
        val calendarTitle = if (cloudConnected) context.scheduleText("日历", "Calendar") else "Calendar"
        heading.text = "${if (kind == "calendar") calendarTitle else "Timetable"} · ${scheduleDate(clock.today())}"
        content.removeAllViews()
        val limit = ScheduleProjection.cardLimit(card.size.columnSpan, card.size.rowSpan)
        val large = card.size.rowSpan > 1
        fun line(text: String, color: Int? = null) {
            content.addView(context.scheduleLabel(text, if (large) 19f else 18f).apply {
                maxLines = if (large && card.size.columnSpan == 2) 2 else 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(if (color == null) 0 else context.dp(7), context.dp(3), 0, 0)
                color?.let { foreground = stripe(it, context.dp(3)) }
            })
        }
        if (kind == "calendar") {
            val range = recentCloudRange(clock, settings.selected)
            if (visible && windowVisibility == VISIBLE && isAttachedToWindow && !interactions.isResizePreview) cloudCalendar?.watch(this, range)
            val events = recentAgenda(source, cloudCalendar, settings.selected, limit)
            cloudBoundary = events.map { it.end }.filter { it > clock.instant() }.minOrNull()
            val stale = cloudCalendar?.hasStaleData(range) == true
            status.text = if (cloudConnected) {
                if (events.isEmpty()) context.scheduleText("近期没有日程", "No upcoming events") else context.scheduleText("近期日程", "Upcoming events")
            } else if (events.isEmpty()) "No upcoming events" else "Upcoming events"
            val staleLabel = "iCloud 缓存可能过期"
            if (stale) {
                if (large) status.append(" · $staleLabel")
                else heading.text = "$calendarTitle · $staleLabel"
            }
            events.forEach { event ->
                val time = event.label(context, clock.today(), clock.zone(), compact = true)
                val calendar = event.remote?.let { " · ${it.calendar.name}" }.orEmpty()
                val color = event.color ?: PanelPalette.forMode(appearance.state.themeMode).accent
                if (large) line("$time  ${event.title}$calendar", color) else {
                    status.text = time
                    line("${event.title}$calendar", color)
                }
            }
        } else {
            val projection = ScheduleProjection.timetableCard(source.state, clock, card.size.columnSpan, card.size.rowSpan)
            status.text = when (projection.status) { ClassStatus.CURRENT -> "Current class"; ClassStatus.NEXT_TODAY -> "Next class today"; ClassStatus.NO_MORE_TODAY -> "No more classes today" }
            projection.occurrences.forEach { occurrence ->
                val entry = occurrence.entry
                if (!large) { status.text = "${status.text} · ${context.scheduleTime(entry.startMinute)}"; line(entry.title) }
                else line("${if (occurrence.date != clock.today()) "${occurrence.date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, clock.locale())} · " else ""}${context.scheduleTime(entry.startMinute)}–${context.scheduleTime(entry.endMinute)}  ${entry.title}${if (entry.location.isNotBlank()) " · ${entry.location}" else ""}")
            }
        }
        if (!source.ready || source.error || source.state.invalidRecords > 0) status.text = if (source.error) "Storage error · changes may be unsaved" else if (!source.ready) "Loading…" else "Invalid stored records hidden"
        if (!interactions.enabled) editors.close()
        applyAppearance(appearance.state)
    }
    override fun applyAppearance(state: AppearanceState) { super.applyAppearance(state); applyAppearanceTree(content, state) }
    override fun primaryAction() {
        if (!interactions.enabled) return
        interactions.claimGesture()
        if (kind == "calendar") {
            val day = source.clock.today()
            val event = recentAgenda(source, cloudCalendar, settings.selected, 1).firstOrNull()
            val remote = event?.remote
            if (remote != null) cloudDialogs.details(remote, event.label(context, day, source.clock.zone()))
            else if (source.ready) editors.event(day, event?.local)
        } else if (source.ready) {
            val occurrence = ScheduleProjection.occurrences(source.state, source.clock).firstOrNull()
            if (occurrence != null) editors.classDetail(occurrence) else editors.entry(null)
        }
    }
}
