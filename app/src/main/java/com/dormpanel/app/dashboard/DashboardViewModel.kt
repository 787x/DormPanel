package com.dormpanel.app.dashboard

import android.app.Application
import com.dormpanel.app.R
import com.dormpanel.app.dashboard.catalog.*
import androidx.lifecycle.AndroidViewModel
import com.dormpanel.app.dashboard.card.coreCardRegistry
import com.dormpanel.app.dashboard.card.DeviceControlCardProvider
import com.dormpanel.app.appearance.AppearanceController
import com.dormpanel.app.appearance.PreferencesAppearanceStore
import com.dormpanel.app.dashboard.persistence.DashboardStores
import com.dormpanel.app.ha.NativeDeviceControls
import com.dormpanel.app.productivity.*
import com.dormpanel.app.schedule.*

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    val appearance = AppearanceController(PreferencesAppearanceStore(application))
    internal val airPlay = com.dormpanel.app.airplay.AirPlayController.get(application)
    val startupPolicy = com.dormpanel.app.startup.StartupPolicy(
        com.dormpanel.app.startup.PreferencesStartupStore(application))
    val deviceControls = com.dormpanel.app.device.DeviceControlController(
        com.dormpanel.app.device.PreferencesDeviceControlStore(application),
        com.dormpanel.app.device.AndroidMediaVolumePort(application),
        com.dormpanel.app.device.AndroidSystemBrightnessPort(application))
    val dataSource = com.dormpanel.app.ha.DashboardBackend(application, appearance, CatalogLabels(
        application.getString(R.string.card_clock), application.getString(R.string.card_clock_description),
        application.getString(R.string.card_weather), application.getString(R.string.card_weather_description),
        application.getString(R.string.card_sensor_description), application.getString(R.string.catalog_switch),
        application.getString(R.string.catalog_dimmable), application.getString(R.string.catalog_temperature),
        application.getString(R.string.card_weather_classic), application.getString(R.string.card_weather_classic_description),
        application.getString(R.string.card_weather_compact), application.getString(R.string.card_weather_compact_description),
    ))
    val apps = com.dormpanel.app.apps.AppSources.create(application)
    val apkInstall = com.dormpanel.app.apps.ApkInstallController(application, apps)
    val productivity = ProductivitySource(ProductivityStores.create(application), AndroidProductivityClock)
    val termProfiles = TermScheduleProfileStore(application)
    private val productivityBindings = ProductivityBindings(application, "dashboard-productivity-bindings")
    val schedule = ScheduleSource(ScheduleStores.create(application, termProfiles), profileResolver = termProfiles::get)
    val cloudCalendar = com.dormpanel.app.icloud.CloudCalendarService.create(application)
    private val productivityData = AndroidProductivity(application, schedule.syncStore)
    private val nativeControls = NativeDeviceControls(dataSource.ha.relayChannel, dataSource.scheduler,
        dataSource.relayIdentity::credentials, deviceControls, appearance, startupPolicy,
        onSessionAvailable = { dataSource.ha.enableLegacyControls(!it) })
    val haRelay = HaScheduleRelayController(dataSource.ha.relayChannel, dataSource.relayIdentity,
        dataSource.relayHttp, { dataSource.relayStatus = it }, apkInstall,
        appVersion = {
            runCatching {
                application.packageManager.getPackageInfo(application.packageName, 0).versionName
            }.getOrNull() ?: "unknown"
        },
        profiles = termProfiles,
        onRegistered = nativeControls::registered,
        onRegistrationFailed = { dataSource.ha.enableLegacyControls(true) },
        isLegacyServer = { dataSource.ha.nativeControlSupported == false })
    val webDav = WebDavSyncController(application, schedule, profiles = termProfiles)
    val sharedSchedule = SharedScheduleController(application, schedule, accounts = webDav.settings)
    val scheduleSession = ScheduleSession(schedule.clock, PreferencesScheduleModeStore(application))
    val scheduleViewport = com.dormpanel.app.schedule.ScheduleViewport()
    val registry = com.dormpanel.app.dashboard.card.DashboardCardRegistry(coreCardRegistry(dataSource, appearance, dataSource.weatherRepository).providers +
        CONTROL_CARD_KINDS.map { DeviceControlCardProvider(it, dataSource, appearance) } +
        com.dormpanel.app.apps.AppCardProvider(apps, apps.icons, appearance) + listOf("todo", "memo", "timer").map { ProductivityCardProvider(it, productivity, appearance, productivityData, productivityBindings) } +
        ScheduleCardProvider("calendar", schedule, appearance, cloudCalendar) + ScheduleCardProvider("timetable", schedule, appearance) +
        listOf(false, true).map { CourseListCardProvider(it, schedule, appearance) } +
        com.dormpanel.app.airplay.AirPlayCardProvider(appearance, airPlay))
    val catalog: CardCatalog = CombinedCardCatalog(dataSource.catalog, com.dormpanel.app.apps.AppCardCatalog(apps),
        ProductivityCatalog(application), ScheduleCatalog(application), com.dormpanel.app.airplay.AirPlayCatalog(application))
    val stateHolder = DashboardStateHolder(
        registry = registry,
        store = DashboardStores.create(application),
        weatherPlaces = dataSource.weatherRepository.places,
    )

    init {
        dataSource.ha.onNativeProtocolSupported = nativeControls::protocolSupported
        termProfiles.editable = { schedule.editable }
        // Reconcile any PackageInstaller session interrupted by process death
        // before the live HA relay callback was registered. Recovered terminal
        // outcomes are queued on the single HA relay state machine so they are
        // acknowledged after registration without re-downloading the APK.
        // Unresolved sessions are left for rediscovery, never reported installed.
        apkInstall.reconcileRecovered().forEach { recovered ->
            val transferId = recovered.haTransferId
            if (transferId != null) haRelay.queueRecoveredTerminal(transferId, recovered.outcome)
        }
        deviceControls.brightnessCommand = dataSource.ha::requestDisplayBrightness
        deviceControls.volumeCommand = dataSource.ha::requestMediaVolume
        deviceControls.systemBrightnessCommand = dataSource.ha::requestSystemBrightness
        deviceControls.blackoutCommand = dataSource.ha::requestBlackout
        deviceControls.systemAutomaticCommand = dataSource.ha::requestSystemAutomatic
        deviceControls.followSystemCommand = dataSource.ha::requestFollowSystem
        deviceControls.keepAwakeCommand = dataSource.ha::requestKeepAwake
        startupPolicy.startAfterBootCommand = dataSource.ha::requestStartAfterBoot
        dataSource.ha.remoteBrightness = { deviceControls.setBrightness(it, remote = true) }
        dataSource.ha.acceptBrightnessSnapshot = { !deviceControls.state.useSystemBrightness }
        dataSource.ha.remoteVolume = { deviceControls.setMediaPercent(it, remote = true) }
        dataSource.ha.remoteSystemBrightness = { deviceControls.setSystemBrightness(it, remote = true) }
        dataSource.ha.remoteBlackout = { if (it) deviceControls.enterBlackout(remote = true) else deviceControls.exitBlackout(remote = true) }
        dataSource.ha.remoteSystemAutomatic = { deviceControls.setSystemAutomatic(it, remote = true) }
        dataSource.ha.remoteFollowSystem = { deviceControls.setFollowSystem(it, remote = true) }
        dataSource.ha.remoteKeepAwake = { deviceControls.setKeepAwake(it, remote = true) }
        dataSource.ha.remoteStartAfterBoot = { startupPolicy.setStartAfterBoot(it, remote = true) }
    }

    override fun onCleared() {
        dataSource.ha.onNativeProtocolSupported = null
        nativeControls.close()
        sharedSchedule.close()
        webDav.close()
        apkInstall.close()
        haRelay.close()
        cloudCalendar.close()
        schedule.close()
        productivity.close()
        apps.close()
        dataSource.close()
        stateHolder.close()
    }
}
