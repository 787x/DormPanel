package com.dormpanel.app.weather

import android.content.SharedPreferences
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset

internal data class QWeatherStatistics(val month: YearMonth, val requests: Int, val limit: Int,
    val lastSuccess: Instant?, val lastError: String?)

internal class QWeatherBudget(private val prefs: SharedPreferences, private val now: () -> Instant) {
    private var retryUntil: Instant? = null
    private var throttles = 0
    private var activeAccount: String? = null
    private var authenticationRejected = false
    private var nextExplicitTestAt = Instant.EPOCH
    private fun month() = YearMonth.from(now().atOffset(ZoneOffset.UTC))

    @Synchronized fun stats(): QWeatherStatistics {
        val month = month()
        return QWeatherStatistics(month, if (prefs.getString("month", null) == month.toString()) prefs.getInt("count", 0) else 0,
            prefs.getInt("limit", 3000),
            prefs.getLong("success", -1).takeIf { it >= 0 }?.let(Instant::ofEpochMilli), prefs.getString("error", null))
    }

    @Synchronized fun setLimit(limit: Int) {
        require(limit in 1..1_000_000) { "月请求上限必须在 1 到 1,000,000 之间" }
        check(prefs.edit().putInt("limit", limit).commit()) { "无法保存请求预算" }
    }

    @Synchronized fun accountChanged(id: String?) {
        activeAccount = id
        authenticationRejected = false
        retryUntil = null
        throttles = 0
        nextExplicitTestAt = Instant.EPOCH
        prefs.edit().remove("success").remove("error").remove("throttleAccount").remove("throttleUntil").apply()
    }

    @Synchronized fun initialize(id: String?) {
        activeAccount = id
        if (id != null && prefs.getString("throttleAccount", null) == id) {
            retryUntil = prefs.getLong("throttleUntil", 0).takeIf { it > 0 }?.let(Instant::ofEpochMilli)
        }
    }

    @Synchronized fun acquire(id: String, core: Boolean, explicitTest: Boolean = false): WeatherFailure? {
        if (id != activeAccount) return WeatherFailure(WeatherState.UNCONFIGURED, "天气凭据已切换")
        if (authenticationRejected && !explicitTest)
            return WeatherFailure(WeatherState.UNAUTHORIZED, "认证已拒绝，请重新配置凭据或手动测试", statusCode = 401)
        if (explicitTest && now() < nextExplicitTestAt)
            return WeatherFailure(WeatherState.ERROR, "请在 10 秒后再次测试")
        retryUntil?.takeIf { now() < it }?.let { return WeatherFailure(WeatherState.ERROR, "和风请求频率受限", it) }
        val stats = stats()
        val ceiling = if (core) stats.limit else stats.limit * 8 / 10
        if (stats.requests >= ceiling) return WeatherFailure(WeatherState.BUDGET,
            if (core) "本机已达月请求上限" else "本机剩余预算保留给核心天气",
            stats.month.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC))
        if (!prefs.edit().putString("month", stats.month.toString()).putInt("count", stats.requests + 1).commit())
            return WeatherFailure(WeatherState.ERROR, "无法保存本机请求计数")
        if (explicitTest) nextExplicitTestAt = now().plusSeconds(10)
        return null
    }

    @Synchronized fun record(id: String, result: WeatherResult, explicitTest: Boolean) {
        if (id != activeAccount) return
        val editor = prefs.edit()
        when (result) {
            is WeatherResult.Data -> {
                if (explicitTest) authenticationRejected = false
                if (retryUntil?.let { now() >= it } != false) {
                    throttles = 0
                    editor.remove("throttleAccount").remove("throttleUntil")
                }
                editor.putLong("success", now().toEpochMilli())
            }
            is WeatherResult.Failed -> {
                val error = result.failure
                if (error.statusCode == 401) authenticationRejected = true
                if (error.retryAt != null) {
                    val backoff = 60L * (1 shl throttles.coerceAtMost(6))
                    throttles++
                    val until = maxOf(retryUntil ?: Instant.MIN, error.retryAt, now().plusSeconds(backoff))
                    retryUntil = until
                    editor.putString("throttleAccount", id).putLong("throttleUntil", until.toEpochMilli())
                }
                editor.putString("error", error.message)
            }
        }
        editor.apply()
    }
}
