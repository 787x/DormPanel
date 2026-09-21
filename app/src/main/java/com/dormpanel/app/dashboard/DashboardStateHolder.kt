package com.dormpanel.app.dashboard

import com.dormpanel.app.dashboard.card.DashboardCardRegistry
import com.dormpanel.app.dashboard.layout.DashboardLayoutEngine
import com.dormpanel.app.dashboard.layout.LayoutFailureReason
import com.dormpanel.app.dashboard.layout.LayoutMutationResult
import com.dormpanel.app.dashboard.model.CardSize
import com.dormpanel.app.dashboard.model.DashboardGridPolicy
import com.dormpanel.app.dashboard.model.PlacedCard
import com.dormpanel.app.dashboard.persistence.DashboardStore
import com.dormpanel.app.dashboard.persistence.QuarantinedCard
import java.util.UUID
import com.dormpanel.app.dashboard.catalog.CardAddCandidate

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
    // All owner operations and callbacks run on the UI scheduler.
    private var closed = false
    private var pendingRecovery: List<QuarantinedCard>? = null
    private val listeners = linkedSetOf<(DashboardUiState) -> Unit>()
    var state = DashboardUiState()
        private set

    init {
        store.load(::onLoaded)
    }

    fun addListener(listener: (DashboardUiState) -> Unit) {
        if (closed) return
        listeners += listener
        listener(state)
    }

    fun removeListener(listener: (DashboardUiState) -> Unit) {
        listeners -= listener
    }

    fun add(candidate: CardAddCandidate): LayoutMutationResult {
        val provider = registry.provider(candidate.providerType)
            ?: return LayoutMutationResult.Failure(LayoutFailureReason.UNKNOWN_PROVIDER, state.cards)
        val newCard = PlacedCard(
            id = UUID.randomUUID().toString(),
            providerType = provider.typeKey,
            column = 0,
            row = 0,
            size = provider.defaultSize,
            configurationJson = candidate.configurationJson,
        )
        return commit(engine.addFirstAvailable(state.cards, newCard))
    }

    fun delete(cardId: String): LayoutMutationResult = commit(engine.delete(state.cards, cardId))

    fun move(cardId: String, column: Int, row: Int): LayoutMutationResult =
        commit(engine.move(state.cards, cardId, column, row))

    fun resize(cardId: String, size: CardSize): LayoutMutationResult {
        val current = state.cards.firstOrNull { it.id == cardId }
            ?: return LayoutMutationResult.Failure(LayoutFailureReason.CARD_NOT_FOUND, state.cards)
        if (current.size == size) return LayoutMutationResult.Success(state.cards)
        return commit(engine.resize(state.cards, cardId, size))
    }

    fun previewMove(
        cards: List<PlacedCard>,
        cardId: String,
        column: Int,
        row: Int,
    ): LayoutMutationResult = engine.move(cards, cardId, column, row)

    fun previewResize(
        cards: List<PlacedCard>,
        cardId: String,
        size: CardSize,
    ): LayoutMutationResult = engine.resize(cards, cardId, size)

    fun close() {
        if (closed) return
        closed = true
        listeners.clear()
        store.close()
    }

    private fun onLoaded(result: Result<com.dormpanel.app.dashboard.persistence.StoredDashboard>) {
        if (closed) return
        result.fold(
            onSuccess = { stored ->
                val repair = com.dormpanel.app.dashboard.layout.DashboardLayoutRepair(
                    DashboardGridPolicy.definition, registry,
                ).repair(stored.rawCards, stored.quarantine)
                val restored = if (!stored.initialized) seededCards() else repair.cards
                state = DashboardUiState(cards = restored, loaded = true)
                // Submit before notifying: a listener may synchronously edit or close this holder.
                if (!stored.initialized) persist(restored)
                else if (restored.map(com.dormpanel.app.dashboard.persistence.RawDashboardCard::from) != stored.rawCards ||
                    repair.quarantine != stored.quarantine) {
                    pendingRecovery = repair.quarantine
                    persist(restored)
                }
                notifyListeners()
            },
            onFailure = {
                state = DashboardUiState(cards = seededCards(), loaded = true, storageError = true)
                notifyListeners()
            },
        )
    }

    private fun commit(result: LayoutMutationResult): LayoutMutationResult {
        if (closed) return LayoutMutationResult.Failure(LayoutFailureReason.INVALID_EXISTING_LAYOUT, state.cards)
        if (result is LayoutMutationResult.Success) {
            state = state.copy(cards = result.cards, storageError = false)
            persist(result.cards)
            notifyListeners()
        }
        return result
    }

    private fun persist(cards: List<PlacedCard>) {
        if (closed) return
        val recovery = pendingRecovery
        if (recovery == null) store.save(cards, ::onSaved)
        else store.saveRepair(cards, recovery) { result ->
            if (closed) return@saveRepair
            // Keep retrying the atomic repair on subsequent edits if disk persistence failed.
            if (result.isSuccess && pendingRecovery === recovery) pendingRecovery = null
            onSaved(result)
        }
    }

    private fun onSaved(result: Result<Unit>) {
        if (closed) return
        if (result.isFailure && !state.storageError) {
            state = state.copy(storageError = true)
            notifyListeners()
        }
    }

    private fun notifyListeners() {
        for (listener in listeners.toList()) {
            if (closed) break
            listener(state)
        }
    }

    private fun seededCards(): List<PlacedCard> = listOf(
        PlacedCard("seed-clock", "clock", 0, 0, CardSize(4, 3)),
        PlacedCard("seed-weather", "weather", 4, 0, CardSize(4, 3)),
        PlacedCard("seed-sensor", "sensor", 0, 3, CardSize(2, 2), "{\"sensorId\":\"room\"}"),
        PlacedCard("seed-light", "light", 2, 3, CardSize(2, 2), "{\"lightId\":\"desk\"}"),
        PlacedCard("seed-bedside", "light", 4, 3, CardSize(2, 2), "{\"lightId\":\"bedside\"}"),
        PlacedCard("seed-ceiling", "light", 6, 3, CardSize(2, 2), "{\"lightId\":\"ceiling\"}"),
    )
}
