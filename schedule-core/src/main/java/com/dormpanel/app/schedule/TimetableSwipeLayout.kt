package com.dormpanel.app.schedule

import android.animation.ValueAnimator
import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.abs

/** Optional bounded materialization for complex pages. All calls run on the UI thread.
 * Only genuinely visible columns are required synchronously; owners may fill hidden columns
 * in finite frame-sized batches and must stop that preparation when ownership ends.
 */
interface TimetablePreviewContent {
    fun reveal(left: Int, right: Int): Boolean
    fun startPreparation()
    fun cancelPreparation()
}

/** Local arbitration and a two-page clipped viewport. Owners supply read-only date projections.
 * No preview is built for DOWN, taps or vertical scrolling. The owner can promote the exact
 * preview tree with takePreview() during navigate; otherwise it is released after navigation.
 */
class TimetableSwipeLayout(context: Context, private val navigate: (Int) -> Unit) : FrameLayout(context) {
    var preparePreview: ((Int) -> View)? = null
    var previewTiming: ((String, Long, Long) -> Unit)? = null
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
    private var animator: ValueAnimator? = null
    private var preview: View? = null
    private var previewDirection = 0
    private var previewFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
    private var offset = 0f
    private val content get() = getChildAt(0)

    init { clipChildren = true; clipToPadding = true }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!swipeEnabled) return super.dispatchTouchEvent(event)
        if (settling && (committing || event.actionMasked != MotionEvent.ACTION_DOWN)) return true
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
            releasePreview(); position(0f)
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
                previewTiming?.let { val now=System.nanoTime(); it("lock",now,now) }
                cancelChild(event)
                parent?.requestDisallowInterceptTouchEvent(true)
            } else rejected = true
        }
        if (horizontal) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> previewPhase("move") { drag(dx) }
                MotionEvent.ACTION_UP -> {
                    drag(dx) // UP may contain a final reversal not delivered in a MOVE.
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
            tracking = false; cancelMotion()
        }
        return handled || tracking
    }

    private fun cancelChild(event: MotionEvent) {
        val cancel = MotionEvent.obtain(event)
        cancel.action = MotionEvent.ACTION_CANCEL
        super.dispatchTouchEvent(cancel); cancel.recycle()
    }

    private fun drag(dx: Float) {
        val direction = when { dx < 0 -> 1; dx > 0 -> -1; else -> previewDirection }
        if (direction != 0 && direction != previewDirection) {
            releasePreview()
            previewDirection = direction
            previewPhase("prepare") { preparePreview?.invoke(direction) }?.let { page ->
                preview = page
                page.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                if (page is ViewGroup) {
                    previewFocusability = page.descendantFocusability
                    page.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                }
                previewPhase("mount") { addView(page, LayoutParams(-1,-1)) }
                // Restore its scroll anchor before exposing the first strip.
                previewPhase("measure") { page.measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(height,MeasureSpec.EXACTLY)) }
                previewPhase("layout") { page.layout(0,0,width,height) }
                (page as? TimetablePreviewContent)?.startPreparation()
            }
        }
        position(dx.coerceIn(-width.toFloat(),width.toFloat()))
    }

    private inline fun <T> previewPhase(name: String, block: () -> T): T {
        val sink=previewTiming ?: return block()
        val start=System.nanoTime()
        try { return block() } finally { sink(name,start,System.nanoTime()) }
    }

    private fun position(x: Float) {
        offset = x
        preview?.let { page ->
            val origin=previewDirection*width+x
            val left=maxOf(0f,-origin).toInt()
            val right=kotlin.math.ceil(minOf(width.toFloat(),width-origin).toDouble()).toInt()
            if(right>left && (page as? TimetablePreviewContent)?.reveal(left,right)==true) {
                previewPhase("measure-visible") { page.measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(height,MeasureSpec.EXACTLY)) }
                previewPhase("layout-visible") { page.layout(0,0,width,height) }
            }
        }
        content?.translationX = x
        preview?.translationX = previewDirection * width + x
    }

    private fun finish(direction: Int) {
        tracking = false; horizontal = false
        velocity?.recycle(); velocity = null
        parent?.requestDisallowInterceptTouchEvent(false)
        settling = true; committing = direction != 0
        val target = if (direction == 0) 0f else -direction * width.toFloat()
        val expectedGeneration = generation
        val complete = {
            if (expectedGeneration == generation) {
                settling = false; committing = false; animator = null
                if (direction != 0) previewPhase("commit") { navigate(direction) }
                if (expectedGeneration == generation) { releasePreview(); position(0f) }
            }
        }
        if (!ValueAnimator.areAnimatorsEnabled()) { position(target); complete(); return }
        animator = ValueAnimator.ofFloat(offset,target).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener { position(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { complete() }
            })
            start()
        }
    }

    /** Transfer ownership on successful navigation; reuse the preview's measured tree. */
    fun takePreview(): View? = preview?.also { page ->
        (page as? TimetablePreviewContent)?.cancelPreparation()
        preview = null; previewDirection = 0
        removeView(page); page.translationX = 0f
        if (page is ViewGroup) page.descendantFocusability = previewFocusability
        page.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_AUTO
    }

    private fun releasePreview() {
        preview?.let { (it as? TimetablePreviewContent)?.cancelPreparation(); removeView(it) }
        preview = null; previewDirection = 0
    }

    fun cancelMotion() {
        generation++
        animator?.cancel(); animator = null
        if (tracking) {
            val time = android.os.SystemClock.uptimeMillis()
            val cancel = MotionEvent.obtain(time,time,MotionEvent.ACTION_CANCEL,downX,downY,0)
            super.dispatchTouchEvent(cancel); cancel.recycle()
        }
        releasePreview(); position(0f)
        velocity?.recycle(); velocity = null
        tracking = false; horizontal = false; rejected = false; settling = false; committing = false
        parent?.requestDisallowInterceptTouchEvent(false)
    }
    override fun onDetachedFromWindow() { cancelMotion(); super.onDetachedFromWindow() }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        if (oldw > 0 && (w != oldw || h != oldh)) cancelMotion()
    }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) { super.onWindowFocusChanged(hasWindowFocus); if (!hasWindowFocus) cancelMotion() }
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); if (!isVisible) cancelMotion() }
}
