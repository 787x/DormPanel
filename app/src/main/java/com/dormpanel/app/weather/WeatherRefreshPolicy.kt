package com.dormpanel.app.weather

import android.content.SharedPreferences
import java.time.Instant
import kotlin.math.ceil

enum class WeatherRefreshMode(val title: String) {
    SAVER("节省请求"), STANDARD("标准"), TIMELY("及时更新"), CUSTOM("自定义"),
}

internal enum class RefreshInterval(
    val title: String, val block: WeatherBlock, val minimum: Long, val maximum: Long,
    val standard: Long, val saver: Long, val timely: Long,
) {
    CURRENT("实时天气", WeatherBlock.CURRENT, 10, 180, 15, 30, 10),
    HOURLY("小时预报", WeatherBlock.HOURLY, 30, 360, 45, 90, 30),
    DAILY("每日预报", WeatherBlock.DAILY, 60, 720, 120, 360, 60),
    ALERTS("天气预警", WeatherBlock.ALERTS, 5, 20, 10, 20, 5),
    AIR_CURRENT("实时空气质量", WeatherBlock.AIR_CURRENT, 30, 360, 45, 90, 30),
    AIR_HOURLY("小时空气质量", WeatherBlock.AIR_HOURLY, 60, 720, 60, 120, 60),
    AIR_DAILY("每日空气质量", WeatherBlock.AIR_DAILY, 480, 1440, 480, 720, 480),
    MINUTELY("分钟降水", WeatherBlock.MINUTELY, 5, 30, 5, 10, 5),
    INDICES("生活指数", WeatherBlock.INDICES, 360, 1440, 360, 720, 360);

    companion object {
        private val byBlock = entries.associateBy(RefreshInterval::block)
        fun forBlock(block: WeatherBlock) = byBlock[block]
    }
}

internal data class WeatherRefreshPolicy(
    val mode: WeatherRefreshMode = WeatherRefreshMode.STANDARD,
    val custom: Map<RefreshInterval, Long> = emptyMap(),
) {
    init { require(custom.all { (type, value) -> value in type.minimum..type.maximum }) }

    fun minutes(interval: RefreshInterval): Long = when (mode) {
        WeatherRefreshMode.SAVER -> interval.saver
        WeatherRefreshMode.STANDARD -> interval.standard
        WeatherRefreshMode.TIMELY -> interval.timely
        WeatherRefreshMode.CUSTOM -> custom[interval] ?: interval.standard
    }

    fun minutes(block: WeatherBlock) = RefreshInterval.forBlock(block)?.let(::minutes) ?: block.minutes

    fun estimate(blocks: Iterable<WeatherBlock>, days: Int): Int = ceil(blocks.sumOf {
        days * 1440.0 / minutes(it) +
            if (it == WeatherBlock.DAILY || it == WeatherBlock.AIR_DAILY || it == WeatherBlock.INDICES) days else 0
    }).toInt()
}

internal class WeatherRefreshSettings(private val prefs: SharedPreferences) {
    fun read() = WeatherRefreshPolicy(
        WeatherRefreshMode.entries.find { it.name == prefs.getString("mode", null) } ?: WeatherRefreshMode.STANDARD,
        RefreshInterval.entries.mapNotNull {
            val value = prefs.getLong(it.name, -1)
            if (value in it.minimum..it.maximum) it to value else null
        }.toMap(),
    )

    fun save(policy: WeatherRefreshPolicy) {
        val editor = prefs.edit().putString("mode", policy.mode.name)
        RefreshInterval.entries.forEach { interval ->
            val minutes = policy.custom[interval]
            if (minutes == null) editor.remove(interval.name) else editor.putLong(interval.name, minutes)
        }
        check(editor.commit()) { "无法保存刷新周期" }
    }
}

enum class WeatherRefreshStatus { STARTED, JOINED, QUEUED, BLOCKED }

data class WeatherRefreshResult(
    val status: WeatherRefreshStatus,
    val reason: String? = null,
    val retryAt: Instant? = null,
)
