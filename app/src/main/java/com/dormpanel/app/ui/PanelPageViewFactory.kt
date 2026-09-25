package com.dormpanel.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.dormpanel.app.R
import com.dormpanel.app.dashboard.DashboardStateHolder
import com.dormpanel.app.dashboard.card.DashboardCardRegistry
import com.dormpanel.app.dashboard.ui.DashboardPageView
import com.dormpanel.app.navigation.PanelPage
import com.dormpanel.app.appearance.AppearanceController
import com.dormpanel.app.dashboard.catalog.CardCatalog

class PanelPageViewFactory(
    private val inflater: LayoutInflater,
    private val dashboardStateHolder: DashboardStateHolder,
    private val cardRegistry: DashboardCardRegistry,
    private val onPageGestureClaimed: () -> Unit,
    private val onReturnHome: () -> Unit,
    private val appearance: AppearanceController,
    private val deviceControls: com.dormpanel.app.device.DeviceControlController,
    private val catalog: CardCatalog,
    private val backend: com.dormpanel.app.ha.DashboardBackend,
    private val apps: com.dormpanel.app.apps.AndroidInstalledApps,
    private val schedule: com.dormpanel.app.schedule.ScheduleSource,
    private val scheduleSession: com.dormpanel.app.schedule.ScheduleSession,
    private val importTimetable: () -> Unit,
    private val receiveTimetable: () -> Unit,
    private val manageTimetables: () -> Unit,
) {
    fun create(page: PanelPage, parent: ViewGroup): View {
        if (page == PanelPage.CALENDAR) return com.dormpanel.app.schedule.SchedulePageView(inflater.context, schedule, scheduleSession, appearance, onReturnHome, importTimetable, receiveTimetable, manageTimetables)
        if (page == PanelPage.APPS) return com.dormpanel.app.apps.AppsPageView(inflater.context, apps, apps.icons, appearance, onReturnHome)
        if (page == PanelPage.HOME_CONTROL) return com.dormpanel.app.home.HomeControlView(inflater.context, backend, appearance, onPageGestureClaimed)
        if (page == PanelPage.CONTROL_CENTER) return ControlCenterView(inflater.context, appearance, deviceControls, onPageGestureClaimed, backend)
        if (page == PanelPage.HOME) {
            return (inflater.inflate(R.layout.view_home_page, parent, false) as DashboardPageView).apply {
                bind(dashboardStateHolder, cardRegistry, onPageGestureClaimed, catalog, appearance, backend)
            }
        }

        val view = inflater.inflate(R.layout.view_placeholder_page, parent, false)
        val content = contentFor(page)
        view.findViewById<TextView>(R.id.page_eyebrow).setText(content.eyebrow)
        view.findViewById<TextView>(R.id.page_title).setText(content.title)
        view.findViewById<TextView>(R.id.page_description).setText(content.description)
        view.findViewById<TextView>(R.id.return_hint).setText(content.returnHint)
        return view
    }

    private fun contentFor(page: PanelPage): PageContent = when (page) {
        PanelPage.APPS -> PageContent(
            R.string.apps_eyebrow,
            R.string.apps_title,
            R.string.apps_description,
            R.string.apps_return_hint,
        )
        PanelPage.CONTROL_CENTER -> PageContent(
            R.string.control_center_eyebrow,
            R.string.control_center_title,
            R.string.control_center_description,
            R.string.control_center_return_hint,
        )
        PanelPage.CALENDAR -> PageContent(
            R.string.calendar_eyebrow,
            R.string.calendar_title,
            R.string.calendar_description,
            R.string.calendar_return_hint,
        )
        PanelPage.HOME_CONTROL -> PageContent(
            R.string.home_control_eyebrow,
            R.string.home_control_title,
            R.string.home_control_description,
            R.string.home_control_return_hint,
        )
        PanelPage.HOME -> error("Home uses its dedicated layout")
    }

    private data class PageContent(
        val eyebrow: Int,
        val title: Int,
        val description: Int,
        val returnHint: Int,
    )
}
