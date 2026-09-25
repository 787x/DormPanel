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
import com.dormpanel.app.schedule.*

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    val appearance = AppearanceController(PreferencesAppearanceStore(application))
    val deviceControls = com.dormpanel.app.device.DeviceControlController(
        com.dormpanel.app.device.PreferencesDeviceControlStore(application),
        com.dormpanel.app.device.AndroidMediaVolumePort(application),
        com.dormpanel.app.device.AndroidSystemBrightnessPort(application))
    val dataSource = com.dormpanel.app.ha.DashboardBackend(application, appearance, CatalogLabels(
        application.getString(R.string.card_clock), application.getString(R.string.card_clock_description),
        application.getString(R.string.card_weather), application.getString(R.string.card_weather_description),
        application.getString(R.string.card_sensor_description), application.getString(R.string.catalog_switch),
        application.getString(R.string.catalog_dimmable), application.getString(R.string.catalog_temperature),
    ))
    val apps = com.dormpanel.app.apps.AppSources.create(application)
    val productivity = ProductivitySource(ProductivityStores.create(application), AndroidProductivityClock)
    val schedule = ScheduleSource(ScheduleStores.create(application))
    val haRelay = HaScheduleRelayController(dataSource.ha.relayChannel, dataSource.relayIdentity,
        dataSource.relayHttp) { dataSource.relayStatus = it }
    val webDav = WebDavSyncController(application, schedule)
    val scheduleSession = ScheduleSession(schedule.clock, PreferencesScheduleModeStore(application))
    val registry = com.dormpanel.app.dashboard.card.DashboardCardRegistry(coreCardRegistry(dataSource, appearance).providers +
        com.dormpanel.app.apps.AppCardProvider(apps, apps.icons, appearance) + listOf("todo", "memo", "timer").map { ProductivityCardProvider(it, productivity, appearance) } +
        listOf("calendar", "timetable").map { ScheduleCardProvider(it, schedule, appearance) })
    val catalog: CardCatalog = CombinedCardCatalog(dataSource.catalog, com.dormpanel.app.apps.AppCardCatalog(apps), ProductivityCatalog(application), ScheduleCatalog())
    val stateHolder = DashboardStateHolder(
        registry = registry,
        store = DashboardStores.create(application),
    )

    init {
        deviceControls.brightnessCommand = dataSource.ha::requestDisplayBrightness
        deviceControls.volumeCommand = dataSource.ha::requestMediaVolume
        deviceControls.systemBrightnessCommand = dataSource.ha::requestSystemBrightness
        deviceControls.blackoutCommand = dataSource.ha::requestBlackout
        dataSource.ha.remoteBrightness = { deviceControls.setBrightness(it, remote = true) }
        dataSource.ha.acceptBrightnessSnapshot = { !deviceControls.state.useSystemBrightness }
        dataSource.ha.remoteVolume = { deviceControls.setMediaPercent(it, remote = true) }
        dataSource.ha.remoteSystemBrightness = { deviceControls.setSystemBrightness(it, remote = true) }
        dataSource.ha.remoteBlackout = { if (it) deviceControls.enterBlackout(remote = true) else deviceControls.exitBlackout(remote = true) }
    }

    override fun onCleared() {
        webDav.close()
        haRelay.close()
        schedule.close()
        productivity.close()
        apps.close()
        dataSource.close()
        stateHolder.close()
    }
}
