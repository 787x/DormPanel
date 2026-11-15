package com.dormpanel.app.dashboard.card

import com.dormpanel.app.data.DashboardData
import com.dormpanel.app.data.DashboardDataSource
import com.dormpanel.app.data.WeatherState as LegacyWeather
import com.dormpanel.app.weather.*

/** One visible card or detail panel owns this set of main-thread demands. */
internal class WeatherCardFeed(
    private val source: DashboardDataSource,
    private val repository: WeatherRepository?,
    private val changed: () -> Unit,
) : AutoCloseable {
    private var configuration: WeatherCardConfiguration? = null
    private var blocks = emptySet<WeatherBlock>()
    private var generation = 0
    private val subscriptions = linkedMapOf<WeatherBlock, WeatherRepository.Subscription>()
    private val snapshots = mutableMapOf<WeatherBlock, WeatherSnapshot>()
    private var stopPlaces: (() -> Unit)? = null
    private var legacyListening = false
    var legacy: LegacyWeather? = null
        private set
    val bound get() = configuration as? WeatherCardConfiguration.Bound
    val place get() = bound?.place(repository?.places)
    val invalid get() = configuration == WeatherCardConfiguration.Invalid || (bound != null && place == null)
    operator fun get(block: WeatherBlock) = snapshots[block]

    private val legacyListener: (DashboardData) -> Unit = { data ->
        if (configuration == WeatherCardConfiguration.Default && data.weather != legacy) {
            legacy = data.weather
            changed()
        }
    }

    fun bind(configuration: WeatherCardConfiguration, blocks: Set<WeatherBlock>) {
        if (this.configuration != configuration) {
            close()
            this.configuration = configuration
            when (configuration) {
                WeatherCardConfiguration.Default -> {
                    legacyListening = true
                    source.addListener(legacyListener)
                }
                is WeatherCardConfiguration.Bound -> {
                    stopPlaces = repository?.places?.observe {
                        subscribe()
                        changed()
                    }
                }
                WeatherCardConfiguration.Invalid -> Unit
            }
        }
        if (this.blocks != blocks) {
            this.blocks = blocks
            (subscriptions.keys - blocks).forEach {
                snapshots.remove(it)
                subscriptions.remove(it)?.close()
            }
        }
        subscribe()
    }

    private fun subscribe() {
        val binding = bound ?: return
        val repository = repository ?: return
        if (place == null) {
            if (subscriptions.isNotEmpty()) stopSubscriptions()
            return
        }
        val active = generation
        (blocks - subscriptions.keys).forEach { block ->
            subscriptions[block] = repository.observe(
                binding.placeId, binding.source, WeatherQuery(block),
                automatic = true,
            ) { snapshot ->
                if (active == generation && block in blocks && snapshots.put(block, snapshot) != snapshot) changed()
            }
        }
    }

    fun refresh(): Map<WeatherBlock, WeatherRefreshResult> =
        subscriptions.mapValues { it.value.refresh() }

    private fun stopSubscriptions() {
        generation++
        snapshots.clear()
        val previous = subscriptions.values.toList()
        subscriptions.clear()
        previous.forEach { it.close() }
    }

    override fun close() {
        configuration = null
        blocks = emptySet()
        stopPlaces?.invoke()
        stopPlaces = null
        stopSubscriptions()
        if (legacyListening) source.removeListener(legacyListener)
        legacyListening = false
        legacy = null
    }
}
