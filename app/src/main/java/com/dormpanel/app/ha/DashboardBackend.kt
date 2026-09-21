package com.dormpanel.app.ha

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.dormpanel.app.appearance.AppearanceController
import com.dormpanel.app.dashboard.catalog.*
import com.dormpanel.app.data.*
import com.dormpanel.app.home.*

class MainHaScheduler : HaScheduler {
    private val handler = Handler(Looper.getMainLooper())
    override fun execute(action: () -> Unit) { handler.post(action) }
    override fun after(delayMillis: Long, action: () -> Unit): () -> Unit {
        val task = Runnable(action); handler.postDelayed(task, delayMillis); return { handler.removeCallbacks(task) }
    }
}
/** Stable providers retain this boundary when settings switch the backend at runtime. */
class DashboardBackend(context: Context, appearance: AppearanceController, labels: CatalogLabels) : DashboardDataSource, HomeControlSource {
    private val scheduler = MainHaScheduler()
    private val http = haHttpClient()
    val rest = HaRestClient(http, scheduler)
    private val preferences = HaSettingsStore(context)
    private val tokens = HaTokenStore(context)
    val ha = HaDashboardDataSource(scheduler, http, appearance)
    private val demo = FakeDashboardDataSource()
    private val demoHome = DemoHomeSource(demo)
    val homeSelection = HomeSelection()
    private val home = DemandHomeSource(demoHome, homeSelection::reconcile)
    override val homeState get() = home.homeState
    override fun addHomeListener(listener: (HomeControlState) -> Unit) = home.addHomeListener(listener)
    override fun removeHomeListener(listener: (HomeControlState) -> Unit) = home.removeHomeListener(listener)
    override fun activateEntity(id: String) = home.activateEntity(id)
    private val demoCatalog = SourceCardCatalog(demo, labels)
    var settings = preferences.read(); private set
    private var active: DashboardDataSource = demo
    private var activeCatalog: CardCatalog = demoCatalog
    private val listeners = linkedSetOf<(DashboardData) -> Unit>()
    private val catalogListeners = linkedSetOf<(List<CardAddCandidate>) -> Unit>()
    private val relay: (DashboardData) -> Unit = { data -> listeners.toList().forEach { it(data) } }
    private val catalogRelay: (List<CardAddCandidate>) -> Unit = { entries -> catalogListeners.toList().forEach { it(entries) } }
    override val state get() = active.state
    val catalog = object : CardCatalog {
        override val candidates get() = activeCatalog.candidates
        override fun addListener(listener: (List<CardAddCandidate>) -> Unit) { catalogListeners += listener; listener(candidates) }
        override fun removeListener(listener: (List<CardAddCandidate>) -> Unit) { catalogListeners -= listener }
    }
    init {
        appearance.themeCommand = ha::requestTheme; appearance.opacityCommand = ha::requestOpacity
        activate()
    }
    fun hasToken() = !tokens.read().isNullOrBlank()
    fun testToken(entered: String) = entered.trim().ifEmpty { tokens.read().orEmpty() }
    fun save(value: HaConnectionSettings, enteredToken: String) {
        val normalized = value.copy(baseUrl = if (value.baseUrl.isBlank()) "" else HaEndpoint.parse(value.baseUrl).base)
        if (enteredToken.isNotBlank()) tokens.write(enteredToken.trim())
        settings = normalized; preferences.write(settings); activate()
    }
    fun clearCredentials() { tokens.clear(); activate() }
    private fun activate() {
        active.removeListener(relay); activeCatalog.removeListener(catalogRelay)
        active = if (settings.mode == BackendMode.DEMO) demo else ha
        activeCatalog = if (settings.mode == BackendMode.DEMO) demoCatalog else ha.catalog
        home.activate(if (settings.mode == BackendMode.DEMO) demoHome else ha) {
            ha.configure(settings, tokens.read())
        }
        active.addListener(relay); activeCatalog.addListener(catalogRelay)
    }
    override fun addListener(listener: (DashboardData) -> Unit) { listeners += listener; listener(state) }
    override fun removeListener(listener: (DashboardData) -> Unit) { listeners -= listener }
    override fun toggleLight(id: String) = active.toggleLight(id)
    override fun setBrightness(id: String, percent: Int) = active.setBrightness(id, percent)
    override fun setColorTemperature(id: String, kelvin: Int) = active.setColorTemperature(id, kelvin)
    fun close() { home.close(); ha.stop(); http.dispatcher.cancelAll(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
}
