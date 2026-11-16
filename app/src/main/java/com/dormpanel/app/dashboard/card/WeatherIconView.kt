package com.dormpanel.app.dashboard.card

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** Small monochrome vector glyphs, tinted with the current panel palette. */
internal class WeatherIconView(context: Context) : View(context) {
    var symbol = WeatherSymbol.UNKNOWN
        set(value) { if (field != value) { field = value; invalidate() } }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.65f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    fun tint(color: Int) { if (paint.color != color) { paint.color = color; invalidate() } }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val side = minOf(width, height)
        canvas.save()
        canvas.translate((width - side) / 2f, (height - side) / 2f)
        canvas.scale(side / 32f, side / 32f)
        when (symbol) {
            WeatherSymbol.SUN -> sun(canvas)
            WeatherSymbol.MOON -> moon(canvas)
            WeatherSymbol.PARTLY, WeatherSymbol.PARTLY_NIGHT -> {
                canvas.save()
                canvas.translate(1f, -3f)
                canvas.scale(.66f, .66f)
                if (symbol == WeatherSymbol.PARTLY) sun(canvas) else moon(canvas)
                canvas.restore()
                cloud(canvas, 3f)
            }
            WeatherSymbol.CLOUD -> cloud(canvas, 0f)
            WeatherSymbol.RAIN, WeatherSymbol.SNOW, WeatherSymbol.STORM -> {
                cloud(canvas, -3f)
                when (symbol) {
                    WeatherSymbol.RAIN -> for (x in 9..24 step 6) canvas.drawLine(x.toFloat(), 23f, x - 2f, 28f, paint)
                    WeatherSymbol.SNOW -> for (x in 9..24 step 6) canvas.drawCircle(x.toFloat(), 25f, 1.25f, paint)
                    else -> stroke(canvas) { moveTo(17f, 20f); lineTo(13f, 25f); lineTo(18f, 25f); lineTo(15f, 30f) }
                }
            }
            WeatherSymbol.FOG, WeatherSymbol.HAZE -> {
                if (symbol == WeatherSymbol.FOG) cloud(canvas, -5f) else {
                    canvas.drawArc(8f, 6f, 24f, 22f, 180f, 180f, false, paint)
                    canvas.drawLine(4f, 14f, 28f, 14f, paint)
                }
                for (y in 21..29 step 4) canvas.drawLine((if (y == 25) 3 else 7).toFloat(), y.toFloat(), 26f, y.toFloat(), paint)
            }
            WeatherSymbol.WIND -> stroke(canvas) {
                moveTo(4f, 12f); lineTo(21f, 12f); cubicTo(30f, 12f, 27f, 3f, 22f, 6f)
                moveTo(3f, 18f); lineTo(27f, 18f)
                moveTo(7f, 24f); lineTo(20f, 24f); cubicTo(26f, 24f, 24f, 31f, 20f, 28f)
            }
            WeatherSymbol.THERMOMETER -> stroke(canvas) {
                moveTo(13f, 19f); lineTo(13f, 7f); quadTo(16f, 2f, 19f, 7f); lineTo(19f, 19f)
                cubicTo(29f, 33f, 3f, 33f, 13f, 19f)
                moveTo(16f, 11f); lineTo(16f, 24f)
            }
            WeatherSymbol.DROPLET -> stroke(canvas) {
                moveTo(16f, 4f); cubicTo(14f, 9f, 7f, 16f, 7f, 21f)
                cubicTo(7f, 31f, 25f, 31f, 25f, 21f)
                cubicTo(25f, 16f, 18f, 9f, 16f, 4f)
            }
            WeatherSymbol.GAUGE -> {
                canvas.drawArc(5f, 7f, 27f, 29f, 180f, 180f, false, paint)
                canvas.drawLine(16f, 18f, 22f, 12f, paint)
                canvas.drawLine(6f, 24f, 26f, 24f, paint)
            }
            WeatherSymbol.UNKNOWN -> {
                canvas.drawCircle(16f, 16f, 11f, paint)
                stroke(canvas) { moveTo(12f, 12f); cubicTo(12f, 6f, 25f, 9f, 18f, 14f); quadTo(16f, 15f, 16f, 18f) }
                canvas.drawCircle(16f, 22f, .55f, paint)
            }
        }
        canvas.restore()
    }

    private fun sun(canvas: Canvas) {
        canvas.drawCircle(16f, 16f, 6.5f, paint)
        for (i in 0 until 8) {
            canvas.save()
            canvas.rotate(i * 45f, 16f, 16f)
            canvas.drawLine(16f, 3f, 16f, 6f, paint)
            canvas.restore()
        }
    }

    private fun moon(canvas: Canvas) = stroke(canvas) {
        moveTo(17f, 5f); cubicTo(5f, 3f, 1f, 24f, 15f, 28f); cubicTo(22f, 30f, 28f, 25f, 28f, 19f)
        cubicTo(17f, 25f, 10f, 15f, 17f, 5f); close()
    }

    private fun cloud(canvas: Canvas, shift: Float) {
        canvas.save()
        canvas.translate(0f, shift)
        stroke(canvas) {
            moveTo(9f, 23f); cubicTo(0f, 23f, 2f, 13f, 9f, 14f); cubicTo(10f, 5f, 24f, 5f, 25f, 14f)
            cubicTo(32f, 14f, 31f, 23f, 25f, 23f); close()
        }
        canvas.restore()
    }

    private inline fun stroke(canvas: Canvas, construct: Path.() -> Unit) {
        path.rewind()
        path.construct()
        canvas.drawPath(path, paint)
    }
}
