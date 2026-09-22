package com.dormpanel.app.schedule

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import com.dormpanel.app.R
import com.dormpanel.app.appearance.*
import com.dormpanel.app.dashboard.card.*
import com.dormpanel.app.dashboard.catalog.*
import com.dormpanel.app.dashboard.model.*
import java.time.Instant

class ScheduleCatalog : CardCatalog {
    override val candidates = listOf("calendar" to "Calendar", "timetable" to "Timetable").map { (key, label) ->
        CardAddCandidate(key, CardCategory.PRODUCTIVITY, key, label, "Local offline schedule", "{}")
    }
    override fun addListener(listener: (List<CardAddCandidate>) -> Unit) = listener(candidates)
    override fun removeListener(listener: (List<CardAddCandidate>) -> Unit) = Unit
}
class ScheduleCardProvider(override val typeKey: String, private val source: ScheduleSource,
    private val appearance: AppearanceController) : DashboardCardProvider {
    init { require(typeKey == "calendar" || typeKey == "timetable") }
    override val displayMetadata = CardDisplayMetadata(typeKey, "", if (typeKey == "calendar") R.string.schedule_calendar else R.string.schedule_timetable, R.string.productivity_local)
    override val sizePolicy = ExplicitCardSizePolicy(listOf(CardSize(2, 1), CardSize(2, 2), CardSize(3, 2)))
    override val defaultSize = CardSize(2, 2)
    override fun createView(context: Context): View = ScheduleCardView(context, appearance, source, typeKey)
    override fun bind(view: View, card: PlacedCard, interactions: CardInteractionScope) = (view as ScheduleCardView).bind(card, interactions)
}
@SuppressLint("ViewConstructor")
class ScheduleCardView(context: Context, appearance: AppearanceController, private val source: ScheduleSource,
    private val kind: String) : DashboardCardView(context, appearance) {
    private val heading = label(18f, true)
    private val status = label(18f, true)
    private val content = column()
    private val editors = ScheduleEditors(context, source, appearance) { interactions.enabled }
    private val display = ScheduleDisplay(this, source, ::refresh)
    private var visible = false
    init { heading.maxLines = 1; status.maxLines = 1; addView(heading); addView(status); addView(content) }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); display.visibility(isShown) }
    override fun onDetachedFromWindow() { display.visibility(false); editors.close(); super.onDetachedFromWindow() }
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); visible = isVisible; display.visibility(isVisible) }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); display.visibility(visible && visibility == VISIBLE) }
    override fun render() {
        val clock = source.clock
        heading.text = "${if (kind == "calendar") "Calendar" else "Timetable"} · ${scheduleDate(clock.today())}"
        content.removeAllViews()
        val limit = ScheduleProjection.cardLimit(card.size.columnSpan, card.size.rowSpan)
        val large = card.size.rowSpan > 1
        fun line(text: String) { content.addView(context.scheduleLabel(text, if (large) 19f else 18f).apply { maxLines = if (large && card.size.columnSpan == 2) 2 else 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, context.dp(3), 0, 0) }) }
        if (kind == "calendar") {
            val events = ScheduleProjection.upcomingEvents(source.state, clock, limit)
            status.text = if (events.isEmpty()) "No upcoming events" else "Upcoming events"
            events.forEach { event ->
                val start = Instant.ofEpochMilli(event.start)
                val date = start.atZone(clock.zone()).toLocalDate()
                val time = "${if (date != clock.today()) "${scheduleDate(date)} · " else ""}${context.scheduleTime(start)}"
                if (large) line("$time  ${event.title}") else { status.text = time; line(event.title) }
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
        if (!interactions.enabled || !source.ready) return
        interactions.claimGesture()
        if (kind == "calendar") editors.event(source.clock.today(), ScheduleProjection.upcomingEvents(source.state, source.clock, 1).firstOrNull())
        else {
            val occurrence = ScheduleProjection.occurrences(source.state, source.clock).firstOrNull()
            if (occurrence?.imported != null) editors.imported(occurrence.imported) else editors.entry(occurrence?.entry)
        }
    }
}
