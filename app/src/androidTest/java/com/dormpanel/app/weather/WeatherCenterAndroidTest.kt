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

    @Test fun fakeWeatherRendersForecastAndOnlyOpensSpecialtyDemandOnSelection() {
        val places = WeatherPlaceStore("[]") { true }
        val zone = ZoneId.of("Asia/Shanghai")
        val wuhan = WeatherSourceRef.QWeatherCity("101200101")
        val beijing = WeatherSourceRef.QWeatherCity("101010100")
        places.save(WeatherPlace("wuhan", "武汉", zone, listOf(wuhan)))
        places.save(WeatherPlace("beijing", "北京", zone, listOf(beijing)))
        val started = mutableListOf<Pair<WeatherSourceRef, WeatherQuery>>()
        val stopped = mutableListOf<Pair<WeatherSourceRef, WeatherQuery>>()
        val provider = object : WeatherProvider {
            override fun plan(source: WeatherSourceRef, zone: ZoneId, query: WeatherQuery, now: Instant): WeatherPlan {
                val temperature = if (source == wuhan) 23.0 else 8.0
                val conditions = WeatherConditions("100", temperature = temperature, feelsLike = temperature - 2)
                val date = now.atZone(zone).toLocalDate()
                val data = when (query.block) {
                    WeatherBlock.CURRENT -> WeatherData.Current(conditions)
                    WeatherBlock.HOURLY -> WeatherData.Forecast(List(24) { hour ->
                        WeatherForecast(WeatherPeriod(start = now.plusSeconds(hour * 3600L)),
                            conditions.copy(temperature = if (hour == 3) null else temperature + hour % 5))
                    })
                    WeatherBlock.DAILY -> WeatherData.Forecast(List(10) { day ->
                        WeatherForecast(WeatherPeriod(date = date.plusDays(day.toLong())),
                            WeatherConditions(minTemperature = temperature - 4 - day, maxTemperature = temperature + day),
                            day = WeatherForecast(WeatherPeriod(isDaytime = true), conditions),
                            night = WeatherForecast(WeatherPeriod(isDaytime = false), conditions.copy(code = "150")))
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
                    emit(WeatherResult.Data(JSONObject(), parsed, zone))
                    val cancel: () -> Unit = { stopped += source to query }
                    cancel
                }, live = query.block != WeatherBlock.HISTORY)
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
                chart.perform(scrollTo(), swipeLeft())
                chart.check { widget, error ->
                    if (error != null) throw error
                    assertTrue(widget.scrollX > 0)
                }
                chart.perform(click())
                onView(withText(containsString("温度  "))).check(matches(isDisplayed()))
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
                onView(withText(containsString("日出  06:30"))).check(matches(isDisplayed()))
                onView(withText(containsString("上弦月"))).check(matches(isDisplayed()))
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
