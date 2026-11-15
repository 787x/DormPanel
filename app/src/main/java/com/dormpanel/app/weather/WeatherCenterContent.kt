package com.dormpanel.app.weather

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.dormpanel.app.appearance.PanelPalette
import com.dormpanel.app.dashboard.card.*
import java.time.Instant
import java.time.ZoneId

internal class WeatherCenterContent(private val context: Context, var palette: PanelPalette) {
    fun dialogBuilder() = if (palette.background == Color.BLACK) AlertDialog.Builder(context)
        else AlertDialog.Builder(context, com.google.android.material.R.style.Theme_Material3_Light_Dialog_Alert)

    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun row(vararg children: View) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        children.forEach {
            val existing = it.layoutParams as? LinearLayout.LayoutParams
            addView(it, LinearLayout.LayoutParams(existing?.width?.takeIf { width -> width > 0 } ?: -2,
                existing?.height?.takeIf { height -> height > 0 } ?: -2).apply { marginEnd = dp(6) })
        }
    }

    fun surface(color: Int, selected: Boolean = false) = RippleDrawable(
        ColorStateList.valueOf(palette.accent and 0x33ffffff),
        GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(if (selected) palette.surface else color)
            if (selected) setStroke(dp(2), color)
        }, null,
    )

    fun card() = column().apply {
        background = surface(palette.surface)
        setPadding(dp(16), dp(11), dp(16), dp(11))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
    }

    fun label(value: String, size: Float = 17f, bold: Boolean = false, subdued: Boolean = false) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(if (subdued) palette.secondary else palette.text)
        setLinkTextColor(palette.accent)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(dp(4), dp(3), dp(4), dp(3))
    }

    fun button(value: String, action: () -> Unit) = button(value, selected = false, action = action)

    fun button(value: String, selected: Boolean, action: () -> Unit) = Button(context).apply {
        text = value
        textSize = 16f
        isAllCaps = false
        minHeight = dp(48)
        minimumWidth = dp(48)
        setTextColor(if (selected) palette.accent else palette.text)
        background = surface(if (selected) palette.accent else palette.surface, selected)
        setPadding(dp(9), dp(4), dp(9), dp(4))
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) }
    }

    fun icon(sky: WeatherSky, size: Int) = WeatherIconView(context).apply {
        symbol = sky.symbol
        tint(palette.accent)
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    fun metadata(snapshot: WeatherSnapshot?) = column().apply {
        val time = snapshot?.let { (it.dataAt ?: it.fetchedAt)?.atZone(it.zone)?.format(weatherUpdateFormat) }
        addView(label(listOfNotNull(snapshot?.source?.let(::weatherSourceName), weatherStatus(snapshot),
            time?.let { "更新 $it" }, snapshot?.error).distinct().joinToString(" · "), 14f, subdued = true))
        addView(label("", 13f, subdued = true).apply { showWeatherAttribution(snapshot?.attribution) })
    }

    fun renderCurrent(box: LinearLayout, c: WeatherConditions?, today: String?, display: String? = c?.temperature.value("°C")) {
        val expanded = box.findViewWithTag<View>("current_metrics")?.visibility == View.VISIBLE
        box.removeAllViews()
        if (c == null) return
        val sky = sky(c.code, c.description)
        val reading = column().apply {
            addView(label(display ?: "—", 60f, bold = true).apply {
                contentDescription = display?.let { "当前温度 $it" } ?: "当前温度缺失"
            })
            addView(label(listOfNotNull(sky.description, c.feelsLike.value("°C")?.let { "体感 $it" }, today)
                .joinToString(" · "), 21f))
        }
        box.addView(row().apply {
            addView(icon(sky, 88))
            addView(reading, LinearLayout.LayoutParams(0, -2, 1f))
        })
        val metrics = conditionMetrics(c.copy(temperature = null, feelsLike = null, minTemperature = null, maxTemperature = null))
        if (metrics.isNotEmpty()) {
            val grid = column().apply {
                tag = "current_metrics"
                visibility = if (expanded) View.VISIBLE else View.GONE
                metrics.chunked(3).forEach { group ->
                    addView(row().apply {
                        group.forEach { value ->
                            val icon = icon(WeatherSky(metricSymbol(value.substringBefore("  ")), ""), 23)
                            val reading = row(icon).apply {
                                addView(label(value, 16f), LinearLayout.LayoutParams(0, -2, 1f))
                            }
                            addView(reading, LinearLayout.LayoutParams(0, -2, 1f))
                        }
                        repeat(3 - group.size) { addView(View(context), LinearLayout.LayoutParams(0, 0, 1f)) }
                    })
                }
            }
            box.addView(button("气象指标") {
                grid.visibility = if (grid.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            })
            box.addView(grid)
        }
    }

    private fun metricSymbol(label: String) = when (label) {
        "湿度", "露点" -> WeatherSymbol.DROPLET
        "风", "阵风" -> WeatherSymbol.WIND
        "气压" -> WeatherSymbol.GAUGE
        "能见度" -> WeatherSymbol.FOG
        "云量" -> WeatherSymbol.CLOUD
        "紫外线" -> WeatherSymbol.SUN
        "降水量", "降水概率", "降水强度", "降水类型" -> WeatherSymbol.RAIN
        else -> WeatherSymbol.THERMOMETER
    }

    fun blockTitle(block: WeatherBlock) = when (block) {
        WeatherBlock.CURRENT -> "实时天气"
        WeatherBlock.HOURLY -> "逐小时预报"
        WeatherBlock.DAILY -> "未来天气"
        WeatherBlock.TWICE_DAILY -> "昼夜预报"
        WeatherBlock.ALERTS -> "有效天气预警"
        WeatherBlock.AIR_CURRENT -> "当前空气质量"
        WeatherBlock.AIR_HOURLY -> "小时空气质量"
        WeatherBlock.AIR_DAILY -> "多日空气质量"
        WeatherBlock.MINUTELY -> "未来约两小时降雨"
        WeatherBlock.INDICES -> "生活指数"
        WeatherBlock.SUN -> "太阳"
        WeatherBlock.MOON -> "月亮"
        WeatherBlock.HISTORY -> "历史天气"
    }

    fun render(
        box: LinearLayout, block: WeatherBlock, snapshot: WeatherSnapshot?, hours: Int, zone: ZoneId,
        show: (AlertDialog.Builder) -> Unit,
    ) {
        val data = snapshot?.data
        val alertsEmpty = block == WeatherBlock.ALERTS && data is WeatherData.Alerts && data.entries.isEmpty()
        val oldScroll = (box.findViewWithTag<HorizontalScrollView>("forecast_scroll"))?.scrollX ?: 0
        box.removeAllViews()
        box.visibility = View.VISIBLE
        if (alertsEmpty) {
            // Keep the status and upstream attribution accessible with a compact disclosure.
            box.addView(button("当前无有效天气预警") { dialog("天气预警", metadata(snapshot), show) })
            return
        }
        box.addView(label(blockTitle(block), 22f, bold = true))
        val now = Instant.now()
        when (data) {
            is WeatherData.Forecast -> when (block) {
                WeatherBlock.HOURLY -> {
                    val points = hourlyTrend(data.entries, zone, now, hours)
                    val byTime = data.entries.associateBy { it.period.start }
                    if (points.isEmpty()) box.addView(label("暂无未来时段"))
                    else trend(box, points, "°C", 3600, now, oldScroll, false) { point ->
                        val c = byTime[point.time]?.conditions
                        slot(sky(c?.code, c?.description), listOfNotNull(
                            c?.temperature.value("°C"), c?.precipitationProbability.value("%")?.let { "降水概率 $it" },
                        ), point, show)
                    }
                }
                WeatherBlock.DAILY -> daily(box, futureDays(data.entries, zone, now), zone, show)
                WeatherBlock.TWICE_DAILY -> periods(box, data.entries, zone, show)
                else -> Unit
            }
            is WeatherData.Alerts -> alerts(box, data, zone, show, snapshot)
            is WeatherData.Minutely -> rain(box, data, zone, now, oldScroll, show)
            is WeatherData.Air -> air(box, block, data, zone, now, oldScroll, show)
            is WeatherData.Indices -> data.entries.forEach { entry ->
                box.addView(label(listOfNotNull(entry.name, entry.category ?: entry.level).joinToString(" · "), 20f, bold = true))
                entry.description?.let { box.addView(label(it)) }
            }
            is WeatherData.Astronomy -> box.addView(label(astronomy(data.value, zone).joinToString("\n")))
            is WeatherData.History -> {
                data.daily?.let { daily(box, listOf(it), zone, show) }
                if (data.hourly.isNotEmpty()) {
                    box.addView(label("历史小时实况", 20f, bold = true))
                    periods(box, data.hourly, zone, show)
                }
            }
            else -> Unit
        }
        box.addView(metadata(snapshot))
    }

    private fun trend(
        box: LinearLayout, points: List<WeatherTrendPoint>, unit: String, step: Long, now: Instant,
        scrollX: Int, includeZero: Boolean, item: (WeatherTrendPoint) -> View,
    ) {
        if (points.isEmpty()) return
        val width = dp(112)
        val scale = weatherScale(points.mapNotNull { it.value }, includeZero)
        val selected = label("", 15f).apply { visibility = View.GONE }
        val chart = WeatherTrendView(context, points, scale, unit, step, width, now, palette) {
            selected.visibility = View.VISIBLE
            selected.text = it.detail
        }
        val strip = column().apply {
            addView(chart, LinearLayout.LayoutParams(chart.chartWidth, dp(130)))
            val tiles = row()
            var edge = 0
            points.forEach { point ->
                val left = ((point.time.epochSecond - points.first().time.epochSecond) * width / step).toInt()
                tiles.addView(item(point), LinearLayout.LayoutParams(width, -2).apply {
                    leftMargin = (left - edge).coerceAtLeast(0)
                })
                edge = left + width
            }
            addView(tiles)
        }
        val viewport = HorizontalScrollView(context).apply {
            tag = "forecast_scroll"
            addView(strip)
            isHorizontalScrollBarEnabled = true
            if (scrollX != 0) post { scrollTo(scrollX, 0) }
        }
        val ticks = column().apply {
            if (scale != null) {
                setPadding(0, dp(12), 0, 0)
                val middle = (scale.min + scale.max) / 2
                listOf(scale.max, middle, scale.min).forEach {
                    addView(label(it.value(unit).orEmpty(), 13f, subdued = true), LinearLayout.LayoutParams(-2, dp(38)))
                }
            }
        }
        box.addView(row().apply {
            gravity = android.view.Gravity.TOP
            addView(ticks)
            addView(viewport, LinearLayout.LayoutParams(0, -2, 1f))
        })
        box.addView(selected)
    }

    private fun slot(
        sky: WeatherSky?, values: List<String>, point: WeatherTrendPoint, show: (AlertDialog.Builder) -> Unit,
    ): View = column().apply {
        gravity = android.view.Gravity.CENTER_HORIZONTAL
        minimumHeight = dp(50)
        background = surface(palette.surface)
        sky?.let { addView(icon(it, 28)) }
        addView(label((listOfNotNull(sky?.description) + values).joinToString("\n").ifEmpty { "—" }, 14f).apply {
            gravity = android.view.Gravity.CENTER
        })
        isFocusable = true
        contentDescription = "${point.label} · ${point.detail}"
        setOnClickListener { show(dialogBuilder().setTitle(point.label).setMessage(point.detail)) }
    }

    private fun daily(box: LinearLayout, entries: List<WeatherForecast>, zone: ZoneId, show: (AlertDialog.Builder) -> Unit) {
        val scale = weatherScale(entries.flatMap { listOfNotNull(it.conditions.minTemperature, it.conditions.maxTemperature) })
        if (entries.isEmpty()) box.addView(label("暂无有效日期的预报"))
        entries.forEach { day ->
            val c = day.conditions
            val phases = listOfNotNull(day.day?.let { "白天" to it }, day.night?.let { "夜间" to it })
            val title = day.localDate(zone)?.format(centerDate) ?: "当日"
            val item = row().apply {
                addView(label(title, 16f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
                val skies = if (phases.isEmpty()) listOf("" to day) else phases
                skies.forEach { (name, part) ->
                    val sky = sky(part.conditions.code, part.conditions.description, part.period.isDaytime)
                    addView(icon(sky, 26))
                    addView(label(listOf(name, sky.description).filter(String::isNotBlank).joinToString(" "), 15f),
                        LinearLayout.LayoutParams(0, -2, 1f))
                }
                val temps = column()
                temps.addView(label(range(c.minTemperature, c.maxTemperature, "°C") ?: "—", 15f))
                temps.addView(temperatureBar(c.minTemperature, c.maxTemperature, scale), LinearLayout.LayoutParams(-1, dp(10)))
                addView(temps, LinearLayout.LayoutParams(0, -2, 1.2f))
            }
            val preview = listOfNotNull(
                c.precipitationProbability.value("%")?.let { "降水概率 $it" },
                c.precipitation.value(" mm")?.let { "降水量 $it" },
                (c.windDirection ?: c.windDegrees.value("°"))?.let { "风向 $it" },
                c.windSpeed.value(" m/s")?.let { "风速 $it" },
                c.humidity.value("%")?.let { "湿度 $it" },
                c.uvIndex.value("")?.let { "紫外线 $it" },
            )
            val summary = column().apply {
                minimumHeight = dp(55)
                addView(item)
                if (preview.isNotEmpty()) addView(label(preview.joinToString(" · "), 14f, subdued = true))
                isFocusable = true
                background = surface(palette.surface)
                setOnClickListener {
                    val text = listOf(conditionMetrics(c).joinToString("\n")) + phases.map { (name, phase) ->
                        listOf(name + " · " + sky(phase.conditions.code, phase.conditions.description).description,
                            *conditionMetrics(phase.conditions).toTypedArray()).joinToString("\n")
                    } + day.astronomy?.let { astronomy(it, zone).joinToString("\n") }.orEmpty()
                    message(title, text.filter(String::isNotBlank).joinToString("\n\n"), show)
                }
            }
            box.addView(summary)
        }
        scale?.let { box.addView(label("共同温标  ${it.min.value("°C")} 至 ${it.max.value("°C")}", 13f, subdued = true)) }
    }

    private fun temperatureBar(low: Double?, high: Double?, scale: WeatherScale?) = object : View(context) {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeWidth = dp(4).toFloat()
        }
        override fun onDraw(canvas: android.graphics.Canvas) {
            if (scale == null || low == null || high == null) return
            val pad = dp(4).toFloat()
            val span = (width - 2 * pad).coerceAtLeast(0f)
            paint.color = palette.secondary
            paint.alpha = 70
            canvas.drawLine(pad, height / 2f, pad + span, height / 2f, paint)
            paint.color = palette.accent
            if (low == high) canvas.drawCircle(pad + span * scale.fraction(low), height / 2f, dp(2).toFloat(), paint)
            else canvas.drawLine(pad + span * scale.fraction(low), height / 2f,
                pad + span * scale.fraction(high), height / 2f, paint)
        }
    }

    private fun periods(box: LinearLayout, entries: List<WeatherForecast>, zone: ZoneId, show: (AlertDialog.Builder) -> Unit) {
        entries.take(24).forEach { entry ->
            val local = entry.period.start?.atZone(zone)
            val title = listOfNotNull(entry.localDate(zone)?.format(centerDate),
                local?.format(centerTime)?.plus(" ${local.offset}") ?: entry.period.localTime?.format(centerTime),
                entry.period.isDaytime?.let { if (it) "白天" else "夜间" }).joinToString(" ")
            val c = entry.conditions
            val sky = sky(c.code, c.description, entry.period.isDaytime)
            box.addView(row(icon(sky, 29), button(listOfNotNull(title, sky.description, c.temperature.value("°C"),
                range(c.minTemperature, c.maxTemperature, "°C")).joinToString(" · ")) {
                message(title, (listOf(sky.description) + conditionMetrics(c)).joinToString("\n"), show)
            }))
        }
    }

    private fun rain(box: LinearLayout, data: WeatherData.Minutely, zone: ZoneId, now: Instant, scrollX: Int, show: (AlertDialog.Builder) -> Unit) {
        data.summary?.let { box.addView(label(it, 19f)) }
        val samples = data.entries.mapNotNull { sample ->
            sample.time?.takeIf { it > now.minusSeconds(300) && it <= now.plusSeconds(7200) }?.let { it to sample }
        }.sortedBy { it.first }.distinctBy { it.first }
        val points = samples.map { (time, entry) ->
            val label = time.atZone(zone).format(centerTime)
            WeatherTrendPoint(time, entry.amountMm, label, listOfNotNull(label, entry.type,
                entry.amountMm.value(" mm / 5 分钟") ?: "降水量缺失").joinToString(" · "))
        }
        val changes = samples.zipWithNext().mapNotNull { (before, after) ->
            val left = before.second.amountMm ?: return@mapNotNull null
            val right = after.second.amountMm ?: return@mapNotNull null
            if (after.first.epochSecond - before.first.epochSecond != 300L || (left > 0) == (right > 0)) return@mapNotNull null
            "${after.first.atZone(zone).format(centerTime)} 左右可能${if (right > 0) "开始降水" else "停止降水"}"
        }
        if (changes.isNotEmpty()) box.addView(label(changes.take(2).joinToString(" · ")))
        if (points.isEmpty()) box.addView(label("暂无未来时段"))
        else trend(box, points, " mm", 300, now, scrollX, true) { point ->
            slot(null, listOfNotNull(point.value.value(" mm")), point, show)
        }
    }

    private fun air(
        box: LinearLayout, block: WeatherBlock, data: WeatherData.Air, zone: ZoneId, now: Instant,
        scrollX: Int, show: (AlertDialog.Builder) -> Unit,
    ) {
        if (block == WeatherBlock.AIR_CURRENT) {
            data.entries.forEach { reading ->
                reading.indexes.forEach { index ->
                    box.addView(label(indexTitle(index), 21f, bold = true))
                    listOfNotNull(index.primaryPollutant?.let { "主要污染物  ${it.fullName ?: it.name ?: it.code.orEmpty()}" },
                        index.health?.effect, index.health?.general, index.health?.sensitive?.let { "敏感人群 · $it" })
                        .forEach { box.addView(label(it)) }
                }
                val pollutants = reading.pollutants.mapNotNull {
                    it.concentration.value(it.unit?.let { unit -> " $unit" }.orEmpty())?.let { amount ->
                        "${it.name ?: it.code.orEmpty()}  $amount"
                    }
                }
                if (pollutants.isNotEmpty()) box.addView(label(pollutants.joinToString("   ·   ")))
                if (reading.indexes.isEmpty() && pollutants.isEmpty()) box.addView(label("暂无可用空气质量指标"))
            }
            return
        }
        val grouped = data.entries.flatMap { entry -> entry.indexes.map { entry.period to it } }
            .groupBy { (_, index) -> index.code ?: index.name }
        if (grouped.isEmpty()) box.addView(label("暂无可用空气质量预报"))
        grouped.forEach { (standard, rows) ->
            box.addView(label(rows.first().second.name ?: standard ?: "指数标准未注明", 18f, bold = true))
            if (block == WeatherBlock.AIR_DAILY) {
                rows.forEach { (period, index) ->
                    val date = period.date ?: period.start?.atZone(zone)?.toLocalDate()
                    box.addView(label(listOfNotNull(date?.format(centerDate), indexTitle(index)).joinToString(" · ")))
                }
            } else if (standard != null) {
                val points = rows.mapNotNull { (period, index) ->
                    val time = period.start?.takeIf { it > now.minusSeconds(3600) && it < now.plusSeconds(864000) }
                        ?: return@mapNotNull null
                    val local = time.atZone(zone)
                    val label = local.format(centerTime)
                    WeatherTrendPoint(time, index.aqi, label, "${local.format(centerDate)} $label ${local.offset}\n${indexTitle(index)}")
                }.sortedBy { it.time }.distinctBy { it.time }
                trend(box, points, " AQI", 3600, now, scrollX, true) { point ->
                    slot(null, listOfNotNull(point.value.value("")), point, show)
                }
            } else rows.forEach { (period, index) ->
                box.addView(label(listOfNotNull(period.start?.atZone(zone)?.format(weatherUpdateFormat), indexTitle(index)).joinToString(" · ")))
            }
        }
    }

    private fun indexTitle(index: AirIndex) = listOfNotNull(index.name ?: index.code,
        (index.display ?: index.aqi.value(""))?.let { "AQI $it" }, index.category ?: index.level).joinToString(" · ")

    private fun alerts(
        box: LinearLayout, data: WeatherData.Alerts, zone: ZoneId, show: (AlertDialog.Builder) -> Unit, snapshot: WeatherSnapshot?,
    ) {
        data.entries.forEach { alert ->
            val title = alert.headline ?: alert.eventName ?: "天气预警"
            val caption = label(listOfNotNull(alert.eventName?.takeUnless { it == title }, alert.severity, alert.sender)
                .joinToString(" · "), 15f)
            val mark = View(context).apply { setBackgroundColor(alertColor(alert.color)) }
            box.addView(row().apply {
                addView(mark, LinearLayout.LayoutParams(dp(5), dp(43)))
                addView(button(title) {
                    val body = column()
                    fun time(name: String, value: Instant?) = value?.atZone(zone)?.format(weatherUpdateFormat)?.let { "$name  $it" }
                    val text = listOfNotNull(alert.eventName, alert.severity, alert.sender,
                        time("发布", alert.issuedAt), time("生效", alert.effectiveAt), time("开始", alert.onsetAt),
                        time("到期", alert.expiresAt), alert.description, alert.criteria, alert.instruction, alert.latestChange)
                    body.addView(label(text.joinToString("\n\n")))
                    body.addView(metadata(snapshot))
                    dialog(title, body, show)
                }, LinearLayout.LayoutParams(0, -2, 1f))
            })
            if (caption.text.isNotEmpty()) box.addView(caption)
        }
    }

    private fun alertColor(color: WeatherColor?): Int {
        if (color?.red != null && color.green != null && color.blue != null) {
            return Color.rgb(color.red.coerceIn(0, 255), color.green.coerceIn(0, 255), color.blue.coerceIn(0, 255))
        }
        return color?.code?.let { runCatching { Color.parseColor(it) }.getOrNull() } ?: palette.accent
    }

    private fun astronomy(value: WeatherAstronomy, zone: ZoneId): List<String> {
        val labels = mapOf(AstronomicalEvent.SUNRISE to "日出", AstronomicalEvent.SUNSET to "日落",
            AstronomicalEvent.MOONRISE to "月升", AstronomicalEvent.MOONSET to "月落",
            AstronomicalEvent.CIVIL_DAWN to "民用晨光", AstronomicalEvent.CIVIL_DUSK to "民用暮光",
            AstronomicalEvent.SOLAR_NOON to "太阳中天", AstronomicalEvent.MOON_TRANSIT to "月亮中天")
        return listOfNotNull(value.date?.format(centerDate)) + labels.mapNotNull { (event, title) ->
            (value.events[event]?.atZone(zone)?.format(centerTime) ?: value.localEvents[event]?.format(centerTime))
                ?.let { "$title  $it" }
        } + listOfNotNull((value.phase ?: value.hourlyPhases.firstNotNullOfOrNull { it.name })?.let { "月相  $it" })
    }

    private fun message(title: String, value: String, show: (AlertDialog.Builder) -> Unit) =
        dialog(title, column().apply { addView(label(value)) }, show)

    private fun dialog(title: String, view: View, show: (AlertDialog.Builder) -> Unit) {
        show(dialogBuilder().setTitle(title).setView(ScrollView(context).apply {
            setPadding(dp(18), dp(8), dp(18), dp(12))
            addView(view)
        }))
    }
}
