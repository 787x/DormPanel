package com.dormpanel.app.dashboard.card

import android.content.Context
import android.view.View
import com.dormpanel.app.R
import com.dormpanel.app.dashboard.layout.CardSizeCatalog
import com.dormpanel.app.dashboard.model.CardSize
import com.dormpanel.app.dashboard.model.CardSizePolicy
import com.dormpanel.app.dashboard.model.PlacedCard

data class CardDisplayMetadata(
    val name: String,
    val description: String,
    val nameResource: Int? = null,
    val descriptionResource: Int? = null,
) {
    fun name(context: Context) = nameResource?.let(context::getString) ?: name
    fun description(context: Context) = descriptionResource?.let(context::getString) ?: description
}

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

}
