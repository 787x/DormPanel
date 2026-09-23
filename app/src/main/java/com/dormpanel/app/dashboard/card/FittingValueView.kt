package com.dormpanel.app.dashboard.card

import android.content.Context
import android.util.TypedValue
import androidx.appcompat.widget.AppCompatTextView

/** A single-line fit computed from scratch on every measure, independent of API 28
 * AutoSize's previous wrap-content height. No callbacks, animation or idle work. */
class FittingValueView(context: Context) : AppCompatTextView(context) {
    private val measuringPaint = android.text.TextPaint()
    private val measuringMetrics = android.graphics.Paint.FontMetrics()
    var maximumTextSp = DashboardTypography.CLOCK
        set(value) { field = value; requestLayout() }

    init { maxLines = 1; includeFontPadding = false }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maximum = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, maximumTextSp, resources.displayMetrics)
        val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) Float.MAX_VALUE
            else (MeasureSpec.getSize(widthMeasureSpec) - compoundPaddingLeft - compoundPaddingRight).coerceAtLeast(1).toFloat()
        val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) Float.MAX_VALUE
            else (MeasureSpec.getSize(heightMeasureSpec) - compoundPaddingTop - compoundPaddingBottom).coerceAtLeast(1).toFloat()
        measuringPaint.set(paint)
        measuringPaint.textSize = maximum
        val textWidth = measuringPaint.measureText(text.toString()).coerceAtLeast(1f)
        measuringPaint.getFontMetrics(measuringMetrics)
        val textHeight = (measuringMetrics.descent - measuringMetrics.ascent).coerceAtLeast(1f)
        val fitted = maximum * minOf(1f, width / textWidth, height / textHeight)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, fitted)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
