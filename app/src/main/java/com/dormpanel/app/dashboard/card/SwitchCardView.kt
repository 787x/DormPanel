package com.dormpanel.app.dashboard.card

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.dormpanel.app.R
import com.dormpanel.app.appearance.AppearanceController
import com.dormpanel.app.appearance.AppearanceState
import com.dormpanel.app.appearance.PanelPalette
import com.dormpanel.app.dashboard.model.*
import com.dormpanel.app.data.*
import com.dormpanel.app.home.label

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
        title.textSize = if (card.size.columnSpan >= 3 && card.size.rowSpan >= 2) 20f else 16f
        buttons.orientation = if (horizontal) HORIZONTAL else VERTICAL
        tiles.forEachIndexed { index, tile ->
            tile.view.layoutParams = if (horizontal) LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
                else LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            if (grouped && index > 0) (tile.view.layoutParams as LayoutParams).apply {
                if (horizontal) marginStart = 4.dp else topMargin = 4.dp
            }
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
            } else if (live?.request != null) "$state · ${context.getString(live.request.label())}" else state
            val canToggle = interactions.enabled && !display.stale && live?.availability == Availability.AVAILABLE &&
                live.request?.busy != true
            tile.view.setOnClickListener(if (canToggle && config != null && channel != null) OnClickListener {
                if (interactions.enabled && !source.toggleSwitch(config.sourceId, channel.identity) && channel.identity.isVirtual) {
                    android.widget.Toast.makeText(context, R.string.device_not_sent, android.widget.Toast.LENGTH_SHORT).show()
                }
            } else null)
            tile.view.isClickable = canToggle
            tile.view.isFocusable = canToggle
            tile.view.contentDescription = if (canToggle) context.getString(R.string.switch_toggle_action, tile.name.text, tile.status.text)
                else "${tile.name.text}, ${tile.status.text}"
            tile.icon.state = when {
                display.stale || live?.availability != Availability.AVAILABLE -> SwitchGlyph.State.UNAVAILABLE
                live.isOn == true -> SwitchGlyph.State.ON
                else -> SwitchGlyph.State.OFF
            }
        }
        applyAppearance(appearance.state)
    }

    override fun applyAppearance(state: AppearanceState) {
        super.applyAppearance(state)
        val palette = PanelPalette.forMode(state.themeMode)
        tiles.forEach { tile ->
            val active = tile.icon.state == SwitchGlyph.State.ON
            tile.icon.palette = palette
            tile.icon.invalidate()
            tile.status.setTextColor(when (tile.icon.state) {
                SwitchGlyph.State.ON -> palette.accent
                SwitchGlyph.State.OFF -> palette.text
                SwitchGlyph.State.UNAVAILABLE -> palette.secondary
            })
            val shape = GradientDrawable().apply {
                cornerRadius = 12.dp.toFloat()
                // Keep grouped hit regions distinct at every card opacity.
                if (grouped) setStroke(1.dp, withAlpha(palette.secondary, 100))
                if (active) setColor(withAlpha(palette.accent, 36))
            }
            val mask = GradientDrawable().apply { cornerRadius = 12.dp.toFloat(); setColor(android.graphics.Color.WHITE) }
            tile.view.background = RippleDrawable(ColorStateList.valueOf(withAlpha(palette.accent, 64)), shape, mask)
        }
    }

    private fun withAlpha(color: Int, alpha: Int) = (color and 0x00ffffff) or (alpha shl 24)

    private inner class Tile {
        val name = fittingLabel(18f).apply {
            ellipsize = android.text.TextUtils.TruncateAt.END
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val status = fittingLabel(14f, secondary = true).apply {
            ellipsize = android.text.TextUtils.TruncateAt.END
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val icon = SwitchGlyph(context)
        private val labels = column().apply {
            addView(name, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        val view: LinearLayout = object : LinearLayout(context) {
            private var lastWidth = 0
            private var lastHeight = 0

            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val width = MeasureSpec.getSize(widthMeasureSpec)
                val height = MeasureSpec.getSize(heightMeasureSpec)
                if (width > 0 && height > 0 && (width != lastWidth || height != lastHeight)) {
                    lastWidth = width
                    lastHeight = height
                    fit(width, height)
                }
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }.apply {
            gravity = Gravity.CENTER
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            addView(icon)
            addView(labels)
        }

        private fun fit(width: Int, height: Int) {
            val inline = width >= 108.dp && (height < 72.dp || width >= height * 1.65f)
            val wideInline = inline && width >= 170.dp
            val roomy = height >= 136.dp && width >= 115.dp
            val large = height >= 200.dp && width >= 165.dp
            val inset = if (roomy) 11.dp else 5.dp
            val gap = if (roomy || wideInline) 10.dp else 5.dp
            view.setPadding(inset, 2.dp, inset, 2.dp)
            view.orientation = if (inline) HORIZONTAL else VERTICAL
            labels.gravity = if (inline) Gravity.CENTER_VERTICAL else Gravity.CENTER
            val align = if (inline) Gravity.START else Gravity.CENTER
            name.gravity = align
            status.gravity = align
            val nameSize = when {
                large -> 40f
                roomy -> 32f
                height >= 90.dp && width >= 110.dp -> 26f
                wideInline && height >= 68.dp -> 27f
                height >= 68.dp -> 22f
                else -> 19f
            }
            name.maximumTextSp = nameSize
            name.minimumWidthTextSp = 15f
            status.maximumTextSp = (nameSize * .84f).coerceIn(17f, 32f)
            status.minimumWidthTextSp = 13f
            val iconWidth = when {
                large -> 76.dp
                roomy -> 58.dp
                height >= 90.dp || (wideInline && height >= 68.dp) -> 46.dp
                else -> 34.dp
            }
            icon.layoutParams = LayoutParams(iconWidth, iconWidth * 9 / 16).apply {
                if (inline) marginEnd = gap else bottomMargin = gap
            }
            labels.layoutParams = LayoutParams(
                when {
                    wideInline -> LayoutParams.WRAP_CONTENT
                    inline -> 0
                    else -> LayoutParams.MATCH_PARENT
                },
                LayoutParams.WRAP_CONTENT,
                if (inline && !wideInline) 1f else 0f,
            )
        }
    }
}

/** The thumb position conveys state even without color. The entire tile remains the touch target. */
internal class SwitchGlyph(context: Context) : View(context) {
    enum class State { ON, OFF, UNAVAILABLE }

    var state = State.UNAVAILABLE
    var palette = PanelPalette.forMode(com.dormpanel.app.appearance.ThemeMode.DARK)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val stroke = (height * .09f).coerceAtLeast(1f)
        val edge = stroke / 2
        val center = height / 2f
        val on = state == State.ON
        paint.color = if (on) palette.accent else palette.secondary
        paint.style = if (on) Paint.Style.FILL else Paint.Style.STROKE
        paint.strokeWidth = stroke
        canvas.drawRoundRect(edge, edge, width - edge, height - edge, center, center, paint)
        if (state == State.UNAVAILABLE) {
            paint.strokeCap = Paint.Cap.ROUND
            val middle = width / 2f
            canvas.drawLine(middle - center / 2, center * 1.5f, middle + center / 2, center * .5f, paint)
        } else {
            paint.style = Paint.Style.FILL
            if (on) paint.color = palette.background
            canvas.drawCircle(if (on) width - center else center, center, height * .29f, paint)
        }
    }
}
