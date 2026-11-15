package com.dormpanel.app.weather

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.dormpanel.app.ha.HaDashboardDataSource
import com.dormpanel.app.ha.HaScheduler
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.Executors

internal class WeatherServices(
    context: Context,
    private val scheduler: HaScheduler,
    private val ha: HaDashboardDataSource,
    private val serverId: () -> String,
    accountId: () -> String?,
    private val now: () -> Instant = Instant::now,
    val settings: QWeatherSettings = QWeatherSettings(context),
    apiFactory: (OkHttpClient, () -> Instant) -> QWeatherApi = { http, clock -> QWeatherApi(http, clock) },
) : AutoCloseable {
    val places = WeatherPlaceStore(context)
    val budget = QWeatherBudget(context.getSharedPreferences("weather_budget", Context.MODE_PRIVATE), now)
    private val worker = Executors.newSingleThreadExecutor()
    private val http = OkHttpClient.Builder().dispatcher(Dispatcher().apply {
        maxRequests = 2
        maxRequestsPerHost = 2
    }).build()
    private val qw = QWeatherProvider(apiFactory(http, now), { settings.config }, { settings.problem }, budget)
    private val home = HaWeatherProvider(ha, serverId, accountId)
    val repository = WeatherRepository(places, { if (it is WeatherSourceRef.QWeather) qw else home },
        DiskWeatherCache(File(context.noBackupFilesDir, "weather-v1"), worker, scheduler::execute), scheduler, now)
    private val diagnostics = linkedSetOf<() -> Unit>()

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var validatedNetwork: Network? = null
    private val network = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            scheduler.execute {
                if (validated && network != validatedNetwork) {
                    validatedNetwork = network
                    repository.onNetworkAvailable()
                } else if (!validated && network == validatedNetwork) validatedNetwork = null
            }
        }
        override fun onLost(network: Network) {
            scheduler.execute { if (network == validatedNetwork) validatedNetwork = null }
        }
    }
    private val networkRegistered = runCatching { connectivity.registerDefaultNetworkCallback(network) }.isSuccess

    init { budget.initialize(settings.config?.id) }

    fun configure(host: String, auth: QWeatherAuth) {
        settings.save(host, auth)
        changed()
    }

    fun clearCredentials() {
        settings.clear()
        changed()
    }

    private fun changed() {
        diagnostics.toList().forEach { it() }
        budget.accountChanged(settings.config?.id)
        repository.onConfigurationChanged()
    }

    fun haSources(): List<Pair<WeatherSourceRef.HomeAssistant, String>> = if (serverId().isEmpty()) emptyList()
    else ha.store.discovered("weather").map { WeatherSourceRef.HomeAssistant(it.id, serverId()) to ha.store.name(it) }

    fun test(done: (String) -> Unit): () -> Unit {
        val source = places.places.asSequence().flatMap { it.sources.asSequence() }
            .filterIsInstance<WeatherSourceRef.QWeather>().firstOrNull()
            ?: WeatherSourceRef.QWeather(WeatherCoordinates("39.92".toBigDecimal(), "116.41".toBigDecimal()))
        val query = WeatherQuery(WeatherBlock.CURRENT)
        return request(checkNotNull(QWeatherProvider.endpoint(source, query)), true,
            { QWeatherParser.parse(it, ZoneId.of("UTC"), query) }, explicitTest = true) { result ->
            if (result is WeatherResult.Data) repository.onConfigurationChanged(resetAuthorization = true)
            done(when (result) {
                is WeatherResult.Data -> if (result.parsed.data is WeatherData.Current) "认证成功，实时天气已返回" else "服务已响应，测试地点无实时数据"
                is WeatherResult.Failed -> result.failure.message
            })
        }
    }

    data class City(val name: String, val area: String, val coordinates: WeatherCoordinates,
                    val locationId: String?, val zone: ZoneId?, val attribution: WeatherAttribution)

    fun search(query: String, done: (List<City>, String?) -> Unit): () -> Unit {
        require(query.isNotBlank() && query.length <= 100) { "请输入城市或区县名称" }
        val geo = QWeatherProvider.cityLookup(query)
        return request(geo, false, { ParsedWeather(null) }) { result ->
            when (result) {
                is WeatherResult.Failed -> done(emptyList(), result.failure.message)
                is WeatherResult.Data -> {
                    val attribution = QWeatherParser.parse(result.wire, ZoneId.of("UTC"), WeatherQuery(WeatherBlock.CURRENT)).attribution
                    val cities = result.wire.objects("location").mapNotNull { item ->
                        runCatching {
                            City(item.getString("name"), listOfNotNull(item.text("adm2"), item.text("adm1"),
                                item.text("country")).distinct().joinToString(" / "),
                                WeatherCoordinates(item.getString("lat").toBigDecimal(), item.getString("lon").toBigDecimal()),
                                item.text("id")?.takeIf { it.matches(Regex("[A-Za-z0-9]{1,40}")) },
                                item.text("tz")?.let { runCatching { ZoneId.of(it) }.getOrNull() }, attribution)
                        }.getOrNull()
                    }
                    done(cities, null)
                }
            }
        }
    }

    fun select(city: City, zone: ZoneId): WeatherPlace = WeatherPlace(UUID.randomUUID().toString(), city.name, zone,
        listOf(WeatherSourceRef.QWeather(city.coordinates, city.locationId))).also(places::addLookup)

    private fun request(endpoint: QWeatherEndpoint, core: Boolean, parse: (JSONObject) -> ParsedWeather,
                        explicitTest: Boolean = false, done: (WeatherResult) -> Unit): () -> Unit {
        val config = settings.config
        if (config == null) {
            done(WeatherResult.Failed(WeatherFailure(WeatherState.UNCONFIGURED, settings.problem)))
            return {}
        }
        var cancelled = false
        var stopNetwork: (() -> Unit)? = null
        lateinit var cancel: () -> Unit
        cancel = {
            cancelled = true
            stopNetwork?.invoke()
            diagnostics -= cancel
            Unit
        }
        diagnostics += cancel
        stopNetwork = qw.request(config, endpoint, core, parse, explicitTest) { result ->
            scheduler.execute {
                if (!cancelled && settings.config?.id == config.id) {
                    diagnostics -= cancel
                    done(result)
                }
            }
        }
        return cancel
    }

    override fun close() {
        if (networkRegistered) connectivity.unregisterNetworkCallback(network)
        diagnostics.toList().forEach { it() }
        repository.close()
        worker.shutdown()
        http.dispatcher.cancelAll()
        http.connectionPool.evictAll()
        http.dispatcher.executorService.shutdown()
    }
}
