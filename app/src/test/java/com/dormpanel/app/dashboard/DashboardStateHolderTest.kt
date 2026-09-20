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

        override fun close() = Unit
    }
}
