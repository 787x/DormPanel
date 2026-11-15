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
    apiFactory: ((OkHttpClient, () -> Instant) -> QWeatherApi)? = null,
    publicLocationUrl: okhttp3.HttpUrl = PublicWeatherLocations.LIST_URL,
) : AutoCloseable {
    val places = WeatherPlaceStore(context)
    val budget = QWeatherBudget(context.getSharedPreferences("weather_budget", Context.MODE_PRIVATE), now)
    private val refreshSettings = WeatherRefreshSettings(context.getSharedPreferences("weather_refresh", Context.MODE_PRIVATE))
    var refreshPolicy = refreshSettings.read()
        private set
    private val worker = Executors.newSingleThreadExecutor()
    private val http = OkHttpClient.Builder().dispatcher(Dispatcher().apply {
        maxRequests = 2
        maxRequestsPerHost = 2
    }).build()
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val qw = QWeatherProvider(apiFactory?.invoke(http, now) ?: QWeatherApi(http, now, offline = {
        runCatching {
            val network = connectivity.activeNetwork
            connectivity.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) != true
        }.getOrDefault(false)
    }), { settings.config }, { settings.problem }, budget, scheduler::execute)
    private val publicLocations = PublicWeatherLocations(http, publicLocationUrl)
    private val home = HaWeatherProvider(ha, serverId, accountId)
    val repository = WeatherRepository(places, { if (it is WeatherSourceRef.HomeAssistant) home else qw },
        DiskWeatherCache(File(context.noBackupFilesDir, "weather-v1"), worker, scheduler::execute), scheduler, now)
    private val diagnostics = linkedSetOf<() -> Unit>()

    private var validatedNetwork: Network? = null
    private val network = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            scheduler.execute {
                if (validated && network != validatedNetwork) {
                    validatedNetwork = network
                    repository.onNetworkAvailable()
                } else if (!validated && network == validatedNetwork) {
                    validatedNetwork = null
                    repository.onNetworkLost()
                }
            }
        }
        override fun onLost(network: Network) {
            scheduler.execute {
                if (network == validatedNetwork) {
                    validatedNetwork = null
                    repository.onNetworkLost()
                }
            }
        }
    }
    private val networkRegistered = runCatching { connectivity.registerDefaultNetworkCallback(network) }.isSuccess

    init {
        budget.initialize(settings.config?.id)
        repository.usePolicy(refreshPolicy)
    }

    fun setRefreshPolicy(policy: WeatherRefreshPolicy) {
        refreshSettings.save(policy)
        refreshPolicy = policy
        repository.usePolicy(policy)
    }

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
        qw.configurationChanged()
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

    fun favorite(previewId: String?, publicCandidate: String?, name: String, zone: ZoneId,
                 done: (WeatherPlace?, String?) -> Unit): () -> Unit {
        val city = publicCandidate?.let { runCatching { WeatherSourceRef.QWeatherCity(it) }.getOrNull() }
        if (city == null) {
            done(null, "此地点没有可验证的公开编号，请用自己的名称、坐标和时区保存")
            return {}
        }
        require(name.isNotBlank()) { "请输入收藏名称" }
        var cancelled = false
        val cancel = publicLocations.verify(city) { found ->
            scheduler.execute {
                if (cancelled) return@execute
                if (found != true) {
                    done(null, if (found == null) "公开地点目录暂时无法访问，请稍后重试"
                    else "公开目录未收录该地点。可以用自己提供的坐标和时区保存")
                } else if (previewId != null && !places.isTemporary(previewId)) {
                    done(null, "预览地点已移除，请重新搜索")
                } else {
                    val place = WeatherPlace(previewId ?: UUID.randomUUID().toString(), name, zone, listOf(city))
                    if (runCatching { places.saveFavorite(place) }.isSuccess) done(place, null)
                    else done(null, "无法保存收藏，请检查存储空间或地点数量")
                }
            }
        }
        return { cancelled = true; cancel() }
    }

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
