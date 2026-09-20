package com.dormpanel.app.dashboard.card

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.dormpanel.app.appearance.AppearanceAware
import com.dormpanel.app.appearance.AppearanceController
import com.dormpanel.app.appearance.AppearanceState
import com.dormpanel.app.appearance.PanelPalette
import com.dormpanel.app.dashboard.model.CardSize
import com.dormpanel.app.dashboard.model.CardSizePolicy
import com.dormpanel.app.dashboard.model.ExplicitCardSizePolicy
import com.dormpanel.app.dashboard.model.PlacedCard
import com.dormpanel.app.dashboard.model.RangeCardSizePolicy
import com.dormpanel.app.data.Availability
import com.dormpanel.app.data.DashboardData
import com.dormpanel.app.data.DashboardDataSource
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class CoreCardKind(val key: String, val title: String, val description: String) {
    CLOCK("clock", "Clock & date", "Local device time"),
    WEATHER("weather", "Weather", "Sample weather and forecast"),
    SENSOR("sensor", "Room climate", "Sample temperature and humidity"),
    LIGHT("light", "Light", "Tap to toggle · Hold for controls"),
}

class CoreCardProvider(
    private val kind: CoreCardKind,
    private val source: DashboardDataSource,
    private val appearance: AppearanceController,
) : DashboardCardProvider {
    override val typeKey = kind.key
    override val displayMetadata = CardDisplayMetadata(kind.title, kind.description)
    override val sizePolicy: CardSizePolicy = when (kind) {
        CoreCardKind.LIGHT -> ExplicitCardSizePolicy(listOf(CardSize(2, 1), CardSize(2, 2), CardSize(3, 2), CardSize(3, 3)))
        else -> RangeCardSizePolicy(CardSize(2, 1), CardSize(4, 3))
    }
    override val defaultSize = if (kind == CoreCardKind.CLOCK || kind == CoreCardKind.WEATHER) CardSize(4, 3) else CardSize(2, 2)
    override val defaultConfigurationJson = when (kind) {
        CoreCardKind.SENSOR -> "{\"sensorId\":\"room\"}"
        CoreCardKind.LIGHT -> "{\"lightId\":\"desk\"}"
        else -> "{}"
    }

    override fun createView(context: Context): View = CoreCardView(context, kind, source, appearance)
    override fun bind(view: View, card: PlacedCard, interactions: CardInteractionScope) {
        (view as CoreCardView).bind(card, interactions)
    }
}

fun coreCardRegistry(source: DashboardDataSource, appearance: AppearanceController) =
    DashboardCardRegistry(CoreCardKind.entries.map { CoreCardProvider(it, source, appearance) })

