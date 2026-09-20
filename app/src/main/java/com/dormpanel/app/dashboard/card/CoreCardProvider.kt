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

class WeatherCardProvider(private val source: DashboardDataSource, private val appearance: AppearanceController) : ProductionCardProvider(
    "weather", R.string.card_weather, R.string.card_weather_description, CardSize(4, 3),
) {
    override fun createView(context: Context): View = WeatherCardView(context, appearance, source)
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

fun coreCardRegistry(source: DashboardDataSource, appearance: AppearanceController) = DashboardCardRegistry(listOf(
    ClockCardProvider(appearance), WeatherCardProvider(source, appearance),
    SensorCardProvider(source, appearance), LightCardProvider(source, appearance),
))
