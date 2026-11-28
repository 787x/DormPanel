package com.dormpanel.app.dashboard.catalog

import com.dormpanel.app.R
import com.dormpanel.app.dashboard.card.SwitchCardConfiguration
import com.dormpanel.app.dashboard.card.SwitchChannelBinding
import com.dormpanel.app.home.HomeEntity
import com.dormpanel.app.home.HomeKind
import com.dormpanel.app.home.HomeTarget

internal val CONTROL_CARD_KINDS = listOf(HomeKind.CLIMATE, HomeKind.FAN)

internal fun HomeTarget.matchesControlCard(kind: HomeKind) = sourceId.isNotBlank() && identity.isPersistent &&
    identity.entityId.startsWith("ha:${kind.name.lowercase()}.")

internal fun controlCardCandidates(entities: List<HomeEntity>) = entities.mapNotNull { entity ->
    if (entity.kind !in CONTROL_CARD_KINDS) return@mapNotNull null
    val target = entity.target?.takeIf { it.matchesControlCard(entity.kind) } ?: return@mapNotNull null
    val type = entity.kind.name.lowercase()
    val config = SwitchCardConfiguration(target.sourceId, target.identity.deviceId,
        listOf(SwitchChannelBinding(target.identity)), entity.name)
    CardAddCandidate("control:${entity.id}", CardCategory.HOME, type, entity.name, "", config.encode(),
        if (entity.kind == HomeKind.CLIMATE) R.string.card_climate_description else R.string.card_fan_description)
}

internal fun controlCardTarget(json: String, kind: HomeKind): HomeTarget? {
    val config = SwitchCardConfiguration.decode(json) ?: return null
    val identity = config.channels.singleOrNull()?.identity ?: return null
    return HomeTarget(config.sourceId, identity).takeIf {
        config.deviceId == identity.deviceId && it.matchesControlCard(kind)
    }
}

internal fun HomeTarget.resolve(entities: List<HomeEntity>, kind: HomeKind) =
    entities.find { it.kind == kind && it.target == this }
