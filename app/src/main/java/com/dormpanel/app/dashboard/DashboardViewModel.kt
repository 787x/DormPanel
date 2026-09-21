package com.dormpanel.app.dashboard

import android.app.Application
import com.dormpanel.app.R
import com.dormpanel.app.dashboard.catalog.*
import androidx.lifecycle.AndroidViewModel
import com.dormpanel.app.dashboard.card.coreCardRegistry
import com.dormpanel.app.appearance.AppearanceController
import com.dormpanel.app.appearance.PreferencesAppearanceStore
import com.dormpanel.app.dashboard.persistence.DashboardStores
import com.dormpanel.app.productivity.*

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    val appearance = AppearanceController(PreferencesAppearanceStore(application))
    val dataSource = com.dormpanel.app.ha.DashboardBackend(application, appearance, CatalogLabels(
        application.getString(R.string.card_clock), application.getString(R.string.card_clock_description),
        application.getString(R.string.card_weather), application.getString(R.string.card_weather_description),
        application.getString(R.string.card_sensor_description), application.getString(R.string.catalog_switch),
        application.getString(R.string.catalog_dimmable), application.getString(R.string.catalog_temperature),
    ))
    val apps = com.dormpanel.app.apps.AppSources.create(application)
    val productivity = ProductivitySource(ProductivityStores.create(application), AndroidProductivityClock)
    val registry = com.dormpanel.app.dashboard.card.DashboardCardRegistry(coreCardRegistry(dataSource, appearance).providers +
        com.dormpanel.app.apps.AppCardProvider(apps, apps.icons, appearance) + listOf("todo", "memo", "timer").map { ProductivityCardProvider(it, productivity, appearance) })
    val catalog: CardCatalog = com.dormpanel.app.apps.CombinedCardCatalog(dataSource.catalog, com.dormpanel.app.apps.AppCardCatalog(apps), ProductivityCatalog(application))
    val stateHolder = DashboardStateHolder(
        registry = registry,
        store = DashboardStores.create(application),
    )

    override fun onCleared() {
        productivity.close()
        apps.close()
        dataSource.close()
        stateHolder.close()
    }
}
