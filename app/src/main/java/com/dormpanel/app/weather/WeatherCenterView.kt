package com.dormpanel.app.weather

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.dormpanel.app.appearance.AppearanceAware
import com.dormpanel.app.appearance.AppearanceController
import com.dormpanel.app.appearance.AppearanceState
import com.dormpanel.app.appearance.PanelPalette
import com.dormpanel.app.dashboard.card.*
import com.dormpanel.app.data.DashboardData
import com.dormpanel.app.ha.BackendMode
import com.dormpanel.app.ha.DashboardBackend
import com.dormpanel.app.ui.PageInteraction
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal class WeatherCenterView(
    context: Context,
    private val backend: DashboardBackend,
    appearance: AppearanceController,
    initial: WeatherCenterSelection,
    private val back: () -> Unit,
    private val repository: WeatherRepository = backend.weatherRepository,
) : LinearLayout(context), PageInteraction, AppearanceAware {
    private enum class Section(val title: String) {
        FORECAST("天气预报"), RAIN("分钟降雨"), AIR("空气质量"), INDICES("生活指数"), SKY("日月天文"), HISTORY("历史天气"),
    }

    private val places = repository.places
    private val settings = WeatherSettingsDialog(context, backend.weatherServices, ::openPlace)
    private val ui = WeatherCenterContent(context, PanelPalette.forMode(appearance.state.themeMode))
    private val sidebar = ui.column()
    private val cityRows = ui.column()
    private val cityScroll = ScrollView(context).apply {
        addView(cityRows)
        setOnScrollChangeListener { _, _, _, _, _ -> schedulePreviews() }
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> schedulePreviews() }
    }
    private data class CityRow(val place: WeatherPlace, val item: LinearLayout, val summary: TextView, val icon: WeatherIconView)
    private val cities = linkedMapOf<String, CityRow>()
    private val previews = linkedMapOf<Pair<String, WeatherSourceRef>, WeatherRepository.Subscription>()
    private val updatePreviews = Runnable { bindPreviews() }
    private val detail = ui.column()
    private val scroll = ScrollView(context).apply { addView(detail); isFillViewport = true }
    private val stops = linkedMapOf<WeatherQuery, WeatherRepository.Subscription>()
    private val snapshots = mutableMapOf<WeatherQuery, WeatherSnapshot>()
    private val sections = mutableMapOf<WeatherBlock, LinearLayout>()
    private val popups = linkedSetOf<AlertDialog>()
    private var alertPopup: AlertDialog? = null
    private var generation = 0
    private var stopPlaces: (() -> Unit)? = null
    private var listeningLegacy = false
    private var section = Section.FORECAST
    private var range = 24
    private var historyDate: LocalDate? = null
    private var requestedHistory: LocalDate? = null
    private var shownPlace: WeatherPlace? = initial.placeId?.let(places::get)
    private var active = true
    private lateinit var header: LinearLayout
    private lateinit var hero: LinearLayout
    private lateinit var refreshFeedback: TextView
    private val current get() = snapshots.values.firstOrNull { it.query.block == WeatherBlock.CURRENT }
    private val daily get() = snapshots.values.firstOrNull { it.query.block == WeatherBlock.DAILY }
    private val zone get() = shownPlace?.zone ?: ZoneId.systemDefault()
    private val legacyListener: (DashboardData) -> Unit = { renderLegacy(it) }

    var selection = initial.resolve(places)
        private set

    init {
        orientation = HORIZONTAL
        buildSidebar()
        addView(sidebar, LayoutParams(0, -1, 1f))
        addView(scroll, LayoutParams(0, -1, 3.1f))
        detail.setPadding(ui.dp(16), ui.dp(12), ui.dp(20), ui.dp(20))
        applyAppearance(appearance.state)
    }

    override fun shouldObservePageSwipe(event: MotionEvent) = false

    override fun handleBack(): Boolean {
        back()
        return true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        stopPlaces = places.observe { select(selection) }
        buildPage()
    }

    override fun onDetachedFromWindow() {
        stopPlaces?.invoke()
        stopPlaces = null
        settings.dismiss()
        popups.toList().forEach(AlertDialog::dismiss)
        release()
        super.onDetachedFromWindow()
    }

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        if (active) {
            subscribe()
            schedulePreviews()
        } else {
            requestedHistory = null
            release(clear = false)
        }
    }

    override fun applyAppearance(state: AppearanceState) {
        ui.palette = PanelPalette.forMode(state.themeMode)
        setBackgroundColor(ui.palette.background)
        if (isAttachedToWindow) {
            buildSidebar()
            buildPage()
        }
    }

    private fun buildSidebar() = with(ui) {
        sidebar.removeAllViews()
        sidebar.setPadding(dp(12), dp(12), dp(8), dp(12))
        sidebar.addView(row(button("‹ 返回", back), label("天气中心", 23f, bold = true)))
        sidebar.addView(button("搜索城市或区县", settings::search))
        sidebar.addView(button("添加地点") { settings.edit(null) })
        sidebar.addView(cityScroll, LayoutParams(-1, 0, 1f))
        sidebar.addView(button("天气服务与地点", settings::show))
    }

    private fun openPlace(place: WeatherPlace) {
        settings.dismiss()
        select(WeatherCenterSelection(place.id))
    }

    private fun select(next: WeatherCenterSelection) {
        val resolved = next.resolve(places)
        val place = resolved.placeId?.let(places::get)
        if (resolved == selection && shownPlace?.zone == place?.zone) {
            shownPlace = place
            renderSidebar()
            renderHeader()
            return
        }
        alertPopup?.dismiss()
        release()
        selection = resolved
        shownPlace = place
        section = Section.FORECAST
        range = 24
        historyDate = null
        requestedHistory = null
        buildPage()
        scroll.scrollTo(0, 0)
    }

    private fun renderSidebar() = with(ui) {
        cityRows.removeAllViews()
        cities.clear()
        places.orderedPlaces.forEach { place ->
            val selected = place.id == selection.placeId
            val reading = if (selected) current else repository.cachedCurrent(place.id, place.sources.first())
            val summary = label("", 15f, subdued = true)
            val glyph = icon(sky(null), 34)
            val caption = column().apply {
                addView(label((if (place.favorite) "★ " else "") + place.name, 20f, bold = selected))
                addView(summary)
            }
            val item = row(glyph).apply {
                addView(caption, LayoutParams(0, -2, 1f))
                minimumHeight = dp(68)
                isFocusable = true
                background = surface(if (selected) palette.accent else palette.surface, selected)
                setOnClickListener { select(WeatherCenterSelection(place.id, selection.source)) }
            }
            val city = CityRow(place, item, summary, glyph)
            cities[place.id] = city
            renderCity(city, reading)
            cityRows.addView(item, LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
        }
        cityRows.addView(button(if (selection.legacy) "✓ 默认天气" else "默认天气") {
            select(WeatherCenterSelection(legacy = true))
        })
        schedulePreviews()
    }

    private fun renderCity(city: CityRow, reading: WeatherSnapshot?) {
        val conditions = reading.conditions()
        val sky = sky(conditions?.code, conditions?.description)
        city.icon.symbol = sky.symbol
        val summary = if (conditions != null) {
            listOfNotNull(conditions.temperature.value("°C"), sky.description,
                "已过期".takeIf { reading?.stale == true }).joinToString(" · ")
        } else if (places.isTemporary(city.place.id)) "临时预览"
        else "点击查看天气"
        city.summary.text = summary
        val selectionLabel = if (city.place.id == selection.placeId) "已选中 · " else ""
        city.item.contentDescription = "$selectionLabel${city.place.name} · $summary"
    }

    private fun schedulePreviews() {
        cityScroll.removeCallbacks(updatePreviews)
        cityScroll.postOnAnimation(updatePreviews)
    }

    private fun bindPreviews() {
        if (!active || !isAttachedToWindow) return
        val top = cityScroll.scrollY
        val bottom = top + cityScroll.height
        val visible = cities.values.filter { city ->
            city.place.id != selection.placeId && city.item.bottom > top && city.item.top < bottom
        }.associateBy { it.place.id to it.place.sources.first() }
        (previews.keys - visible.keys).forEach { previews.remove(it)?.close() }
        (visible.keys - previews.keys).forEach { (id, source) ->
            // QWeather thumbnails can read the existing cache. HA current weather reads the live entity.
            previews[id to source] = repository.observe(id, source, WeatherQuery(WeatherBlock.CURRENT),
                automatic = source is WeatherSourceRef.HomeAssistant) { snapshot ->
                if (id != selection.placeId) cities[id]?.takeIf { it.place.sources.first() == source }
                    ?.let { renderCity(it, snapshot) }
            }
        }
    }

    private fun buildPage(): Unit = with(ui) {
        detail.removeAllViews()
        sections.clear()
        shownPlace = selection.placeId?.let(places::get)
        header = column()
        detail.addView(header)
        hero = card()
        detail.addView(hero)
        refreshFeedback = label("", 15f, subdued = true).apply { visibility = GONE }
        renderSidebar()
        renderHeader()
        when {
            selection.legacy -> renderLegacy(backend.state)
            shownPlace == null -> hero.addView(label("搜索城市或添加经纬度地点，即可查看天气。", 21f))
            else -> {
                if (selection.source !is WeatherSourceRef.HomeAssistant) {
                    addBlock(WeatherBlock.ALERTS)
                    val alert = sections.getValue(WeatherBlock.ALERTS)
                    detail.removeView(alert)
                    detail.addView(alert, 1)
                }
                renderHero()
                val tabs = row()
                Section.entries.filter { section ->
                    selection.source !is WeatherSourceRef.HomeAssistant || section == Section.FORECAST
                }.forEach { item ->
                    tabs.addView(button(item.title, selected = section == item) {
                        if (section != item) {
                            section = item
                            requestedHistory = null
                            buildPage()
                            scroll.scrollTo(0, 0)
                        }
                    }, LayoutParams(0, -2, 1f).apply { marginEnd = dp(4); bottomMargin = dp(6) })
                }
                detail.addView(tabs)
                if (section == Section.FORECAST) detail.addView(row().apply {
                    listOf(24, 48, 72, 240).forEach { hours ->
                        addView(button(if (hours == 240) "最多 240 小时" else "$hours 小时", selected = range == hours) {
                            if (range != hours) {
                                range = hours
                                buildPage()
                            }
                        }, LayoutParams(0, -2, 1f).apply { marginEnd = dp(4); bottomMargin = dp(6) })
                    }
                })
                if (section == Section.HISTORY) detail.addView(button(historyDate?.format(centerDate) ?: "选择历史日期") {
                    val dates = (1L..10).map { LocalDate.now(zone).minusDays(it) }
                    show(ui.dialogBuilder().setTitle("选择历史日期")
                        .setItems(dates.map { it.format(centerDate) }.toTypedArray()) { _, index ->
                            historyDate = dates[index]
                            requestedHistory = null
                            buildPage()
                        })
                })
                val visible = when (section) {
                    Section.FORECAST -> listOf(WeatherBlock.HOURLY, WeatherBlock.DAILY) +
                        listOf(WeatherBlock.TWICE_DAILY).filter { selection.source is WeatherSourceRef.HomeAssistant }
                    Section.SKY -> listOf(WeatherBlock.SUN, WeatherBlock.MOON)
                    else -> queries().map { it.block }.filterNot { it in core }
                }
                visible.forEach(::addBlock)
                if (section == Section.SKY) renderBlock(WeatherBlock.DAILY, daily)
            }
        }
        subscribe()
    }

    private fun renderHeader() = with(ui) {
        if (!::header.isInitialized) return
        header.removeAllViews()
        val source = selection.source
        val place = shownPlace
        val title = place?.name ?: if (selection.legacy) "默认天气" else "选择天气地点"
        val titleView = label(title, 31f, bold = true)
        val actions = row()
        actions.addView(titleView, LayoutParams(0, -2, 1f))
        if (place != null && source != null) {
            actions.addView(button("地点管理") { settings.showPlace(place.id) }, LayoutParams(-2, -2).apply { marginEnd = dp(6) })
            if (source !is WeatherSourceRef.HomeAssistant) {
                actions.addView(button("刷新可见天气", action = ::refresh), LayoutParams(-2, -2))
            }
        }
        header.addView(actions)
        if (source != null && place != null) {
            header.addView(row().apply {
                addView(button(weatherSourceName(source)) { chooseSource(place) }, LayoutParams(-2, -2))
                addView(label(place.zone.id, 16f, subdued = true), LayoutParams(0, -2, 1f))
                if (source !is WeatherSourceRef.HomeAssistant) {
                    addView(button("和风天气官网") { header.openWeatherLink(QWEATHER_WEBSITE) }, LayoutParams(-2, -2))
                }
            })
        }
        if (::refreshFeedback.isInitialized) {
            (refreshFeedback.parent as? LinearLayout)?.removeView(refreshFeedback)
            header.addView(refreshFeedback)
        }
    }

    private fun chooseSource(place: WeatherPlace) {
        val labels = place.sources.map { source ->
            val snapshot = repository.cachedCurrent(place.id, source)
            val status = snapshot?.let(::weatherStatus) ?: when (repository.capabilities(place.id, source).blocks[WeatherBlock.CURRENT]) {
                WeatherState.UNCONFIGURED -> "未配置"
                WeatherState.UNAUTHORIZED -> "认证或权限失败"
                WeatherState.UNSUPPORTED -> "不支持"
                WeatherState.BUDGET -> "请求受限"
                WeatherState.ERROR -> "暂不可用"
                else -> "选择后查看"
            }
            listOfNotNull(weatherSourceName(source), status,
                snapshot?.fetchedAt?.atZone(place.zone)?.format(weatherUpdateFormat)).joinToString(" · ")
        }
        show(ui.dialogBuilder().setTitle("选择查看来源").setSingleChoiceItems(
            labels.toTypedArray(), place.sources.indexOf(selection.source),
        ) { dialog, index ->
            dialog.dismiss()
            select(WeatherCenterSelection(place.id, place.sources[index]))
        })
    }

    private fun addBlock(block: WeatherBlock) {
        sections[block] = ui.card().also(detail::addView)
        renderBlock(block, snapshots.values.firstOrNull { it.query.block == block })
    }

    private val core get() = setOf(WeatherBlock.CURRENT, WeatherBlock.DAILY, WeatherBlock.ALERTS)

    private fun queries(): List<WeatherQuery> = buildList {
        val ha = selection.source is WeatherSourceRef.HomeAssistant
        add(WeatherQuery(WeatherBlock.CURRENT))
        add(WeatherQuery(WeatherBlock.DAILY, days = if (ha) 7 else 10))
        if (!ha) add(WeatherQuery(WeatherBlock.ALERTS))
        when (section) {
            Section.FORECAST -> {
                add(WeatherQuery(WeatherBlock.HOURLY, hours = if (ha) 24 else range))
                if (ha) add(WeatherQuery(WeatherBlock.TWICE_DAILY))
            }
            Section.RAIN -> add(WeatherQuery(WeatherBlock.MINUTELY))
            Section.AIR -> listOf(WeatherBlock.AIR_CURRENT, WeatherBlock.AIR_HOURLY, WeatherBlock.AIR_DAILY)
                .forEach { add(WeatherQuery(it)) }
            Section.INDICES -> add(WeatherQuery(WeatherBlock.INDICES, indexTypes = setOf(1, 2, 3, 4, 5, 7)))
            Section.SKY -> astronomyBlocks.filter(::needsAstronomy).forEach { add(WeatherQuery(it)) }
            Section.HISTORY -> historyDate?.let { add(WeatherQuery(WeatherBlock.HISTORY, date = it)) }
        }
    }

    private fun subscribe() {
        if (!active || !isAttachedToWindow) return
        if (selection.legacy) {
            if (!listeningLegacy) {
                listeningLegacy = true
                backend.addListener(legacyListener)
            }
            return
        }
        val place = shownPlace ?: return
        val source = selection.source ?: return
        val token = generation
        val desired = queries()
        (stops.keys - desired.toSet()).forEach { query ->
            stops.remove(query)?.close()
            snapshots.remove(query)
        }
        desired.filterNot { it in stops }.forEach { query ->
            val sub = repository.observe(place.id, source, query, automatic = query.block != WeatherBlock.HISTORY) { snapshot ->
                if (token != generation || query !in queries()) return@observe
                val previous = snapshots.put(query, snapshot)
                if (query.block == WeatherBlock.ALERTS && previous?.data != snapshot.data) alertPopup?.dismiss()
                when (query.block) {
                    WeatherBlock.CURRENT -> {
                        renderHero()
                        cities[place.id]?.let { renderCity(it, snapshot) }
                    }
                    WeatherBlock.DAILY -> {
                        renderHero()
                        renderBlock(query.block, snapshot)
                        if (section == Section.SKY) post { if (token == generation) subscribe() }
                    }
                    else -> renderBlock(query.block, snapshot)
                }
            }
            stops[query] = sub
            if (query.block == WeatherBlock.HISTORY && requestedHistory != query.date) {
                post {
                    if (token == generation && stops[query] === sub && requestedHistory != query.date) {
                        requestedHistory = query.date
                        if (snapshots[query]?.data == null) feedback(centerRefreshText(sub.refresh(), zone))
                    }
                }
            }
        }
    }

    private fun release(clear: Boolean = true) {
        generation++
        cityScroll.removeCallbacks(updatePreviews)
        previews.values.forEach { it.close() }
        previews.clear()
        stops.values.forEach { it.close() }
        stops.clear()
        if (clear) snapshots.clear()
        if (listeningLegacy) backend.removeListener(legacyListener)
        listeningLegacy = false
    }

    private fun refresh() {
        feedback(stops.entries.joinToString("\n") { (query, subscription) ->
            "${ui.blockTitle(query.block)} · ${centerRefreshText(subscription.refresh(), zone)}"
        })
    }

    private fun feedback(message: String) {
        refreshFeedback.text = message
        refreshFeedback.visibility = if (message.isEmpty()) GONE else VISIBLE
    }

    private fun renderHero() = with(ui) {
        val c = current.conditions()
        val today = daily.today(Instant.now())?.conditions
        renderCurrent(hero, c, range(today?.minTemperature, today?.maxTemperature, "°C"))
        hero.addView(metadata(current))
    }

    private fun renderLegacy(data: DashboardData) = with(ui) {
        if (!::hero.isInitialized) return
        val old = data.weather
        val c = old.conditions ?: WeatherConditions(code = old.condition,
            temperature = old.temperature?.toDouble()?.takeIf { old.temperatureUnit == "°C" },
            humidity = old.humidity?.toDouble())
        renderCurrent(hero, c, range(old.low, old.high, old.temperatureUnit), old.temperature.value(old.temperatureUnit))
        hero.addView(label(listOfNotNull(old.name, if (backend.settings.mode == BackendMode.DEMO) "演示天气"
            else "Home Assistant · 默认天气", old.status(),
            old.updatedAt?.atZone(zone)?.format(weatherUpdateFormat)).joinToString(" · "), 15f, subdued = true))
        val lines = old.lines(Instant.now(), 10)
        lines.forEach { item -> hero.addView(row(icon(item.sky, 32),
            label(listOfNotNull(item.label, item.sky.description, item.temperature, item.rain).joinToString(" · "), 17f))) }
    }

    private fun renderBlock(block: WeatherBlock, snapshot: WeatherSnapshot?) {
        if (block == WeatherBlock.DAILY && section == Section.SKY) {
            astronomyBlocks.forEach { target -> renderBlock(target, snapshots.values.firstOrNull { it.query.block == target }) }
            return
        }
        val box = sections[block] ?: return
        val dailyPart = if (block in astronomyBlocks) dailyAstronomy(block) else null
        val reuseDaily = dailyPart != null && (!needsAstronomy(block) || snapshot?.data == null)
        val shown = if (reuseDaily) daily?.copy(data = WeatherData.Astronomy(checkNotNull(dailyPart))) else snapshot
        ui.render(box, block, shown, range, zone) { builder ->
            val popup = show(builder)
            if (block == WeatherBlock.ALERTS) alertPopup = popup
        }
        if (reuseDaily && needsAstronomy(block) && snapshot?.data == null) box.addView(ui.metadata(snapshot))
    }

    private val astronomyBlocks = listOf(WeatherBlock.SUN, WeatherBlock.MOON)
    private val lunarEvents = setOf(AstronomicalEvent.MOONRISE, AstronomicalEvent.MOONSET, AstronomicalEvent.MOON_TRANSIT,
        AstronomicalEvent.MOON_UNDERFOOT)

    private fun dailyAstronomy(block: WeatherBlock): WeatherAstronomy? {
        val data = daily.today(Instant.now())?.astronomy ?: return null
        val moon = block == WeatherBlock.MOON
        return data.copy(
            events = data.events.filterKeys { (it in lunarEvents) == moon },
            localEvents = data.localEvents.filterKeys { (it in lunarEvents) == moon },
            phase = data.phase.takeIf { moon },
            hourlyPhases = data.hourlyPhases.takeIf { moon }.orEmpty(),
        ).takeIf { it.events.isNotEmpty() || it.localEvents.isNotEmpty() || it.phase != null || it.hourlyPhases.isNotEmpty() }
    }

    private fun needsAstronomy(block: WeatherBlock): Boolean {
        val data = daily ?: return false
        if (data.state in setOf(WeatherState.LOADING, WeatherState.UNLOADED) && data.data == null) return false
        val part = dailyAstronomy(block) ?: return true
        val events = if (block == WeatherBlock.MOON) listOf(AstronomicalEvent.MOONRISE, AstronomicalEvent.MOONSET)
            else listOf(AstronomicalEvent.SUNRISE, AstronomicalEvent.SUNSET)
        return events.any { it !in part.events && it !in part.localEvents } ||
            (block == WeatherBlock.MOON && part.phase == null && part.hourlyPhases.isEmpty())
    }

    private fun show(builder: AlertDialog.Builder): AlertDialog {
        val popup = builder.setNegativeButton("关闭", null).show()
        popups += popup
        popup.setOnDismissListener {
            popups -= popup
            if (alertPopup === popup) alertPopup = null
        }
        return popup
    }
}
