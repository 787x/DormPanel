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
    private val unsupportedForecasts = mutableSetOf<WeatherBlock>()
    private val emptyForecasts = mutableSetOf<WeatherBlock>()
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
        if (!compactForecast) {
            unsupportedForecasts.clear()
            emptyForecasts.clear()
        }
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
        val ha = binding.source as? WeatherSourceRef.HomeAssistant
        val current = snapshots[WeatherBlock.CURRENT]
        val candidates = if (ha != null && compactForecast) haForecastCandidates(current) else emptyList()
        forecastBlock = when {
            !compactForecast -> null
            ha == null -> WeatherBlock.DAILY
            else -> candidates.firstOrNull { it !in unsupportedForecasts && it !in emptyForecasts }
                ?: forecastBlock?.takeIf { it in candidates }
                ?: candidates.firstOrNull { it in emptyForecasts }
        }
        val recovery = if (current == null || current.network == WeatherNetworkStatus.OFFLINE ||
            current.state in setOf(WeatherState.ERROR, WeatherState.UNAUTHORIZED, WeatherState.UNCONFIGURED)) emptyList()
        else candidates.takeWhile { it != forecastBlock }.filter { it in emptyForecasts }
        val required = blocks + listOfNotNull(forecastBlock) + recovery
        (subscriptions.keys - required).forEach {
            snapshots.remove(it)
            subscriptions.remove(it)?.close()
        }
        val active = generation
        (required - subscriptions.keys).forEach { block ->
            val listener: (WeatherSnapshot) -> Unit = listener@{ snapshot ->
                if (active != generation) return@listener
                val previous = snapshots.put(block, snapshot)
                if (previous == snapshot) return@listener
                if (ha != null && compactForecast) {
                    val recheck = if (block == WeatherBlock.CURRENT) {
                        previous != null && (previous.haForecastFeatures != snapshot.haForecastFeatures ||
                            (previous.state in setOf(WeatherState.ERROR, WeatherState.UNAUTHORIZED, WeatherState.UNCONFIGURED) &&
                                snapshot.state == WeatherState.FRESH) ||
                            (previous.network == WeatherNetworkStatus.OFFLINE && snapshot.network != WeatherNetworkStatus.OFFLINE))
                    } else {
                        if (snapshot.data is WeatherData.Forecast) {
                            emptyForecasts -= block
                        } else if (snapshot.state == WeatherState.UNSUPPORTED && !snapshot.refreshing) {
                            emptyForecasts -= block
                            unsupportedForecasts += block
                        } else if (snapshot.haForecastRecovering || (snapshot.state == WeatherState.EMPTY && !snapshot.refreshing)) {
                            emptyForecasts += block
                        }
                        false
                    }
                    if (recheck) {
                        val reconsidered = haForecastCandidates(snapshot).filterTo(mutableSetOf()) {
                            it in emptyForecasts || it in unsupportedForecasts
                        }
                        unsupportedForecasts.clear()
                        if (reconsidered.isNotEmpty()) repository.recheckEmptyHaForecasts(binding.placeId, ha, reconsidered)
                    }
                    subscribe()
                }
                changed()
            }
            subscriptions[block] = if (ha != null && compactForecast && block in haForecastOrder) {
                repository.observeHaCardForecast(binding.placeId, ha, block, listener)
            } else repository.observe(binding.placeId, binding.source, WeatherQuery(block), automatic = true, listener)
        }
    }

    private fun haForecastCandidates(current: WeatherSnapshot?): List<WeatherBlock> {
        if (current == null || (current.data == null &&
                current.state in setOf(WeatherState.UNLOADED, WeatherState.LOADING))) return emptyList()
        val features = current.haForecastFeatures ?: return haForecastOrder
        return haForecastOrder.filter { features and checkNotNull(it.haForecastFeature()) != 0 }
    }

    fun refresh(): Map<WeatherBlock, WeatherRefreshResult> =
        subscriptions.mapValues { it.value.refresh() }

    private fun stopSubscriptions() {
        generation++
        forecastBlock = null
        unsupportedForecasts.clear()
        emptyForecasts.clear()
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
