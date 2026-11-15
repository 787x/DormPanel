package com.dormpanel.app.weather

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import com.dormpanel.app.appearance.PanelPalette
import java.time.Instant
import kotlin.math.abs

/** Uses elapsed time for x, including gaps and repeated civil hours. */
internal class WeatherTrendView(
    context: Context,
    private val points: List<WeatherTrendPoint>,
    private var series: WeatherTrendSeries,
    private val stepSeconds: Long,
    private val slot: Int,
    private val now: Instant,
    private val palette: PanelPalette,
    private val inspect: (WeatherTrendPoint) -> Unit,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private val scaled = resources.displayMetrics.scaledDensity
    private val origin = points.first().time
    private val offsets = points.map { (it.time.epochSecond - origin.epochSecond) / stepSeconds.toFloat() }
    val chartWidth = ((offsets.last() + 1) * slot).toInt()
    private var selected = -1
    private var down = 0f

    init {
        updateSeries(series)
        isClickable = true
    }

    fun updateSeries(value: WeatherTrendSeries) {
        series = value
        contentDescription = "趋势图 ${value.unit.trim()}。也可选择下方时段查看明细。"
        invalidate()
    }

    fun position(index: Int) = (offsets[index] + .5f) * slot
    private fun y(value: Double) = height - 30 * density - (series.scale?.fraction(value) ?: 0f) * (height - 54 * density)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bottom = height - 30 * density
        paint.textSize = 12 * scaled
        paint.strokeWidth = density
        val clip = canvas.clipBounds
        series.scale?.let { scale ->
            listOf(scale.min, (scale.min + scale.max) / 2, scale.max).forEach { value ->
                val y = y(value)
                paint.color = palette.secondary
                paint.alpha = 85
                canvas.drawLine(clip.left.toFloat(), y, clip.right.toFloat(), y, paint)
                paint.alpha = 255
            }
        }
        if (now >= origin.minusSeconds(stepSeconds) && now <= points.last().time.plusSeconds(stepSeconds)) {
            val mark = (((now.epochSecond - origin.epochSecond) / stepSeconds.toFloat() + .5f) * slot).coerceAtLeast(1f)
            paint.color = palette.secondary
            canvas.drawLine(mark, 14 * density, mark, bottom, paint)
            canvas.drawText("现在", mark + 3 * density, 13 * density, paint)
        }
        points.forEachIndexed { index, point ->
            val x = position(index)
            if (x < clip.left - slot || x > clip.right + slot) return@forEachIndexed
            paint.color = palette.secondary
            if (point.dateBreak && index > 0) canvas.drawLine(x - slot / 2, 0f, x - slot / 2, height.toFloat(), paint)
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(point.label, x, height - 8 * density, paint)
            paint.textAlign = Paint.Align.LEFT
            val value = series.values[index] ?: return@forEachIndexed
            val y = y(value)
            paint.color = palette.accent
            paint.strokeWidth = 2 * density
            if (series.bars && value > 0) {
                val half = minOf(slot * .18f, neighborDistance(index) * .4f)
                if (selected == index) paint.alpha = 145
                canvas.drawRect(x - half, y, x + half, bottom, paint)
                paint.alpha = 255
            } else {
                val prior = series.values.getOrNull(index - 1)
                if (!series.bars && prior != null && point.time.epochSecond - points[index - 1].time.epochSecond <= stepSeconds) {
                    canvas.drawLine(position(index - 1), y(prior), x, y, paint)
                }
                canvas.drawCircle(x, y, (if (selected == index) 6 else 3) * density, paint)
            }
        }
    }

    val pointCount get() = points.size

    fun indicesNear(left: Int, viewportWidth: Int, overscan: Int): IntRange {
        fun lowerBound(x: Int): Int {
            var start = 0
            var end = points.size
            while (start < end) {
                val middle = (start + end) / 2
                if (position(middle) < x) start = middle + 1 else end = middle
            }
            return start
        }
        return lowerBound(left - overscan) until lowerBound(left + viewportWidth + overscan)
    }

    fun neighborDistance(index: Int) = minOf(
        if (index == 0) slot.toFloat() else position(index) - position(index - 1),
        if (index == points.lastIndex) slot.toFloat() else position(index + 1) - position(index),
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> down = event.x
            MotionEvent.ACTION_UP -> if (abs(event.x - down) <= 16 * density) {
                val closest = points.indices.minBy { abs(position(it) - event.x) }
                if (abs(position(closest) - event.x) <= slot / 2f) {
                    selected = closest
                    inspect(points[selected])
                    invalidate()
                    performClick()
                }
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
