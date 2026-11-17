package com.dormpanel.app.dashboard.card

import com.dormpanel.app.weather.WeatherPlaceStore
import com.dormpanel.app.weather.WeatherSourceJson
import com.dormpanel.app.weather.WeatherSourceRef
import org.json.JSONObject

enum class WeatherCardMetric { FEELS_LIKE, HUMIDITY, WIND }

sealed interface WeatherCardConfiguration {
    data object Default : WeatherCardConfiguration
    data object Invalid : WeatherCardConfiguration
    data class Bound(
        val placeId: String,
        val source: WeatherSourceRef,
        val displayName: String? = null,
        val metric: WeatherCardMetric? = null,
    ) : WeatherCardConfiguration {
        fun select(placeId: String, source: WeatherSourceRef) =
            copy(placeId = placeId, source = source, displayName = displayName.takeIf { this.placeId == placeId })

        fun encode(): String = JSONObject().put("version", VERSION).put("placeId", placeId)
            .put("source", WeatherSourceJson.encode(source)).put("displayName", displayName)
            .put("metric", metric?.name).toString()
    }

    companion object {
        private const val VERSION = 1
        private val fields = setOf("version", "placeId", "source", "displayName", "metric")
        fun decode(value: String): WeatherCardConfiguration = runCatching {
            require(value.length <= 4096)
            // Reject trailing content after the configuration object.
            val tokener = org.json.JSONTokener(value)
            val json = tokener.nextValue() as? JSONObject ?: error("Expected weather card object")
            require(tokener.nextClean() == '\u0000')
            if (json.length() == 0) return Default
            require(json.get("version") == VERSION)
            require(json.keys().asSequence().all { it in fields })
            fun string(key: String) = json.get(key) as? String ?: error("Invalid weather card field")
            val placeId = string("placeId")
            require(placeId.isNotBlank() && placeId.length <= 200)
            val name = if (json.has("displayName")) string("displayName") else null
            require(name == null || (name.isNotBlank() && name.length <= 80))
            val metric = if (json.has("metric")) WeatherCardMetric.valueOf(string("metric")) else null
            val reference = json.getJSONObject("source")
            val source = WeatherSourceJson.decode(reference)
            require(reference.keys().asSequence().toSet() == WeatherSourceJson.encode(source).keys().asSequence().toSet())
            Bound(placeId, source, name, metric)
        }.getOrDefault(Invalid)
    }
}

internal fun WeatherCardConfiguration.Bound.savedPlace(places: WeatherPlaceStore?) =
    places?.get(placeId)?.takeUnless { places.isTemporary(placeId) }

internal fun WeatherCardConfiguration.Bound.place(places: WeatherPlaceStore?) =
    savedPlace(places)?.takeIf { source in it.sources }

internal fun WeatherCardConfiguration.canSave(places: WeatherPlaceStore?) = when (this) {
    WeatherCardConfiguration.Default -> true
    WeatherCardConfiguration.Invalid -> false
    is WeatherCardConfiguration.Bound -> place(places) != null
}

data class WeatherCardNavigation(
    val cardId: String,
    val placeId: String?,
    val source: WeatherSourceRef?,
    val destination: Destination,
) {
    enum class Destination { QUICK_DETAIL, WEATHER_CENTER }
}
