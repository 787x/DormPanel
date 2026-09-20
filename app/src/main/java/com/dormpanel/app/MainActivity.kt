package com.dormpanel.app

import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
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

class MainActivity : AppCompatActivity() {
    private val router = NavigationRouter.default()
    private val dashboardViewModel: DashboardViewModel by viewModels()
    private lateinit var pageContainer: FrameLayout
    private lateinit var pageViewFactory: PanelPageViewFactory
    private lateinit var swipeGestureDetector: SwipeGestureDetector
    private var currentPage = PanelPage.HOME
    private var transitionInProgress = false
    private var activePageInteraction: PageInteraction? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        pageContainer = findViewById(R.id.page_container)
        pageViewFactory = PanelPageViewFactory(
            inflater = layoutInflater,
            dashboardStateHolder = dashboardViewModel.stateHolder,
            cardRegistry = dashboardViewModel.registry,
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

                val returnDirection = router.direction(currentPage, router.initialPage)
                showPage(router.initialPage, returnDirection, animate = true)
            }
        })
        currentPage = savedInstanceState
            ?.getString(STATE_CURRENT_PAGE)
            ?.let { savedName -> PanelPage.entries.firstOrNull { it.name == savedName } }
            ?: router.initialPage
        showPage(currentPage, direction = null, animate = false)
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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_CURRENT_PAGE, currentPage.name)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
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

    private fun showPage(page: PanelPage, direction: SwipeDirection?, animate: Boolean) {
        val previousView = pageContainer.getChildAt(pageContainer.childCount - 1)
        val nextView = pageViewFactory.create(page, pageContainer)
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

    @Suppress("DEPRECATION")
    private fun enterImmersiveMode() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.insetsController?.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            window.insetsController?.systemBarsBehavior =
                android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        }
    }

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
