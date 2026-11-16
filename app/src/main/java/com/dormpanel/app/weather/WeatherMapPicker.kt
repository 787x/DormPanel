package com.dormpanel.app.weather

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import com.dormpanel.app.appearance.PanelPalette
import com.dormpanel.app.appearance.ThemeMode
import com.dormpanel.app.dashboard.card.openWeatherLink
import java.io.DataInputStream
import java.math.BigDecimal
import java.util.zip.GZIPInputStream
import kotlin.math.roundToInt

/** Bundled physical land outlines and reference towns. No tile requests or device location. */
internal object WeatherMapPicker {
    fun show(context: Context, seed: WeatherCoordinates?, onPoint: (WeatherCoordinates) -> Unit): AlertDialog {
        val ui = WeatherCenterContent(context, PanelPalette.forMode(ThemeMode.DARK))
        val body = ui.column().apply { setPadding(ui.dp(16), 0, ui.dp(16), 0) }
        val coordinates = ui.label("正在加载离线底图", 18f)
        val map = CoordinateMap(context, seed) {
            coordinates.text = "纬度 ${it.latitude.toPlainString()}  ·  经度 ${it.longitude.toPlainString()}"
        }
        body.addView(map, LinearLayout.LayoutParams(-1, ui.dp(300)))
        body.addView(coordinates)
        val controls = ui.row(ui.button("缩小") { map.zoom(.5f) }, ui.button("放大") { map.zoom(2f) },
            ui.button("选地图中心") { map.chooseCenter() })
        body.addView(controls)
        body.addView(ui.label("离线概览地图，仅显示陆地轮廓和参考城市，不含道路、校园或建筑细节。可在地点表单手动填写精确经纬度。", 14f))
        body.addView(ui.button("底图 · Natural Earth") {
            body.openWeatherLink("https://www.naturalearthdata.com/about/terms-of-use/")
        })
        val dialog = AlertDialog.Builder(context).setTitle("在离线概览地图上选点").setView(body)
            .setNegativeButton("手动输入", null).setPositiveButton("使用坐标", null).show()
        val accept = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        accept.isEnabled = seed != null
        map.onSelectionChanged = { accept.isEnabled = true }
        accept.setOnClickListener {
            map.selected?.let(onPoint)
            dialog.dismiss()
        }
        // Decode a small local asset once per picker, away from the UI thread.
        Thread({
            val world = runCatching { load(context.applicationContext) }.getOrNull()
            map.post {
                if (!dialog.isShowing) return@post
                if (world == null) {
                    coordinates.text = "底图无法读取，请返回手动输入经纬度。"
                    body.removeView(map)
                    body.removeView(controls)
                    accept.isEnabled = false
                    map.onSelectionChanged = {}
                } else {
                    map.world = world
                    if (map.selected == null) coordinates.text = "点击地图选择位置"
                    map.invalidate()
                }
            }
        }, "weather-map-decode").start()
        return dialog
    }

    private data class Outline(val vertices: FloatArray, val bounds: RectF)
    private data class City(val x: Float, val y: Float, val rank: Int, val name: String)
    private data class World(val land: List<Outline>, val cities: List<City>)

    // The .bin suffix keeps Android's asset packager from expanding the gzip stream.
    private fun load(context: Context): World =
        DataInputStream(GZIPInputStream(context.assets.open("weather-map.ne.bin")).buffered()).use { input ->
            val land = List(input.readInt()) {
                val points = FloatArray(input.readInt() * 2) { index ->
                    input.readInt() / if (index % 2 == 0) 10000f else -10000f
                }
                val bounds = RectF(points[0], points[1], points[0], points[1])
                for (index in points.indices step 2) {
                    bounds.left = minOf(bounds.left, points[index])
                    bounds.right = maxOf(bounds.right, points[index])
                    bounds.top = minOf(bounds.top, points[index + 1])
                    bounds.bottom = maxOf(bounds.bottom, points[index + 1])
                }
                Outline(points, bounds)
            }
            val cities = List(input.readInt()) {
                val x = input.readInt() / 10000f
                val y = input.readInt() / -10000f
                val rank = input.readUnsignedByte()
                val name = ByteArray(input.readUnsignedShort()).also(input::readFully)
                City(x, y, rank, String(name, Charsets.UTF_8))
            }.sortedBy { it.rank }
            World(land, cities)
        }

