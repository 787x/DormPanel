package com.dormpanel.app.weather

import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal class QWeatherConfig(val id: String, val host: String, val auth: QWeatherAuth) {
    init { require(host == qWeatherHost(host)) }
}
internal data class QWeatherEndpoint(val path: String, val parameters: Map<String, String> = emptyMap()) {
    fun url(host: String): HttpUrl = HttpUrl.Builder().scheme("https").host(host).addPathSegments(path)
        .apply { parameters.forEach { (name, value) -> addQueryParameter(name, value) } }.build()
}

internal class QWeatherApi(
    http: OkHttpClient,
    private val now: () -> Instant,
    private val offline: () -> Boolean = { false },
    private val requestUrl: (QWeatherConfig, QWeatherEndpoint) -> HttpUrl = { config, endpoint -> endpoint.url(config.host) },
) {
    private val client = http.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(java.time.Duration.ofSeconds(25)).build()

    fun execute(
        config: QWeatherConfig, endpoint: QWeatherEndpoint,
        parse: (JSONObject) -> ParsedWeather,
        callback: (WeatherResult) -> Unit,
    ): () -> Unit {
        val request = try {
            val (name, value) = config.auth.header(now())
            Request.Builder().url(requestUrl(config, endpoint)).header(name, value).build()
        } catch (_: Exception) {
            callback(WeatherResult.Failed(WeatherFailure(WeatherState.UNAUTHORIZED,
                "无法生成认证请求，请重新配置凭据", attempted = false)))
            return {}
        }
        val call = client.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) return
                val tls = generateSequence<Throwable>(e) { it.cause }.any { it is javax.net.ssl.SSLException }
                val recoverable = !tls && (offline() || e is java.net.UnknownHostException ||
                    e is java.net.ConnectException || e is java.net.NoRouteToHostException)
                callback(WeatherResult.Failed(WeatherFailure(WeatherState.ERROR,
                    if (tls) "HTTPS 证书验证失败" else "天气连接失败或超时", recoverOnNetwork = recoverable)))
            }
            override fun onResponse(call: Call, response: Response) {
                val result = response.use {
                    try {
                        when {
                            it.code == 204 -> emptyResult(parse)
                            !it.isSuccessful -> failure(it.code, it.header("Retry-After"))
                            else -> {
                                val source = it.body?.source() ?: throw IOException()
                                if (source.request(MAX_BODY + 1)) throw IOException()
                                val json = JSONObject(source.readUtf8())
                                when (val code = responseCode(json)) {
                                    200 -> WeatherResult.Data(json, parse(json))
                                    204 -> WeatherResult.Data(json, parse(json))
                                    else -> failure(code, it.header("Retry-After"))
                                }
                            }
                        }
                    } catch (_: Exception) {
                        WeatherResult.Failed(WeatherFailure(WeatherState.ERROR, "天气响应无法读取或格式异常"))
                    }
                }
                if (!call.isCanceled()) callback(result)
            }
        })
        return { call.cancel() }
    }

    private fun emptyResult(parse: (JSONObject) -> ParsedWeather): WeatherResult {
        val body = JSONObject().put("metadata", JSONObject().put("zeroResult", true))
        return WeatherResult.Data(body, parse(body))
    }

    private fun responseCode(json: JSONObject): Int {
        val problem = json.optJSONObject("error")
        return problem?.text("status")?.toIntOrNull()
            ?: json.text("code")?.toIntOrNull()
            ?: if (problem?.text("type")?.substringAfterLast('#') in parameterProblems) 400 else 200
    }

    private fun failure(code: Int, retry: String?): WeatherResult.Failed {
        val state = when (code) {
            401, 402, 403 -> WeatherState.UNAUTHORIZED
            404, 405, 410 -> WeatherState.UNSUPPORTED
            408, 425, 429 -> WeatherState.ERROR
            in 400..499 -> WeatherState.INVALID_REQUEST
            else -> WeatherState.ERROR
        }
        val retryAt = if (code != 429) null else retry?.toLongOrNull()?.let { now().plusSeconds(it.coerceIn(0, 31_536_000)) }
            ?: retry?.let { runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull() }
            ?: now().plusSeconds(60)
        val message = when (code) {
            400, 422 -> "请求参数无效或不被服务接受，请检查地点或请求选项"
            401 -> "认证失败，请检查主机、凭据和设备时间"
            402, 403 -> "无权访问接口，请检查项目权限和订阅"
            404, 405, 410 -> "该接口或地点数据不可用"
            429 -> "和风请求频率受限"
            else -> if (state == WeatherState.INVALID_REQUEST) "服务拒绝此请求，请检查请求配置" else "天气服务请求失败"
        }
        return WeatherResult.Failed(WeatherFailure(state, "$message（$code）", retryAt, statusCode = code))
    }

    companion object {
        private const val MAX_BODY = 2L * 1024 * 1024
        private val parameterProblems = setOf("invalid-parameter", "invalid-parameters", "missing-parameter",
            "missing-parameters", "no-such-location", "data-not-available")
    }
}

