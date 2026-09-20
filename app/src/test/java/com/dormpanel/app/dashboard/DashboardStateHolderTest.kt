package com.dormpanel.app.dashboard

import com.dormpanel.app.dashboard.card.DashboardCardRegistry
import com.dormpanel.app.dashboard.layout.LayoutMutationResult
import com.dormpanel.app.dashboard.persistence.DashboardStore
import com.dormpanel.app.dashboard.persistence.StoredDashboard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardStateHolderTest {
    @Test
    fun `first launch seeds once and persists stable ids`() {
        val store = FakeStore(StoredDashboard(false, 0, emptyList()))

        val holder = DashboardStateHolder(DashboardCardRegistry.mock(), store)

        assertTrue(holder.state.loaded)
        assertEquals(listOf("seed-focus", "seed-status", "seed-shortcuts", "seed-shortcuts-wide"), holder.state.cards.map { it.id })
        assertEquals(1, store.savedSnapshots.size)
        assertEquals(holder.state.cards, store.savedSnapshots.single())
    }

    @Test
    fun `initialized empty dashboard is restored without reseeding`() {
        val store = FakeStore(StoredDashboard(true, 4, emptyList()))

        val holder = DashboardStateHolder(DashboardCardRegistry.mock(), store)

        assertTrue(holder.state.loaded)
        assertTrue(holder.state.cards.isEmpty())
        assertTrue(store.savedSnapshots.isEmpty())
    }

    @Test
    fun `drag preview does not persist but committed move does`() {
        val registry = DashboardCardRegistry.mock()
        val store = FakeStore(StoredDashboard(false, 0, emptyList()))
        val holder = DashboardStateHolder(registry, store)
        store.savedSnapshots.clear()

        val preview = holder.previewMove(holder.state.cards, "seed-shortcuts-wide", 2, 3)

        assertTrue(preview is LayoutMutationResult.Success)
        assertTrue(store.savedSnapshots.isEmpty())

        val committed = holder.move("seed-shortcuts-wide", 2, 3)

        assertTrue(committed is LayoutMutationResult.Success)
        assertEquals(1, store.savedSnapshots.size)
        assertEquals(2, store.savedSnapshots.single().first { it.id == "seed-shortcuts-wide" }.column)
        assertEquals(3, store.savedSnapshots.single().first { it.id == "seed-shortcuts-wide" }.row)
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
