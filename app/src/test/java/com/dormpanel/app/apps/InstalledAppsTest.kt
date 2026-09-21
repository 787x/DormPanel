package com.dormpanel.app.apps

import com.dormpanel.app.dashboard.catalog.*
import com.dormpanel.app.dashboard.layout.*
import com.dormpanel.app.dashboard.model.*
import com.dormpanel.app.dashboard.persistence.RawDashboardCard
import org.junit.Assert.*
import org.junit.Test

class InstalledAppsTest {
    private class Favorites : FavoriteStore {
        var saved = emptySet<String>()
        override fun read() = saved
        override fun write(components: Set<String>) { saved = components.toSet() }
    }
    private val first = InstalledApp("test/test.First", "Alpha", "test")
    private val second = InstalledApp("test/test.Second", "Alpha", "test")
    private val third = InstalledApp("other/other.Main", "Zulu", "other")
    @Test fun favoritesLabelAndExactComponentDetermineStableOrder() {
        val store = Favorites()
        val state = AppState("own/own.Main", store) { true }
        state.update(listOf(third, second, first, first, InstalledApp("own/own.Main", "Own", "own")))
        assertEquals(listOf(first, second, third), state.apps)
        state.toggleFavorite(third.component)
        assertEquals(listOf(third, first, second), state.apps)
        state.update(listOf(second, third, first))
        assertEquals(listOf(third, first, second), state.apps)
        val restored = AppState("own/own.Main", store) { true }
        restored.update(listOf(first, second, third))
        assertEquals(state.apps, restored.apps)
        restored.toggleFavorite(third.component)
        assertTrue(store.saved.isEmpty())
    }
    @Test fun disappearanceAndReappearanceNeverFallbackToSamePackage() {
        var launched = ""
        val state = AppState("own/own.Main", Favorites()) { launched = it; true }
        state.update(listOf(first, second)); assertTrue(state.launch(first.component))
        state.update(listOf(second)); launched = ""
        assertFalse(state.launch(first.component)); assertEquals("", launched)
        state.update(listOf(first, second)); assertTrue(state.launch(first.component))
        assertEquals(first.component, launched)
        val race = AppState("own/own.Main", Favorites()) { throw SecurityException() }
        race.update(listOf(first)); assertFalse(race.launch(first.component))
    }
    private class FakeSource : InstalledAppSource {
        val state = AppState("own/own.Main", Favorites()) { true }
        var refreshes = 0
        override val apps get() = state.apps
        override fun isFavorite(component: String) = state.isFavorite(component)
        override fun toggleFavorite(component: String) = state.toggleFavorite(component)
        override fun addListener(listener: (List<InstalledApp>) -> Unit) = state.addListener(listener)
        override fun removeListener(listener: (List<InstalledApp>) -> Unit) = state.removeListener(listener)
        override fun launch(component: String) = state.launch(component)
        override fun openAppSettings(component: String) = state.openAppSettings(component)
        override fun refresh() { refreshes++ }
        override fun close() = state.close()
    }
    @Test fun composedCatalogIsLiveDeduplicatedAndUnsubscribesWithoutPolling() {
        val source = FakeSource()
        val apps = AppCardCatalog(source)
        val clock = CardAddCandidate("clock", CardCategory.INFORMATION, "clock", "Clock", "", "{}")
        val existing = object : CardCatalog {
            override val candidates = listOf(clock)
            override fun addListener(listener: (List<CardAddCandidate>) -> Unit) = listener(candidates)
            override fun removeListener(listener: (List<CardAddCandidate>) -> Unit) = Unit
        }
        val combined = CombinedCardCatalog(existing, apps, apps)
        var deliveries = 0
        var latest = emptyList<CardAddCandidate>()
        val listener: (List<CardAddCandidate>) -> Unit = { latest = it; deliveries++ }
        combined.addListener(listener)
        source.state.update(listOf(first, second))
        assertEquals(3, latest.size)
        assertEquals(first.component, AppConfiguration.decode(latest[1].configurationJson).component)
        assertEquals(second.component, AppConfiguration.decode(latest[2].configurationJson).component)
        source.state.update(listOf(second)); assertEquals(2, latest.size)
        combined.removeListener(listener)
        val count = deliveries
        source.state.update(listOf(first)); assertEquals(count, deliveries)
        assertEquals(0, source.refreshes)
    }
    @Test fun configurationRoundTripAndMissingAppDoesNotQuarantineValidCard() {
        val config = AppConfiguration("removed/removed.Exact")
        assertEquals(config, AppConfiguration.decode(config.encode()))
        val raw = RawDashboardCard("shortcut", "app", 0, 0, 2, 1, config.encode())
        val repair = DashboardLayoutRepair(GridDefinition(8, 6), CardSizeCatalog {
            if (it == "app") ExplicitCardSizePolicy(listOf(CardSize(1, 1), CardSize(2, 1), CardSize(2, 2))) else null
        }).repair(listOf(raw))
        assertTrue(repair.quarantine.isEmpty())
        assertEquals(listOf(raw.toModel()), repair.cards)
    }

    @Test fun settingsResolveOnlyExactDiscoveredComponentToItsPackage() {
        val requested = mutableListOf<String>()
        val state = AppState("own/own.Main", Favorites(), settingsLauncher = { requested.add(it); true }) { true }
        state.update(listOf(first, second, third))
        assertTrue(state.openAppSettings(first.component))
        assertTrue(state.openAppSettings(second.component))
        assertTrue(state.openAppSettings(third.component))
        assertEquals(listOf("test", "test", "other"), requested)
        state.update(listOf(second))
        assertFalse(state.openAppSettings(first.component))
        assertFalse(state.openAppSettings("invalid"))
        assertEquals(3, requested.size)
    }

    @Test fun settingsFailureIsSafe() {
        val denied = AppState("own/own.Main", Favorites(), settingsLauncher = { throw SecurityException() }) { true }
        denied.update(listOf(first)); assertFalse(denied.openAppSettings(first.component))
        val missing = AppState("own/own.Main", Favorites(), settingsLauncher = { false }) { true }
        missing.update(listOf(first)); assertFalse(missing.openAppSettings(first.component))
    }
}
