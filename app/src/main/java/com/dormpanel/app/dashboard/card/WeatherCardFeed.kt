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
    private var compactForecast = false
    private var forecastBlock: WeatherBlock? = null
    private val declinedForecasts = mutableSetOf<WeatherBlock>()
    private var syncing = false
    private var resync = false
    private var generation = 0
    val forecast get() = forecastBlock?.let(snapshots::get)
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

    fun bind(configuration: WeatherCardConfiguration, blocks: Set<WeatherBlock>, compactForecast: Boolean = false) {
        val previous = this.configuration
        val sameBinding = previous is WeatherCardConfiguration.Bound && configuration is WeatherCardConfiguration.Bound &&
            previous.placeId == configuration.placeId && previous.source == configuration.source
        if (previous != configuration && !sameBinding) {
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
        this.configuration = configuration
        this.blocks = blocks
        if (!compactForecast) declinedForecasts.clear()
        this.compactForecast = compactForecast
        subscribe()
    }

    private fun subscribe() {
        // observe() may publish the cached snapshot synchronously.
        resync = true
        if (syncing) return
        syncing = true
        try {
            while (resync) {
                resync = false
                reconcileSubscriptions()
            }
        } finally {
            syncing = false
        }
    }

    private fun reconcileSubscriptions() {
        val binding = bound ?: return
        val repository = repository ?: return
        if (place == null) {
            if (subscriptions.isNotEmpty()) stopSubscriptions()
            return
        }
        forecastBlock = if (!compactForecast) null else if (binding.source is WeatherSourceRef.HomeAssistant) {
            val current = snapshots[WeatherBlock.CURRENT]
            val features = current?.haForecastFeatures
            val candidates = haForecastOrder.filter {
                features == null || features and checkNotNull(it.haForecastFeature()) != 0
            }
            if (current == null || (current.data == null && current.state in setOf(WeatherState.UNLOADED, WeatherState.LOADING))) null
            else candidates.firstOrNull { it !in declinedForecasts } ?: forecastBlock?.takeIf { it in candidates }
        } else WeatherBlock.DAILY
        val required = blocks + listOfNotNull(forecastBlock)
        (subscriptions.keys - required).forEach {
            snapshots.remove(it)
            subscriptions.remove(it)?.close()
        }
        val active = generation
        (required - subscriptions.keys).forEach { block ->
            subscriptions[block] = repository.observe(
                binding.placeId, binding.source, WeatherQuery(block),
                automatic = true,
            ) { snapshot ->
                if (active != generation || (block !in blocks && block != forecastBlock)) return@observe
                val previous = snapshots.put(block, snapshot)
                if (previous == snapshot) return@observe
                if (compactForecast && binding.source is WeatherSourceRef.HomeAssistant) {
                    if (block == WeatherBlock.CURRENT &&
                        (previous?.haForecastFeatures != snapshot.haForecastFeatures ||
                            (previous?.state == WeatherState.ERROR && snapshot.state == WeatherState.FRESH) ||
                            (previous?.network == WeatherNetworkStatus.OFFLINE && snapshot.network != WeatherNetworkStatus.OFFLINE))) {
                        declinedForecasts.clear()
                    }
                    // Keep a transient failure subscribed so its next live update can recover.
                    if (block == forecastBlock && snapshot.data == null &&
                        (snapshot.state == WeatherState.EMPTY || snapshot.state == WeatherState.UNSUPPORTED)) {
                        declinedForecasts += block
                    }
                    subscribe()
                }
                changed()
            }
        }
    }

    fun refresh(): Map<WeatherBlock, WeatherRefreshResult> =
        subscriptions.mapValues { it.value.refresh() }

    private fun stopSubscriptions() {
        generation++
        forecastBlock = null
        declinedForecasts.clear()
        snapshots.clear()
        val previous = subscriptions.values.toList()
        subscriptions.clear()
        previous.forEach { it.close() }
    }

    private val haForecastOrder = listOf(WeatherBlock.DAILY, WeatherBlock.TWICE_DAILY, WeatherBlock.HOURLY)

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
