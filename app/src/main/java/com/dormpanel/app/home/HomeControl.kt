package com.dormpanel.app.home

import com.dormpanel.app.data.*

data class HomeArea(val id: String, val name: String)
data class HomeDevice(val id: String, val name: String, val areaId: String?)
enum class HomeKind { LIGHT, SWITCH, SENSOR, SCENE, SCRIPT, CLIMATE, FAN, HUMIDIFIER, COVER, INPUT_BOOLEAN }
data class HomeEntity(
    val id: String, val name: String, val kind: HomeKind, val areaId: String?, val deviceId: String?,
    val availability: Availability, val value: String = "", val unit: String = "",
    val isOn: Boolean = false, val light: LightState? = null, val actionable: Boolean = true,
    val controls: HomeDeviceState? = null, val target: HomeTarget? = null,
    val request: DeviceRequestState? = null,
)
data class HomeControlState(
    val areas: List<HomeArea> = emptyList(), val devices: List<HomeDevice> = emptyList(),
    val entities: List<HomeEntity> = emptyList(), val connected: Boolean = false,
)
interface HomeControlSource {
    val homeState: HomeControlState
    fun addHomeListener(listener: (HomeControlState) -> Unit)
    fun removeHomeListener(listener: (HomeControlState) -> Unit)
    fun activateEntity(id: String): Boolean
    fun command(target: HomeTarget, command: HomeCommand): Boolean = false
}
/** null is All; empty string is Unassigned. Owned by the session, never by the View. */
class HomeSelection {
    var areaId: String? = null
    fun reconcile(state: HomeControlState) {
        if (areaId == "" && state.entities.none { it.areaId == null } ||
            areaId != null && areaId != "" && state.areas.none { it.id == areaId }) areaId = null
    }
}
data class HomeGroup(val area: HomeArea?, val device: HomeDevice?, val entities: List<HomeEntity>)
fun HomeControlState.groups(selection: String?): List<HomeGroup> = entities
    .filter { selection == null || it.areaId.orEmpty() == selection }
    .groupBy { it.areaId to it.deviceId }
    .map { (key, values) -> HomeGroup(areas.find { it.id == key.first }, devices.find { it.id == key.second },
        values.sortedWith(compareBy({ it.kind.ordinal }, { it.name }, { it.id }))) }
    .sortedWith(compareBy({ it.area?.name ?: "\uffff" }, { it.area?.id ?: "" }, { it.device?.name ?: "\uffff" }, { it.device?.id ?: "" }))

/** Demo lights are projected directly from the dashboard owner. */
class DemoHomeSource(private val dashboard: DashboardDataSource) : HomeControlSource {
    private val demoDevices = DemoDevices()
    private val listeners = linkedSetOf<(HomeControlState) -> Unit>()
    private val areas = listOf(HomeArea("bedroom", "Bedroom"), HomeArea("study", "Study"), HomeArea("demo-switches", "Demo switches"))
    override val homeState get() = HomeControlState(areas,
        listOf(HomeDevice("bed", "Bedside", "bedroom"), HomeDevice("desk", "Desk", "study"),
            HomeDevice("climate-room", "Climate sensor", "bedroom"), HomeDevice("climate-balcony", "Balcony sensor", null)) +
            dashboard.state.switches.deviceNames.filterKeys { it != "desk" }.map { (id, name) -> HomeDevice(id, name, "demo-switches") },
        dashboard.state.lights.values.map { light ->
            val area = when (light.id) { "bedside" -> "bedroom"; "desk" -> "study"; else -> null }
            HomeEntity(light.id, light.name, HomeKind.LIGHT, area, when(light.id) { "bedside" -> "bed"; "desk" -> "desk"; else -> null }, light.availability, isOn = light.isOn, light = light)
        } + dashboard.state.sensors.values.flatMap { sensor ->
            val area = when(sensor.id) { "room" -> "bedroom"; "desk" -> "study"; else -> null }
            val device = if (sensor.id == "desk") "desk" else "climate-${sensor.id}"
            listOf(
                HomeEntity("temperature:${sensor.id}", "Temperature", HomeKind.SENSOR, area, device, sensor.availability, sensor.temperature?.toString().orEmpty(), sensor.temperatureUnit),
                HomeEntity("humidity:${sensor.id}", "Humidity", HomeKind.SENSOR, area, device, sensor.availability, sensor.humidity?.toString().orEmpty(), sensor.humidityUnit))
        } + dashboard.state.switches.channels.values.map {
            if (it.identity.isVirtual) HomeEntity(it.identity.entityId, it.name, HomeKind.INPUT_BOOLEAN,
                "demo-switches", null, it.availability, isOn = it.isOn == true, controls = HomeDeviceState.VirtualSwitch(it.isOn),
                target = HomeTarget(dashboard.state.switches.sourceId, it.identity))
            else HomeEntity(it.identity.entityId, it.name, HomeKind.SWITCH,
                if (it.identity.deviceId == "desk") "study" else "demo-switches",
                it.identity.deviceId, it.availability, isOn = it.isOn == true)
        } + demoDevices.entities() + listOf(
            HomeEntity("scene:night", "Good night", HomeKind.SCENE, "bedroom", null, Availability.AVAILABLE),
            HomeEntity("script:study", "Start studying", HomeKind.SCRIPT, "study", null, Availability.AVAILABLE)), true)
    private val dashboardListener: (DashboardData) -> Unit = { publish() }
    private fun publish() { val next = homeState; listeners.toList().forEach { it(next) } }
    override fun addHomeListener(listener: (HomeControlState) -> Unit) {
        if (!listeners.add(listener)) return
        if (listeners.size == 1) dashboard.addListener(dashboardListener) else listener(homeState)
    }
    override fun removeHomeListener(listener: (HomeControlState) -> Unit) {
        if (listeners.remove(listener) && listeners.isEmpty()) dashboard.removeListener(dashboardListener)
    }
    override fun command(target: HomeTarget, command: HomeCommand): Boolean {
        if (target.identity.isVirtual) {
            val switches = dashboard.state.switches
            val channel = switches.resolve(target.sourceId, target.identity) ?: return false
            val on = channel.isOn ?: return false
            val power = command as? HomeCommand.Power ?: return false
            return channel.availability == Availability.AVAILABLE &&
                (on == power.on || dashboard.toggleSwitch(target.sourceId, target.identity))
        }
        if (!demoDevices.apply(target, command)) return false
        publish()
        return true
    }
    override fun activateEntity(id: String): Boolean {
        val entity = homeState.entities.find { it.id == id && it.availability == Availability.AVAILABLE } ?: return false
        when (entity.kind) {
            HomeKind.LIGHT -> dashboard.setLightPower(id, !entity.isOn)
            HomeKind.SWITCH -> return dashboard.state.switches.let { switches ->
                switches.channels[id]?.let { dashboard.toggleSwitch(switches.sourceId, it.identity) } == true
            }
            HomeKind.SCENE -> dashboard.state.lights.values.filter { it.isOn }.forEach { dashboard.toggleLight(it.id) }
            HomeKind.SCRIPT -> if (dashboard.state.lights["desk"]?.isOn == false) dashboard.toggleLight("desk")
            HomeKind.SENSOR -> return false
            else -> return entity.target?.let { target ->
                entity.controls?.primaryCommand()?.let { command(target, it) }
            } == true
        }
        return true
    }
}
