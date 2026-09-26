package com.dormpanel.app.ha

import com.dormpanel.app.data.*
import com.dormpanel.app.home.*
import com.dormpanel.app.dashboard.catalog.*
import com.dormpanel.app.appearance.*
import okhttp3.OkHttpClient
import org.json.JSONObject

class HaDashboardDataSource(private val scheduler: HaScheduler, http: OkHttpClient, private val appearance: AppearanceController,
    private val projector: HaProjector = DefaultHaProjector,
    private val memory: LightControlMemory = LightControlMemory(),
) : DashboardDataSource, HomeControlSource {
    val store = HaEntityStore()
    var settings = HaConnectionSettings(); private set
    var status = HaStatus(HaConnectionState.NOT_CONFIGURED); private set
    override var state = projector.dashboard(store, false, ""); private set
    // Fresh access is independent of notification deduplication; never cache unused Home projections.
    override val homeState get() = projector.home(store, connected, store.lightStates(connected).mapValues { memory.project(it.value) })
    private var lastEmittedHomeState: HomeControlState? = null
    private val homeListeners = linkedSetOf<(HomeControlState) -> Unit>()
    override fun addHomeListener(listener: (HomeControlState) -> Unit) {
        if (!homeListeners.add(listener)) return
        val fresh = homeState
        lastEmittedHomeState = fresh
        listener(fresh)
    }
    override fun removeHomeListener(listener: (HomeControlState) -> Unit) {
        homeListeners -= listener
        if (homeListeners.isEmpty()) lastEmittedHomeState = null
    }
    override fun activateEntity(id: String): Boolean {
        val entity = homeState.entities.find { it.id == id } ?: return false
        if (!connected || entity.availability != Availability.AVAILABLE || !entity.actionable) return false
        if (entity.kind == HomeKind.LIGHT) return powerLight(id, !entity.isOn)
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
    private val relayReadyListeners = linkedSetOf<() -> Unit>()
    private val relayEventListeners = linkedSetOf<(JSONObject) -> Unit>()
    private var initialized = false
    private val registryPending = mutableSetOf<String>()
    private val registryDirty = mutableSetOf<String>()
    private var forecastTimer: (() -> Unit)? = null
    private var weatherId = ""
    private val bufferedEvents = mutableListOf<JSONObject>()
    // One timer per light: brightness and Kelvin are sent in one coherent payload.
    // Keep the unsent command separate from observations: an earlier service's event
    // may update memory during this 180ms window, but must not rewrite the queued input.
    private val pendingLights = mutableMapOf<String, LightControlValues>()
    private val commands = LatestCommands(scheduler) { entity, _, _ ->
        val intent = pendingLights.remove("ha:$entity")
        val light = state.lights["ha:$entity"]
        if (connected && light?.availability == Availability.AVAILABLE) sendOn(light, intent)
    }
    var remoteBrightness: ((Int) -> Unit)? = null
    var acceptBrightnessSnapshot: () -> Boolean = { true }
    var remoteVolume: ((Int) -> Unit)? = null
    var remoteSystemBrightness: ((Int) -> Unit)? = null
    var remoteBlackout: ((Boolean) -> Unit)? = null
    var remoteSystemAutomatic: ((Boolean) -> Unit)? = null
    var remoteFollowSystem: ((Boolean) -> Unit)? = null
    var remoteKeepAwake: ((Boolean) -> Unit)? = null
    var remoteStartAfterBoot: ((Boolean) -> Unit)? = null
    private val deviceCommands = LatestCommands(scheduler) { entity, property, value ->
        if (numberHelper(entity, if (property == "brightness") 1 else 0) != null) socket.service("input_number", "set_value", entity,
            JSONObject().put("value", value))
    }
    private val socket = HaWebSocketClient(http, scheduler, ::statusChanged, ::initialize, ::event)
    val connected get() = status.state == HaConnectionState.CONNECTED
    val relayChannel: HaRelayChannel = object : HaRelayChannel {
        override val origin get() = settings.baseUrl
        override val ready get() = connected
        override fun request(message: JSONObject, callback: (JSONObject) -> Unit) = socket.request(message, callback)
        override fun addReadyListener(listener: () -> Unit) { relayReadyListeners += listener; if (connected) listener() }
        override fun removeReadyListener(listener: () -> Unit) { relayReadyListeners -= listener }
        override fun addEventListener(listener: (JSONObject) -> Unit) { relayEventListeners += listener }
        override fun removeEventListener(listener: (JSONObject) -> Unit) { relayEventListeners -= listener }
    }
    fun addStatusListener(listener: (HaStatus) -> Unit) { statusListeners += listener; listener(status) }
    fun removeStatusListener(listener: (HaStatus) -> Unit) { statusListeners -= listener }
    private fun statusChanged(value: HaStatus) {
        status = value
        if (!connected) { initialized = false; registryPending.clear(); registryDirty.clear(); commands.clear(); deviceCommands.clear(); pendingLights.clear(); forecastTimer?.invoke(); forecastTimer = null }
        publish(rebuild = true); statusListeners.toList().forEach { it(value) }
    }
    fun configure(value: HaConnectionSettings, token: String?) {
        val changedServer = settings.baseUrl != value.baseUrl
        stop(); settings = value; memory.scope(value.baseUrl)
        if (changedServer) { store.snapshot(org.json.JSONArray()); store.metadata.clear(); store.areas.clear(); store.devices.clear(); store.scriptServices(null); store.registryKnown = false; store.forecast = emptyList(); weatherId = "" }
        if (value.mode != BackendMode.HOME_ASSISTANT || token.isNullOrBlank() || value.baseUrl.isBlank()) {
            statusChanged(HaStatus(HaConnectionState.NOT_CONFIGURED, if (value.mode == BackendMode.HOME_ASSISTANT) "Enter HA URL and access token." else "Demo backend")); return
        }
        runCatching { HaEndpoint.parse(value.baseUrl) }.onSuccess { socket.start(it, token) }
            .onFailure { statusChanged(HaStatus(HaConnectionState.ERROR, "Invalid HA base URL.")) }
    }
    fun stop() { socket.stop(); commands.clear(); deviceCommands.clear(); pendingLights.clear(); forecastTimer?.invoke(); forecastTimer = null; initialized = false; bufferedEvents.clear(); registryPending.clear(); registryDirty.clear() }
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
                                store.lightStates(true).values.forEach(memory::observe)
                                selectWeather(); socket.synchronized(); applyAppearance(); applyDeviceHelpers(); fetchForecast()
                                relayReadyListeners.toList().forEach { it() }
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
        socket.request(JSONObject().put("type", "subscribe_events").put("event_type", "dormpanel_transfer_available"))
    }
    private fun refreshRegistry(kind: String, done: () -> Unit = {}) {
        if (!registryPending.add(kind)) { registryDirty += kind; return }
        fun complete() {
            registryPending -= kind
            if (registryDirty.remove(kind)) { refreshRegistry(kind, done); return }
            // Initialization's nested completions publish once at synchronized(), not once per unwind.
            val wasInitialized = initialized
            if (wasInitialized) {
                val previous = weatherId
                selectWeather()
                publish(rebuild = true)
                if (weatherId != previous) fetchForecast()
            }
            done()
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
            "dormpanel_transfer_available" -> relayEventListeners.toList().forEach { it(event.optJSONObject("data") ?: JSONObject()) }
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
                if (id.startsWith("light.")) store.lightStates(true)["ha:$id"]?.let(memory::observe)
                fun topology(entity: HaEntity?) = entity?.let { listOf(it.attributes.text("friendly_name"), it.attributes.text("device_class"), it.attributes.optJSONArray("supported_color_modes")?.toString(), it.attributes.text("min_color_temp_kelvin"), it.attributes.text("max_color_temp_kelvin")) }
                val topologyChanged = topology(old) != topology(next)
                val previous = weatherId
                if (topologyChanged) selectWeather()
                publish(rebuild = topologyChanged, homeRelevant = id.substringBefore('.') in HOME_DOMAINS)
                if (weatherId != previous) fetchForecast()
                applyAppearance(id)
                applyDeviceHelpers(id)
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
            if (connected && entity == weatherId && result.optBoolean("success")) { store.forecast = HaEntityStore.forecast(result.optJSONObject("result"), entity); publish(homeRelevant = false) }
        }
        forecastTimer = scheduler.after(3600000) { fetchForecast() }
    }
    private fun publish(rebuild: Boolean = false, homeRelevant: Boolean = true) {
        val raw = projector.dashboard(store, connected, weatherId)
        val next = raw.copy(lights = raw.lights.mapValues { memory.project(it.value) })
        val dashboardChanged = next != state
        state = next // Synchronous catalog listeners must also see fresh light projections.
        if (rebuild) rebuildCatalog(next)
        if (dashboardChanged) listeners.toList().forEach { it(next) }
        if (homeRelevant && homeListeners.isNotEmpty()) {
            val home = projector.home(store, connected, next.lights)
            if (home != lastEmittedHomeState) {
                lastEmittedHomeState = home
                homeListeners.toList().forEach { it(home) }
            }
        }
    }
    private fun rebuildCatalog(data: DashboardData) {
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
    override fun toggleLight(id: String) { state.lights[id]?.let { setLightPower(id, !it.isOn) } }
    override fun setLightPower(id: String, on: Boolean) { powerLight(id, on) }
    private fun powerLight(id: String, on: Boolean): Boolean {
        val light = state.lights[id] ?: return false
        if (!connected || light.availability != Availability.AVAILABLE) return false
        commands.cancel(id.removePrefix("ha:"))
        pendingLights.remove(id)
        return if (on) sendOn(light) else socket.service("light", "turn_off", id.removePrefix("ha:"))
    }
    private fun sendOn(light: LightState, intent: LightControlValues? = null): Boolean {
        val values = memory.project(light)
        val data = JSONObject()
        if (light.capabilities.brightness) (intent?.brightness ?: values.controlBrightness)?.let { data.put("brightness_pct", it) }
        light.capabilities.colorTemperature?.let { range ->
            (intent?.kelvin ?: values.controlTemperature)?.takeIf { it in range }?.let { data.put("color_temp_kelvin", it) }
        }
        return socket.service("light", "turn_on", light.id.removePrefix("ha:"), data)
    }
    override fun setBrightness(id: String, percent: Int) {
        val light = state.lights[id] ?: return
        if (!connected || light.availability != Availability.AVAILABLE || !light.capabilities.brightness) return
        memory.remember(id, brightness = percent.coerceIn(1, 100))
        pendingLights[id] = (pendingLights[id] ?: LightControlValues()).copy(brightness = percent.coerceIn(1, 100))
        publish()
        commands.put(id.removePrefix("ha:"), "turn_on", 1)
    }
    override fun setColorTemperature(id: String, kelvin: Int) {
        val light = state.lights[id] ?: return
        val range = light.capabilities.colorTemperature ?: return
        if (!connected || light.availability != Availability.AVAILABLE) return
        memory.remember(id, kelvin = kelvin.coerceIn(range))
        if (light.isOn || id in pendingLights) {
            pendingLights[id] = (pendingLights[id] ?: LightControlValues()).copy(kelvin = kelvin.coerceIn(range))
        }
        publish()
        // OFF Kelvin is pending intent only; a later brightness/ON request applies it.
        if (light.isOn) commands.put(id.removePrefix("ha:"), "turn_on", 1)
    }
    private companion object {
        val HOME_DOMAINS = HomeKind.entries.map { it.name.lowercase() }.toSet()
    }
    private fun appearanceHelper(id: String, domain: String): HaEntity? {
        if (!connected || !id.startsWith("$domain.")) return null
        return store.entities[id]?.takeIf { it.usable && store.enabled(it) }
    }
    private fun numberHelper(id: String, minimum: Int = 0): HaEntity? = appearanceHelper(id, "input_number")?.takeIf { helper ->
        val configuredMin = helper.attributes.number("min")
        val configuredMax = helper.attributes.number("max")
        (configuredMin == null || configuredMin <= minimum) && (configuredMax == null || configuredMax >= 100)
    }
    private fun booleanHelper(id: String): HaEntity? = appearanceHelper(id, "input_boolean")

    private fun requestDeviceNumber(id: String, percent: Int, minimum: Int) {
        if (percent !in minimum..100 || numberHelper(id, minimum) == null) return
        deviceCommands.put(id, if (minimum == 1) "brightness" else "volume", percent)
    }
    fun requestDisplayBrightness(percent: Int) {
        if (percent in 1..100) requestDeviceNumber(settings.displayBrightnessEntity, percent, 1)
    }
    fun requestSystemBrightness(percent: Int) {
        if (percent in 1..100) requestDeviceNumber(settings.systemBrightnessEntity, percent, 1)
    }
    fun requestBlackout(enabled: Boolean) = requestBooleanHelper(settings.blackoutEntity, enabled)
    fun requestSystemAutomatic(enabled: Boolean) = requestBooleanHelper(settings.systemAutomaticEntity, enabled)
    fun requestFollowSystem(enabled: Boolean) = requestBooleanHelper(settings.followSystemEntity, enabled)
    fun requestKeepAwake(enabled: Boolean) = requestBooleanHelper(settings.keepAwakeEntity, enabled)
    fun requestStartAfterBoot(enabled: Boolean) = requestBooleanHelper(settings.startAfterBootEntity, enabled)
    fun requestMediaVolume(percent: Int) = requestDeviceNumber(settings.mediaVolumeEntity, percent, 0)

    private fun requestBooleanHelper(id: String, enabled: Boolean) {
        val helper = booleanHelper(id) ?: return
        socket.service("input_boolean", if (enabled) "turn_on" else "turn_off", helper.id)
    }

    private fun applyDeviceHelpers(changedEntity: String? = null) {
        if (!connected) return
        fun value(id: String, minimum: Int): Int? {
            val helper = numberHelper(id, minimum) ?: return null
            val numeric = helper.value.toDoubleOrNull()?.takeIf { it.isFinite() && it in minimum.toDouble()..100.0 } ?: return null
            return kotlin.math.round(numeric).toInt()
        }
        fun applyBoolean(id: String, callback: ((Boolean) -> Unit)?) {
            if (callback == null || (changedEntity != null && changedEntity != id)) return
            when (booleanHelper(id)?.value?.lowercase()) {
                "on" -> callback(true)
                "off" -> callback(false)
            }
        }
        // Snapshot dependency order: Follow System before DormPanel brightness, and
        // Automatic mode before System brightness, so a manual brightness value can apply.
        applyBoolean(settings.followSystemEntity, remoteFollowSystem)
        // A reconnect snapshot must not turn Follow System into Override.
        if ((changedEntity != null || acceptBrightnessSnapshot()) &&
            (changedEntity == null || changedEntity == settings.displayBrightnessEntity))
            value(settings.displayBrightnessEntity, 1)?.let { remoteBrightness?.invoke(it) }
        applyBoolean(settings.systemAutomaticEntity, remoteSystemAutomatic)
        if (changedEntity == null || changedEntity == settings.systemBrightnessEntity)
            value(settings.systemBrightnessEntity, 1)?.let { remoteSystemBrightness?.invoke(it) }
        if (changedEntity == null || changedEntity == settings.mediaVolumeEntity)
            value(settings.mediaVolumeEntity, 0)?.let { remoteVolume?.invoke(it) }
        applyBoolean(settings.blackoutEntity, remoteBlackout)
        applyBoolean(settings.keepAwakeEntity, remoteKeepAwake)
        applyBoolean(settings.startAfterBootEntity, remoteStartAfterBoot)
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
