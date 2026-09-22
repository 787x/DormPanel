package com.dormpanel.app

import android.os.Bundle
import android.view.MotionEvent
import android.view.View
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
    private val appearanceListener: (AppearanceState) -> Unit = { state ->
        pageContainer.setBackgroundColor(PanelPalette.forMode(state.themeMode).background)
        for (index in 0 until pageContainer.childCount) applyPageAppearance(pageContainer.getChildAt(index), state)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        pageContainer = findViewById(R.id.page_container)
        scheduleImports = com.dormpanel.app.schedule.ScheduleImportUi(this, dashboardViewModel.schedule, dashboardViewModel.appearance) {
            // Generic MIME allows .ics documents from providers that do not report text/calendar.
            timetablePicker.launch(arrayOf("text/calendar", "*/*"))
        }
        pageViewFactory = PanelPageViewFactory(
            inflater = layoutInflater,
            dashboardStateHolder = dashboardViewModel.stateHolder,
            cardRegistry = dashboardViewModel.registry,
            appearance = dashboardViewModel.appearance,
            catalog = dashboardViewModel.catalog,
            backend = dashboardViewModel.dataSource,
            apps = dashboardViewModel.apps,
            schedule = dashboardViewModel.schedule,
            scheduleSession = dashboardViewModel.scheduleSession,
            importTimetable = scheduleImports::choose,
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
        enterImmersiveMode()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
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
        scheduleImports.close()
        dashboardViewModel.appearance.removeListener(appearanceListener)
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
