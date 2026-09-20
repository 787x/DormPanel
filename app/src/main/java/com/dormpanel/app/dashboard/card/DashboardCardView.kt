package com.dormpanel.app.dashboard.card

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.dormpanel.app.R
import com.dormpanel.app.appearance.*
import com.dormpanel.app.dashboard.model.PlacedCard
import com.dormpanel.app.data.*
import org.json.JSONObject

/** Shared lifecycle/appearance mechanics only. Subclasses own their entire view hierarchy. */
@SuppressLint("ViewConstructor")
abstract class DashboardCardView(context: Context, protected val appearance: AppearanceController) : LinearLayout(context), AppearanceAware {
    protected lateinit var card: PlacedCard
    protected val isBound get() = ::card.isInitialized
    protected var interactions = CardInteractionScope(false) {}
    private val labels = mutableListOf<Pair<TextView, Boolean>>()
    init { orientation = VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(20.dp, 12.dp, 20.dp, 12.dp) }

    fun bind(card: PlacedCard, scope: CardInteractionScope) {
        this.card = card
        tag = card.id
        interactions = scope
        setOnClickListener(if (scope.enabled) OnClickListener { primaryAction() } else null)
        setOnLongClickListener(if (scope.enabled) OnLongClickListener { scope.claimGesture(); secondaryAction(); true } else null)
        isClickable = scope.enabled
        isLongClickable = scope.enabled
        render()
        applyAppearance(appearance.state)
    }
    protected open fun primaryAction() = Unit
    protected open fun secondaryAction() = Unit
    protected abstract fun render()
    protected fun refresh() { if (::card.isInitialized) render() }
    protected fun entityId(key: String, legacyDefault: String): String = try {
        JSONObject(card.configurationJson).optString(key, legacyDefault)
    } catch (_: org.json.JSONException) { "" }

    protected fun label(size: Float, secondary: Boolean = false) = TextView(context).apply {
        textSize = size
        includeFontPadding = false
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        if (size >= DashboardTypography.VALUE) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        labels += this to secondary
    }
    protected fun row() = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    protected fun column() = LinearLayout(context).apply { orientation = VERTICAL; gravity = Gravity.CENTER_VERTICAL }
    protected fun View.show(show: Boolean) { visibility = if (show) VISIBLE else GONE }
    protected fun describe(vararg texts: CharSequence) { contentDescription = texts.filter { it.isNotBlank() }.joinToString(", ") }
    protected val Int.dp get() = (this * resources.displayMetrics.density).toInt()
    override fun applyAppearance(state: AppearanceState) {
        val palette = PanelPalette.forMode(state.themeMode)
        labels.forEach { (view, secondary) -> view.setTextColor(if (secondary) palette.secondary else palette.text) }
    }
}

/** The retained value is only a render-change key; commands always read the authoritative source. */
@SuppressLint("ViewConstructor")
abstract class SourceCardView<T>(context: Context, appearance: AppearanceController, protected val source: DashboardDataSource) : DashboardCardView(context, appearance) {
    private var renderedKey: T? = null
    protected abstract fun select(data: DashboardData): T?
    private val listener: (DashboardData) -> Unit = { data ->
        if (isBound) {
            val key = select(data)
            if (key != renderedKey) { renderedKey = key; refresh() }
        }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); source.addListener(listener); refresh() }
    override fun onDetachedFromWindow() { source.removeListener(listener); renderedKey = null; super.onDetachedFromWindow() }
}

internal fun Availability.label(context: Context): String = context.getString(when (this) {
    Availability.AVAILABLE -> R.string.state_available
    Availability.UNAVAILABLE -> R.string.state_unavailable
    Availability.STALE -> R.string.state_stale
})
