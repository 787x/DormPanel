package com.dormpanel.app.dashboard

import com.dormpanel.app.dashboard.card.coreCardRegistry
import com.dormpanel.app.appearance.*
import com.dormpanel.app.data.FakeDashboardDataSource
import com.dormpanel.app.dashboard.layout.LayoutMutationResult
import com.dormpanel.app.dashboard.model.CardSize
import com.dormpanel.app.dashboard.persistence.DashboardStore
import com.dormpanel.app.dashboard.persistence.StoredDashboard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardStateHolderTest {
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
        override fun save(cards: List<com.dormpanel.app.dashboard.model.PlacedCard>, callback: (Result<Unit>) -> Unit) { saves++; saved = callback }
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
