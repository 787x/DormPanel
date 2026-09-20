package com.dormpanel.app.navigation

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.max

/** Observes touch streams without consuming them, leaving taps available to page content. */
class SwipeGestureDetector(
    context: Context,
    private val onSwipe: (SwipeDirection) -> Unit,
) : GestureDetector.SimpleOnGestureListener() {
    private val density = context.resources.displayMetrics.density
    private val minimumDistancePx = max(96f * density, ViewConfiguration.get(context).scaledTouchSlop * 4f)
    private val minimumVelocityPx = 180f * density
    private val detector = GestureDetector(context, this)

    fun onTouchEvent(event: MotionEvent) {
        detector.onTouchEvent(event)
    }

    fun cancel() {
        val now = SystemClock.uptimeMillis()
        MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0).also { event ->
            detector.onTouchEvent(event)
            event.recycle()
        }
    }

    override fun onDown(event: MotionEvent): Boolean = true

    override fun onFling(
        start: MotionEvent?,
        end: MotionEvent,
        velocityX: Float,
        velocityY: Float,
    ): Boolean {
        if (start == null) return false

        val deltaX = end.x - start.x
        val deltaY = end.y - start.y
        val horizontal = abs(deltaX) > abs(deltaY)
        val primaryDistance = if (horizontal) abs(deltaX) else abs(deltaY)
        val secondaryDistance = if (horizontal) abs(deltaY) else abs(deltaX)
        val primaryVelocity = if (horizontal) abs(velocityX) else abs(velocityY)

        if (primaryDistance < minimumDistancePx || primaryVelocity < minimumVelocityPx) return false
        if (primaryDistance < secondaryDistance * AXIS_DOMINANCE_RATIO) return false

        val direction = if (horizontal) {
            if (deltaX < 0) SwipeDirection.LEFT else SwipeDirection.RIGHT
        } else {
            if (deltaY < 0) SwipeDirection.UP else SwipeDirection.DOWN
        }
        onSwipe(direction)
        return true
    }

    private companion object {
        const val AXIS_DOMINANCE_RATIO = 1.25f
    }
}
