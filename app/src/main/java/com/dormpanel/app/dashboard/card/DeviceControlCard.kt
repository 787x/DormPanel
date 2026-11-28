package com.dormpanel.app.dashboard.card

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import com.dormpanel.app.R
import com.dormpanel.app.appearance.AppearanceController
import com.dormpanel.app.appearance.AppearanceState
import com.dormpanel.app.appearance.applyAppearanceTree
import com.dormpanel.app.dashboard.catalog.controlCardTarget
import com.dormpanel.app.dashboard.catalog.resolve
import com.dormpanel.app.dashboard.model.CardSize
import com.dormpanel.app.dashboard.model.PlacedCard
import com.dormpanel.app.home.*

internal class DeviceControlCardProvider(
    private val kind: HomeKind,
    private val source: HomeControlSource,
    private val appearance: AppearanceController,
) : ProductionCardProvider(
    kind.name.lowercase(),
    if (kind == HomeKind.CLIMATE) R.string.card_climate else R.string.card_fan,
    if (kind == HomeKind.CLIMATE) R.string.card_climate_description else R.string.card_fan_description,
    CardSize(2, 2),
) {
    override fun createView(context: Context): View = DeviceControlCard(context, appearance, source, kind, displayMetadata.name(context))
}

private class DeviceControlCard(context: Context, appearance: AppearanceController,
    private val source: HomeControlSource, private val kind: HomeKind, private val fallbackName: String,
) : DashboardCardView(context, appearance) {
    private var target: HomeTarget? = null
    private var configuredName = fallbackName
    private var details: HomeDeviceDetails? = null
    private var rendered: HomeEntity? = null
    private var subscribed = false
    private val name = label(19f).apply { maxLines = 1 }
    private val value = label(15f, secondary = true)
    private val request = label(14f, secondary = true)
    private val labels = column().apply { addView(name); addView(value); addView(request) }
    private val power = Button(context).apply { isAllCaps = false; minWidth = 0 }
    private val open = Button(context).apply { setText(R.string.home_details); isAllCaps = false; minWidth = 0 }
    private val actions = row().apply { addView(power); addView(open) }
    private val listener: (HomeControlState) -> Unit = { state ->
        val entity = target?.resolve(state.entities, kind)
        if (isBound && rendered != entity) { rendered = entity; refresh() }
    }

    init {
        setPadding(10.dp, 3.dp, 10.dp, 3.dp)
        addView(labels)
        addView(actions)
    }

    override fun onBound(previous: PlacedCard?) {
        setOnClickListener(null)
        setOnLongClickListener(null)
        isClickable = false
        isLongClickable = false
        if (!interactions.enabled || previous?.configurationJson != card.configurationJson) details?.dismiss()
        target = controlCardTarget(card.configurationJson, kind)
        configuredName = SwitchCardConfiguration.decode(card.configurationJson)?.name?.ifBlank { fallbackName } ?: fallbackName
        rendered = target?.resolve(source.homeState.entities, kind)
        val compact = card.size.rowSpan == 1
        orientation = if (compact) HORIZONTAL else VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        labels.layoutParams = if (compact) LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            else LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        actions.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, 48.dp)
        value.maxLines = if (compact) 2 else 3
        name.textSize = if (compact) 16f else 21f
        listOf(power, open).forEach { button ->
            button.layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, 48.dp)
            button.setPadding(8.dp, 0, 8.dp, 0)
            button.setOnLongClickListener { true }
        }
        updateSubscription()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateSubscription()
        refresh()
    }

    override fun onDetachedFromWindow() {
        details?.dismiss()
        setSubscribed(false)
        super.onDetachedFromWindow()
    }

    private fun updateSubscription() = setSubscribed(isAttachedToWindow && isBound && target != null && !interactions.isResizePreview)

    private fun setSubscribed(active: Boolean) {
        if (active == subscribed) return
        subscribed = active
        if (active) source.addHomeListener(listener) else source.removeHomeListener(listener)
    }

    override fun render() {
        val entity = rendered
        val binding = target
        name.text = entity?.name ?: configuredName
        value.text = entity?.deviceValue(context, compact = card.size.rowSpan == 1) ?: context.getString(R.string.device_missing)
        val status = entity?.request
        request.show(status != null)
        request.text = status?.let { context.getString(it.label()) }.orEmpty()
        val command = entity?.controls?.primaryCommand()
        val label = command?.actionLabel()
        power.show(label != null)
        if (label != null) power.setText(label)
        power.isEnabled = interactions.enabled && command != null && entity.canSend(command)
        power.setOnClickListener {
            if (interactions.enabled && binding != null && command != null) {
                sendDeviceCommand(context, source, binding, command)
            }
        }
        open.isEnabled = interactions.enabled && binding != null && entity != null
        open.setOnClickListener {
            if (interactions.enabled && binding != null && details == null) {
                interactions.claimGesture()
                details = HomeDeviceDetails(context, source, appearance, binding, kind) { details = null }.also { it.show() }
            }
        }
        importantForAccessibility = if (interactions.enabled) IMPORTANT_FOR_ACCESSIBILITY_AUTO
            else IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    override fun applyAppearance(state: AppearanceState) {
        super.applyAppearance(state)
        applyAppearanceTree(actions, state)
    }
}