internal class QWeatherProvider(
    private val api: QWeatherApi,
    private val config: () -> QWeatherConfig?,
    private val configurationError: () -> String,
    private val budget: QWeatherBudget,
    private val dispatch: (() -> Unit) -> Unit,
) : WeatherProvider {
    override fun plan(source: WeatherSourceRef, zone: ZoneId, query: WeatherQuery, now: Instant): WeatherPlan {
        val city = source as? WeatherSourceRef.QWeatherCity
        val position = when (source) {
            is WeatherSourceRef.QWeather -> source
            is WeatherSourceRef.QWeatherCity -> WeatherSourceRef.QWeather(
                WeatherCoordinates(java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO), source.locationId)
            is WeatherSourceRef.HomeAssistant -> error("Unexpected HA source")
        }
        val account = config()
        val today = now.atZone(zone).toLocalDate()
        val datedQuery = if (query.block in setOf(WeatherBlock.SUN, WeatherBlock.MOON, WeatherBlock.HISTORY)) {
            query.copy(date = query.date ?: if (query.block == WeatherBlock.HISTORY) today.minusDays(1) else today)
        } else query
        val endpoint = endpoint(position, datedQuery)
        val error = when {
            endpoint == null -> WeatherFailure(WeatherState.UNSUPPORTED,
                if (query.block == WeatherBlock.HISTORY) "历史天气需要明确的 Location ID" else "和风没有半日预报")
            datedQuery.block == WeatherBlock.HISTORY && datedQuery.date !in today.minusDays(10)..today.minusDays(1) ->
                WeatherFailure(WeatherState.UNSUPPORTED, "历史天气仅支持昨天起最近 10 天")
            datedQuery.block in setOf(WeatherBlock.SUN, WeatherBlock.MOON) && datedQuery.date !in today..today.plusDays(59) ->
                WeatherFailure(WeatherState.UNSUPPORTED, "天文日期必须在当地今天起 60 天内")
            account == null -> WeatherFailure(WeatherState.UNCONFIGURED, configurationError())
            else -> null
        }
        val parse = { json: JSONObject, timeZone: ZoneId -> QWeatherParser.parse(json, timeZone, datedQuery) }
        val url = endpoint?.url("cache.qweatherapi.com")
        val key = url?.encodedPath.orEmpty() + "?" + url?.encodedQuery.orEmpty()
        val group = when {
            query.block == WeatherBlock.HISTORY && position.locationId != null -> "qw-history:${position.locationId}"
            city != null -> "qw-city:${city.locationId}"
            else -> "qw:${position.coordinates.path}"
        }
        val cityKey = city?.takeIf { query.block != WeatherBlock.HISTORY }?.let { "city:${it.locationId}:" }.orEmpty()
        return WeatherPlan("qw:${account?.id}:$cityKey$key:${query.block}", group, datedQuery,
            parse, { callback ->
                val confirmed = checkNotNull(account)
                fun fetch(target: QWeatherEndpoint) = request(confirmed, target, query.block.core, { parse(it, zone) }) { result ->
                    callback(if (result is WeatherResult.Data) result.copy(zone = zone) else result)
                }
                if (city == null || query.block == WeatherBlock.HISTORY) fetch(checkNotNull(endpoint))
                else resolveAndFetch(confirmed, city, query.block.core, now, { result ->
                    when (result) {
                        is CityResolution.Failure -> {
                            callback(WeatherResult.Failed(result.error))
                            val cancel: () -> Unit = {}
                            cancel
                        }
                        is CityResolution.Position -> fetch(checkNotNull(endpoint(
                            WeatherSourceRef.QWeather(result.coordinates, city.locationId), datedQuery)))
                    }
                })
            }, unavailable = error, restriction = { account?.let { budget.restriction(it.id, query.block.core) } })
    }

    private sealed interface CityResolution {
        data class Position(val coordinates: WeatherCoordinates) : CityResolution
        data class Failure(val error: WeatherFailure) : CityResolution
    }
    private class CityLookup {
        val consumers = mutableListOf<(CityResolution) -> Unit>()
        var cancel: (() -> Unit)? = null
    }
    private data class ResolvedCity(val coordinates: WeatherCoordinates, val validUntil: Instant)
    private val lookups = mutableMapOf<Pair<String, String>, CityLookup>()
    private val resolved = LinkedHashMap<Pair<String, String>, ResolvedCity>(16, 0.75f, true)

    // Retain rejections across repository entry eviction and shared GeoAPI lookups.
    private val invalidRequests = LinkedHashMap<Pair<String, QWeatherEndpoint>, WeatherFailure>(16, 0.75f, true)

    fun configurationChanged() {
        resolved.clear()
        synchronized(invalidRequests) { invalidRequests.clear() }
    }

    private fun resolveAndFetch(
        account: QWeatherConfig, city: WeatherSourceRef.QWeatherCity, core: Boolean, now: Instant,
        fetch: (CityResolution) -> (() -> Unit),
    ): () -> Unit {
        val key = account.id to city.locationId
        resolved[key]?.takeIf { now < it.validUntil }?.let { return fetch(CityResolution.Position(it.coordinates)) }
        var stopWeather: (() -> Unit)? = null
        val consume: (CityResolution) -> Unit = { stopWeather = fetch(it) }
        val existing = lookups[key]
        val job = existing ?: CityLookup().also { lookups[key] = it }
        job.consumers += consume
        if (existing == null) {
            job.cancel = request(account, cityLookup(city.locationId), core, { ParsedWeather(null) }) { result ->
                dispatch {
                    if (lookups[key] !== job) return@dispatch
                    lookups.remove(key)
                    val outcome = when (result) {
                        is WeatherResult.Failed -> CityResolution.Failure(result.failure)
                        is WeatherResult.Data -> {
                            val coordinates = cityCoordinates(result.wire, city.locationId)
                            if (coordinates == null) CityResolution.Failure(
                                WeatherFailure(WeatherState.UNSUPPORTED, "和风未找到此公开地点编号"))
                            else {
                                resolved[key] = ResolvedCity(coordinates, now.plusSeconds(86_400))
                                if (resolved.size > 32) resolved.remove(resolved.keys.first())
                                CityResolution.Position(coordinates)
                            }
                        }
                    }
                    job.consumers.toList().forEach { it(outcome) }
                }
            }
        }
        return {
            stopWeather?.invoke()
            job.consumers.remove(consume)
            if (job.consumers.isEmpty() && lookups[key] === job) {
                lookups.remove(key)
                job.cancel?.invoke()
            }
        }
    }

    fun request(account: QWeatherConfig, endpoint: QWeatherEndpoint, core: Boolean,
                parse: (JSONObject) -> ParsedWeather, explicitTest: Boolean = false,
                callback: (WeatherResult) -> Unit): () -> Unit {
        val key = account.id to endpoint
        val invalid = synchronized(invalidRequests) { invalidRequests[key] }
        val denied = invalid ?: budget.acquire(account.id, core, explicitTest)
        if (denied != null) {
            callback(WeatherResult.Failed(denied.copy(attempted = false)))
            return {}
        }
        return api.execute(account, endpoint, parse) { result ->
            if (result is WeatherResult.Failed && result.failure.state == WeatherState.INVALID_REQUEST) {
                synchronized(invalidRequests) {
                    invalidRequests[key] = result.failure
                    if (invalidRequests.size > 256) invalidRequests.remove(invalidRequests.keys.first())
                }
            }
            budget.record(account.id, result, explicitTest)
            callback(result)
        }
    }

    companion object {
        internal fun cityCoordinates(wire: JSONObject, id: String): WeatherCoordinates? =
            wire.objects("location").firstOrNull { it.text("id") == id }?.let {
                runCatching { WeatherCoordinates(it.getString("lat").toBigDecimal(), it.getString("lon").toBigDecimal()) }.getOrNull()
            }

        fun cityLookup(query: String) = QWeatherEndpoint("geo/v2/city/lookup",
            mapOf("location" to query, "number" to "10", "lang" to "zh"))

        fun endpoint(source: WeatherSourceRef.QWeather, query: WeatherQuery): QWeatherEndpoint? {
            val coordinates = source.coordinates
            val language = mapOf("lang" to query.language)
            val v1 = when (query.block) {
                WeatherBlock.CURRENT -> "weather/v1/current"
                WeatherBlock.HOURLY -> "weather/v1/hourly"
                WeatherBlock.DAILY -> "weather/v1/daily"
                WeatherBlock.ALERTS -> "weatheralert/v1/current"
                WeatherBlock.AIR_CURRENT -> "airquality/v1/current"
                WeatherBlock.AIR_HOURLY -> "airquality/v1/hourly"
                WeatherBlock.AIR_DAILY -> "airquality/v1/daily"
                else -> null
            }
            if (v1 != null) {
                val range = when (query.block) {
                    WeatherBlock.HOURLY -> mapOf("hours" to query.hours.toString())
                    WeatherBlock.DAILY -> mapOf("days" to query.days.toString())
                    else -> emptyMap()
                }
                return QWeatherEndpoint("$v1/${coordinates.path}", language + range)
            }
            val location = mapOf("location" to coordinates.query)
            val date = query.date?.format(DateTimeFormatter.BASIC_ISO_DATE)
            return when (query.block) {
                WeatherBlock.MINUTELY -> QWeatherEndpoint("v7/minutely/5m", location + language)
                WeatherBlock.INDICES -> QWeatherEndpoint("v7/indices/${query.indexDays}d",
                    location + language + ("type" to query.indexTypes.sorted().joinToString(",")))
                WeatherBlock.SUN -> QWeatherEndpoint("v7/astronomy/sun", location + ("date" to checkNotNull(date)))
                WeatherBlock.MOON -> QWeatherEndpoint("v7/astronomy/moon", location + language + ("date" to checkNotNull(date)))
                WeatherBlock.HISTORY -> source.locationId?.let {
                    QWeatherEndpoint("v7/historical/weather",
                        language + mapOf("location" to it, "date" to checkNotNull(date), "unit" to "m"))
                }
                else -> null
            }
        }
    }
}
