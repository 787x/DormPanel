package com.dormpanel.app.data

/** The registry entry distinguishes a reused entity ID from the original channel. */
data class SwitchIdentity(val entityId: String, val deviceId: String?, val entryId: String) {
    // Older builds serialized connection-local observations in this field.
    val isVirtual get() = entityId.removePrefix("ha:").startsWith("input_boolean.")
    val isLegacyObservation get() = entryId.startsWith("observed:")
    val isPersistent get() = entryId.isNotBlank() && !isLegacyObservation
}

data class DashboardSwitch(
    val identity: SwitchIdentity,
    val name: String,
    val isOn: Boolean?,
    val availability: Availability,
    val request: com.dormpanel.app.home.DeviceRequestState? = null,
)

data class DashboardSwitches(
    val sourceId: String = "",
    val connected: Boolean = false,
    val channels: Map<String, DashboardSwitch> = emptyMap(),
    val deviceNames: Map<String, String> = emptyMap(),
) {
    fun resolve(sourceId: String, identity: SwitchIdentity): DashboardSwitch? =
        channels[identity.entityId]?.takeIf {
            identity.isPersistent && this.sourceId == sourceId && it.identity == identity
        }
}
