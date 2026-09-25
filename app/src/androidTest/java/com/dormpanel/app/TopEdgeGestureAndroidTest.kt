package com.dormpanel.app

import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.GeneralSwipeAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Swipe
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dormpanel.app.dashboard.ui.DashboardPageView
import com.dormpanel.app.ui.ControlCenterView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TopEdgeGestureAndroidTest {
    private fun currentPage(activity: MainActivity) =
        (activity.findViewById<ViewGroup>(R.id.page_container)).let { it.getChildAt(it.childCount - 1) }

    private fun send(activity: MainActivity, downX: Float, downY: Float,
        endX: Float, endY: Float, finish: Int = MotionEvent.ACTION_UP) {
        val start = SystemClock.uptimeMillis()
        val events = listOf(
            Triple(MotionEvent.ACTION_DOWN, downX to downY, start),
            Triple(MotionEvent.ACTION_MOVE, (downX + endX) / 2 to (downY + endY) / 2, start + 40),
            Triple(finish, endX to endY, start + 80),
        )
        events.forEach { (action, point, time) ->
            val event = MotionEvent.obtain(start, time, action, point.first, point.second, 0)
            try { activity.dispatchTouchEvent(event) } finally { event.recycle() }
        }
    }

    private fun swipeDownFrom(startY: Float) {
        onView(withId(R.id.page_container)).perform(GeneralSwipeAction(Swipe.FAST,
            { view -> floatArrayOf(view.width / 2f, startY) },
            { view -> floatArrayOf(view.width / 2f, view.height - 100f) }, Press.FINGER))
    }

    @Test fun topEdgeDownAndHorizontalStreamsNeverNavigateButBelowEdgeStillDoes() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val edge = activity.resources.getDimension(R.dimen.system_top_edge_gesture_zone)
                assertEquals(32f * activity.resources.displayMetrics.density, edge, .01f)
                assertTrue(currentPage(activity) is DashboardPageView)

                send(activity, 640f, 1f, 640f, 700f)
                assertTrue(currentPage(activity) is DashboardPageView)

                send(activity, 100f, edge - 1f, 800f, 500f)
                assertTrue(currentPage(activity) is DashboardPageView)

            }
            swipeDownFrom(100f * androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation().targetContext.resources.displayMetrics.density + 1f)
            scenario.onActivity { assertTrue(currentPage(it) is ControlCenterView) }
        }
    }

    @Test fun cancelClearsEdgeStream() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                send(activity, 640f, 1f, 640f, 700f, MotionEvent.ACTION_CANCEL)
                assertTrue(currentPage(activity) is DashboardPageView)
            }
            swipeDownFrom(100f * androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation().targetContext.resources.displayMetrics.density)
            scenario.onActivity { assertTrue(currentPage(it) is ControlCenterView) }
        }
    }
}
