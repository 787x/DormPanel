package com.dormpanel.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.database.ContentObserver
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.dormpanel.app.dashboard.DashboardViewModel
import com.dormpanel.app.navigation.NavigationRouter
import com.dormpanel.app.navigation.PanelPage
import com.dormpanel.app.navigation.SwipeDirection
import com.dormpanel.app.navigation.SwipeGestureDetector
import com.dormpanel.app.ui.PanelPageViewFactory
import com.dormpanel.app.ui.PageInteraction
import com.dormpanel.app.ui.hidePanelSystemBars
import com.dormpanel.app.appearance.AppearanceState
import com.dormpanel.app.appearance.PanelPalette
import com.dormpanel.app.appearance.applyAppearanceTree

class MainActivity : AppCompatActivity() {
    private val router = NavigationRouter.default()
    private val dashboardViewModel: DashboardViewModel by viewModels()
    private lateinit var scheduleImports: com.dormpanel.app.schedule.ScheduleImportUi
    private val timetablePicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scheduleImports.selected(uri)
    }
    private lateinit var pageContainer: FrameLayout
    private lateinit var pageViewFactory: PanelPageViewFactory
    private lateinit var swipeGestureDetector: SwipeGestureDetector
    private var currentPage = PanelPage.HOME
    private var transitionInProgress = false
    private var activePageInteraction: PageInteraction? = null
    private var blackoutView: View? = null
    private val volumeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { dashboardViewModel.deviceControls.refreshAudio() }
    }
    private val deviceListener: (com.dormpanel.app.device.DeviceControlState) -> Unit = { state ->
        val attributes = window.attributes
        val effective = if (state.blackout) 1 else state.brightness
        val brightness = if (state.useSystemBrightness && !state.blackout) -1f else effective / 100f
        if (attributes.screenBrightness != brightness) {
            attributes.screenBrightness = brightness
            window.attributes = attributes
        }
        if (state.keepAwake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (state.blackout && blackoutView == null) {
            blackoutView = View(this).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                contentDescription = "Blackout screen. Tap to restore."
                isClickable = true
                setOnClickListener { dashboardViewModel.deviceControls.exitBlackout() }
            }
            pageContainer.addView(blackoutView, FrameLayout.LayoutParams(-1, -1))
        } else if (!state.blackout) {
            blackoutView?.let(pageContainer::removeView)
            blackoutView = null
        }
    }
    private val appearanceListener: (AppearanceState) -> Unit = { state ->
        pageContainer.setBackgroundColor(PanelPalette.forMode(state.themeMode).background)
        for (index in 0 until pageContainer.childCount) applyPageAppearance(pageContainer.getChildAt(index), state)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        pageContainer = findViewById(R.id.page_container)
        scheduleImports = com.dormpanel.app.schedule.ScheduleImportUi(this, dashboardViewModel.schedule, dashboardViewModel.appearance, dashboardViewModel.webDav) {
            // Generic MIME allows .ics documents from providers that do not report text/calendar.
            timetablePicker.launch(arrayOf("text/calendar", "*/*"))
        }
        dashboardViewModel.haRelay.attach { delivery ->
            scheduleImports.relayPreview(delivery.preview) { outcome ->
                dashboardViewModel.haRelay.resolve(delivery.transferId, outcome)
            }
        }
        pageViewFactory = PanelPageViewFactory(
            inflater = layoutInflater,
            dashboardStateHolder = dashboardViewModel.stateHolder,
            cardRegistry = dashboardViewModel.registry,
            appearance = dashboardViewModel.appearance,
            deviceControls = dashboardViewModel.deviceControls,
            catalog = dashboardViewModel.catalog,
            backend = dashboardViewModel.dataSource,
            apps = dashboardViewModel.apps,
            schedule = dashboardViewModel.schedule,
            scheduleSession = dashboardViewModel.scheduleSession,
            importTimetable = scheduleImports::choose,
            receiveTimetable = scheduleImports::receive,
            manageTimetables = scheduleImports::sources,
            onReturnHome = ::returnHome,
            onPageGestureClaimed = {
                swipeGestureDetector.cancel()
            },
        )
        swipeGestureDetector = SwipeGestureDetector(this, ::navigate)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (activePageInteraction?.handleBack() == true) return
                if (currentPage == router.initialPage) {
                    finish()
                    return
                }

                returnHome()
            }
        })
        currentPage = savedInstanceState
            ?.getString(STATE_CURRENT_PAGE)
            ?.let { savedName -> PanelPage.entries.firstOrNull { it.name == savedName } }
            ?: router.initialPage
        showPage(currentPage, direction = null, animate = false)
        dashboardViewModel.appearance.addListener(appearanceListener)
        dashboardViewModel.deviceControls.addListener(deviceListener)
        enterImmersiveMode()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (dashboardViewModel.deviceControls.state.blackout) return super.dispatchTouchEvent(event)
        if (activePageInteraction?.shouldObservePageSwipe(event) != false) {
            swipeGestureDetector.onTouchEvent(event)
        } else if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            swipeGestureDetector.cancel()
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onResume() {
        super.onResume()
        dashboardViewModel.apps.refresh()
        dashboardViewModel.schedule.refresh()
        dashboardViewModel.deviceControls.refreshAudio()
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver)
    }

    override fun onPause() {
        contentResolver.unregisterContentObserver(volumeObserver)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_CURRENT_PAGE, currentPage.name)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        dashboardViewModel.haRelay.detach()
        scheduleImports.close()
        dashboardViewModel.appearance.removeListener(appearanceListener)
        dashboardViewModel.deviceControls.removeListener(deviceListener)
        for (index in 0 until pageContainer.childCount) {
            pageContainer.getChildAt(index).animate().cancel()
        }
        super.onDestroy()
    }

    private fun navigate(direction: SwipeDirection) {
        if (transitionInProgress) return
        val destination = router.destination(currentPage, direction) ?: return
        showPage(destination, direction, animate = true)
    }

    private fun returnHome() {
        if (transitionInProgress || currentPage == router.initialPage) return
        showPage(router.initialPage, router.direction(currentPage, router.initialPage), animate = true)
    }

    private fun showPage(page: PanelPage, direction: SwipeDirection?, animate: Boolean) {
        val previousView = pageContainer.getChildAt(pageContainer.childCount - 1)
        val nextView = pageViewFactory.create(page, pageContainer)
        applyPageAppearance(nextView, dashboardViewModel.appearance.state)
        activePageInteraction = nextView as? PageInteraction
        currentPage = page

        if (!animate || previousView == null || direction == null) {
            pageContainer.removeAllViews()
            pageContainer.addView(nextView)
            transitionInProgress = false
            return
        }

        transitionInProgress = true
        val travel = resources.getDimension(R.dimen.page_transition_distance)
        val (startX, startY) = direction.offset(travel)
        nextView.alpha = 0f
        nextView.translationX = startX
        nextView.translationY = startY
        pageContainer.addView(nextView)

        previousView.animate()
            .alpha(0f)
            .translationX(-startX)
            .translationY(-startY)
            .setDuration(TRANSITION_DURATION_MS)
            .start()
        nextView.animate()
            .alpha(1f)
            .translationX(0f)
            .translationY(0f)
            .setDuration(TRANSITION_DURATION_MS)
            .withEndAction {
                if (previousView.parent === pageContainer) pageContainer.removeView(previousView)
                transitionInProgress = false
            }
            .start()
    }

    private fun applyPageAppearance(view: View, state: AppearanceState) {
        view.setBackgroundColor(PanelPalette.forMode(state.themeMode).background)
        applyAppearanceTree(view, state)
    }

    private fun enterImmersiveMode() = window.hidePanelSystemBars()

    private fun SwipeDirection.offset(distance: Float): Pair<Float, Float> = when (this) {
        SwipeDirection.LEFT -> distance to 0f
        SwipeDirection.RIGHT -> -distance to 0f
        SwipeDirection.UP -> 0f to distance
        SwipeDirection.DOWN -> 0f to -distance
    }

    private companion object {
        const val STATE_CURRENT_PAGE = "current_page"
        const val TRANSITION_DURATION_MS = 160L
    }
}