/** Configuration and rendered text are UI projections; all device state stays in the source. */
class CoreCardView(
    context: Context,
    private val kind: CoreCardKind,
    private val source: DashboardDataSource,
    private val appearance: AppearanceController,
) : LinearLayout(context), AppearanceAware {
    private val title = label(14f)
    private val value = label(28f).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val detail = label(16f)
    private val extra = label(14f)
    private var density = CardDensity.STANDARD
    private var entityId = ""
    private var interaction = CardInteractionScope(false) {}
    private var activeClock = false
    private var aggregatedVisible = false
    private var dialog: LightQuickControls? = null
    private val dataListener: (DashboardData) -> Unit = { render() }
    private val tick = object : Runnable {
        override fun run() {
            if (!activeClock) return
            render()
            postDelayed(this, millisUntilNextMinute(System.currentTimeMillis()))
        }
    }
    private val timeChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            removeCallbacks(tick)
            if (activeClock) tick.run()
        }
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(20.dp, 10.dp, 20.dp, 10.dp)
        listOf(title, value, detail, extra).forEach { addView(it) }
        applyAppearance(appearance.state)
    }

    fun bind(card: PlacedCard, interactions: CardInteractionScope) {
        density = cardDensity(card.size)
        entityId = try {
            val config = JSONObject(card.configurationJson)
            when (kind) {
                CoreCardKind.SENSOR -> config.optString("sensorId", "room")
                CoreCardKind.LIGHT -> config.optString("lightId", "desk")
                else -> ""
            }
        } catch (_: org.json.JSONException) { "" }
        interaction = interactions
        if (!interactions.enabled) { dialog?.dismiss(); dialog = null }
        setOnClickListener(if (interactions.enabled && kind == CoreCardKind.LIGHT) OnClickListener {
            source.toggleLight(entityId)
        } else null)
        // Consume a card long press even on informational cards; only empty space enters edit mode.
        setOnLongClickListener(if (interactions.enabled) OnLongClickListener {
            interaction.claimGesture()
            if (kind == CoreCardKind.LIGHT && dialog == null) {
                dialog = LightQuickControls(context, source, appearance, entityId, interaction) { dialog = null }
                dialog?.show()
            }
            true
        } else null)
        isClickable = interactions.enabled
        isLongClickable = interactions.enabled
        render()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (kind != CoreCardKind.CLOCK) source.addListener(dataListener)
        updateClockActivity()
    }

    override fun onDetachedFromWindow() {
        source.removeListener(dataListener)
        stopClock()
        dialog?.dismiss()
        dialog = null
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        aggregatedVisible = isVisible
        updateClockActivity()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateClockActivity()
    }

    private fun updateClockActivity() {
        if (kind != CoreCardKind.CLOCK) return
        val shouldRun = isAttachedToWindow && aggregatedVisible && windowVisibility == VISIBLE
        if (shouldRun == activeClock) return
        if (!shouldRun) { stopClock(); return }
        activeClock = true
        ContextCompat.registerReceiver(context, timeChanged, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        tick.run()
    }

    private fun stopClock() {
        removeCallbacks(tick)
        if (activeClock) context.unregisterReceiver(timeChanged)
        activeClock = false
    }

    override fun applyAppearance(state: AppearanceState) {
        val palette = PanelPalette.forMode(state.themeMode)
        title.setTextColor(palette.secondary)
        value.setTextColor(palette.text)
        detail.setTextColor(palette.text)
        extra.setTextColor(palette.secondary)
    }

    private fun render() {
        val compact = density == CardDensity.COMPACT
        val expanded = density == CardDensity.EXPANDED
        title.visibility = if (compact && kind == CoreCardKind.CLOCK) GONE else VISIBLE
        detail.visibility = if (compact) GONE else VISIBLE
        extra.visibility = if (expanded) VISIBLE else GONE
        value.textSize = if (kind == CoreCardKind.CLOCK) { if (compact) 32f else if (expanded) 64f else 44f } else if (compact) 21f else 30f
        when (kind) {
            CoreCardKind.CLOCK -> {
                val now = Date()
                title.text = "LOCAL TIME"
                value.text = DateFormat.getTimeFormat(context).format(now)
                detail.text = SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(now)
                extra.text = SimpleDateFormat("yyyy · z", Locale.getDefault()).format(now)
            }
            CoreCardKind.WEATHER -> {
                val weather = source.state.weather
                title.text = if (compact) weather.condition else "WEATHER · SAMPLE"
                value.text = if (weather.availability == Availability.UNAVAILABLE) "Unavailable" else "${weather.temperature}°${if (compact) "" else "  ${weather.condition}"}"
                detail.text = "${weather.low}–${weather.high}°  ·  Humidity ${weather.humidity}%"
                extra.text = weather.forecast
                if (weather.availability != Availability.AVAILABLE) {
                    title.text = "Weather · ${weather.availability.label()}"
                    if (weather.availability == Availability.UNAVAILABLE) { detail.text = "Weather data unavailable"; extra.text = "" }
                }
            }
            CoreCardKind.SENSOR -> {
                val sensor = source.state.sensors[entityId]
                val status = sensor?.availability ?: Availability.UNAVAILABLE
                title.text = (sensor?.name ?: "Sensor") + if (status == Availability.AVAILABLE) "" else " · ${status.label()}"
                val temperature = if (status == Availability.UNAVAILABLE) "—" else sensor?.temperature?.let { String.format(Locale.getDefault(), "%.1f°", it) } ?: "—"
                val humidity = if (status == Availability.UNAVAILABLE) "—" else sensor?.humidity?.let { "$it%" } ?: "—"
                value.text = if (compact) "$temperature  ·  $humidity" else temperature
                detail.text = "Humidity $humidity"
                extra.text = "Sample data · ${status.label()}"
            }
            CoreCardKind.LIGHT -> {
                val light = source.state.lights[entityId]
                val status = light?.availability ?: Availability.UNAVAILABLE
                title.text = light?.name ?: "Light"
                value.text = if (status != Availability.AVAILABLE) status.label() else if (light?.isOn == true) "On" else "Off"
                detail.text = if (light == null) "Device not found" else buildList {
                    if (light.capabilities.brightness) add("${light.brightness}%")
                    if (light.capabilities.colorTemperature != null) add("${light.colorTemperature} K")
                    if (isEmpty()) add("On / off")
                }.joinToString("  ·  ")
                extra.text = "Tap to toggle\nHold for controls · Sample"
            }
        }
        contentDescription = listOf(title, value, detail, extra).filter { it.visibility == VISIBLE }.joinToString(", ") { it.text }
    }

    private fun label(size: Float) = TextView(context).apply {
        textSize = size
        includeFontPadding = false
        setPadding(0, 2.dp, 0, 2.dp)
    }
    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()
}

internal fun Availability.label() = when (this) {
    Availability.AVAILABLE -> "Available"
    Availability.UNAVAILABLE -> "Unavailable"
    Availability.STALE -> "Stale"
}
