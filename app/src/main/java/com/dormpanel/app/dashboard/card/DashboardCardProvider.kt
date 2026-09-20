package com.dormpanel.app.dashboard.card

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.dormpanel.app.R
import com.dormpanel.app.dashboard.layout.CardSizeCatalog
import com.dormpanel.app.dashboard.model.CardSize
import com.dormpanel.app.dashboard.model.CardSizePolicy
import com.dormpanel.app.dashboard.model.ExplicitCardSizePolicy
import com.dormpanel.app.dashboard.model.PlacedCard
import com.dormpanel.app.dashboard.model.RangeCardSizePolicy

data class CardDisplayMetadata(
    val name: String,
    val description: String,
)

class CardInteractionScope(
    val enabled: Boolean,
    private val onGestureClaimed: () -> Unit,
) {
    /** Call when a delayed interaction such as long press takes ownership of the stream. */
    fun claimGesture() {
        if (enabled) onGestureClaimed()
    }

    /** Marks controls such as sliders as owning their stream from ACTION_DOWN. */
    fun claimFromDown(view: View) {
        view.setTag(R.id.tag_claims_page_gesture, if (enabled) true else null)
    }
}

interface DashboardCardProvider {
    val typeKey: String
    val displayMetadata: CardDisplayMetadata
    val sizePolicy: CardSizePolicy
    val defaultSize: CardSize
    val defaultConfigurationJson: String get() = "{}"

    fun createView(context: Context): View
    fun bind(view: View, card: PlacedCard, interactions: CardInteractionScope)
}

class DashboardCardRegistry(
    providers: List<DashboardCardProvider>,
) : CardSizeCatalog {
    private val providersByType = providers.associateBy { it.typeKey }
    val providers: List<DashboardCardProvider> = providers.toList()

    init {
        require(providersByType.size == providers.size) { "Card provider type keys must be unique" }
        providers.forEach { provider ->
            require(provider.sizePolicy.allows(provider.defaultSize)) {
                "${provider.typeKey} default size is unsupported"
            }
        }
    }

    fun provider(typeKey: String): DashboardCardProvider? = providersByType[typeKey]

    override fun sizePolicy(providerType: String): CardSizePolicy? =
        providersByType[providerType]?.sizePolicy

    fun snapSize(providerType: String, candidate: CardSize, maximum: CardSize): CardSize? =
        providersByType[providerType]?.sizePolicy?.snap(candidate, maximum)

    fun hasAlternativeSize(providerType: String, current: CardSize, maximum: CardSize): Boolean =
        providersByType[providerType]?.sizePolicy?.hasAlternative(current, maximum) == true

    companion object {
        fun mock(): DashboardCardRegistry = DashboardCardRegistry(
            listOf(
                MockCardProvider(
                    typeKey = "mock.focus",
                    name = "Focus",
                    description = "Freely resizable demo card",
                    eyebrow = "FOCUS BLOCK",
                    body = "Quiet workspace",
                    accentColor = 0xFF76B7FF.toInt(),
                    sizePolicy = RangeCardSizePolicy(
                        minimum = CardSize(2, 1),
                        maximum = CardSize(4, 3),
                    ),
                    defaultSize = CardSize(3, 2),
                ),
                MockCardProvider(
                    typeKey = "mock.status",
                    name = "Status",
                    description = "Compact or square demo card",
                    eyebrow = "ROOM STATUS",
                    body = "All systems calm",
                    accentColor = 0xFF5DD39E.toInt(),
                    sizePolicy = ExplicitCardSizePolicy(listOf(CardSize(2, 1), CardSize(2, 2))),
                    defaultSize = CardSize(2, 2),
                ),
                MockCardProvider(
                    typeKey = "mock.shortcuts",
                    name = "Shortcuts",
                    description = "Wide strip demo card",
                    eyebrow = "SHORTCUTS",
                    body = "Study  ·  Relax  ·  Sleep",
                    accentColor = 0xFFDAA65E.toInt(),
                    sizePolicy = ExplicitCardSizePolicy(listOf(CardSize(2, 1), CardSize(4, 1))),
                    defaultSize = CardSize(2, 1),
                ),
            ),
        )
    }
}

private class MockCardProvider(
    override val typeKey: String,
    name: String,
    description: String,
    private val eyebrow: String,
    private val body: String,
    private val accentColor: Int,
    override val sizePolicy: CardSizePolicy,
    override val defaultSize: CardSize,
) : DashboardCardProvider {
    override val displayMetadata = CardDisplayMetadata(name, description)

    override fun createView(context: Context): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(20.dp(context), 16.dp(context), 20.dp(context), 16.dp(context))
        addView(TextView(context).apply {
            id = R.id.card_eyebrow
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.12f
        })
        addView(TextView(context).apply {
            id = R.id.card_body
            textSize = 21f
            setTextColor(context.getColor(R.color.panel_primary_text))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        })
    }

    override fun bind(view: View, card: PlacedCard, interactions: CardInteractionScope) {
        view.findViewById<TextView>(R.id.card_eyebrow).apply {
            text = eyebrow
            setTextColor(accentColor)
        }
        val bodyView = view.findViewById<TextView>(R.id.card_body)
        bodyView.text = view.context.getString(
            R.string.mock_card_body_with_size,
            body,
            card.size.columnSpan,
            card.size.rowSpan,
        )
        view.contentDescription = "${displayMetadata.name}, ${card.size.columnSpan} by ${card.size.rowSpan}"
        view.isClickable = interactions.enabled
        view.isLongClickable = interactions.enabled
        if (interactions.enabled) {
            view.setOnClickListener {
                bodyView.text = view.context.getString(
                    R.string.mock_card_tap_action,
                    card.size.columnSpan,
                    card.size.rowSpan,
                )
            }
            view.setOnLongClickListener {
                interactions.claimGesture()
                bodyView.text = view.context.getString(
                    R.string.mock_card_secondary_action,
                    card.size.columnSpan,
                    card.size.rowSpan,
                )
                true
            }
        } else {
            view.setOnClickListener(null)
            view.setOnLongClickListener(null)
        }
    }
}

private fun Int.dp(context: Context): Int = (this * context.resources.displayMetrics.density).toInt()
