package com.dormpanel.phone

import android.content.Context
import android.content.SharedPreferences
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.appcompat.app.AlertDialog
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.*
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.schedule.*
import org.hamcrest.Matchers.startsWith
import org.junit.Assert.*
import org.junit.Test
import java.time.*
import java.util.concurrent.*

/** Real SharedPreferences reads with an editor that fails before applying staged writes. */
class CourseColorReliabilityTest {
    private val i get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = i.targetContext
    private class DiskFault(val backing: SharedPreferences) : SharedPreferences by backing {
        var fail = true
        var throwing = false
        var attempts = 0
        override fun edit(): SharedPreferences.Editor {
            val editor = backing.edit()
            return object : SharedPreferences.Editor by editor {
                override fun clear(): SharedPreferences.Editor { editor.clear(); return this }
                override fun remove(key: String): SharedPreferences.Editor { editor.remove(key); return this }
                override fun putInt(key: String, value: Int): SharedPreferences.Editor { editor.putInt(key,value); return this }
                override fun putLong(key: String, value: Long): SharedPreferences.Editor { editor.putLong(key,value); return this }
                override fun commit(): Boolean {
                    attempts++
                    if (throwing) throw IllegalStateException("injected preference disk failure")
                    return if (fail) false else editor.commit()
                }
            }
        }
    }
    private fun context(activity: TimetableActivity, disk: DiskFault): Context =
        object : android.view.ContextThemeWrapper(activity,activity.theme) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                if (name == "course_colors_v1") disk else super.getSharedPreferences(name,mode)
        }
    private fun <T> await(call: ((Result<T>) -> Unit) -> Unit): T {
        val future = CompletableFuture<Result<T>>()
        call { future.complete(it) }
        return future.get(20,TimeUnit.SECONDS).getOrThrow()
    }
    private fun isolated(block: (RoomScheduleStore, DiskFault) -> Unit) {
        require(c.packageName.endsWith(".pr68testbed"))
        val name = "pr68-reliability-${java.util.UUID.randomUUID()}"
        val executor = Executors.newSingleThreadExecutor()
        val store = RoomScheduleStore(Room.databaseBuilder(c,ScheduleDatabase::class.java,"$name.db").build(),executor)
        val disk = DiskFault(c.getSharedPreferences(name,0))
        try { block(store,disk) } finally { ScheduleStores.overrideFactory = null; store.close(); executor.shutdown() }
    }
    private fun focus(scenario: ActivityScenario<TimetableActivity>) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(i.uiAutomation.executeShellCommand(
            "am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n ${c.packageName}/com.dormpanel.phone.TimetableActivity"
        )).use { it.readBytes() }
        val until = System.currentTimeMillis()+10000
        var ready = false
        while (!ready && System.currentTimeMillis()<until) {
            scenario.onActivity { ready = it.source.ready && !it.source.error && it.hasWindowFocus() }
            Thread.sleep(100)
        }
        assertTrue("Activity ready and focused",ready)
    }
    private fun entry(id: String) = TimetableEntry(id,"可靠性课程",LocalDate.now().dayOfWeek.value,0,1439)
    @Test fun automaticWriteFailureStillRendersCardsManagementAndWidget() = isolated { store,disk ->
        await<Unit> { store.put(entry("auto"),it) }
        ScheduleStores.overrideFactory = { store }
        launchPhoneTestActivity().use { scenario ->
            focus(scenario)
            lateinit var editor: PhoneEditors
            scenario.onActivity { activity ->
                val wrapped = context(activity,disk)
                val state = activity.source.state
                val monday = LocalDate.now().with(DayOfWeek.MONDAY)
                val rows = ScheduleProjection.week(state,monday,ZoneId.systemDefault())
                var expected: Int? = null
                for (throwing in listOf(false,true)) {
                    disk.throwing = throwing
                    val colors = CourseColors.snapshot(wrapped,state)
                    val color = colors.rgb(rows.single())
                    if (expected == null) expected = color else assertEquals(expected,color)
                    val week = PhoneWeekView(wrapped,rows,monday,ZoneId.systemDefault(),{},colors=colors)
                    week.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1000,View.MeasureSpec.EXACTLY))
                    week.layout(0,0,1080,1000)
                    fun cards(view: View): Int = (if(view is CourseCard) 1 else 0) +
                        if(view is ViewGroup) (0 until view.childCount).sumOf { cards(view.getChildAt(it)) } else 0
                    assertEquals(1,cards(week))
                    val dayCard = CourseCard(wrapped,rows,ZoneId.systemDefault(),rgb=color) {}
                    assertNotNull(dayCard.background)
                    val render = TodayWidgetUpdates::class.java.getDeclaredMethod("render",Context::class.java,Int::class.javaPrimitiveType,Pair::class.java,String::class.java).apply { isAccessible=true }
                    val views = render.invoke(TodayWidgetUpdates,wrapped,-1,state to TodayCourses.project(state,Instant.now(),ZoneId.systemDefault()),null) as RemoteViews
                    assertNotNull(views.apply(c.applicationContext,FrameLayout(c.applicationContext)).findViewById<View>(R.id.widget_course_color))
                }
                editor = PhoneEditors(wrapped,activity.source)
                editor.courseManagement()
            }
            try {
                onView(withText(startsWith("可靠性课程\n"))).inRoot(isDialog()).check(matches(isDisplayed()))
                assertTrue(disk.attempts >= 3)
                assertTrue(disk.backing.all.isEmpty())
            } finally { scenario.onActivity { editor.close() } }
        }
    }
    @Test fun manualWriteFailureKeepsDraftAndEmptySaveSkipsTransactions() = isolated { store,disk ->
        await<Unit> { store.put(entry("manual"),it) }
        var transactions = 0
        ScheduleStores.overrideFactory = { object : ScheduleStore by store {
            override fun putClassOverrides(expected: ScheduleState, items: List<ClassOverride>, callback: (Result<Unit>) -> Unit) {
                transactions++; callback(Result.failure(IllegalStateException("unexpected transaction")))
            }
        } }
        launchPhoneTestActivity().use { scenario ->
            focus(scenario)
            lateinit var editor: PhoneEditors
            lateinit var wrapped: Context
            scenario.onActivity {
                wrapped = context(it,disk); editor = PhoneEditors(wrapped,it.source); editor.courseManagement()
            }
            try {
                fun open() = onView(withText(startsWith("可靠性课程\n"))).inRoot(isDialog()).perform(click())
                open()
                val before = disk.attempts
                onView(withText("保存修改")).inRoot(isDialog()).perform(click())
                onView(withText("保存修改")).check(doesNotExist())
                assertEquals(0,transactions); assertEquals(before,disk.attempts)
                open()
                onView(withText(startsWith("课程颜色 ·"))).perform(scrollTo(),click())
                onView(withText("保持自动配色")).inRoot(isDialog()).perform(click())
                val automaticAttempts = disk.attempts
                onView(withText("保存修改")).perform(click())
                onView(withText("保存修改")).check(doesNotExist())
                assertEquals(0,transactions); assertEquals(automaticAttempts,disk.attempts)
                open()
                onView(withText(startsWith("课程颜色 ·"))).perform(scrollTo(),click())
                onView(withText("手动固定颜色")).perform(scrollTo(),click())
                onView(withText("使用此颜色")).perform(click())
                onView(withText("保存修改")).perform(click())
                onView(withText("颜色保存失败，草稿保留")).perform(scrollTo()).check(matches(isDisplayed()))
                onView(withText(startsWith("课程颜色 · 手动"))).perform(scrollTo()).check(matches(isDisplayed()))
                scenario.onActivity {
                    val colors = CourseColors.snapshot(wrapped,it.source.state)
                    assertNull(colors.manual(colors.identity.key(null,"manual")!!))
                }
                assertEquals(0,transactions); assertTrue(await<ScheduleState>(store::load).classOverrides.isEmpty())
                disk.throwing = true
                onView(withText("保存修改")).perform(click())
                onView(withText("颜色保存失败，草稿保留")).perform(scrollTo()).check(matches(isDisplayed()))
                assertEquals(0,transactions); assertTrue(disk.backing.all.isEmpty())
                disk.throwing = false
                // Retrying this very draft, after disk recovery, saves only the color.
                disk.fail = false
                onView(withText("保存修改")).perform(click())
                scenario.onActivity {
                    val colors = CourseColors.snapshot(wrapped,it.source.state)
                    assertNotNull(colors.manual(colors.identity.key(null,"manual")!!))
                }
                assertEquals(0,transactions)
            } finally { scenario.onActivity { editor.close() } }
        }
    }
    @Test fun pickerDistinguishesAutomaticFixedSameColorAndCancel() = isolated { store,_ ->
        await<Unit> { store.put(entry("picker"),it) }
        ScheduleStores.overrideFactory = { store }
        launchPhoneTestActivity().use { scenario ->
            focus(scenario)
            fun dialogView(matcher: org.hamcrest.Matcher<View>) = onView(matcher).inRoot(isDialog())
            val chosen = mutableListOf<Int?>()
            lateinit var dialog: AlertDialog
            fun open(manual: Int? = null) { scenario.onActivity {
                dialog = CourseColorPicker.show(it,manual ?: 0x4789ff,manual,"可靠性课程",0x4789ff) { chosen.add(it) }
                dialog.show()
            } }
            try {
                open()
                dialogView(withText("自动配色")).check(matches(isChecked()))
                dialogView(withText("保持自动配色")).perform(click())
                assertEquals(listOf<Int?>(null),chosen)
                open()
                dialogView(withText("手动固定颜色")).perform(scrollTo(),click())
                dialogView(withText("使用此颜色")).perform(click())
                assertEquals(listOf<Int?>(null,0x4789ff),chosen)
                open(0xb49138)
                dialogView(withText("自动配色")).perform(scrollTo(),click())
                dialogView(withText("保持自动配色")).perform(click())
                assertEquals(listOf<Int?>(null,0x4789ff,null),chosen)
                open()
                dialogView(withContentDescription("预设颜色 #719447")).perform(scrollTo(),click())
                dialogView(withText("取消")).perform(click())
                assertEquals(3,chosen.size)
            } finally { scenario.onActivity { dialog.dismiss() } }
        }
    }
}
