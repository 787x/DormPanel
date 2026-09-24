package com.dormpanel.app.dashboard.card

import android.annotation.SuppressLint
import android.content.*
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import androidx.core.content.ContextCompat
import com.dormpanel.app.R
import com.dormpanel.app.appearance.AppearanceController
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Clock's only time and scheduling boundary. Production schedules one callback per minute. */
interface ClockTiming {
    fun nowMillis(): Long
    fun schedule(view: View, callback: Runnable, delayMillis: Long)
    fun cancel(view: View, callback: Runnable)
}

object DeviceClockTiming : ClockTiming {
    override fun nowMillis() = System.currentTimeMillis()
    override fun schedule(view: View, callback: Runnable, delayMillis: Long) {
        view.postDelayed(callback, delayMillis)
    }
    override fun cancel(view: View, callback: Runnable) { view.removeCallbacks(callback) }
}

@SuppressLint("ViewConstructor")
class ClockCardView(context: Context, appearance: AppearanceController,
    private var timing: ClockTiming = DeviceClockTiming) : DashboardCardView(context, appearance) {
    private val heading = label(DashboardTypography.SECONDARY, true)
    private val time = fittingLabel(DashboardTypography.CLOCK).apply {
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private val date = label(DashboardTypography.TITLE)
    private val calendar = label(DashboardTypography.SECONDARY, true)
    private var activeClock = false
    private var aggregatedVisible = false
    private val tick = object : Runnable {
        override fun run() {
            if (!activeClock) return
            refresh()
            timing.schedule(this@ClockCardView, this, millisUntilNextMinute(timing.nowMillis()))
        }
    }
    private val timeChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { timing.cancel(this@ClockCardView, tick); if (activeClock) tick.run() }
    }
    init {
        addView(heading)
        addView(time, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(date)
        addView(calendar, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = 12.dp })
    }
    override fun render() {
        val presentation = clockPresentation(card.size)
        gravity = Gravity.CENTER_VERTICAL or if (presentation.centered) Gravity.CENTER_HORIZONTAL else Gravity.START
        time.gravity = if (presentation.centered) Gravity.CENTER else Gravity.START
        time.maximumTextSp = presentation.timeSp
        val now = Date(timing.nowMillis())
        heading.text = context.getString(R.string.local_time)
        heading.show(presentation.calendarDetail)
        time.text = DateFormat.getTimeFormat(context).format(now)
        date.text = DateFormat.getMediumDateFormat(context).format(now)
        date.show(presentation.date)
        calendar.text = SimpleDateFormat("EEEE · yyyy · z", Locale.getDefault()).format(now)
        calendar.show(presentation.calendarDetail)
        describe(time.text, if (presentation.date) date.text else "", if (presentation.calendarDetail) heading.text else "")
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateClockActivity() }
    override fun onDetachedFromWindow() { stopClock(); super.onDetachedFromWindow() }
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); aggregatedVisible = isVisible; updateClockActivity() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); updateClockActivity() }
    private fun updateClockActivity() {
        val shouldRun = isAttachedToWindow && aggregatedVisible && windowVisibility == VISIBLE
        if (shouldRun == activeClock) return
        if (!shouldRun) { stopClock(); return }
        activeClock = true
        ContextCompat.registerReceiver(context, timeChanged, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED); addAction(Intent.ACTION_DATE_CHANGED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        tick.run()
    }
    /** Replaces the boundary on this retained view; useful for deterministic instrumentation. */
    fun replaceTimingForTest(replacement: ClockTiming) {
        timing.cancel(this, tick)
        timing = replacement
        if (activeClock) tick.run() else refresh()
    }
    fun dispatchTimeChangeForTest(action: String) {
        require(action in setOf(Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_DATE_CHANGED))
        timeChanged.onReceive(context, Intent(action))
    }
    private fun stopClock() { timing.cancel(this, tick); if (activeClock) context.unregisterReceiver(timeChanged); activeClock = false }
}