    private class CoordinateMap(context: Context, seed: WeatherCoordinates?, val onPoint: (WeatherCoordinates) -> Unit) :
        View(context) {
        var world: World? = null
        var selected = seed
            private set
        var onSelectionChanged: () -> Unit = {}
        private var centerX = seed?.longitude?.toFloat() ?: 105f
        private var centerY = seed?.latitude?.toFloat()?.unaryMinus() ?: -33f
        private var span = if (seed == null) 90f else 12f
        private val pixels get() = width.coerceAtLeast(1) / span
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        private val ink = Color.rgb(21, 49, 52)
        private val density = resources.displayMetrics.density
        private val claimed = mutableListOf<RectF>()
        private val scaleGesture = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoom(detector.scaleFactor)
                return true
            }
        }).apply { isQuickScaleEnabled = false }
        private val drag = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                choose(centerX + (e.x - width / 2f) / pixels, centerY + (e.y - height / 2f) / pixels)
                performClick()
                return true
            }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (!scaleGesture.isInProgress && e2.pointerCount == 1) {
                    centerX = (centerX + distanceX / pixels).coerceIn(-180f, 180f)
                    centerY = (centerY + distanceY / pixels).coerceIn(-90f, 90f)
                    invalidate()
                }
                return true
            }
        })

        init {
            contentDescription = "离线地图。可拖动、缩放、点击选点，也可使用选地图中心按钮。"
            seed?.let(onPoint)
        }

        fun zoom(factor: Float) {
            span = (span / factor).coerceIn(1f, 360f)
            invalidate()
        }

        fun chooseCenter() = choose(centerX, centerY)

        private fun choose(x: Float, y: Float) {
            if (world == null) return
            fun decimal(value: Float) = BigDecimal.valueOf((value * 10000).roundToInt().toLong(), 4)
            val point = WeatherCoordinates(decimal((-y).coerceIn(-90f, 90f)), decimal(x.coerceIn(-180f, 180f)))
            selected = point
            onPoint(point)
            onSelectionChanged()
            invalidate()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            scaleGesture.onTouchEvent(event)
            drag.onTouchEvent(event)
            return true
        }

        override fun performClick(): Boolean = super.performClick()
        private fun x(worldX: Float) = (worldX - centerX) * pixels + width / 2f
        private fun y(worldY: Float) = (worldY - centerY) * pixels + height / 2f

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.rgb(32, 63, 80))
            val visible = RectF(centerX - width / 2f / pixels, centerY - height / 2f / pixels,
                centerX + width / 2f / pixels, centerY + height / 2f / pixels)
            paint.color = Color.rgb(157, 183, 155)
            world?.land?.forEach { ring ->
                if (!RectF.intersects(visible, ring.bounds)) return@forEach
                val p = ring.vertices
                path.rewind()
                path.moveTo(x(p[0]), y(p[1]))
                for (index in 2 until p.size step 2) path.lineTo(x(p[index]), y(p[index + 1]))
                path.close()
                canvas.drawPath(path, paint)
            }
            claimed.clear()
            paint.textSize = 12 * resources.displayMetrics.scaledDensity
            world?.cities?.forEach { city ->
                val maxRank = when {
                    span >= 100 -> 1
                    span >= 30 -> 3
                    span >= 10 -> 6
                    else -> 10
                }
                if (city.rank > maxRank || !visible.contains(city.x, city.y)) return@forEach
                val x = x(city.x)
                val y = y(city.y)
                val bounds = RectF(x - 3 * density, y - paint.textSize, x + 6 * density + paint.measureText(city.name), y + 3 * density)
                if (claimed.any { RectF.intersects(it, bounds) }) return@forEach
                claimed += bounds
                paint.color = ink
                canvas.drawCircle(x, y, 2.4f * density, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2 * density
                paint.color = Color.rgb(190, 208, 186)
                canvas.drawText(city.name, x + 5 * density, y, paint)
                paint.style = Paint.Style.FILL
                paint.color = ink
                canvas.drawText(city.name, x + 5 * density, y, paint)
            }
            fun cross(x: Float, y: Float, size: Float, color: Int) {
                paint.color = color
                paint.strokeWidth = 2 * density
                canvas.drawLine(x - size, y, x + size, y, paint)
                canvas.drawLine(x, y - size, x, y + size, paint)
            }
            cross(width / 2f, height / 2f, 7 * density, Color.WHITE)
            selected?.let { cross(x(it.longitude.toFloat()), y(-it.latitude.toFloat()), 11 * density, Color.rgb(255, 170, 43)) }
        }
    }
}
