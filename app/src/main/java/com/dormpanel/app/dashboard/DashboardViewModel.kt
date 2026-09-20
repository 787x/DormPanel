package com.dormpanel.app.dashboard

import android.app.Application
import com.dormpanel.app.R
import com.dormpanel.app.dashboard.catalog.*
import androidx.lifecycle.AndroidViewModel
import com.dormpanel.app.dashboard.card.coreCardRegistry
import com.dormpanel.app.appearance.AppearanceController
import com.dormpanel.app.appearance.PreferencesAppearanceStore
import com.dormpanel.app.dashboard.persistence.RoomDashboardStore

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    val appearance = AppearanceController(PreferencesAppearanceStore(application))
    val dataSource = com.dormpanel.app.ha.DashboardBackend(application, appearance, CatalogLabels(
        application.getString(R.string.card_clock), application.getString(R.string.card_clock_description),
        application.getString(R.string.card_weather), application.getString(R.string.card_weather_description),
        application.getString(R.string.card_sensor_description), application.getString(R.string.catalog_switch),
        application.getString(R.string.catalog_dimmable), application.getString(R.string.catalog_temperature),
    ))
    val registry = coreCardRegistry(dataSource, appearance)
    val catalog: CardCatalog = dataSource.catalog
    val stateHolder = DashboardStateHolder(
        registry = registry,
        store = RoomDashboardStore(application),
    )

    override fun onCleared() {
        dataSource.close()
        stateHolder.close()
    }
}
