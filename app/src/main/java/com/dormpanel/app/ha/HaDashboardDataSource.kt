package com.dormpanel.app.ha

import com.dormpanel.app.data.*
import com.dormpanel.app.dashboard.catalog.*
import com.dormpanel.app.appearance.*
import okhttp3.OkHttpClient
import org.json.JSONObject

class HaDashboardDataSource(private val scheduler: HaScheduler, http: OkHttpClient, private val appearance: AppearanceController) : DashboardDataSource {
    val store = HaEntityStore()
    var settings = HaConnectionSettings(); private set
    var status = HaStatus(HaConnectionState.NOT_CONFIGURED); private set
    override var state = store.normalized(false, ""); private set
    var candidates = emptyList<CardAddCandidate>(); private set
    private val listeners = linkedSetOf<(DashboardData) -> Unit>()
    private val catalogListeners = linkedSetOf<(List<CardAddCandidate>) -> Unit>()
    private val statusListeners = linkedSetOf<(HaStatus) -> Unit>()
    private var initialized = false
    private var registryPending = false
    private var forecastTimer: (() -> Unit)? = null
    private var weatherId = ""
    private val bufferedEvents = mutableListOf<JSONObject>()
    private val commands = LatestCommands(scheduler) { entity, property, value ->
        if (connected) {
            if (property == "value") socket.service("input_number", "set_value", entity, JSONObject().put(property, value))
            else if (state.lights["ha:$entity"]?.availability == Availability.AVAILABLE)
                socket.service("light", "turn_on", entity, JSONObject().put(property, value))
        }
    }
    private val socket = HaWebSocketClient(http, scheduler, ::statusChanged, ::initialize, ::event)
    val connected get() = status.state == HaConnectionState.CONNECTED
    fun addStatusListener(listener: (HaStatus) -> Unit) { statusListeners += listener; listener(status) }
    fun removeStatusListener(listener: (HaStatus) -> Unit) { statusListeners -= listener }
    private fun statusChanged(value: HaStatus) {
        status = value
        if (!connected) { initialized = false; registryPending = false; commands.clear(); forecastTimer?.invoke(); forecastTimer = null }
        publish(); statusListeners.toList().forEach { it(value) }
    }
    fun configure(value: HaConnectionSettings, token: String?) {
        val changedServer = settings.baseUrl != value.baseUrl
        stop(); settings = value
        if (changedServer) { store.snapshot(org.json.JSONArray()); store.metadata.clear(); store.registryKnown = false; store.forecast = emptyList(); weatherId = ""; rebuildCatalog() }
        if (value.mode != BackendMode.HOME_ASSISTANT || token.isNullOrBlank() || value.baseUrl.isBlank()) {
            statusChanged(HaStatus(HaConnectionState.NOT_CONFIGURED, if (value.mode == BackendMode.HOME_ASSISTANT) "Enter HA URL and access token." else "Demo backend")); rebuildCatalog(); return
        }
        runCatching { HaEndpoint.parse(value.baseUrl) }.onSuccess { socket.start(it, token) }
            .onFailure { statusChanged(HaStatus(HaConnectionState.ERROR, "Invalid HA base URL.")) }
    }
    fun stop() { socket.stop(); commands.clear(); forecastTimer?.invoke(); forecastTimer = null; initialized = false; bufferedEvents.clear(); registryPending = false }
    private fun initialize() {
        bufferedEvents.clear()
        socket.request(JSONObject().put("type", "subscribe_events").put("event_type", "state_changed")) { subscription ->
            if (!subscription.optBoolean("success")) { socket.resyncFailed(); return@request }
            socket.request(JSONObject().put("type", "get_states")) { snapshot ->
                val states = snapshot.optJSONArray("result")
                if (!snapshot.optBoolean("success") || states == null) { socket.resyncFailed(); return@request }
                store.snapshot(states)
                // Events preceding the get_states result are already represented by its newer snapshot.
                bufferedEvents.clear()
                refreshRegistry {
                    initialized = true
                    bufferedEvents.forEach(store::event); bufferedEvents.clear()
                    rebuildCatalog(); selectWeather(); socket.synchronized(); applyAppearance(); fetchForecast()
                }
            }
        }
        socket.request(JSONObject().put("type", "subscribe_events").put("event_type", "entity_registry_updated"))
    }
    private fun refreshRegistry(done: () -> Unit = {}) {
        if (registryPending) return
        registryPending = true
        socket.request(JSONObject().put("type", "config/entity_registry/list_for_display")) { display ->
            if (display.optBoolean("success")) store.registry(display.opt("result"))
            // Full registry supplies disabled_by and stable device IDs, and supports older HA versions.
            socket.request(JSONObject().put("type", "config/entity_registry/list")) { full ->
                if (full.optBoolean("success")) store.registry(full.opt("result"))
                registryPending = false
                done(); if (initialized) { rebuildCatalog(); publish() }
            }
        }
    }
    private fun event(event: JSONObject) {
        when (event.optString("event_type")) {
            "entity_registry_updated" -> if (initialized) refreshRegistry()
            "state_changed" -> {
                val data = event.optJSONObject("data") ?: return
                if (!initialized) {
                    if (bufferedEvents.size < 10000) bufferedEvents += data else socket.resyncFailed()
                    return
                }
                val id = data.optString("entity_id")
                val old = store.entities[id]
                store.event(data)
                val next = store.entities[id]
                fun topology(entity: HaEntity?) = entity?.let { listOf(it.attributes.text("friendly_name"), it.attributes.text("device_class"), it.attributes.optJSONArray("supported_color_modes")?.toString(), it.attributes.text("min_color_temp_kelvin"), it.attributes.text("max_color_temp_kelvin")) }
                if (topology(old) != topology(next)) { rebuildCatalog(); val previous = weatherId; selectWeather(); if (weatherId != previous) fetchForecast() }
                publish(); applyAppearance()
            }
        }
    }
    private fun selectWeather() {
        val next = settings.weatherEntity.ifEmpty { store.discovered("weather").firstOrNull()?.id.orEmpty() }
        if (next != weatherId) { weatherId = next; store.forecast = emptyList() }
    }
    private fun fetchForecast() {
        forecastTimer?.invoke()
        if (!connected || weatherId.isEmpty()) return
        val entity = weatherId
        val features = store.entities[entity]?.attributes?.optInt("supported_features") ?: 0
        val type = when { features and 1 != 0 -> "daily"; features and 2 != 0 -> "hourly"; features and 4 != 0 -> "twice_daily"; else -> null }
        if (type != null) socket.service("weather", "get_forecasts", entity, JSONObject().put("type", type), true) { result ->
            if (connected && entity == weatherId && result.optBoolean("success")) { store.forecast = HaEntityStore.forecast(result.optJSONObject("result"), entity); publish() }
        }
        forecastTimer = scheduler.after(3600000) { fetchForecast() }
    }
    private fun publish() {
        val next = store.normalized(connected, weatherId)
        if (next != state) { state = next; listeners.toList().forEach { it(next) } }
    }
    private fun rebuildCatalog() {
        val data = store.normalized(connected, weatherId)
        val next = buildList {
            add(CardAddCandidate("clock", CardCategory.INFORMATION, "clock", "Clock", "Local time", "{}"))
            add(CardAddCandidate("weather", CardCategory.INFORMATION, "weather", "Weather", "Preferred HA weather", "{}"))
            data.lights.values.forEach { add(CardAddCandidate(it.id, CardCategory.HOME, "light", it.name, "Home Assistant light", entityConfiguration("lightId", it.id))) }
            data.sensors.values.forEach { add(CardAddCandidate(it.id, CardCategory.HOME, "sensor", it.name, "Home Assistant climate", entityConfiguration("sensorId", it.id))) }
        }
        if (next != candidates) { candidates = next; catalogListeners.toList().forEach { it(next) } }
    }
    override fun addListener(listener: (DashboardData) -> Unit) { listeners += listener; listener(state) }
    override fun removeListener(listener: (DashboardData) -> Unit) { listeners -= listener }
    // JVM erasure requires distinct catalog method implementation through a dedicated facade.
    val catalog: CardCatalog = object : CardCatalog {
        override val candidates get() = this@HaDashboardDataSource.candidates
        override fun addListener(listener: (List<CardAddCandidate>) -> Unit) { catalogListeners += listener; listener(candidates) }
        override fun removeListener(listener: (List<CardAddCandidate>) -> Unit) { catalogListeners -= listener }
    }
    override fun toggleLight(id: String) {
        val light = state.lights[id] ?: return
        if (connected && light.availability == Availability.AVAILABLE) {
            commands.cancel(id.removePrefix("ha:"))
            socket.service("light", if (light.isOn) "turn_off" else "turn_on", id.removePrefix("ha:"))
        }
    }
    override fun setBrightness(id: String, percent: Int) {
        val light = state.lights[id] ?: return
        if (connected && light.availability == Availability.AVAILABLE && light.capabilities.brightness) commands.put(id.removePrefix("ha:"), "brightness_pct", percent.coerceIn(0, 100))
    }
    override fun setColorTemperature(id: String, kelvin: Int) {
        val light = state.lights[id] ?: return
        val range = light.capabilities.colorTemperature ?: return
        if (connected && light.availability == Availability.AVAILABLE) commands.put(id.removePrefix("ha:"), "color_temp_kelvin", kelvin.coerceIn(range))
    }
    fun requestTheme(mode: ThemeMode): Boolean {
        if (!connected || settings.themeEntity.isEmpty()) return false
        val helper = store.entities[settings.themeEntity]
        val options = helper?.attributes?.optJSONArray("options")
        val option = options?.let { list -> (0 until list.length()).map { list.optString(it) }.firstOrNull { it.equals(mode.name, true) } }
        if (option != null) socket.service("input_select", "select_option", settings.themeEntity, JSONObject().put("option", option))
        return true
    }
    fun requestOpacity(opacity: Float): Boolean {
        if (!connected || settings.opacityEntity.isEmpty()) return false
        if (opacity.isFinite()) commands.put(settings.opacityEntity, "value", (opacity.coerceIn(0f, 1f) * 100).toInt())
        return true
    }
    private fun applyAppearance() {
        if (!connected) return
        val theme = when (store.entities[settings.themeEntity]?.value?.lowercase()) { "light" -> ThemeMode.LIGHT; "dark" -> ThemeMode.DARK; else -> appearance.state.themeMode }
        val opacity = store.entities[settings.opacityEntity]?.value?.toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..100f }?.div(100f) ?: appearance.state.cardSurfaceOpacity
        appearance.update(AppearanceState(theme, opacity))
    }
}
