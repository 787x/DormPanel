package com.dormpanel.app.dashboard

import com.dormpanel.app.dashboard.card.coreCardRegistry
import com.dormpanel.app.dashboard.card.WeatherCardConfiguration
import com.dormpanel.app.dashboard.layout.LayoutFailureReason
import com.dormpanel.app.weather.WeatherPlace
import com.dormpanel.app.weather.WeatherPlaceStore
import com.dormpanel.app.weather.WeatherSourceRef
import java.time.ZoneId
import com.dormpanel.app.appearance.*
import com.dormpanel.app.data.FakeDashboardDataSource
import com.dormpanel.app.dashboard.layout.LayoutMutationResult
import com.dormpanel.app.dashboard.model.CardSize
import com.dormpanel.app.dashboard.persistence.DashboardStore
import com.dormpanel.app.dashboard.persistence.StoredDashboard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class DashboardStateHolderTest {
    @Test fun `automatic fallback persists the chosen size and retains neighbors`() {
        val cards = buildList {
            for (row in 0..5) for (column in 0..3) {
                if (row != 5 || column != 3) add(com.dormpanel.app.dashboard.model.PlacedCard("$column:$row", "sensor", column * 2, row, CardSize(2, 1)))
            }
        }
        val store = FakeStore(StoredDashboard(true, 1, cards))
        val holder = DashboardStateHolder(registry(), store)
        val candidate = com.dormpanel.app.dashboard.catalog.CardAddCandidate("clock", com.dormpanel.app.dashboard.catalog.CardCategory.INFORMATION, "clock", "Clock", "Local", "{}")
        assertTrue(holder.add(candidate) is LayoutMutationResult.Success)
        assertEquals(cards, holder.state.cards.dropLast(1))
        assertEquals(CardSize(2, 1), holder.state.cards.last().size)
        assertEquals(holder.state.cards, store.savedSnapshots.single())
    }

    @Test
    fun `first launch seeds once and persists stable ids`() {
        val store = FakeStore(StoredDashboard(false, 0, emptyList()))

        val holder = DashboardStateHolder(registry(), store)

        assertTrue(holder.state.loaded)
        assertEquals(listOf("seed-clock", "seed-weather", "seed-sensor", "seed-light", "seed-bedside", "seed-ceiling"), holder.state.cards.map { it.id })
        assertEquals(1, store.savedSnapshots.size)
        assertEquals(holder.state.cards, store.savedSnapshots.single())
    }

    @Test
    fun `initialized empty dashboard is restored without reseeding`() {
        val store = FakeStore(StoredDashboard(true, 4, emptyList()))

        val holder = DashboardStateHolder(registry(), store)

        assertTrue(holder.state.loaded)
        assertTrue(holder.state.cards.isEmpty())
        assertTrue(store.savedSnapshots.isEmpty())
    }

    @Test
    fun `drag preview does not persist but committed move does`() {
        val registry = registry()
        val store = FakeStore(StoredDashboard(false, 0, emptyList()))
        val holder = DashboardStateHolder(registry, store)
        store.savedSnapshots.clear()

        val preview = holder.previewMove(holder.state.cards, "seed-light", 2, 3)

        assertTrue(preview is LayoutMutationResult.Success)
        assertTrue(store.savedSnapshots.isEmpty())

        val committed = holder.move("seed-light", 2, 3)

        assertTrue(committed is LayoutMutationResult.Success)
        assertEquals(1, store.savedSnapshots.size)
        assertEquals(2, store.savedSnapshots.single().first { it.id == "seed-light" }.column)
        assertEquals(3, store.savedSnapshots.single().first { it.id == "seed-light" }.row)
    }

    @Test
    fun `resize preview does not persist and commit persists exactly once`() {
        val store = FakeStore(StoredDashboard(false, 0, emptyList()))
        val holder = DashboardStateHolder(registry(), store)
        store.savedSnapshots.clear()

        val preview = holder.previewResize(holder.state.cards, "seed-clock", CardSize(4, 2))

        assertTrue(preview is LayoutMutationResult.Success)
        assertTrue(store.savedSnapshots.isEmpty())

        val committed = holder.resize("seed-clock", CardSize(4, 2))
        assertTrue(committed is LayoutMutationResult.Success)
        assertEquals(1, store.savedSnapshots.size)

        holder.resize("seed-clock", CardSize(4, 2))
        assertEquals(1, store.savedSnapshots.size)
    }

    @Test fun `candidate configuration is preserved and duplicate devices get distinct card ids`() {
        val store = FakeStore(StoredDashboard(true, 1, emptyList()))
        val holder = DashboardStateHolder(registry(), store)
        val bedside = com.dormpanel.app.dashboard.catalog.CardAddCandidate("light:bedside", com.dormpanel.app.dashboard.catalog.CardCategory.HOME,
            "light", "Bedside", "Dimmable", "{\"lightId\":\"bedside\"}")
        assertTrue(holder.add(bedside) is LayoutMutationResult.Success)
        assertTrue(holder.add(bedside) is LayoutMutationResult.Success)
        val ceiling = bedside.copy(candidateId = "light:ceiling", configurationJson = "{\"lightId\":\"ceiling\"}")
        assertTrue(holder.add(ceiling) is LayoutMutationResult.Success)
        assertEquals(listOf(bedside.configurationJson, bedside.configurationJson, ceiling.configurationJson), holder.state.cards.map { it.configurationJson })
        assertEquals(3, holder.state.cards.map { it.id }.distinct().size)
        assertEquals(holder.state.cards, store.savedSnapshots.last())
    }

    @Test fun `configuration writes are scoped to one card and unauthorized bindings are rejected`() {
        val places = WeatherPlaceStore("[]") { true }
        val source = WeatherSourceRef.QWeatherCity("101010100")
        val place = WeatherPlace("beijing", "北京", ZoneId.of("Asia/Shanghai"), listOf(source))
        places.save(place)
        places.addLookup(place.copy(id = "preview"))
        val store = DelayedStore()
        val holder = DashboardStateHolder(registry(), store, places)
        store.loaded(Result.success(StoredDashboard(false, 0, emptyList())))
        val initial = holder.state.cards
        val binding = WeatherCardConfiguration.Bound(place.id, source)
        fun failure(cardId: String, config: String) =
            (holder.updateConfiguration(cardId, config) as LayoutMutationResult.Failure).reason
        assertEquals(LayoutFailureReason.CARD_NOT_FOUND, failure("missing", binding.encode()))
        assertEquals(LayoutFailureReason.INVALID_CONFIGURATION, failure("seed-clock", binding.encode()))
        assertEquals(LayoutFailureReason.INVALID_CONFIGURATION, failure("seed-weather", """{"version":2}"""))
        assertEquals(LayoutFailureReason.INVALID_CONFIGURATION, failure("seed-weather", binding.copy(placeId = "preview").encode()))
        assertEquals(LayoutFailureReason.INVALID_CONFIGURATION,
            failure("seed-weather", binding.copy(source = WeatherSourceRef.QWeatherCity("101020100")).encode()))
        assertEquals(initial, holder.state.cards)
        assertEquals(1, store.saves)

        assertTrue(holder.updateConfiguration("seed-weather", binding.encode()) is LayoutMutationResult.Success)
        assertEquals(initial.map { if (it.id == "seed-weather") it.copy(configurationJson = binding.encode()) else it }, holder.state.cards)
        assertEquals(2, store.saves)
        store.saved(Result.failure(IllegalStateException("disk full")))
        assertTrue(holder.state.storageError)
        assertTrue(holder.updateConfiguration("seed-weather", binding.encode()) is LayoutMutationResult.Success)
        assertEquals(3, store.saves)
    }

    @Test fun `configuration acknowledgements follow the submitted card revisions and retry latest layout`() {
        val places = WeatherPlaceStore("[]") { true }
        val source = WeatherSourceRef.QWeatherCity("101010100")
        places.save(WeatherPlace("city", "北京", ZoneId.of("Asia/Shanghai"), listOf(source)))
        val store = DelayedStore()
        val holder = DashboardStateHolder(registry(), store, places)
        val left = com.dormpanel.app.dashboard.model.PlacedCard("left", "weather", 0, 0, CardSize(2, 1))
        val right = left.copy(id = "right", providerType = "weather_compact_2x3", column = 2, size = CardSize(2, 3))
        store.loaded(Result.success(StoredDashboard(true, 1, listOf(left, right))))
        assertEquals(listOf(left, right), holder.state.cards)
        assertTrue(holder.resize(right.id, CardSize(3, 3)) is LayoutMutationResult.Failure)
        val state = WeatherCardConfiguration.Bound("city", source)
        val initiating = state.copy(displayName = "第一次").encode()
        val latest = state.copy(displayName = "第二次").encode()
        val neighbor = state.copy(displayName = "另一张").encode()
        val failed = Result.failure<Unit>(IllegalStateException("disk full"))

        holder.updateConfiguration(left.id, initiating)
        assertEquals(ConfigurationSaveState.SAVING, holder.state.configurationSaves[left.id])
        val firstAck = store.saved
        holder.updateConfiguration(left.id, latest)
        val secondAck = store.saved
        firstAck(Result.success(Unit))
        assertEquals(ConfigurationSaveState.SAVING, holder.state.configurationSaves[left.id])
        holder.updateConfiguration(right.id, neighbor)
        val combinedAck = store.saved
        secondAck(failed)
        assertFalse(holder.state.storageError)
        assertEquals(mapOf(left.id to ConfigurationSaveState.SAVING, right.id to ConfigurationSaveState.SAVING),
            holder.state.configurationSaves)
        combinedAck(failed)
        assertEquals(mapOf(left.id to ConfigurationSaveState.FAILED, right.id to ConfigurationSaveState.FAILED),
            holder.state.configurationSaves)
        holder.move(right.id, 4, 0)
        store.saved(failed)
        holder.updateConfiguration(left.id, latest)
        assertEquals(listOf(left.copy(configurationJson = latest), right.copy(column = 4, configurationJson = neighbor)),
            store.lastSnapshot)
        store.saved(Result.success(Unit))
        assertTrue(holder.state.configurationSaves.isEmpty())
        assertFalse(holder.state.storageError)
        assertEquals(store.lastSnapshot, holder.state.cards)
    }

    private fun registry() = coreCardRegistry(FakeDashboardDataSource(), AppearanceController(object : AppearanceStore {
        override fun read() = AppearanceState()
        override fun write(state: AppearanceState) = Unit
    }))

    @Test fun `legacy mock layout is repaired and valid production layout preserved`() {
        val legacy = com.dormpanel.app.dashboard.model.PlacedCard("old", "mock.focus", 0, 0, CardSize(3, 2))
        val store = FakeStore(StoredDashboard(true, 1, listOf(legacy)))
        val repaired = DashboardStateHolder(registry(), store)
        assertTrue(repaired.state.cards.none { it.providerType.startsWith("mock.") })
        assertEquals(1, store.savedSnapshots.size)
        val valid = repaired.state.cards.map { it.copy(id = "custom-${it.id}") }
        val restoredStore = FakeStore(StoredDashboard(true, 2, valid))
        assertEquals(valid, DashboardStateHolder(registry(), restoredStore).state.cards)
        assertTrue(restoredStore.savedSnapshots.isEmpty())
    }

    private class DelayedStore : DashboardStore {
        lateinit var loaded: (Result<StoredDashboard>) -> Unit
        lateinit var saved: (Result<Unit>) -> Unit
        var saves = 0
        var closes = 0
        val repairs = mutableListOf<List<com.dormpanel.app.dashboard.persistence.QuarantinedCard>>()
        override fun load(callback: (Result<StoredDashboard>) -> Unit) { loaded = callback }
        var lastSnapshot = emptyList<com.dormpanel.app.dashboard.model.PlacedCard>()
        override fun save(cards: List<com.dormpanel.app.dashboard.model.PlacedCard>, callback: (Result<Unit>) -> Unit) {
            saves++
            saved = callback
            lastSnapshot = cards
        }
        override fun saveRepair(cards: List<com.dormpanel.app.dashboard.model.PlacedCard>, quarantine: List<com.dormpanel.app.dashboard.persistence.QuarantinedCard>, callback: (Result<Unit>) -> Unit) {
            repairs += quarantine
            save(cards, callback)
        }
        override fun close() { closes++ }
    }
    @Test fun `late load and late failure cannot seed save mutate or notify a closed owner`() {
        val store = DelayedStore()
        val holder = DashboardStateHolder(registry(), store)
        var notifications = 0
        holder.addListener { notifications++ }
        val before = holder.state
        holder.close(); holder.close()
        store.loaded(Result.success(StoredDashboard(false, 0, emptyList())))
        store.loaded(Result.failure(IllegalStateException("late")))
        holder.addListener { notifications++ }
        assertEquals(before, holder.state)
        assertEquals(1, notifications)
        assertEquals(0, store.saves)
        assertEquals(1, store.closes)
    }
    @Test fun `late save completion and edits after close cannot change state or notify`() {
        val store = DelayedStore()
        val holder = DashboardStateHolder(registry(), store)
        store.loaded(Result.success(StoredDashboard(false, 0, emptyList())))
        var notifications = 0
        holder.addListener { notifications++ }
        val before = holder.state
        holder.close()
        store.saved(Result.failure(IllegalStateException("late")))
        holder.delete("seed-clock")
        store.saved(Result.success(Unit))
        assertEquals(before, holder.state)
        assertEquals(1, notifications)
        assertEquals(1, store.saves)
    }
    @Test fun `listener closing during load cannot enqueue work after shutdown`() {
        val store = DelayedStore()
        val holder = DashboardStateHolder(registry(), store)
        holder.addListener { if (it.loaded) holder.close() }
        store.loaded(Result.success(StoredDashboard(false, 0, emptyList())))
        assertEquals(1, store.saves)
        assertEquals(1, store.closes)
    }

    @Test fun `failed repair keeps quarantine on subsequent edit and late repair callback is ignored`() {
        val store = DelayedStore()
        val holder = DashboardStateHolder(registry(), store)
        val valid = com.dormpanel.app.dashboard.model.PlacedCard("valid", "clock", 0, 0, CardSize(2, 1))
        val unknown = valid.copy(id = "missing", providerType = "future")
        store.loaded(Result.success(StoredDashboard(true, 1, listOf(valid, unknown))))
        assertEquals(listOf(valid), holder.state.cards)
        assertEquals(unknown, store.repairs.single().single().raw.toModel())
        store.saved(Result.failure(IllegalStateException("disk full")))
        holder.move("valid", 2, 0)
        assertEquals(2, store.repairs.size)
        assertEquals(store.repairs.first(), store.repairs.last())
        val before = holder.state
        holder.close()
        store.saved(Result.failure(IllegalStateException("late repair")))
        assertEquals(before, holder.state)
    }

    private class FakeStore(private val stored: StoredDashboard) : DashboardStore {
        val savedSnapshots = mutableListOf<List<com.dormpanel.app.dashboard.model.PlacedCard>>()

        override fun load(callback: (Result<StoredDashboard>) -> Unit) {
            callback(Result.success(stored))
        }

        override fun save(
            cards: List<com.dormpanel.app.dashboard.model.PlacedCard>,
            callback: (Result<Unit>) -> Unit,
        ) {
            savedSnapshots += cards.toList()
            callback(Result.success(Unit))
        }

        override fun saveRepair(cards: List<com.dormpanel.app.dashboard.model.PlacedCard>, quarantine: List<com.dormpanel.app.dashboard.persistence.QuarantinedCard>, callback: (Result<Unit>) -> Unit) = save(cards, callback)
        override fun close() = Unit
    }
}
