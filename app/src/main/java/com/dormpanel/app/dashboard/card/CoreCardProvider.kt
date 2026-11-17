package com.dormpanel.app.dashboard.card

import android.content.Context
import android.view.View
import com.dormpanel.app.R
import com.dormpanel.app.appearance.AppearanceController
import com.dormpanel.app.dashboard.model.*
import com.dormpanel.app.data.DashboardDataSource

abstract class ProductionCardProvider(
    override val typeKey: String,
    name: Int,
    description: Int,
    override val defaultSize: CardSize,
) : DashboardCardProvider {
    override val displayMetadata = CardDisplayMetadata("", "", name, description)
    override val sizePolicy = RangeCardSizePolicy(CardSize(2, 1), CardSize(4, 3))
    override fun bind(view: View, card: PlacedCard, interactions: CardInteractionScope) =
        (view as DashboardCardView).bind(card, interactions)
}

class ClockCardProvider(private val appearance: AppearanceController) : ProductionCardProvider(
    "clock", R.string.card_clock, R.string.card_clock_description, CardSize(4, 3),
) {
    override fun createView(context: Context): View = ClockCardView(context, appearance)
}

class WeatherCardProvider(
    private val source: DashboardDataSource,
    private val appearance: AppearanceController,
    private val repository: com.dormpanel.app.weather.WeatherRepository? = null,
) : ProductionCardProvider(
    WEATHER_CARD, R.string.card_weather, R.string.card_weather_description, CardSize(4, 3),
) {
    override fun createView(context: Context): View = WeatherCardView(context, appearance, source, repository)
}

internal const val WEATHER_CARD = "weather"
internal const val CLASSIC_WEATHER_CARD = "weather_classic"
internal fun String.isWeatherCard() = this == WEATHER_CARD || this == CLASSIC_WEATHER_CARD

private class ClassicWeatherCardProvider(
    private val source: DashboardDataSource,
    private val appearance: AppearanceController,
    private val repository: com.dormpanel.app.weather.WeatherRepository?,
) : ProductionCardProvider(
    CLASSIC_WEATHER_CARD, R.string.card_weather_classic, R.string.card_weather_classic_description, CardSize(4, 3),
) {
    override fun createView(context: Context): View = ClassicWeatherCardView(context, appearance, source, repository)
}

class SensorCardProvider(private val source: DashboardDataSource, private val appearance: AppearanceController) : ProductionCardProvider(
    "sensor", R.string.card_sensor, R.string.card_sensor_description, CardSize(2, 2),
) {
    override fun createView(context: Context): View = SensorCardView(context, appearance, source)
}

class LightCardProvider(private val source: DashboardDataSource, private val appearance: AppearanceController) : ProductionCardProvider(
    "light", R.string.card_light, R.string.card_light_description, CardSize(2, 2),
) {
    override fun createView(context: Context): View = LightCardView(context, appearance, source)
}

fun coreCardRegistry(
    source: DashboardDataSource,
    appearance: AppearanceController,
    weather: com.dormpanel.app.weather.WeatherRepository? = null,
) = DashboardCardRegistry(listOf(
    ClockCardProvider(appearance), WeatherCardProvider(source, appearance, weather),
    ClassicWeatherCardProvider(source, appearance, weather),
    SensorCardProvider(source, appearance), LightCardProvider(source, appearance),
))
