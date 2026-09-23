package com.dormpanel.app.dashboard.card

import android.annotation.SuppressLint
import android.content.*
import android.text.format.DateFormat
import android.view.Gravity
import androidx.core.content.ContextCompat
import com.dormpanel.app.R
import com.dormpanel.app.appearance.AppearanceController
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@SuppressLint("ViewConstructor")
class ClockCardView(context: Context, appearance: AppearanceController) : DashboardCardView(context, appearance) {
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
            postDelayed(this, millisUntilNextMinute(System.currentTimeMillis()))
        }
    }
    private val timeChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { removeCallbacks(tick); if (activeClock) tick.run() }
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
        val now = Date()
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
    private fun stopClock() { removeCallbacks(tick); if (activeClock) context.unregisterReceiver(timeChanged); activeClock = false }
}
