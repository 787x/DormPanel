package com.dormpanel.app.dashboard

import com.dormpanel.app.dashboard.card.DashboardCardRegistry
import com.dormpanel.app.dashboard.layout.DashboardLayoutEngine
import com.dormpanel.app.dashboard.layout.LayoutFailureReason
import com.dormpanel.app.dashboard.layout.LayoutMutationResult
import com.dormpanel.app.dashboard.model.CardSize
import com.dormpanel.app.dashboard.model.DashboardGridPolicy
import com.dormpanel.app.dashboard.model.PlacedCard
import com.dormpanel.app.dashboard.persistence.DashboardStore
import java.util.UUID

data class DashboardUiState(
    val cards: List<PlacedCard> = emptyList(),
    val loaded: Boolean = false,
    val storageError: Boolean = false,
)

class DashboardStateHolder(
    private val registry: DashboardCardRegistry,
    private val store: DashboardStore,
) {
    private val engine = DashboardLayoutEngine(DashboardGridPolicy.definition, registry)
    private val listeners = linkedSetOf<(DashboardUiState) -> Unit>()
    var state = DashboardUiState()
        private set

    init {
        store.load(::onLoaded)
    }

    fun addListener(listener: (DashboardUiState) -> Unit) {
        listeners += listener
        listener(state)
    }

    fun removeListener(listener: (DashboardUiState) -> Unit) {
        listeners -= listener
    }

    fun add(providerType: String): LayoutMutationResult {
        val provider = registry.provider(providerType)
            ?: return LayoutMutationResult.Failure(LayoutFailureReason.UNKNOWN_PROVIDER, state.cards)
        val newCard = PlacedCard(
            id = UUID.randomUUID().toString(),
            providerType = provider.typeKey,
            column = 0,
            row = 0,
            size = provider.defaultSize,
            configurationJson = provider.defaultConfigurationJson,
        )
        return commit(engine.addFirstAvailable(state.cards, newCard))
    }

    fun delete(cardId: String): LayoutMutationResult = commit(engine.delete(state.cards, cardId))

    fun move(cardId: String, column: Int, row: Int): LayoutMutationResult =
        commit(engine.move(state.cards, cardId, column, row))

    fun resize(cardId: String, size: CardSize): LayoutMutationResult =
        commit(engine.resize(state.cards, cardId, size))

    fun previewMove(
        cards: List<PlacedCard>,
        cardId: String,
        column: Int,
        row: Int,
    ): LayoutMutationResult = engine.move(cards, cardId, column, row)

    fun close() = store.close()

    private fun onLoaded(result: Result<com.dormpanel.app.dashboard.persistence.StoredDashboard>) {
        result.fold(
            onSuccess = { stored ->
                val needsSeedOrRepair = !stored.initialized || engine.validate(stored.cards) != null
                val restored = if (!needsSeedOrRepair) {
                    stored.cards
                } else {
                    seededCards()
                }
                state = DashboardUiState(cards = restored, loaded = true)
                notifyListeners()
                if (needsSeedOrRepair) persist(restored)
            },
            onFailure = {
                state = DashboardUiState(cards = seededCards(), loaded = true, storageError = true)
                notifyListeners()
            },
        )
    }

    private fun commit(result: LayoutMutationResult): LayoutMutationResult {
        if (result is LayoutMutationResult.Success) {
            state = state.copy(cards = result.cards, storageError = false)
            notifyListeners()
            persist(result.cards)
        }
        return result
    }

    private fun persist(cards: List<PlacedCard>) {
        store.save(cards) { result ->
            if (result.isFailure && !state.storageError) {
                state = state.copy(storageError = true)
                notifyListeners()
            }
        }
    }

    private fun notifyListeners() = listeners.toList().forEach { it(state) }

    private fun seededCards(): List<PlacedCard> = listOf(
        PlacedCard("seed-focus", "mock.focus", 0, 0, CardSize(4, 2)),
        PlacedCard("seed-status", "mock.status", 4, 0, CardSize(2, 2)),
        PlacedCard("seed-shortcuts", "mock.shortcuts", 6, 0, CardSize(2, 1)),
        PlacedCard("seed-shortcuts-wide", "mock.shortcuts", 0, 2, CardSize(4, 1)),
    )
}
