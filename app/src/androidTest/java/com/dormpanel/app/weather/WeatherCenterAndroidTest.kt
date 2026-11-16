package com.dormpanel.app.weather

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.IsolatedDashboardRule
import com.dormpanel.app.MainActivity
import com.dormpanel.app.R
import com.dormpanel.app.appearance.ThemeMode
import com.dormpanel.app.dashboard.DashboardViewModel
import com.dormpanel.app.ha.MainHaScheduler
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.containsString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class WeatherCenterAndroidTest {
    @get:Rule val dashboard = IsolatedDashboardRule()

    @Test fun controlCenterOpensWeatherAndOfflineMapFillsCoordinates() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.page_container)).perform(swipeDown())
            onView(withText("天气中心")).perform(click())
            onView(withText("‹ 返回")).check(matches(isDisplayed()))
            onView(withText("添加地点")).perform(click())
            onView(withText("在地图上选点")).perform(scrollTo(), click())
            onView(withText(containsString("不含道路、校园或建筑细节"))).check(matches(isDisplayed()))
            val coordinates = onView(withText(containsString("纬度 33.0000")))
            for (attempt in 1..15) {
                onView(withText("选地图中心")).perform(click())
                if (runCatching { coordinates.check(matches(isDisplayed())) }.isSuccess) break
                android.os.SystemClock.sleep(40)
            }
            coordinates.check(matches(isDisplayed()))
            capture("pr80-map")
            onView(withText("使用坐标")).perform(click())
            onView(allOf(withClassName(containsString("EditText")), withText("33.0000"))).check(matches(isDisplayed()))
            onView(allOf(withClassName(containsString("EditText")), withText("105.0000"))).check(matches(isDisplayed()))
            val name = "地图选点测试-" + java.util.UUID.randomUUID().toString().take(6)
            onView(withHint("地点名称")).perform(replaceText(name), closeSoftKeyboard())
            onView(withHint("地点时区（例如 Asia/Shanghai）")).perform(replaceText("Asia/Shanghai"), closeSoftKeyboard())
            try {
                onView(withText("保存地点")).perform(scrollTo(), click())
                scenario.onActivity { activity ->
                    val view = activity.findViewById<ViewGroup>(R.id.page_container).getChildAt(0) as WeatherCenterView
                    val source = view.selection.source as WeatherSourceRef.QWeather
                    assertEquals(WeatherCoordinates("33.0000".toBigDecimal(), "105.0000".toBigDecimal()), source.coordinates)
                    val places = ViewModelProvider(activity)[DashboardViewModel::class.java].dataSource.weatherRepository.places
                    assertEquals(name, places.get(view.selection.placeId!!)?.name)
                }
                onView(withText("‹ 返回")).perform(click())
                scenario.onActivity { activity ->
                    assertTrue(activity.findViewById<ViewGroup>(R.id.page_container).getChildAt(0) is com.dormpanel.app.ui.ControlCenterView)
                }
            } finally {
                scenario.onActivity { activity ->
                    val places = ViewModelProvider(activity)[DashboardViewModel::class.java].dataSource.weatherRepository.places
                    places.places.firstOrNull { it.name == name }?.let { places.delete(it.id) }
                }
            }
        }
    }

    @Test fun unreadableMapLeavesManualCoordinateEntryAvailable() {
        val name = "手动坐标测试-" + java.util.UUID.randomUUID().toString().take(6)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var dialog: WeatherSettingsDialog
            scenario.onActivity { activity ->
                val context = object : android.content.ContextWrapper(activity) {
                    override fun getApplicationContext(): android.content.Context = this
                    override fun getAssets(): android.content.res.AssetManager = throw java.io.FileNotFoundException()
                }
                val services = ViewModelProvider(activity)[DashboardViewModel::class.java].dataSource.weatherServices
                dialog = WeatherSettingsDialog(context, services)
                dialog.edit(null)
            }
            try {
                onView(withText("在地图上选点")).perform(scrollTo(), click())
                val fallback = onView(withText(containsString("底图无法读取")))
                for (attempt in 1..15) {
                    if (runCatching { fallback.check(matches(isDisplayed())) }.isSuccess) break
                    android.os.SystemClock.sleep(40)
                }
                fallback.check(matches(isDisplayed()))
                onView(withText("手动输入")).perform(click())
                onView(withHint("地点名称")).perform(replaceText(name))
                onView(withHint("地点时区（例如 Asia/Shanghai）")).perform(replaceText("Asia/Shanghai"))
                onView(withHint("纬度 −90 至 90")).perform(replaceText("30.5928123456789"))
                onView(withHint("经度 −180 至 180")).perform(replaceText("114.3053919876543"), closeSoftKeyboard())
                onView(withText("保存地点")).perform(scrollTo(), click())
                scenario.onActivity { activity ->
                    val places = ViewModelProvider(activity)[DashboardViewModel::class.java].dataSource.weatherRepository.places
                    val saved = places.places.single { it.name == name }.sources.single() as WeatherSourceRef.QWeather
                    assertEquals(WeatherCoordinates("30.5928123456789".toBigDecimal(), "114.3053919876543".toBigDecimal()),
                        saved.coordinates)
                }
            } finally {
                scenario.onActivity { activity ->
                    dialog.dismiss()
                    val places = ViewModelProvider(activity)[DashboardViewModel::class.java].dataSource.weatherRepository.places
                    places.places.firstOrNull { it.name == name }?.let { places.delete(it.id) }
                }
            }
        }
    }

    @Test fun offlineAstronomyShowsSameDayCacheAndUpdatesAtLocalMidnight() {
        val zone = ZoneId.of("Asia/Shanghai")
        val today = java.time.LocalDate.of(2026, 11, 15)
        val tomorrow = today.plusDays(1)
        val midnight = tomorrow.atStartOfDay(zone).toInstant()
        val source = WeatherSourceRef.QWeatherCity("101200101")
        val places = WeatherPlaceStore("[]") { true }
        places.save(WeatherPlace("wuhan", "武汉", zone, listOf(source)))
        val rise = AstronomicalEvent.MOONRISE
        val set = AstronomicalEvent.MOONSET
        val forecast = WeatherData.Forecast(listOf(
            WeatherForecast(WeatherPeriod(date = today), WeatherConditions(), astronomy =
                WeatherAstronomy(today, emptyMap(), "当晚月相", emptyList(), mapOf(rise to today.atTime(17, 10)))),
            WeatherForecast(WeatherPeriod(date = tomorrow), WeatherConditions(), astronomy =
                WeatherAstronomy(tomorrow, emptyMap(), "次日月相", emptyList(),
                    mapOf(rise to tomorrow.atTime(18, 10), set to tomorrow.atTime(7, 10)))),
        ))
        val moon = WeatherData.Astronomy(WeatherAstronomy(today, emptyMap(), null, emptyList(),
            mapOf(set to tomorrow.atTime(6, 5))))
        var starts = 0
        val provider = object : WeatherProvider {
            override fun plan(source: WeatherSourceRef, zone: ZoneId, query: WeatherQuery, now: Instant): WeatherPlan {
                val dated = if (query.block == WeatherBlock.MOON) query.copy(date = now.atZone(zone).toLocalDate()) else query
                val key = when {
                    dated.block == WeatherBlock.DAILY -> "daily"
                    dated.block == WeatherBlock.MOON && dated.date == today -> "moon"
                    else -> dated.toString()
                }
                return WeatherPlan(key, "wuhan", dated, { _, _ ->
                    ParsedWeather(when (key) { "daily" -> forecast; "moon" -> moon; else -> null },
                        WeatherAttribution(sources = listOf(if (key == "moon") "月亮缓存来源" else "每日缓存来源")))
                }, {
                    starts++
                    {}
                })
            }
        }
        val cache = object : WeatherCache {
            override fun read(group: String, key: String, done: (WeatherCacheRecord?) -> Unit) =
                done(when (key) {
                    "daily" -> WeatherCacheRecord(JSONObject(), midnight.minusSeconds(3 * 3600))
                    "moon" -> WeatherCacheRecord(JSONObject(), midnight.minusSeconds(25 * 3600))
                    else -> null
                })
            override fun write(group: String, key: String, record: WeatherCacheRecord, done: (Boolean) -> Unit) = done(true)
            override fun clear(groups: Set<String>?, done: (Boolean) -> Unit) = done(true)
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var view: WeatherCenterView
            lateinit var repository: WeatherRepository
            var boundaryAt: Long? = null
            fun now(): Instant = boundaryAt?.let { midnight.plusMillis(android.os.SystemClock.elapsedRealtime() - it) }
                ?: midnight.minusSeconds(60)
            scenario.onActivity { activity ->
                val vm = ViewModelProvider(activity)[DashboardViewModel::class.java]
                repository = WeatherRepository(places, { provider }, cache, MainHaScheduler(), ::now).apply { onNetworkLost() }
                view = WeatherCenterView(activity, vm.dataSource, vm.appearance, WeatherCenterSelection("wuhan", source),
                    {}, repository, ::now)
                activity.findViewById<FrameLayout>(R.id.page_container).apply {
                    removeAllViews()
                    addView(view, FrameLayout.LayoutParams(-1, -1))
                }
            }
            try {
                scenario.onActivity { boundaryAt = android.os.SystemClock.elapsedRealtime() + 7000 }
                onView(withText("日月天文")).perform(scrollTo(), click())
                onView(withText(containsString("当晚月相"))).perform(scrollTo()).check(matches(isDisplayed()))
                onView(withText("月落  06:05")).perform(scrollTo()).check(matches(isDisplayed()))
                onView(withText("月亮缓存来源")).perform(scrollTo()).check(matches(isDisplayed()))
                onView(allOf(withText(containsString("离线 · 已过期")), hasSibling(withText("月亮缓存来源"))))
                    .perform(scrollTo()).check(matches(isDisplayed()))
                val after = onView(withText(containsString("次日月相")))
                val deadline = android.os.SystemClock.elapsedRealtime() + 8500
                while (android.os.SystemClock.elapsedRealtime() < deadline) {
                    if (runCatching { after.check(matches(withEffectiveVisibility(Visibility.VISIBLE))) }.isSuccess) break
                    android.os.SystemClock.sleep(150)
                }
                after.perform(scrollTo()).check(matches(isDisplayed()))
                onView(withText(containsString("当晚月相"))).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
                onView(withText("月落  06:05")).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
                assertEquals(0, starts)
            } finally {
                scenario.onActivity {
                    (view.parent as? ViewGroup)?.removeView(view)
                    repository.close()
                }
            }
        }
    }

    @Test fun fakeWeatherRendersForecastAndOnlyOpensSpecialtyDemandOnSelection() {
        val places = WeatherPlaceStore("[]") { true }
        val zone = ZoneId.of("Asia/Shanghai")
        val wuhan = WeatherSourceRef.QWeatherCity("101200101")
        val beijing = WeatherSourceRef.QWeatherCity("101010100")
        places.save(WeatherPlace("wuhan", "武汉", zone, listOf(wuhan)))
        places.save(WeatherPlace("beijing", "北京", zone, listOf(beijing)))
        val shanghai = WeatherSourceRef.QWeatherCity("101020100")
        places.save(WeatherPlace("shanghai", "上海", zone, listOf(shanghai)))
        val far = (0..10).map { index ->
            val source = WeatherSourceRef.QWeatherCity("1011001%02d".format(java.util.Locale.ROOT, index))
            places.save(WeatherPlace("extra-$index", "列表测试 $index", zone, listOf(source)))
            source
        }
        var finishWuhanDaily: (() -> Unit)? = null
        var finishBeijingSun: (() -> Unit)? = null
        val started = mutableListOf<Pair<WeatherSourceRef, WeatherQuery>>()
        val stopped = mutableListOf<Pair<WeatherSourceRef, WeatherQuery>>()
        val provider = object : WeatherProvider {
            override fun plan(source: WeatherSourceRef, zone: ZoneId, query: WeatherQuery, now: Instant): WeatherPlan {
                val temperature = when (source) {
                    wuhan -> 23.0
                    shanghai -> 17.0
                    else -> 8.0
                }
                val conditions = WeatherConditions("100", temperature = temperature, feelsLike = temperature - 2)
                val date = now.atZone(zone).toLocalDate()
                val data = when (query.block) {
                    WeatherBlock.CURRENT -> WeatherData.Current(conditions)
                    WeatherBlock.HOURLY -> WeatherData.Forecast((0 until query.hours).filterNot { it == 4 }.map { hour ->
                        WeatherForecast(WeatherPeriod(start = now.plusSeconds(hour * 3600L)),
                            conditions.copy(temperature = if (hour == 3) null else temperature + hour % 5,
                                precipitation = if (hour == 2) null else (hour % 7) * .1,
                                precipitationProbability = if (hour == 3) null else (hour % 10) * 10.0,
                                windSpeed = 4.0 + hour % 3, windDirection = "北风"))
                    })
                    WeatherBlock.DAILY -> WeatherData.Forecast(List(10) { day ->
                        WeatherForecast(WeatherPeriod(date = date.plusDays(day.toLong())),
                            WeatherConditions(minTemperature = temperature - 4 - day, maxTemperature = temperature + day),
                            day = WeatherForecast(WeatherPeriod(isDaytime = true), conditions),
                            night = WeatherForecast(WeatherPeriod(isDaytime = false), conditions.copy(code = "150")),
                            astronomy = if (day == 0 && source in setOf(wuhan, shanghai)) WeatherAstronomy(date, emptyMap(),
                                if (source == wuhan) "满月" else "新月", emptyList(), buildMap {
                                    put(AstronomicalEvent.SUNRISE, date.atTime(6, 17))
                                    put(AstronomicalEvent.SUNSET, date.atTime(17, 19))
                                    put(AstronomicalEvent.MOONRISE, date.atTime(14, 10))
                                    if (source == wuhan) put(AstronomicalEvent.MOONSET, date.atTime(3, 20))
                                }) else null)

                    })
                    WeatherBlock.MINUTELY -> WeatherData.Minutely("测试降雨摘要", List(24) { step ->
                        MinutePrecipitation(now.plusSeconds(step * 300L), if (step < 3) 0.0 else .2, "rain")
                    })
                    WeatherBlock.ALERTS -> WeatherData.Alerts(emptyList())
                    WeatherBlock.AIR_CURRENT, WeatherBlock.AIR_HOURLY, WeatherBlock.AIR_DAILY -> {
                        val index = AirIndex("cn-mee", "中国标准", 42.0, "42", "1", "优", null, null,
                            AirHealth("适合户外活动", "保持通风", null))
                        val pollutant = AirPollutant("pm2p5", "PM2.5", null, 19.0, "µg/m³", emptyList())
                        val rows = if (query.block == WeatherBlock.AIR_CURRENT) 1 else 3
                        WeatherData.Air(List(rows) {
                            val period = if (query.block == WeatherBlock.AIR_DAILY) WeatherPeriod(date = date.plusDays(it.toLong()))
                                else WeatherPeriod(start = now.plusSeconds(it * 3600L))
                            AirQuality(period, listOf(index), listOf(pollutant), emptyList())
                        })
                    }
                    WeatherBlock.INDICES -> WeatherData.Indices(listOf(
                        LifestyleIndex(date, "3", "穿衣指数", "2", "舒适", "建议穿长袖衬衫"),
                    ))
                    WeatherBlock.SUN -> WeatherData.Astronomy(WeatherAstronomy(date,
                        mapOf(AstronomicalEvent.SUNRISE to date.atTime(6, 30).atZone(zone).toInstant()), null, emptyList()))
                    WeatherBlock.MOON -> WeatherData.Astronomy(WeatherAstronomy(date, emptyMap(), "上弦月", emptyList()))
                    WeatherBlock.HISTORY -> WeatherData.History(
                        WeatherForecast(WeatherPeriod(date = query.date), conditions), emptyList(),
                    )
                    WeatherBlock.TWICE_DAILY -> null
                }
                val parsed = ParsedWeather(data, WeatherAttribution(sources = listOf("测试数据归因")))
                return WeatherPlan("$source/$query", source.toString(), query, { _, _ -> parsed }, { emit ->
                    started += source to query
                    val send = { emit(WeatherResult.Data(JSONObject(), parsed, zone)) }
                    when {
                        source == wuhan && query.block == WeatherBlock.DAILY && finishWuhanDaily == null -> finishWuhanDaily = send
                        source == beijing && query.block == WeatherBlock.SUN && finishBeijingSun == null -> finishBeijingSun = send
                        source == shanghai && query.block == WeatherBlock.MOON ->
                            emit(WeatherResult.Failed(WeatherFailure(WeatherState.UNSUPPORTED, "测试月相不可用")))
                        else -> send()
                    }
                    val cancel: () -> Unit = { stopped += source to query }
                    cancel
                }, live = query.block !in setOf(WeatherBlock.HISTORY, WeatherBlock.MOON))
            }
        }
        val cache = object : WeatherCache {
            override fun read(group: String, key: String, done: (WeatherCacheRecord?) -> Unit) = done(null)
            override fun write(group: String, key: String, record: WeatherCacheRecord, done: (Boolean) -> Unit) = done(true)
            override fun clear(groups: Set<String>?, done: (Boolean) -> Unit) = done(true)
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var view: WeatherCenterView
            lateinit var repository: WeatherRepository
            scenario.onActivity { activity ->
                val vm = ViewModelProvider(activity)[DashboardViewModel::class.java]
                repository = WeatherRepository(places, { provider }, cache, MainHaScheduler(), Instant::now)
                view = WeatherCenterView(activity, vm.dataSource, vm.appearance, WeatherCenterSelection("wuhan", wuhan), {}, repository)
                activity.findViewById<FrameLayout>(R.id.page_container).apply {
                    removeAllViews()
                    addView(view, FrameLayout.LayoutParams(-1, -1))
                }
                // The fixture replaces the factory view. Route gestures through that page as the Activity normally does.
                MainActivity::class.java.getDeclaredField("activePageInteraction").apply { isAccessible = true }.set(activity, view)
                view.applyAppearance(vm.appearance.state.copy(themeMode = ThemeMode.DARK))
                assertEquals(Color.BLACK, (view.background as ColorDrawable).color)
            }
            try {
                onView(withContentDescription("当前温度 23°C")).check(matches(isDisplayed()))
                onView(withText("逐小时预报")).check(matches(isDisplayed()))
                val chart = onView(withTagValue(org.hamcrest.Matchers.equalTo<Any>("forecast_scroll")))
                assertFalse(started.any { it.second.block in listOf(WeatherBlock.MINUTELY, WeatherBlock.HISTORY) })
                capture("pr80-weather-dark")
                onView(withContentDescription(containsString("北京 · 8°C"))).check(matches(isDisplayed()))
                onView(withContentDescription(containsString("上海 · 17°C"))).check(matches(isDisplayed()))
                assertTrue(started.filter { it.first != wuhan }.all { it.second.block == WeatherBlock.CURRENT })
                assertFalse(started.any { it.first == far.last() })
                val cities = onView(withTagValue(org.hamcrest.Matchers.equalTo<Any>("weather_city_list")))
                repeat(3) { cities.perform(swipeUp()) }
                onView(withContentDescription(containsString("列表测试 10 · 8°C"))).check(matches(isDisplayed()))
                assertTrue(stopped.any { it.first == beijing && it.second.block == WeatherBlock.CURRENT })
                repeat(3) { cities.perform(swipeDown()) }
                chart.perform(scrollTo(), swipeLeft())
                chart.check { widget, error ->
                    if (error != null) throw error
                    assertTrue(widget.scrollX > 0)
                }
                chart.perform(click())
                onView(withText(containsString("温度  "))).check(matches(isDisplayed()))
                onView(withText("关闭")).perform(click())
                checkLongestForecast(scenario, started, wuhan)
                onView(withText("日月天文")).perform(scrollTo(), click())
                assertTrue(stopped.any { it.first == wuhan && it.second.block == WeatherBlock.HOURLY && it.second.hours == 240 })
                onView(withText("太阳")).check(matches(isDisplayed()))
                onView(withText("月亮")).check(matches(isDisplayed()))
                onView(allOf(withText("正在加载每日预报"), isDescendantOfA(
                    withTagValue(org.hamcrest.Matchers.equalTo<Any>(WeatherBlock.SUN)),
                ))).check(matches(isDisplayed()))
                assertFalse(started.any { it.first == wuhan && it.second.block in listOf(WeatherBlock.SUN, WeatherBlock.MOON) })
                scenario.onActivity { checkNotNull(finishWuhanDaily).invoke() }
                onView(withText(containsString("日出  06:17"))).check(matches(isDisplayed()))
                onView(withText(containsString("满月"))).check(matches(isDisplayed()))
                assertFalse(started.any { it.first == wuhan && it.second.block in listOf(WeatherBlock.SUN, WeatherBlock.MOON) })
                onView(withText("上海")).perform(click())
                onView(withText(containsString("月升  14:10"))).check(matches(isDisplayed()))
                onView(withText(containsString("测试月相不可用"))).perform(scrollTo()).check(matches(isDisplayed()))
                onView(withText(containsString("新月"))).perform(scrollTo()).check(matches(isDisplayed()))
                onView(withText("暂无月落数据")).perform(scrollTo()).check(matches(isDisplayed()))
                assertTrue(started.any { it.first == shanghai && it.second.block == WeatherBlock.MOON })
                assertFalse(started.any { it.first == shanghai && it.second.block == WeatherBlock.SUN })
                onView(withText(containsString("满月"))).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
                onView(withText("武汉")).perform(click())
                onView(withText("分钟降雨")).perform(scrollTo(), click())
                onView(withText("测试降雨摘要")).check(matches(isDisplayed()))
                assertTrue(started.any { it.first == wuhan && it.second.block == WeatherBlock.MINUTELY })
                onView(withText("北京")).perform(click())
                onView(withContentDescription("当前温度 8°C")).check(matches(isDisplayed()))
                assertTrue(stopped.any { it.first == wuhan && it.second.block == WeatherBlock.MINUTELY })
                assertFalse(started.any { it.first == beijing && it.second.block == WeatherBlock.MINUTELY })
                onView(withText("空气质量")).perform(scrollTo(), click())
                onView(withText(containsString("PM2.5  19 µg/m³"))).check(matches(isDisplayed()))
                onView(withText("多日空气质量")).perform(scrollTo()).check(matches(isDisplayed()))
                onView(withText("生活指数")).perform(scrollTo(), click())
                onView(withText("建议穿长袖衬衫")).check(matches(isDisplayed()))
                onView(withText("日月天文")).perform(scrollTo(), click())
                onView(withText(containsString("上弦月"))).check(matches(isDisplayed()))
                onView(withText(containsString("日出  06:30"))).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
                scenario.onActivity { checkNotNull(finishBeijingSun).invoke() }
                onView(withText(containsString("日出  06:30"))).check(matches(isDisplayed()))
                onView(withText("生活指数")).perform(scrollTo(), click())
                onView(withText("日月天文")).perform(scrollTo(), click())
                onView(withText(containsString("上弦月"))).perform(scrollTo()).check(matches(isDisplayed()))
                assertEquals(1, started.count { it.first == beijing && it.second.block == WeatherBlock.MOON })
                onView(withText("历史天气")).perform(scrollTo(), click())
                assertFalse(started.any { it.second.block == WeatherBlock.HISTORY })
                onView(withText("选择历史日期")).perform(scrollTo(), click())
                onView(withText(java.time.LocalDate.now(zone).minusDays(1).format(centerDate))).perform(click())
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                assertTrue(started.any { it.first == beijing && it.second.block == WeatherBlock.HISTORY })
                scenario.onActivity { activity ->
                    val vm = ViewModelProvider(activity)[DashboardViewModel::class.java]
                    view.applyAppearance(vm.appearance.state.copy(themeMode = ThemeMode.LIGHT))
                    assertNotEquals(Color.BLACK, (view.background as ColorDrawable).color)
                }
                onView(withText("天气预报")).perform(scrollTo(), click())
                capture("pr80-weather-light")
                scenario.onActivity {
                    view.setActive(false)
                    assertTrue(stopped.any { it.first == beijing && it.second.block == WeatherBlock.CURRENT })
                    view.setActive(true)
                }
                onView(withContentDescription("当前温度 8°C")).check(matches(isDisplayed()))
            } finally {
                scenario.onActivity {
                    (view.parent as? ViewGroup)?.removeView(view)
                    repository.close()
                }
            }
            assertTrue(stopped.any { it.first == beijing && it.second.block == WeatherBlock.CURRENT })
        }
    }

    private fun checkLongestForecast(
        scenario: ActivityScenario<MainActivity>,
        started: List<Pair<WeatherSourceRef, WeatherQuery>>,
        source: WeatherSourceRef,
    ) {
        val frames = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val listener = android.view.Window.OnFrameMetricsAvailableListener { _, result, _ ->
            frames += result.getMetric(android.view.FrameMetrics.TOTAL_DURATION)
        }
        val beforePss = android.os.Debug.getPss()
        scenario.onActivity { it.window.addOnFrameMetricsAvailableListener(listener, handler) }
        try {
            val openedAt = android.os.SystemClock.elapsedRealtime()
            onView(withText("最多 240 小时")).perform(scrollTo(), click())
            val chart = onView(withTagValue(org.hamcrest.Matchers.equalTo<Any>("forecast_scroll")))
            chart.check { widget, error ->
                if (error != null) throw error
                val strip = (widget as android.widget.HorizontalScrollView).getChildAt(0) as ViewGroup
                val graph = strip.getChildAt(0) as WeatherTrendView
                val tiles = strip.getChildAt(1) as ViewGroup
                assertTrue(graph.pointCount in 238..240)
                assertTrue(tiles.childCount in 1..16)
                for (index in 0 until tiles.childCount) {
                    val cell = tiles.getChildAt(index)
                    val center = cell.left + cell.width / 2f
                    val nearest = (0 until graph.pointCount).minBy { kotlin.math.abs(graph.position(it) - center) }
                    assertEquals(graph.position(nearest), center, 1f)
                }
            }
            val openedIn = android.os.SystemClock.elapsedRealtime() - openedAt
            val longestPss = android.os.Debug.getPss()
            lateinit var originalChart: View
            chart.check { widget, _ -> originalChart = widget }
            repeat(3) {
                onView(allOf(withText("降水概率"), isClickable())).perform(scrollTo(), click())
                onView(withText("100%")).check(matches(isDisplayed()))
                onView(allOf(withText("降水量"), isClickable())).perform(scrollTo(), click())
                onView(withContentDescription(containsString("趋势图 mm"))).check(matches(isDisplayed()))
                onView(allOf(withText("风速"), isClickable())).perform(scrollTo(), click())
                onView(withContentDescription(containsString("趋势图 m/s"))).check(matches(isDisplayed()))
                chart.perform(scrollTo(), swipeLeft()).check { widget, error ->
                    if (error != null) throw error
                    assertSame(originalChart, widget)
                    assertTrue(widget.scrollX > 0)
                    val strip = (widget as android.widget.HorizontalScrollView).getChildAt(0) as ViewGroup
                    val tiles = strip.getChildAt(1) as ViewGroup
                    assertTrue(tiles.childCount in 1..16)
                }
            }
            chart.perform(scrollTo())
            frames.clear()
            repeat(3) { chart.perform(swipeLeft()) }
            val scrolling = synchronized(frames) { frames.filter { it >= 0 }.sorted() }
            val p50Ms = scrolling.getOrNull(scrolling.size / 2)?.div(1_000_000)
            val p95Ms = scrolling.getOrNull((scrolling.size * .95).toInt())?.div(1_000_000)
            capture("pr80-240h-wind")
            onView(withText("24 小时")).perform(scrollTo(), click())
            onView(withText("48 小时")).perform(scrollTo(), click())
            onView(withText("72 小时")).perform(scrollTo(), click())
            onView(withText("最多 240 小时")).perform(scrollTo(), click())
            assertEquals(listOf(24, 240), started.filter { it.first == source && it.second.block == WeatherBlock.HOURLY }
                .map { it.second.hours })
            android.util.Log.i("WeatherCenterCheck",
                "240h open+check=${openedIn}ms, scroll frames=${scrolling.size}, p50=${p50Ms}ms, p95=${p95Ms}ms, PSS=$beforePss -> $longestPss KiB")
        } finally {
            scenario.onActivity { it.window.removeOnFrameMetricsAvailableListener(listener) }
        }
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val directory = instrumentation.targetContext.getExternalFilesDir(null) ?: return
        directory.resolve("$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
