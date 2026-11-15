package com.dormpanel.app.dashboard.card

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.text.InputFilter
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.dormpanel.app.appearance.*
import com.dormpanel.app.data.Availability
import com.dormpanel.app.data.DashboardDataSource
import com.dormpanel.app.ui.hidePanelSystemBars
import com.dormpanel.app.weather.*
import java.time.Instant
import java.time.ZoneId

internal abstract class WeatherPanel(
    context: Context,
    protected val appearance: AppearanceController,
    title: String,
    private val widthDp: Int,
) : Dialog(context) {
    protected val root = column()
    protected val heading = label(28f, title)
    protected val actions = LinearLayout(context).apply { gravity = Gravity.END }
    private val theme: (AppearanceState) -> Unit = ::style

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        root.setPadding(24.dp, 14.dp, 24.dp, 12.dp)
        root.addView(heading)
        setContentView(root)
    }

    protected fun body(view: View) {
        root.addView(ScrollView(context).apply { addView(view) },
            LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(actions)
    }

    override fun onStart() {
        super.onStart()
        val metrics = context.resources.displayMetrics
        window?.apply {
            hidePanelSystemBars()
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(minOf(widthDp.dp, metrics.widthPixels - 48.dp), minOf(680.dp, metrics.heightPixels - 56.dp))
        }
        matchActivityBrightness()
        appearance.addListener(theme)
    }

    override fun onStop() {
        appearance.removeListener(theme)
        super.onStop()
    }

    protected open fun style(state: AppearanceState) {
        val palette = PanelPalette.forMode(state.themeMode)
        root.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(palette.background)
            setStroke(1.dp, palette.secondary)
            cornerRadius = 22.dp.toFloat()
        }
        applyAppearanceTree(root, state)
    }

    protected fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    protected fun button(title: String, action: () -> Unit) = Button(context).apply {
        text = title
        textSize = 18f
        isAllCaps = false
        minHeight = 52.dp
        setOnClickListener { action() }
    }
    protected fun label(size: Float, title: String = "") = TextView(context).apply {
        text = title
        textSize = size
        setPadding(0, 4.dp, 0, 4.dp)
    }
    protected val Int.dp get() = (this * context.resources.displayMetrics.density).toInt()
}

