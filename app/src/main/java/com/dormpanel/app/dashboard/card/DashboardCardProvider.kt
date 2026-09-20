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
import com.dormpanel.app.dashboard.model.PlacedCard

data class CardDisplayMetadata(
    val name: String,
    val description: String,
)

interface DashboardCardProvider {
    val typeKey: String
    val displayMetadata: CardDisplayMetadata
    val supportedSizes: List<CardSize>
    val defaultSize: CardSize
    val defaultConfigurationJson: String get() = "{}"

    fun createView(context: Context): View
    fun bind(view: View, card: PlacedCard)
}

class DashboardCardRegistry(
    providers: List<DashboardCardProvider>,
) : CardSizeCatalog {
    private val providersByType = providers.associateBy { it.typeKey }
    val providers: List<DashboardCardProvider> = providers.toList()

    init {
        require(providersByType.size == providers.size) { "Card provider type keys must be unique" }
        providers.forEach { provider ->
            require(provider.supportedSizes.isNotEmpty()) { "${provider.typeKey} has no supported sizes" }
            require(provider.defaultSize in provider.supportedSizes) {
                "${provider.typeKey} default size is unsupported"
            }
        }
    }

    fun provider(typeKey: String): DashboardCardProvider? = providersByType[typeKey]

    override fun supportedSizes(providerType: String): List<CardSize>? =
        providersByType[providerType]?.supportedSizes

    companion object {
        fun mock(): DashboardCardRegistry = DashboardCardRegistry(
            listOf(
                MockCardProvider(
                    typeKey = "mock.focus",
                    name = "Focus",
                    description = "Large multi-size demo card",
                    eyebrow = "FOCUS BLOCK",
                    body = "Quiet workspace",
                    accentColor = 0xFF76B7FF.toInt(),
                    supportedSizes = listOf(CardSize(2, 2), CardSize(4, 2)),
                    defaultSize = CardSize(4, 2),
                ),
                MockCardProvider(
                    typeKey = "mock.status",
                    name = "Status",
                    description = "Compact or square demo card",
                    eyebrow = "ROOM STATUS",
                    body = "All systems calm",
                    accentColor = 0xFF5DD39E.toInt(),
                    supportedSizes = listOf(CardSize(2, 1), CardSize(2, 2)),
                    defaultSize = CardSize(2, 2),
                ),
                MockCardProvider(
                    typeKey = "mock.shortcuts",
                    name = "Shortcuts",
                    description = "Wide strip demo card",
                    eyebrow = "SHORTCUTS",
                    body = "Study  ·  Relax  ·  Sleep",
                    accentColor = 0xFFDAA65E.toInt(),
                    supportedSizes = listOf(CardSize(2, 1), CardSize(4, 1)),
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
    override val supportedSizes: List<CardSize>,
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

    override fun bind(view: View, card: PlacedCard) {
        view.findViewById<TextView>(R.id.card_eyebrow).apply {
            text = eyebrow
            setTextColor(accentColor)
        }
        view.findViewById<TextView>(R.id.card_body).text = body
        view.contentDescription = "${displayMetadata.name}, ${card.size.columnSpan} by ${card.size.rowSpan}"
    }
}

private fun Int.dp(context: Context): Int = (this * context.resources.displayMetrics.density).toInt()
