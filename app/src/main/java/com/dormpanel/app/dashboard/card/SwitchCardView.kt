package com.dormpanel.app.dashboard.card

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.dormpanel.app.R
import com.dormpanel.app.appearance.AppearanceController
import com.dormpanel.app.appearance.AppearanceState
import com.dormpanel.app.appearance.PanelPalette
import com.dormpanel.app.dashboard.model.*
import com.dormpanel.app.data.*

internal class SwitchCardProvider(
    private val source: DashboardDataSource,
    private val appearance: AppearanceController,
    private val grouped: Boolean,
) : ProductionCardProvider(
    if (grouped) SWITCH_GROUP_CARD else SWITCH_CARD,
    if (grouped) R.string.card_switch_group else R.string.card_switch,
    if (grouped) R.string.card_switch_group_description else R.string.card_switch_description,
    if (grouped) CardSize(2, 1) else CardSize(1, 1),
) {
    override val sizePolicy = if (grouped) ExplicitCardSizePolicy(
        listOf(defaultSize, CardSize(1, 2)) +
            RangeCardSizePolicy(CardSize(1, 1), CardSize(4, 3)).legalSizes(CardSize(4, 3))
                .filter { it.columnSpan * it.rowSpan > 2 },
    ) else RangeCardSizePolicy(CardSize(1, 1), CardSize(4, 3))

    override fun createView(context: Context): View = SwitchCardView(context, appearance, source, grouped)
}

private class SwitchCardView(
    context: Context, appearance: AppearanceController, source: DashboardDataSource, private val grouped: Boolean,
) : SourceCardView<SwitchCardView.Display>(context, appearance, source) {
    data class Display(val device: String?, val stale: Boolean, val channels: List<DashboardSwitch?>)

    private var binding: SwitchCardConfiguration? = null
    private val title = label(16f, secondary = true).apply { maxLines = 1 }
    private val buttons = row()
    private val tiles = List(if (grouped) 3 else 1) { Tile() }

    init {
        setPadding(if (grouped) 6.dp else 0, if (grouped) 4.dp else 0,
            if (grouped) 6.dp else 0, if (grouped) 4.dp else 0)
        gravity = Gravity.TOP
        addView(title, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            leftMargin = 5.dp
            bottomMargin = 3.dp
        })
        addView(buttons, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        tiles.forEach { buttons.addView(it.view) }
    }

    override fun onBound(previous: PlacedCard?) {
        if (previous?.configurationJson != card.configurationJson || previous.providerType != card.providerType) {
            binding = SwitchCardConfiguration.decode(card.configurationJson)?.takeIf { it.isValid(card.providerType) }
            resetRenderKey()
        }
        // The title and gaps are inert. Buttons let the page router observe swipes.
        setOnClickListener(null)
        setOnLongClickListener(null)
        isClickable = false
        isLongClickable = false
        importantForAccessibility = if (interactions.enabled) IMPORTANT_FOR_ACCESSIBILITY_AUTO
            else IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val horizontal = card.size.columnSpan >= card.size.rowSpan
        buttons.orientation = if (horizontal) HORIZONTAL else VERTICAL
        tiles.forEachIndexed { index, tile ->
            tile.view.layoutParams = if (horizontal) LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
                else LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            if (grouped && index > 0) (tile.view.layoutParams as LayoutParams).apply {
                if (horizontal) marginStart = 4.dp else topMargin = 4.dp
            }
            tile.name.textSize = if (card.size.columnSpan * card.size.rowSpan <= 2) 16f else 20f
        }
    }

    override fun select(data: DashboardData): Display {
        val config = binding ?: return Display(null, false, emptyList())
        val switches = data.switches
        val sameSource = switches.sourceId == config.sourceId
        return Display(switches.deviceNames[config.deviceId].takeIf { sameSource }, sameSource && !switches.connected,
            config.channels.map { switches.resolve(config.sourceId, it.identity) })
    }

    override fun render() {
        val config = binding
        val display = select(source.state)
        title.show(grouped)
        title.text = config?.name?.ifBlank { display.device ?: config.deviceId.orEmpty() }
            ?: context.getString(R.string.card_switch_group)
        tiles.forEachIndexed { index, tile ->
            val channel = config?.channels?.getOrNull(index)
            tile.view.show(index == 0 || channel != null)
            val live = display.channels.getOrNull(index)
            tile.name.text = channel?.name?.ifBlank {
                live?.name ?: channel.identity.entityId.removePrefix("ha:")
            } ?: context.getString(R.string.card_switch)
            val state = when (live?.isOn) {
                true -> context.getString(R.string.switch_on)
                false -> context.getString(R.string.switch_off)
                null -> context.getString(R.string.state_unavailable)
            }
            tile.status.text = if (channel?.identity?.isLegacyObservation == true) {
                context.getString(R.string.switch_rebind_required)
            } else if (display.stale) {
                if (live?.isOn == null) context.getString(R.string.switch_disconnected)
                else context.getString(R.string.switch_stale_state, state)
            } else state
            val canToggle = interactions.enabled && !display.stale && live?.availability == Availability.AVAILABLE
            tile.view.setOnClickListener(if (canToggle && config != null && channel != null) OnClickListener {
                if (interactions.enabled) source.toggleSwitch(config.sourceId, channel.identity)
            } else null)
            tile.view.isClickable = canToggle
            tile.view.isFocusable = canToggle
            tile.view.contentDescription = if (canToggle) context.getString(R.string.switch_toggle_action, tile.name.text, tile.status.text)
                else "${tile.name.text}, ${tile.status.text}"
            tile.active = !display.stale && live?.isOn == true
        }
        applyAppearance(appearance.state)
    }

    override fun applyAppearance(state: AppearanceState) {
        super.applyAppearance(state)
        val palette = PanelPalette.forMode(state.themeMode)
        tiles.forEach { tile ->
            tile.status.setTextColor(if (tile.active) palette.accent else palette.secondary)
            val shape = GradientDrawable().apply {
                cornerRadius = 12.dp.toFloat()
                // Keep grouped hit regions distinct at every card opacity.
                if (grouped) setStroke(1.dp, withAlpha(palette.secondary, 100))
                if (tile.active) setColor(withAlpha(palette.accent, 36))
            }
            val mask = GradientDrawable().apply { cornerRadius = 12.dp.toFloat(); setColor(android.graphics.Color.WHITE) }
            tile.view.background = RippleDrawable(ColorStateList.valueOf(withAlpha(palette.accent, 64)), shape, mask)
        }
    }

    private fun withAlpha(color: Int, alpha: Int) = (color and 0x00ffffff) or (alpha shl 24)

    private inner class Tile {
        val name: TextView = label(16f).apply { maxLines = 1 }
        val status: TextView = label(14f, secondary = true).apply { maxLines = 1 }
        var active = false
        val view: LinearLayout = column().apply {
            gravity = Gravity.CENTER
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            setPadding(5.dp, 2.dp, 5.dp, 2.dp)
            name.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            status.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(name)
            addView(status, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = 4.dp })
        }
    }
}
