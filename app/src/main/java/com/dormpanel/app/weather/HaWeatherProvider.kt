package com.dormpanel.app.weather

import com.dormpanel.app.ha.HaConnectionState
import com.dormpanel.app.ha.HaDashboardDataSource
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import kotlin.math.pow

internal class HaWeatherProvider(
    private val ha: HaDashboardDataSource,
    private val serverId: () -> String,
    private val accountId: () -> String?,
) : WeatherProvider {
    override fun plan(source: WeatherSourceRef, zone: ZoneId, query: WeatherQuery, now: Instant): WeatherPlan {
        require(source is WeatherSourceRef.HomeAssistant)
        val forecast = when (query.block) {
            WeatherBlock.DAILY -> "daily" to 1
            WeatherBlock.HOURLY -> "hourly" to 2
            WeatherBlock.TWICE_DAILY -> "twice_daily" to 4
            else -> null
        }
        val account = accountId()
        val unavailable = when {
            forecast == null && query.block != WeatherBlock.CURRENT ->
                WeatherFailure(WeatherState.UNSUPPORTED, "HA 来源没有此天气数据")
            serverId() != source.serverId || account == null ->
                WeatherFailure(WeatherState.UNCONFIGURED, "请启用此地点绑定的 Home Assistant 服务")
            else -> null
        }
        val parse = { wire: JSONObject, timeZone: ZoneId -> HaWeatherParser.parse(wire, timeZone, query.block) }
        val group = "ha:${source.serverId}:${source.entityId}"
        return WeatherPlan("$group:$account:${query.block}", group, query, parse, { emit ->
            var stopForecast: (() -> Unit)? = null
            var lastCurrent: String? = null
            fun fail(state: WeatherState, message: String, offline: Boolean = false) =
                emit(WeatherResult.Failed(WeatherFailure(state, message, offline = offline)))
            fun update() {
                if (accountId() != account || serverId() != source.serverId) return
                val entity = ha.store.entities[source.entityId]?.takeIf(ha.store::enabled)
                if (!ha.connected || entity == null || !entity.usable) {
                    stopForecast?.invoke()
                    stopForecast = null
                    lastCurrent = null
                    val authError = ha.status.state == HaConnectionState.AUTH_ERROR
                    fail(if (authError) WeatherState.UNAUTHORIZED else WeatherState.ERROR,
                        if (authError) "HA 认证失败，请检查凭据" else "HA 连接或天气实体暂不可用",
                        offline = !ha.connected && !authError)
                    return
                }
                fun send(wire: JSONObject) = emit(WeatherResult.Data(wire, parse(wire, zone), zone))
                fun wire() = JSONObject().put("attrs", ha.store.entities[source.entityId]?.attributes)
                if (forecast == null) {
                    val value = wire().put("state", entity.value).put("time", entity.updatedAt)
                    val identity = value.toString()
                    if (identity != lastCurrent) {
                        lastCurrent = identity
                        send(value)
                    }
                } else if (entity.attributes.has("supported_features") &&
                    entity.attributes.optInt("supported_features") and forecast.second == 0) {
                    stopForecast?.invoke()
                    stopForecast = null
                    fail(WeatherState.UNSUPPORTED, "此 HA 实体不支持此预报类型")
                } else if (stopForecast == null) {
                    stopForecast = ha.forecasts.observe(source.entityId, forecast.first) { result ->
                        if (result.forecast != null) send(wire().put("forecast", result.forecast))
                        else fail(WeatherState.ERROR, "HA 天气预报暂不可用")
                    }
                }
            }
            val stopWeather = ha.observeWeather { if (it == source.entityId) update() }
            val status: (com.dormpanel.app.ha.HaStatus) -> Unit = { update() }
            ha.addStatusListener(status)
            val cancel: () -> Unit = {
                stopWeather()
                ha.removeStatusListener(status)
                stopForecast?.invoke()
            }
            cancel
        }, live = true, unavailable = unavailable)
    }
}