internal class WeatherSourcePicker(
    context: Context,
    appearance: AppearanceController,
    private val repository: WeatherRepository,
    private val haName: (WeatherSourceRef.HomeAssistant) -> String?,
    private val initial: WeatherCardConfiguration,
    private val openSettings: () -> Unit,
    private val editDisplay: Boolean = false,
    private val confirm: (WeatherSourcePicker, WeatherCardConfiguration) -> Unit,
) : WeatherPanel(context, appearance, "选择天气地点", 840) {
    private val choices = column()
    private val status = label(18f)
    private val original = initial as? WeatherCardConfiguration.Bound
    private var selected: WeatherCardConfiguration? = original?.takeIf { editDisplay && it.canSave(repository.places) }
    private var placeId: String? = (selected as? WeatherCardConfiguration.Bound)?.placeId
    private var saving = false
    private var saveFailed = false
    private val alias = EditText(context).apply {
        contentDescription = "卡片名称"
        setSingleLine()
        filters = arrayOf(InputFilter.LengthFilter(80))
    }
    private val metricOptions = listOf(null, WeatherCardMetric.FEELS_LIKE, WeatherCardMetric.HUMIDITY, WeatherCardMetric.WIND)
    private val metricButtons = listOf("自动", "体感温度", "湿度", "风速").map { name ->
        RadioButton(context).apply {
            id = View.generateViewId()
            text = name
            textSize = 18f
            minimumHeight = 52.dp
        }
    }
    private val metric = RadioGroup(context).apply {
        orientation = LinearLayout.HORIZONTAL
        metricButtons.forEach { addView(it, RadioGroup.LayoutParams(0, -2, 1f)) }
    }
    private val preferences = column().apply {
        addView(label(18f, "卡片名称（留空使用地点名称）"))
        addView(alias)
        addView(label(18f, "次要天气指标"))
        addView(metric)
    }
    private var stopPlaces: (() -> Unit)? = null
    private val cancel = button("取消") { dismiss() }
    private val back = button("返回地点") { placeId = null; selected = null; render() }
    private val settingsButton = button("天气服务与地点", openSettings)
    private val select = button("确认") {
        val draft = draft()
        if (draft != null && draft.canSave(repository.places)) confirm(this, draft)
        else status.text = "无法应用所选来源，请检查来源和桌面状态"
    }

    init {
        body(column().apply { addView(status); addView(choices); addView(preferences) })
        root.isFocusableInTouchMode = true
        root.requestFocus()
        actions.addView(cancel)
        actions.addView(back)
        actions.addView(settingsButton)
        actions.addView(select)
        populatePreferences(selected as? WeatherCardConfiguration.Bound)
    }

    override fun onStart() {
        super.onStart()
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        stopPlaces = repository.places.observe(::render)
        render()
    }

    override fun onStop() {
        stopPlaces?.invoke()
        stopPlaces = null
        super.onStop()
    }

    private fun render() {
        choices.removeAllViews()
        val saved = repository.places.places.filterNot { repository.places.isTemporary(it.id) }
        val place = saved.firstOrNull { it.id == placeId }
        if (place == null) {
            heading.text = "选择天气地点"
            status.text = if (saved.isEmpty()) "暂无已保存地点。请在「天气服务与地点」收藏地点或绑定 HA 实体。" else "请选择已保存地点"
            saved.forEach { item -> choices.addView(button("${item.name}   ·   ${item.zone.id}") {
                placeId = item.id
                selected = null
                render()
            }) }
            choices.addView(button("默认天气 · HA Preferred weather / Demo") {
                saveFailed = false
                selected = WeatherCardConfiguration.Default
                selectedText("默认天气")
            })
        } else {
            heading.text = "选择来源 · ${place.name}"
            status.text = "请选择此地点的天气来源"
            place.sources.distinct().forEach { source ->
                val name = sourceLabel(source)
                val state = repository.capabilities(place.id, source).blocks[WeatherBlock.CURRENT]
                val warning = when (state) {
                    WeatherState.UNSUPPORTED -> " · 不支持"
                    WeatherState.UNAUTHORIZED -> " · 认证或权限失败"
                    WeatherState.UNCONFIGURED -> " · 未配置"
                    WeatherState.ERROR -> " · 暂不可用"
                    WeatherState.BUDGET -> " · 请求受限"
                    WeatherState.INVALID_REQUEST -> " · 请求无效"
                    else -> ""
                }
                choices.addView(button(name + warning) {
                    val old = draft() as? WeatherCardConfiguration.Bound ?: original
                    saveFailed = false
                    selected = old?.select(place.id, source) ?: WeatherCardConfiguration.Bound(place.id, source)
                    populatePreferences(selected as WeatherCardConfiguration.Bound)
                    selectedText(name + warning)
                })
            }
        }
        val pending = selected
        if (pending != null && pending.canSave(repository.places)) selectedText(
            if (pending is WeatherCardConfiguration.Bound) sourceLabel(pending.source) else "默认天气")
        else selected = null
        preferences.visibility = if (selected is WeatherCardConfiguration.Bound) View.VISIBLE else View.GONE
        updateSaveControls()
        back.visibility = if (place == null) View.GONE else View.VISIBLE
        style(appearance.state)
    }

    private fun sourceLabel(source: WeatherSourceRef): String {
        val detail = when (source) {
            is WeatherSourceRef.HomeAssistant -> haName(source)
            is WeatherSourceRef.QWeather -> source.locationId ?: "${source.coordinates.latitude}, ${source.coordinates.longitude}"
            is WeatherSourceRef.QWeatherCity -> source.locationId
        }
        return listOfNotNull(weatherSourceName(source), detail).distinct().joinToString(" · ")
    }

    private fun selectedText(name: String) {
        status.text = "已选择 ${repository.places.get(placeId.orEmpty())?.name.orEmpty()}  $name".trimEnd()
        preferences.visibility = if (selected is WeatherCardConfiguration.Bound) View.VISIBLE else View.GONE
        updateSaveControls()
    }

    private fun draft() = when (val config = selected) {
        is WeatherCardConfiguration.Bound -> config.copy(
            displayName = alias.text.toString().trim().ifEmpty { null },
            metric = metricOptions.getOrNull(metricButtons.indexOfFirst { it.id == metric.checkedRadioButtonId }),
        )
        else -> config
    }

    private fun populatePreferences(config: WeatherCardConfiguration.Bound?) {
        alias.hint = config?.placeId?.let(repository.places::get)?.name ?: "地点默认名称"
        alias.setText(config?.displayName.orEmpty())
        metric.check(metricButtons[metricOptions.indexOf(config?.metric)].id)
    }

    fun showSaveState(inProgress: Boolean, failed: Boolean = false) {
        saving = inProgress
        saveFailed = failed
        updateSaveControls()
    }

    fun showRejected() {
        showSaveState(false)
        status.text = "无法应用所选配置，请检查来源和桌面状态"
    }

    private fun updateSaveControls() {
        setCancelable(!saving)
        for (index in 0 until choices.childCount) choices.getChildAt(index).isEnabled = !saving
        back.isEnabled = !saving
        settingsButton.isEnabled = !saving
        alias.isEnabled = !saving
        metricButtons.forEach { it.isEnabled = !saving }
        cancel.isEnabled = !saving
        select.isEnabled = selected != null && !saving
        select.text = if (saveFailed) "重试保存" else if (editDisplay) "保存" else "确认"
        if (saving) status.text = "正在保存卡片设置…"
        else if (saveFailed) status.text = "卡片设置未保存。当前修改已保留，可重试保存。"
    }
}

