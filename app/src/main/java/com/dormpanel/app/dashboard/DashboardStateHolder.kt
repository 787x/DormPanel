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
import com.dormpanel.app.dashboard.card.WeatherCardConfiguration
import com.dormpanel.app.dashboard.card.canSave
import com.dormpanel.app.dashboard.card.isWeatherCard
import com.dormpanel.app.dashboard.card.isSwitchCard
import com.dormpanel.app.dashboard.card.SwitchCardConfiguration
import java.util.UUID
import com.dormpanel.app.dashboard.catalog.CardAddCandidate

enum class ConfigurationSaveState { SAVING, FAILED }

data class DashboardUiState(
    val cards: List<PlacedCard> = emptyList(),
    val loaded: Boolean = false,
    val storageError: Boolean = false,
    val configurationSaves: Map<String, ConfigurationSaveState> = emptyMap(),
)

class DashboardStateHolder(
    private val registry: DashboardCardRegistry,
    private val store: DashboardStore,
    private val weatherPlaces: com.dormpanel.app.weather.WeatherPlaceStore? = null,
) {
    private val engine = DashboardLayoutEngine(DashboardGridPolicy.definition, registry)
    // All owner operations and callbacks run on the UI scheduler.
    private var closed = false
    private var pendingRecovery: List<QuarantinedCard>? = null
    private class ConfigurationWrite(val waitForLatest: Boolean = false, var attempt: Long = 0)
    private val configurationWrites = mutableMapOf<String, ConfigurationWrite>()
    private var writeAttempt = 0L
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

    fun add(candidate: CardAddCandidate): LayoutMutationResult = add(candidate, UUID.randomUUID().toString())

    internal fun add(candidate: CardAddCandidate, cardId: String): LayoutMutationResult {
        val provider = registry.provider(candidate.providerType)
            ?: return LayoutMutationResult.Failure(LayoutFailureReason.UNKNOWN_PROVIDER, state.cards)
        if (!validConfiguration(provider.typeKey, candidate.configurationJson, editing = false)) {
            return LayoutMutationResult.Failure(LayoutFailureReason.INVALID_CONFIGURATION, state.cards)
        }
        val newCard = PlacedCard(
            id = cardId,
            providerType = provider.typeKey,
            column = 0,
            row = 0,
            size = provider.defaultSize,
            configurationJson = candidate.configurationJson,
        )
        val placement = engine.addBestFit(state.cards, newCard)
        if (!closed && placement is LayoutMutationResult.Success && provider.typeKey.isSwitchCard()) {
            beginConfigurationSave(cardId, waitForLatest = true)
        }
        return commit(placement)
    }

    fun updateConfiguration(cardId: String, configurationJson: String): LayoutMutationResult {
        val card = state.cards.firstOrNull { it.id == cardId }
            ?: return LayoutMutationResult.Failure(LayoutFailureReason.CARD_NOT_FOUND, state.cards)
        if (!validConfiguration(card.providerType, configurationJson, editing = true)) {
            return LayoutMutationResult.Failure(LayoutFailureReason.INVALID_CONFIGURATION, state.cards)
        }
        if (closed) return LayoutMutationResult.Failure(LayoutFailureReason.INVALID_EXISTING_LAYOUT, state.cards)
        val saveState = state.configurationSaves[cardId]
        if (card.configurationJson == configurationJson &&
            (saveState == ConfigurationSaveState.SAVING || (saveState == null && !state.storageError))) {
            return LayoutMutationResult.Success(state.cards)
        }
        beginConfigurationSave(cardId, configurationWrites[cardId]?.waitForLatest == true)
        return commit(LayoutMutationResult.Success(state.cards.map {
            if (it.id == cardId) it.copy(configurationJson = configurationJson) else it
        }))
    }

    private fun beginConfigurationSave(cardId: String, waitForLatest: Boolean) {
        configurationWrites[cardId] = ConfigurationWrite(waitForLatest)
        state = state.copy(configurationSaves = state.configurationSaves + (cardId to ConfigurationSaveState.SAVING))
    }

    private fun validConfiguration(type: String, json: String, editing: Boolean) = when {
        type.isWeatherCard() -> WeatherCardConfiguration.decode(json).canSave(weatherPlaces)
        type.isSwitchCard() -> SwitchCardConfiguration.decode(json)?.canSave(type) == true
        else -> !editing
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
            val ids = result.cards.mapTo(mutableSetOf()) { it.id }
            configurationWrites.keys.retainAll(ids)
            state = state.copy(cards = result.cards, storageError = false,
                configurationSaves = state.configurationSaves.filterKeys { it in ids })
            persist(result.cards)
            notifyListeners()
        }
        return result
    }

    private fun persist(cards: List<PlacedCard>) {
        if (closed) return
        val attempt = ++writeAttempt
        val writes = configurationWrites.toMap()
        writes.values.forEach { it.attempt = attempt }
        val recovery = pendingRecovery
        val complete: (Result<Unit>) -> Unit = complete@{ result ->
            if (closed) return@complete
            if (recovery != null && result.isSuccess && pendingRecovery === recovery) pendingRecovery = null

            val saves = state.configurationSaves.toMutableMap()
            writes.forEach { (id, write) ->
                if (configurationWrites[id] === write) {
                    if (result.isSuccess && (!write.waitForLatest || write.attempt == attempt)) {
                        configurationWrites.remove(id)
                        saves.remove(id)
                    } else if (result.isFailure && write.attempt == attempt) {
                        saves[id] = ConfigurationSaveState.FAILED
                    }
                }
            }
            val error = if (attempt == writeAttempt) result.isFailure else state.storageError
            if (error != state.storageError || saves != state.configurationSaves) {
                state = state.copy(storageError = error, configurationSaves = saves)
                notifyListeners()
            }
        }
        if (recovery == null) store.save(cards, complete)
        else store.saveRepair(cards, recovery, complete)
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
