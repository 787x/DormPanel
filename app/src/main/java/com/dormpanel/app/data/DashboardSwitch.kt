package com.dormpanel.app.data

/** The registry entry distinguishes a reused entity ID from the original channel. */
data class SwitchIdentity(val entityId: String, val deviceId: String?, val entryId: String)

data class DashboardSwitch(
    val identity: SwitchIdentity,
    val name: String,
    val isOn: Boolean?,
    val availability: Availability,
)

data class DashboardSwitches(
    val sourceId: String = "",
    val connected: Boolean = false,
    val channels: Map<String, DashboardSwitch> = emptyMap(),
    val deviceNames: Map<String, String> = emptyMap(),
) {
    fun resolve(sourceId: String, identity: SwitchIdentity): DashboardSwitch? =
        channels[identity.entityId]?.takeIf { this.sourceId == sourceId && it.identity == identity }
}