internal object HaWeatherParser {
    fun parse(wire: JSONObject, zone: ZoneId, block: WeatherBlock): ParsedWeather {
        val attrs = wire.optJSONObject("attrs") ?: JSONObject()
        val data = if (block == WeatherBlock.CURRENT) {
            WeatherData.Current(conditions(attrs, attrs, wire.text("state")))
        } else {
            wire.objects("forecast").map {
                val time = it.time("datetime")
                val conditions = conditions(it, attrs, it.text("condition"))
                WeatherForecast(WeatherPeriod(time, date = if (block != WeatherBlock.HOURLY) time?.atZone(zone)?.toLocalDate() else null,
                    isDaytime = it.opt("is_daytime") as? Boolean), if (block == WeatherBlock.HOURLY) conditions else
                    conditions.copy(maxTemperature = conditions.temperature))
            }.takeIf { it.isNotEmpty() }?.let(WeatherData::Forecast)
        }
        return ParsedWeather(data, WeatherAttribution(sources = listOfNotNull("Home Assistant", attrs.text("attribution"))),
            wire.time("time"))
    }

    internal fun conditions(row: JSONObject, attrs: JSONObject, code: String?): WeatherConditions {
        fun quantity(key: String, unit: String, conversions: Map<String, Double>): Double? =
            row.number(key)?.let { value -> conversions[attrs.text(unit)]?.times(value)?.takeIf(Double::isFinite) }
        fun temperature(key: String) = row.number(key)?.let {
            when (attrs.text("temperature_unit")) {
                "°C" -> it
                "°F" -> (it - 32) * (5.0 / 9)
                "K" -> it - 273.15
                else -> null
            }
        }
        val beaufort = attrs.text("wind_speed_unit") == "Beaufort"
        fun wind(key: String) = if (beaufort) row.number(key)?.takeIf { it in 0.0..12.0 }?.let { 0.836 * it.pow(1.5) }
            else quantity(key, "wind_speed_unit", windUnits)
        fun percent(key: String) = row.number(key)?.takeIf { it in 0.0..100.0 }
        return WeatherConditions(
            code = code, temperature = temperature("temperature"), feelsLike = temperature("apparent_temperature"),
            minTemperature = temperature("templow"), humidity = percent("humidity"), dewPoint = temperature("dew_point"),
            windDirection = row.text("wind_bearing")?.takeIf { it.toDoubleOrNull() == null },
            windDegrees = row.number("wind_bearing"), windSpeed = wind("wind_speed"), gust = wind("wind_gust_speed"),
            windScale = row.number("wind_speed")?.takeIf { beaufort && it in 0.0..12.0 },
            pressure = quantity("pressure", "pressure_unit", pressureUnits),
            visibility = quantity("visibility", "visibility_unit", distanceUnits),
            cloudCover = percent("cloud_coverage"), uvIndex = row.number("uv_index"),
            precipitation = quantity("precipitation", "precipitation_unit", precipitationUnits),
            precipitationProbability = percent("precipitation_probability"), ozone = row.number("ozone"),
        )
    }

    private val windUnits = mapOf("m/s" to 1.0, "km/h" to 1 / 3.6, "mph" to 0.44704, "kn" to 1852 / 3600.0,
        "ft/s" to 0.3048, "in/d" to 0.0254 / 86400)
    private val pressureUnits = mapOf("hPa" to 1.0, "mbar" to 1.0, "Pa" to 0.01, "kPa" to 10.0,
        "bar" to 1000.0, "inHg" to 33.86389, "mmHg" to 1.333224, "psi" to 68.94757, "cbar" to 10.0)
    private val distanceUnits = mapOf("m" to 1.0, "km" to 1000.0, "mi" to 1609.344, "ft" to 0.3048,
        "yd" to 0.9144, "in" to 0.0254, "cm" to 0.01, "mm" to 0.001)
    private val precipitationUnits = mapOf("mm" to 1.0, "cm" to 10.0, "in" to 25.4, "m" to 1000.0)
}