internal class WeatherDetailDialog(
    context: Context,
    appearance: AppearanceController,
    source: DashboardDataSource,
    private val repository: WeatherRepository,
    private val configuration: WeatherCardConfiguration,
    private val changeBinding: () -> Unit,
    private val more: (() -> Unit)?,
    private val editDisplay: (() -> Unit)? = null,
) : WeatherPanel(context, appearance, "天气详情", 1080) {
    private val feed = WeatherCardFeed(source, repository, ::render)
    private val provider = label(18f)
    private val officialLink = label(18f, "和风天气官网").apply {
        paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
        isFocusable = true
        setOnClickListener { openWeatherLink(QWEATHER_WEBSITE) }
    }
    private val currentAttribution = label(16f)
    private val icon = WeatherIconView(context)
    private val reading = label(36f)
    private val current = label(19f)
    private val feedback = label(17f)
    private val forecasts = listOf(WeatherBlock.HOURLY, WeatherBlock.DAILY, WeatherBlock.TWICE_DAILY)
    private val sections = (forecasts + listOf(WeatherBlock.AIR_CURRENT, WeatherBlock.ALERTS))
        .associateWith { label(18f) to label(16f) }
    private val refresh = button("刷新天气") {
        feedback.text = feed.refresh().entries.groupBy { it.value.reason ?: "已提交刷新" }
            .entries.joinToString("\n") { (message, items) ->
                items.joinToString("、") { blockName(it.key) } + " · " + message +
                    (items.maxOfOrNull { it.value.retryAt ?: Instant.EPOCH }?.takeIf { it != Instant.EPOCH }?.let {
                        " · 最早 " + it.atZone(feed.place?.zone ?: ZoneId.systemDefault()).format(weatherUpdateFormat)
                    } ?: "")
            }
    }

    init {
        val left = column().apply {
            setPadding(0, 0, 24.dp, 0)
            addView(provider)
            addView(officialLink)
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(icon, LinearLayout.LayoutParams(56.dp, 56.dp).apply { marginEnd = 12.dp })
                addView(reading, LinearLayout.LayoutParams(0, -2, 1f))
            })
            addView(current)
            addView(currentAttribution)
            addView(feedback)
        }
        val right = column().apply { sections.values.forEach { (data, attribution) -> addView(data); addView(attribution) } }
        body(LinearLayout(context).apply {
            addView(left, LinearLayout.LayoutParams(0, -2, 1f))
            addView(right, LinearLayout.LayoutParams(0, -2, 1.25f))
        })
        actions.addView(button("关闭") { dismiss() })
        actions.addView(button("切换天气", changeBinding))
        editDisplay?.let { actions.addView(button("卡片显示设置", it)) }
        actions.addView(refresh)
        more?.let { actions.addView(button("查看天气", it)) }
    }

    override fun onStart() {
        super.onStart()
        val bound = configuration as? WeatherCardConfiguration.Bound
        val blocks = when (bound?.source) {
            null -> emptySet()
            is WeatherSourceRef.HomeAssistant -> setOf(WeatherBlock.CURRENT, WeatherBlock.HOURLY, WeatherBlock.DAILY, WeatherBlock.TWICE_DAILY)
            else -> setOf(WeatherBlock.CURRENT, WeatherBlock.HOURLY, WeatherBlock.DAILY, WeatherBlock.AIR_CURRENT, WeatherBlock.ALERTS)
        }
        feed.bind(configuration, blocks)
        render()
    }

    override fun onStop() {
        feed.close()
        super.onStop()
    }

    override fun style(state: AppearanceState) {
        super.style(state)
        val accent = PanelPalette.forMode(state.themeMode).accent
        icon.tint(accent)
        officialLink.setTextColor(accent)
        currentAttribution.setLinkTextColor(accent)
        sections.values.forEach { (_, attribution) -> attribution.setLinkTextColor(accent) }
    }

    private fun render() {
        val legacy = feed.legacy
        val validLegacy = legacy?.takeIf { it.availability != Availability.UNAVAILABLE }
        val snapshot = feed[WeatherBlock.CURRENT]
        val daily = feed[WeatherBlock.DAILY]
        val now = Instant.now()
        val conditions = snapshot.conditions() ?: validLegacy?.conditions
        val today = daily.today(now)?.conditions
        val weather = sky(conditions?.code ?: validLegacy?.condition, conditions?.description)
        val zone = feed.place?.zone ?: ZoneId.systemDefault()
        val unit = validLegacy?.temperatureUnit ?: "°C"
        val temp = if (validLegacy != null) validLegacy.temperature.value(unit) else conditions?.temperature.value(unit)
        heading.text = feed.place?.name ?: legacy?.name ?: feed.bound?.displayName ?: "天气详情"
        val source = feed.bound?.source
        officialLink.visibility = if (source != null && source !is WeatherSourceRef.HomeAssistant) View.VISIBLE else View.GONE
        provider.text = listOfNotNull(
            feed.bound?.let { weatherSourceName(it.source) } ?: legacy?.let {
                it.entityId?.let { entity -> "Home Assistant · $entity" } ?: if (it.name != null) "Demo" else "HA Preferred weather"
            },
            (snapshot?.dataAt ?: snapshot?.fetchedAt ?: legacy?.updatedAt)?.atZone(zone)?.format(weatherUpdateFormat)
                ?.let { "更新 $it" },
        ).joinToString("\n")
        reading.text = listOfNotNull(temp, weather.description.takeIf { !feed.invalid }).joinToString("  ")
        icon.symbol = weather.symbol
        icon.visibility = if (feed.invalid) View.GONE else View.VISIBLE
        current.text = listOfNotNull(
            when {
                feed.invalid -> "地点或来源已失效，请重新选择"
                legacy != null -> legacy.status()
                else -> weatherStatus(snapshot)
            },
            snapshot?.error,
            range(today?.minTemperature ?: validLegacy?.low, today?.maxTemperature ?: validLegacy?.high, unit),
            conditions?.metric(WeatherCardMetric.FEELS_LIKE),
            conditions?.metric(WeatherCardMetric.HUMIDITY) ?: validLegacy?.humidity.value("%")?.let { "湿度 $it" },
            conditions?.metric(WeatherCardMetric.WIND),
            conditions?.pressure.value(" hPa")?.let { "气压 $it" },
            conditions?.visibility?.div(1000).value(" km")?.let { "能见度 $it" },
            conditions?.uvIndex.value("")?.let { "紫外线 $it" },
        ).joinToString("\n")
        refresh.isEnabled = feed.place != null
        currentAttribution.showWeatherAttribution(snapshot?.attribution)
        sections.forEach { (block, views) ->
            val (label, attribution) = views
            val part = feed[block]
            attribution.showWeatherAttribution(part?.attribution)
            val legacyBlock = when (legacy?.forecastType) {
                "daily" -> WeatherBlock.DAILY
                "hourly" -> WeatherBlock.HOURLY
                "twice_daily" -> WeatherBlock.TWICE_DAILY
                else -> null
            }
            val legacyLines = if (block == legacyBlock) validLegacy?.lines(now, 5).orEmpty() else emptyList()
            label.visibility = if (part != null || legacyLines.isNotEmpty()) View.VISIBLE else View.GONE
            if (label.visibility == View.VISIBLE) {
                val records = when (val data = part?.data) {
                    is WeatherData.Alerts -> data.entries.mapNotNull {
                        listOfNotNull(it.headline ?: it.eventName, it.description ?: it.instruction).joinToString("\n").ifBlank { null }
                    }.ifEmpty { listOf("当前无有效预警") }
                    is WeatherData.Air -> data.entries.firstOrNull()?.indexes.orEmpty().map {
                        listOfNotNull(it.name?.takeIf(String::isNotBlank) ?: "AQI", it.display ?: it.aqi.value(""),
                            it.category, it.primaryPollutant?.name).joinToString(" · ")
                    }
                    else -> (legacyLines.ifEmpty { part.forecastLines(now, if (more == null) { if (block == WeatherBlock.HOURLY) 12 else 7 } else { if (block == WeatherBlock.HOURLY) 6 else 5 }) }).map {
                        listOfNotNull(it.label, it.sky.description, it.temperature, it.rain).joinToString("  ")
                    }
                }
                val header = blockName(block)
                val text = (listOfNotNull(header, part?.let(::weatherStatus), part?.error) +
                    records).joinToString("\n")
                label.text = android.text.SpannableString(text).apply {
                    setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, header.length, 0)
                    setSpan(android.text.style.RelativeSizeSpan(1.2f), 0, header.length, 0)
                }
            }
        }
    }

    private fun blockName(block: WeatherBlock) = when (block) {
        WeatherBlock.CURRENT -> "实时天气"
        WeatherBlock.HOURLY -> "逐小时预报"
        WeatherBlock.DAILY -> "每日预报"
        WeatherBlock.TWICE_DAILY -> "昼夜预报"
        WeatherBlock.ALERTS -> "天气预警"
        WeatherBlock.AIR_CURRENT -> "空气质量"
        else -> block.name
    }
}
