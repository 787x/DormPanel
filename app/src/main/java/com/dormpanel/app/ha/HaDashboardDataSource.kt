package com.dormpanel.app.ha

import com.dormpanel.app.data.*
import com.dormpanel.app.home.*
import com.dormpanel.app.dashboard.catalog.*
import com.dormpanel.app.appearance.*
import okhttp3.OkHttpClient
import org.json.JSONObject

class HaDashboardDataSource(private val scheduler: HaScheduler, http: OkHttpClient, private val appearance: AppearanceController) : DashboardDataSource, HomeControlSource {
    val store = HaEntityStore()
    var settings = HaConnectionSettings(); private set
    var status = HaStatus(HaConnectionState.NOT_CONFIGURED); private set
    override var state = store.normalized(false, ""); private set
    override var homeState = HomeControlState(); private set
    private val homeListeners = linkedSetOf<(HomeControlState) -> Unit>()
    override fun addHomeListener(listener: (HomeControlState) -> Unit) { homeListeners += listener; listener(homeState) }
    override fun removeHomeListener(listener: (HomeControlState) -> Unit) { homeListeners -= listener }
    override fun activateEntity(id: String): Boolean {
        val entity = homeState.entities.find { it.id == id } ?: return false
        if (!connected || entity.availability != Availability.AVAILABLE || !entity.actionable) return false
        if (entity.kind == HomeKind.LIGHT) return powerLight(id)
        val domain = when(entity.kind) {
            HomeKind.SWITCH -> "switch"
            HomeKind.SCENE -> "scene"
            HomeKind.SCRIPT -> "script"
            else -> return false
        }
        return socket.service(domain, if (entity.kind == HomeKind.SWITCH && entity.isOn) "turn_off" else "turn_on", id.removePrefix("ha:"))
    }
    var candidates = emptyList<CardAddCandidate>(); private set
    private val listeners = linkedSetOf<(DashboardData) -> Unit>()
    private val catalogListeners = linkedSetOf<(List<CardAddCandidate>) -> Unit>()
    private val statusListeners = linkedSetOf<(HaStatus) -> Unit>()
    private var initialized = false
    private val registryPending = mutableSetOf<String>()
    private val registryDirty = mutableSetOf<String>()
    private var forecastTimer: (() -> Unit)? = null
    private var weatherId = ""
    private val bufferedEvents = mutableListOf<JSONObject>()
    private val commands = LatestCommands(scheduler) { entity, property, value ->
        if (connected) {
            if (state.lights["ha:$entity"]?.availability == Availability.AVAILABLE)
                socket.service("light", "turn_on", entity, JSONObject().put(property, value))
        }
    }
    private val socket = HaWebSocketClient(http, scheduler, ::statusChanged, ::initialize, ::event)
    val connected get() = status.state == HaConnectionState.CONNECTED
    fun addStatusListener(listener: (HaStatus) -> Unit) { statusListeners += listener; listener(status) }
    fun removeStatusListener(listener: (HaStatus) -> Unit) { statusListeners -= listener }
    private fun statusChanged(value: HaStatus) {
        status = value
        if (!connected) { initialized = false; registryPending.clear(); registryDirty.clear(); commands.clear(); forecastTimer?.invoke(); forecastTimer = null }
        publish(); statusListeners.toList().forEach { it(value) }
    }
    fun configure(value: HaConnectionSettings, token: String?) {
        val changedServer = settings.baseUrl != value.baseUrl
        stop(); settings = value
        if (changedServer) { store.snapshot(org.json.JSONArray()); store.metadata.clear(); store.areas.clear(); store.devices.clear(); store.scriptServices(null); store.registryKnown = false; store.forecast = emptyList(); weatherId = ""; rebuildCatalog() }
        if (value.mode != BackendMode.HOME_ASSISTANT || token.isNullOrBlank() || value.baseUrl.isBlank()) {
            statusChanged(HaStatus(HaConnectionState.NOT_CONFIGURED, if (value.mode == BackendMode.HOME_ASSISTANT) "Enter HA URL and access token." else "Demo backend")); rebuildCatalog(); return
        }
        runCatching { HaEndpoint.parse(value.baseUrl) }.onSuccess { socket.start(it, token) }
            .onFailure { statusChanged(HaStatus(HaConnectionState.ERROR, "Invalid HA base URL.")) }
    }
    fun stop() { socket.stop(); commands.clear(); forecastTimer?.invoke(); forecastTimer = null; initialized = false; bufferedEvents.clear(); registryPending.clear(); registryDirty.clear() }
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
                refreshRegistry("entity") {
                    refreshRegistry("device") {
                        refreshRegistry("area") {
                            refreshRegistry("service") {
                                initialized = true
                                bufferedEvents.forEach(store::event); bufferedEvents.clear()
                                rebuildCatalog(); selectWeather(); socket.synchronized(); applyAppearance(); fetchForecast()
                                registryDirty.toList().forEach { kind -> registryDirty -= kind; refreshRegistry(kind) }
                            }
                        }
                    }
                }
            }
        }
        (listOf("entity", "device", "area").map { "${it}_registry_updated" } + listOf("service_registered", "service_removed")).forEach { event ->
            socket.request(JSONObject().put("type", "subscribe_events").put("event_type", event))
        }
    }
    private fun refreshRegistry(kind: String, done: () -> Unit = {}) {
        if (!registryPending.add(kind)) { registryDirty += kind; return }
        fun complete() {
            registryPending -= kind
            if (registryDirty.remove(kind)) { refreshRegistry(kind, done); return }
            done()
            if (initialized) { rebuildCatalog(); publish() }
        }
        fun full() {
            socket.request(JSONObject().put("type", "config/${kind}_registry/list")) { result ->
                if (result.optBoolean("success")) {
                    val array = result.optJSONArray("result")
                    when (kind) {
                        "entity" -> store.registry(result.opt("result"))
                        "device" -> array?.let(store::deviceRegistry)
                        "area" -> array?.let(store::areaRegistry)
                    }
                }
                complete()
            }
        }
        if (kind == "service") {
            socket.request(JSONObject().put("type", "get_services")) { result ->
                store.scriptServices(result.optJSONObject("result"))
                complete()
            }
        } else if (kind == "entity") socket.request(JSONObject().put("type", "config/entity_registry/list_for_display")) { result ->
            if (result.optBoolean("success")) store.registry(result.opt("result"))
            full()
        } else full()
    }
    private fun event(event: JSONObject) {
        when (event.optString("event_type")) {
            "service_registered", "service_removed" -> if (event.optJSONObject("data")?.text("domain") == "script") {
                if (initialized) refreshRegistry("service") else registryDirty += "service"
            }
            "entity_registry_updated", "device_registry_updated", "area_registry_updated" -> {
                val kind = event.optString("event_type").substringBefore('_')
                if (initialized) refreshRegistry(kind) else registryDirty += kind
            }
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
                publish(); applyAppearance(id)
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
        val home = store.home(connected, next.lights)
        if (home != homeState) { homeState = home; homeListeners.toList().forEach { it(home) } }
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
    override fun toggleLight(id: String) { powerLight(id) }
    private fun powerLight(id: String): Boolean {
        val light = state.lights[id] ?: return false
        if (!connected || light.availability != Availability.AVAILABLE) return false
        commands.cancel(id.removePrefix("ha:"))
        return socket.service("light", if (light.isOn) "turn_off" else "turn_on", id.removePrefix("ha:"))
    }
    override fun setBrightness(id: String, percent: Int) {
        val light = state.lights[id] ?: return
        if (connected && light.availability == Availability.AVAILABLE && light.capabilities.brightness) commands.put(id.removePrefix("ha:"), "brightness_pct", percent.coerceIn(1, 100))
    }
    override fun setColorTemperature(id: String, kelvin: Int) {
        val light = state.lights[id] ?: return
        val range = light.capabilities.colorTemperature ?: return
        if (connected && light.availability == Availability.AVAILABLE) commands.put(id.removePrefix("ha:"), "color_temp_kelvin", kelvin.coerceIn(range))
    }
    private fun appearanceHelper(id: String, domain: String): HaEntity? {
        if (!connected || !id.startsWith("$domain.")) return null
        return store.entities[id]?.takeIf { it.usable && store.enabled(it) }
    }
    private fun themeOption(helper: HaEntity, mode: ThemeMode): String? {
        val options = helper.attributes.optJSONArray("options") ?: return null
        return (0 until options.length()).map { options.optString(it) }.firstOrNull { it.equals(mode.name, true) }
    }
    fun requestTheme(mode: ThemeMode): Boolean {
        val helper = appearanceHelper(settings.themeEntity, "input_select") ?: return false
        val option = themeOption(helper, mode) ?: return false
        return socket.service("input_select", "select_option", helper.id, JSONObject().put("option", option))
    }
    fun requestOpacity(opacity: Float): Boolean {
        if (!opacity.isFinite()) return false
        val helper = appearanceHelper(settings.opacityEntity, "input_number") ?: return false
        // Return actual socket admission, not acceptance by a timer that may later fail to send.
        return socket.service("input_number", "set_value", helper.id,
            JSONObject().put("value", (opacity.coerceIn(0f, 1f) * 100).toInt()))
    }
    private fun applyAppearance(changedEntity: String? = null) {
        if (!connected) return
        var next = appearance.state
        if (changedEntity == null || changedEntity == settings.themeEntity) {
            val helper = appearanceHelper(settings.themeEntity, "input_select")
            val mode = when (helper?.value?.lowercase()) { "light" -> ThemeMode.LIGHT; "dark" -> ThemeMode.DARK; else -> null }
            if (helper != null && mode != null && themeOption(helper, mode) != null) next = next.copy(themeMode = mode)
        }
        if (changedEntity == null || changedEntity == settings.opacityEntity) {
            val helper = appearanceHelper(settings.opacityEntity, "input_number")
            val opacity = helper?.value?.toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..100f }
            if (opacity != null) next = next.copy(cardSurfaceOpacity = opacity / 100f)
        }
        // Unrelated light/weather events must not undo a local fallback using an old helper value.
        appearance.update(next)
    }
}
