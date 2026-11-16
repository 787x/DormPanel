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
    /** A heading can prefer ellipsis over further width-based shrinking. */
    var minimumWidthTextSp = 0f
        set(value) { field = value; requestLayout() }
    /** API 28 can round mixed-script glyph advances differently at the fitted size. */
    var verifyFittedWidth = false

    init { setSingleLine(true); includeFontPadding = false }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maximum = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, maximumTextSp, resources.displayMetrics)
        val measuredWidth = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE
            else MeasureSpec.getSize(widthMeasureSpec)
        val width = (minOf(measuredWidth, maxWidth.takeIf { it >= 0 } ?: Int.MAX_VALUE) -
            compoundPaddingLeft - compoundPaddingRight - 2).coerceAtLeast(1).toFloat()
        val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) Float.MAX_VALUE
            else (MeasureSpec.getSize(heightMeasureSpec) - compoundPaddingTop - compoundPaddingBottom - 2).coerceAtLeast(1).toFloat()
        measuringPaint.set(paint)
        measuringPaint.textSize = maximum
        val textWidth = measuringPaint.measureText(text.toString()).coerceAtLeast(1f)
        measuringPaint.getFontMetrics(measuringMetrics)
        // Leave room for TextView's integer glyph advances and line metrics.
        val textHeight = (measuringMetrics.descent - measuringMetrics.ascent).coerceAtLeast(1f)
        val minimumWidthSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, minimumWidthTextSp, resources.displayMetrics)
        val fitted = minOf(maximum, (maximum * width / textWidth).coerceAtLeast(minimumWidthSize),
            maximum * height / textHeight)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, fitted)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (verifyFittedWidth) {
            val lineWidth = layout?.takeIf { it.lineCount > 0 }?.getLineWidth(0) ?: return
            if (lineWidth > width && fitted > minimumWidthSize) {
                val corrected = (fitted * (width - 2).coerceAtLeast(1f) / lineWidth).coerceAtLeast(minimumWidthSize)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, corrected)
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }
    }
}
