package com.dormpanel.app.dashboard.catalog

import com.dormpanel.app.data.DashboardDataSource
import com.dormpanel.app.data.DashboardData
import com.dormpanel.app.dashboard.card.CLASSIC_WEATHER_CARD
import com.dormpanel.app.dashboard.card.COMPACT_WEATHER_CARD
import com.dormpanel.app.dashboard.card.WEATHER_CARD
import com.dormpanel.app.dashboard.card.SWITCH_CARD
import com.dormpanel.app.dashboard.card.SWITCH_GROUP_CARD
import com.dormpanel.app.dashboard.card.SwitchCardConfiguration
import com.dormpanel.app.dashboard.card.SwitchChannelBinding
import com.dormpanel.app.data.DashboardSwitches

enum class CardCategory { INFORMATION, HOME, PRODUCTIVITY, SYSTEM, APPS }

data class CardAddCandidate(
    val candidateId: String,
    val category: CardCategory,
    val providerType: String,
    val displayName: String,
    val description: String,
    val configurationJson: String,
    val descriptionResource: Int? = null,
)

/** Discovery contract, independent of views and network protocols. Callbacks run on the main thread. */
interface CardCatalog {
    val candidates: List<CardAddCandidate>
    fun addListener(listener: (List<CardAddCandidate>) -> Unit)
    fun removeListener(listener: (List<CardAddCandidate>) -> Unit)
}

data class CatalogLabels(val clock: String, val clockDetail: String, val weather: String, val weatherDetail: String,
    val sensor: String, val light: String, val dimmableLight: String, val temperatureLight: String,
    val classicWeather: String, val classicWeatherDetail: String,
    val compactWeather: String, val compactWeatherDetail: String,
)

internal fun weatherCandidates(weather: String, weatherDetail: String, classic: String, classicDetail: String,
    compact: String, compactDetail: String) =
    listOf(
        CardAddCandidate(WEATHER_CARD, CardCategory.INFORMATION, WEATHER_CARD, weather, weatherDetail, "{}"),
        CardAddCandidate(COMPACT_WEATHER_CARD, CardCategory.INFORMATION, COMPACT_WEATHER_CARD, compact, compactDetail, "{}"),
        CardAddCandidate(CLASSIC_WEATHER_CARD, CardCategory.INFORMATION, CLASSIC_WEATHER_CARD, classic, classicDetail, "{}"),
    )

internal fun CatalogLabels.weatherCandidates() =
    weatherCandidates(weather, weatherDetail, classicWeather, classicWeatherDetail, compactWeather, compactWeatherDetail)

/** Catalog is a projection of the current source, not a second device-state owner. */
class SourceCardCatalog(private val source: DashboardDataSource, private val labels: CatalogLabels,
    private val extraCandidates: () -> List<CardAddCandidate> = { emptyList() },
) : CardCatalog {
    override val candidates: List<CardAddCandidate>
        get() = buildList {
            add(CardAddCandidate("clock", CardCategory.INFORMATION, "clock", labels.clock, labels.clockDetail, "{}"))
            addAll(labels.weatherCandidates())
            source.state.sensors.values.forEach {
                add(CardAddCandidate("sensor:${it.id}", CardCategory.HOME, "sensor", it.name, labels.sensor, entityConfiguration("sensorId", it.id)))
            }
            source.state.lights.values.forEach {
                val description = when {
                    it.capabilities.colorTemperature != null -> labels.temperatureLight
                    it.capabilities.brightness -> labels.dimmableLight
                    else -> labels.light
                }
                add(CardAddCandidate("light:${it.id}", CardCategory.HOME, "light", it.name, description, entityConfiguration("lightId", it.id)))
            }
            addAll(source.state.switches.cardCandidates())
            addAll(extraCandidates())
        }
    private val subscriptions = mutableMapOf<(List<CardAddCandidate>) -> Unit, (DashboardData) -> Unit>()
    override fun addListener(listener: (List<CardAddCandidate>) -> Unit) {
        removeListener(listener)
        var last: List<CardAddCandidate>? = null
        val subscription: (DashboardData) -> Unit = {
            val next = candidates
            if (next != last) { last = next; listener(next) }
        }
        subscriptions[listener] = subscription
        source.addListener(subscription)
    }
    override fun removeListener(listener: (List<CardAddCandidate>) -> Unit) {
        subscriptions.remove(listener)?.let(source::removeListener)
    }
}

internal fun DashboardSwitches.cardCandidates(): List<CardAddCandidate> {
    if (!connected) return emptyList()
    val ordered = channels.values.filter { it.identity.isPersistent }.sortedBy { it.identity.entityId }
    return buildList {
        ordered.forEach { channel ->
            val config = SwitchCardConfiguration(sourceId, channel.identity.deviceId, listOf(SwitchChannelBinding(channel.identity)))
            add(CardAddCandidate("switch:${channel.identity.entityId}", CardCategory.HOME, SWITCH_CARD,
                channel.name, "Tap to toggle one switch", config.encode(), com.dormpanel.app.R.string.card_switch_description))
        }
        ordered.filter { it.identity.deviceId != null && !it.identity.isVirtual }.groupBy { it.identity.deviceId }.forEach { (device, members) ->
            // Larger devices require an explicit selection. For smaller devices the dialog
            // presents an editable initial order, with no claim about physical position.
            val initial = members.takeIf { it.size <= 3 }.orEmpty().map { SwitchChannelBinding(it.identity) }
            val config = SwitchCardConfiguration(sourceId, device, initial)
            add(CardAddCandidate("switch-group:$device", CardCategory.HOME, SWITCH_GROUP_CARD,
                deviceNames[device] ?: device.orEmpty(), "Control up to three channels independently",
                config.encode(), com.dormpanel.app.R.string.card_switch_group_description))
        }
    }
}

/** JSON string escaping kept platform-independent so candidate creation is covered by JVM tests. */
internal fun entityConfiguration(key: String, id: String): String {
    fun quote(value: String) = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                else -> if (char.code < 32) append("\\u" + char.code.toString(16).padStart(4, '0')) else append(char)
            }
        }
        append('"')
    }
    return "{${quote(key)}:${quote(id)}}"
}
