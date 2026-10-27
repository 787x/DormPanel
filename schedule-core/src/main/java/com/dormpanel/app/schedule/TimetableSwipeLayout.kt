package com.dormpanel.app.schedule

import android.animation.ValueAnimator
import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.abs

/** Local event arbitration, independent of dates, stores and either app's page navigation.
 * Observe before ScrollView can disallow interception; cancel its target exactly once on lock.
 * The single child contains the date headings and courses, never the fixed toolbar.
 */
class TimetableSwipeLayout(context: Context, private val navigate: (Int) -> Unit) : FrameLayout(context) {
    var swipeEnabled = false
        set(value) { if (field != value) cancelMotion(); field = value }
    private val config = ViewConfiguration.get(context)
    private val slop = config.scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var tracking = false
    private var horizontal = false
    private var rejected = false
    private var settling = false
    private var committing = false
    private var generation = 0
    private var velocity: VelocityTracker? = null
    private val content get() = getChildAt(0)

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!swipeEnabled) return super.dispatchTouchEvent(event)
        if (settling && (committing || event.actionMasked != MotionEvent.ACTION_DOWN)) return true
        // An incoming page or rebound can be grabbed immediately by a fresh finger.
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            cancelMotion()
            tracking = true; downX = event.x; downY = event.y
            velocity = VelocityTracker.obtain()
        }
        if (!tracking) return super.dispatchTouchEvent(event)
        velocity?.addMovement(event)
        val dx = event.x - downX
        val dy = event.y - downY
        if (event.pointerCount > 1 || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            cancelChild(event); rejected = true; horizontal = false
            content?.translationX = 0f
            // Suppress the entire multi-touch stream, including a remaining finger's UP.
            velocity?.recycle(); velocity = null
            return true
        }
        if (rejected && velocity == null) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) cancelMotion()
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_MOVE && !horizontal && !rejected && maxOf(abs(dx), abs(dy)) > slop) {
            if (abs(dx) > abs(dy) * 1.7f) {
                horizontal = true
                cancelChild(event)
                parent?.requestDisallowInterceptTouchEvent(true)
            } else rejected = true // Direction lock: a vertical/diagonal scroll cannot become a page turn.
        }
        if (horizontal) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> content?.translationX = dragOffset(dx)
                MotionEvent.ACTION_UP -> {
                    velocity?.computeCurrentVelocity(1000, config.scaledMaximumFlingVelocity.toFloat())
                    val vx = velocity?.xVelocity ?: 0f
                    val vy = velocity?.yVelocity ?: 0f
                    val distance = abs(dx) >= maxOf(width * .23f, slop * 4f)
                    val fling = abs(dx) >= slop * 3 && abs(vx) >= maxOf(config.scaledMinimumFlingVelocity.toFloat(), 900f * resources.displayMetrics.density) &&
                        abs(vx) > abs(vy) * 1.7f && vx * dx > 0
                    finish(if (abs(dx) > abs(dy) * 1.7f && (distance || fling)) { if (dx < 0) 1 else -1 } else 0)
                }
                MotionEvent.ACTION_CANCEL -> finish(0)
            }
            return true
        }
        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            tracking = false
            cancelMotion()
        }
        return handled || tracking
    }

    private fun cancelChild(event: MotionEvent) {
        val cancel = MotionEvent.obtain(event)
        cancel.action = MotionEvent.ACTION_CANCEL
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
    }

    private fun dragOffset(dx: Float): Float {
        // Bounded live translation; no screenshots, duplicate course views or offscreen bitmaps.
        val limit = width * .24f
        return dx.coerceIn(-limit, limit)
    }

    private fun finish(direction: Int) {
        tracking = false; horizontal = false
        velocity?.recycle(); velocity = null
        parent?.requestDisallowInterceptTouchEvent(false)
        val view = content ?: return
        settling = true
        committing = direction != 0
        val complete = {
            settling = false; committing = false; view.translationX = 0f; view.alpha = 1f
            if (direction != 0) navigate(direction)
        }
        if (!ValueAnimator.areAnimatorsEnabled()) { complete(); return }
        view.animate().translationX(if (direction == 0) 0f else -direction * width * .24f)
            .alpha(if (direction == 0) 1f else .85f).setDuration(if (direction == 0) 180 else 120)
            .setInterpolator(DecelerateInterpolator()).withEndAction { complete() }.start()
    }

    /** Called on the newly rendered content, after the owner has atomically changed its date. */
    fun arrive(direction: Int) {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        val view = content ?: return
        view.translationX = direction * width * .08f
        // Width is not known until layout when the owner rebuilds the hierarchy.
        view.alpha = .85f
        settling = true
        val expectedGeneration = generation
        view.post {
            if (generation != expectedGeneration || !settling || !isAttachedToWindow) return@post
            view.translationX = direction * width * .08f
            view.animate().translationX(0f).alpha(1f).setDuration(80)
                .setInterpolator(DecelerateInterpolator()).withEndAction { settling = false }.start()
        }
    }

    fun cancelMotion() {
        generation++ // Invalidate even an arrival runnable that has not started its animator yet.
        if (tracking) {
            val time = android.os.SystemClock.uptimeMillis()
            val cancel = MotionEvent.obtain(time,time,MotionEvent.ACTION_CANCEL,downX,downY,0)
            super.dispatchTouchEvent(cancel); cancel.recycle()
        }
        content?.animate()?.withEndAction(null)?.cancel()
        content?.let { it.translationX = 0f; it.alpha = 1f }
        velocity?.recycle(); velocity = null
        tracking = false; horizontal = false; rejected = false; settling = false; committing = false
        parent?.requestDisallowInterceptTouchEvent(false)
    }
    override fun onDetachedFromWindow() { cancelMotion(); super.onDetachedFromWindow() }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) { super.onWindowFocusChanged(hasWindowFocus); if (!hasWindowFocus) cancelMotion() }
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); if (!isVisible) cancelMotion() }
}
