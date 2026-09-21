package com.dormpanel.app.dashboard.catalog

import com.dormpanel.app.data.DashboardDataSource
import com.dormpanel.app.data.DashboardData

enum class CardCategory { INFORMATION, HOME, PRODUCTIVITY, SYSTEM, APPS }

data class CardAddCandidate(
    val candidateId: String,
    val category: CardCategory,
    val providerType: String,
    val displayName: String,
    val description: String,
    val configurationJson: String,
)

/** Discovery contract, independent of views and network protocols. Callbacks run on the main thread. */
interface CardCatalog {
    val candidates: List<CardAddCandidate>
    fun addListener(listener: (List<CardAddCandidate>) -> Unit)
    fun removeListener(listener: (List<CardAddCandidate>) -> Unit)
}

data class CatalogLabels(val clock: String, val clockDetail: String, val weather: String, val weatherDetail: String,
    val sensor: String, val light: String, val dimmableLight: String, val temperatureLight: String)

/** Catalog is a projection of the current source, not a second device-state owner. */
class SourceCardCatalog(private val source: DashboardDataSource, private val labels: CatalogLabels) : CardCatalog {
    override val candidates: List<CardAddCandidate>
        get() = buildList {
            add(CardAddCandidate("clock", CardCategory.INFORMATION, "clock", labels.clock, labels.clockDetail, "{}"))
            add(CardAddCandidate("weather", CardCategory.INFORMATION, "weather", labels.weather, labels.weatherDetail, "{}"))
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
