package com.dormpanel.app.dashboard.card

import com.dormpanel.app.data.SwitchIdentity
import org.json.JSONArray
import org.json.JSONObject

internal const val SWITCH_CARD = "switch"
internal const val SWITCH_GROUP_CARD = "switch_group"
internal fun String.isSwitchCard() = this == SWITCH_CARD || this == SWITCH_GROUP_CARD

internal data class SwitchChannelBinding(val identity: SwitchIdentity, val name: String = "")

internal data class SwitchCardConfiguration(
    val sourceId: String,
    val deviceId: String?,
    val channels: List<SwitchChannelBinding>,
    val name: String = "",
) {
    fun encode() = JSONObject()
        .put("source", sourceId)
        .put("device", deviceId)
        .put("name", name)
        .put("channels", JSONArray().apply {
            channels.forEach { channel ->
                put(JSONObject().put("entity", channel.identity.entityId)
                    .put("device", channel.identity.deviceId)
                    .put("entry", channel.identity.entryId)
                    .put("name", channel.name))
            }
        }).toString()

    fun isValid(type: String): Boolean =
        sourceId.isNotBlank() && channels.all { it.identity.entryId.isNotBlank() &&
            it.identity.entityId.isNotBlank() && it.identity.deviceId == deviceId } &&
            channels.map { it.identity.entityId }.distinct().size == channels.size &&
            when (type) {
                SWITCH_CARD -> channels.size == 1
                SWITCH_GROUP_CARD -> !deviceId.isNullOrBlank() && channels.size in 1..3
                else -> false
            }

    companion object {
        fun decode(json: String): SwitchCardConfiguration? = runCatching {
            val config = JSONObject(json)
            val entries = config.getJSONArray("channels")
            require(entries.length() <= 3)
            SwitchCardConfiguration(config.getString("source"), config.optionalDevice(),
                List(entries.length()) { index ->
                    val entry = entries.getJSONObject(index)
                    SwitchChannelBinding(SwitchIdentity(entry.getString("entity"), entry.optionalDevice(),
                        entry.getString("entry")), entry.optString("name"))
                }, config.optString("name"))
        }.getOrNull()

        private fun JSONObject.optionalDevice() = if (isNull("device")) null else getString("device")
    }
}
