package com.dormpanel.app.weather

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

internal data class WeatherFailure(val state: WeatherState, val message: String, val retryAt: Instant? = null, val recoverOnNetwork: Boolean = false, val statusCode: Int? = null, val attempted: Boolean = true, val offline: Boolean = false) {
    val blocksRetry get() = state == WeatherState.INVALID_REQUEST || state == WeatherState.UNAUTHORIZED ||
        state == WeatherState.UNSUPPORTED
}
internal sealed interface WeatherResult {
    data class Data(val wire: JSONObject, val parsed: ParsedWeather, val zone: ZoneId? = null) : WeatherResult
    data class Failed(val failure: WeatherFailure) : WeatherResult
}

internal data class WeatherPlan(
    val key: String,
    val group: String,
    val query: WeatherQuery,
    val parse: (JSONObject, ZoneId) -> ParsedWeather,
    val start: ((WeatherResult) -> Unit) -> (() -> Unit),
    val live: Boolean = false,
    val unavailable: WeatherFailure? = null,
    val restriction: () -> WeatherFailure? = { null },
)

internal interface WeatherProvider {
    fun plan(source: WeatherSourceRef, zone: ZoneId, query: WeatherQuery, now: Instant): WeatherPlan
}
