package com.dormpanel.app.apps

import com.dormpanel.app.dashboard.catalog.*
import org.json.JSONObject

/** Flattened ComponentName is the identity, including for multi-activity packages. */
data class InstalledApp(val component: String, val label: String, val packageName: String, val available: Boolean = true, val iconRevision: Int = 0)
interface FavoriteStore {
    fun read(): Set<String>
    fun write(components: Set<String>)
}
interface InstalledAppSource {
    val apps: List<InstalledApp>
    fun isFavorite(component: String): Boolean
    fun toggleFavorite(component: String)
    fun addListener(listener: (List<InstalledApp>) -> Unit)
    fun removeListener(listener: (List<InstalledApp>) -> Unit)
    fun refresh()
    fun launch(component: String): Boolean
    fun close()
}

/** Main-thread state; platform discovery and launch are injected and shared by every surface. */
class AppState(private val ownComponent: String, private val favorites: FavoriteStore,
    private val launcher: (String) -> Boolean,
) {
    private var favoriteIds = favorites.read().toSet()
    var apps: List<InstalledApp> = emptyList(); private set
    private val listeners = linkedSetOf<(List<InstalledApp>) -> Unit>()
    fun update(discovered: List<InstalledApp>) {
        val next = discovered.filter { it.available && it.component != ownComponent }
            .distinctBy { it.component }
            .sortedWith(compareBy<InstalledApp> { it.component !in favoriteIds }
                .thenBy { it.label.lowercase(java.util.Locale.ROOT) }.thenBy { it.component })
        if (apps != next) { apps = next; listeners.toList().forEach { it(next) } }
    }
    fun isFavorite(component: String) = component in favoriteIds
    fun toggleFavorite(component: String) {
        favoriteIds = if (isFavorite(component)) favoriteIds - component else favoriteIds + component
        favorites.write(favoriteIds)
        val previous = apps
        update(apps)
        if (previous == apps) listeners.toList().forEach { it(apps) }
    }
    fun launch(component: String): Boolean = apps.any { it.component == component } &&
        runCatching { launcher(component) }.getOrDefault(false)
    fun addListener(listener: (List<InstalledApp>) -> Unit) { listeners.add(listener); listener(apps) }
    fun removeListener(listener: (List<InstalledApp>) -> Unit) { listeners.remove(listener) }
    fun close() = listeners.clear()
}

data class AppConfiguration(val component: String) {
    fun encode() = entityConfiguration("component", component)
    companion object {
        fun decode(json: String) = AppConfiguration(runCatching { JSONObject(json).optString("component", "") }.getOrDefault(""))
    }
}

class AppCardCatalog(private val source: InstalledAppSource) : CardCatalog {
    override val candidates get() = source.apps.map {
        CardAddCandidate("app:${it.component}", CardCategory.APPS, "app", it.label, it.packageName, AppConfiguration(it.component).encode())
    }
    private val subscriptions = mutableMapOf<(List<CardAddCandidate>) -> Unit, (List<InstalledApp>) -> Unit>()
    override fun addListener(listener: (List<CardAddCandidate>) -> Unit) {
        removeListener(listener)
        val subscription: (List<InstalledApp>) -> Unit = { listener(candidates) }
        subscriptions[listener] = subscription; source.addListener(subscription)
    }
    override fun removeListener(listener: (List<CardAddCandidate>) -> Unit) { subscriptions.remove(listener)?.let(source::removeListener) }
}

class CombinedCardCatalog(private vararg val sources: CardCatalog) : CardCatalog {
    override val candidates get() = sources.flatMap { it.candidates }.distinctBy { it.candidateId }
    private val subscriptions = mutableMapOf<(List<CardAddCandidate>) -> Unit, (List<CardAddCandidate>) -> Unit>()
    override fun addListener(listener: (List<CardAddCandidate>) -> Unit) {
        removeListener(listener)
        var last: List<CardAddCandidate>? = null
        val subscription: (List<CardAddCandidate>) -> Unit = {
            val next = candidates
            if (last != next) { last = next; listener(next) }
        }
        subscriptions[listener] = subscription; sources.forEach { it.addListener(subscription) }
    }
    override fun removeListener(listener: (List<CardAddCandidate>) -> Unit) {
        subscriptions.remove(listener)?.let { subscription -> sources.forEach { it.removeListener(subscription) } }
    }
}
