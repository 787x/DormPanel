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
        var memoryOnFailure = false
        var attempts = 0
        var actualWrites = 0
        var skippedNoOps = 0
        private var memory = backing.all.toMutableMap()
        override fun getAll(): Map<String, *> = if (memoryOnFailure) memory.toMap() else backing.all
        override fun edit(): SharedPreferences.Editor {
            val editor = backing.edit()
            var clear = false
            val changes = mutableMapOf<String,Any?>()
            return object : SharedPreferences.Editor by editor {
                override fun clear(): SharedPreferences.Editor { clear=true; editor.clear(); return this }
                override fun remove(key: String): SharedPreferences.Editor { changes[key]=null; editor.remove(key); return this }
                override fun putInt(key: String, value: Int): SharedPreferences.Editor { changes[key]=value; editor.putInt(key,value); return this }
                override fun putLong(key: String, value: Long): SharedPreferences.Editor { changes[key]=value; editor.putLong(key,value); return this }
                override fun putString(key: String, value: String?): SharedPreferences.Editor { changes[key]=value; editor.putString(key,value); return this }
                override fun commit(): Boolean {
                    attempts++
                    val next = if (clear) mutableMapOf<String,Any?>() else all.toMutableMap()
                    changes.forEach { (key,value) -> if(value==null) next.remove(key) else next[key]=value }
                    val changed = next != all
                    if (memoryOnFailure) memory = next
                    if (throwing) throw IllegalStateException("injected preference disk failure")
                    if (fail) return false
                    // Deliberately model a backend that treats an unchanged in-memory edit
                    // as a successful no-op, even though a previous disk write failed.
                    if (memoryOnFailure && !changed) { skippedNoOps++; return true }
                    actualWrites++
                    return editor.commit()
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
    @Test fun failedCommitMemoryIsQuarantinedAndSameValueRetryReallyWrites() = isolated { store,disk ->
        await<Unit> { store.put(entry("memory"),it) }
        ScheduleStores.overrideFactory = { store }
        disk.memoryOnFailure = true
        disk.fail = false
        launchPhoneTestActivity().use { scenario ->
            focus(scenario)
            scenario.onActivity { activity ->
                val wrapped = context(activity,disk)
                val state = activity.source.state
                val initial = CourseColors.snapshot(wrapped,state)
                val key = initial.identity.key(null,"memory")!!
                val storedKey = "m"+java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
                CourseColors.save(wrapped,key,0x112233)
                disk.fail = true
                assertTrue(runCatching { CourseColors.save(wrapped,key,0xabcdef) }.isFailure)
                assertEquals(0xabcdef,disk.all[storedKey])
                assertEquals(0x112233,disk.backing.all[storedKey])
                assertEquals(0x112233,CourseColors.snapshot(wrapped,state).manual(key))
                val writes = disk.actualWrites
                disk.fail = false
                CourseColors.save(wrapped,key,0xabcdef)
                assertEquals(writes+1,disk.actualWrites)
                assertEquals(0xabcdef,disk.backing.all[storedKey])
                assertEquals(0xabcdef,CourseColors.snapshot(wrapped,state).manual(key))
                disk.fail = true
                assertTrue(runCatching { CourseColors.save(wrapped,key,null) }.isFailure)
                assertNull(disk.all[storedKey])
                assertEquals(0xabcdef,CourseColors.snapshot(wrapped,state).manual(key))
                disk.fail = false
                CourseColors.save(wrapped,key,null)
                assertNull(disk.backing.all[storedKey])
                assertNull(CourseColors.snapshot(wrapped,state).manual(key))
                disk.throwing = true
                assertTrue(runCatching { CourseColors.save(wrapped,key,0x778899) }.isFailure)
                assertEquals(0x778899,disk.all[storedKey])
                assertNull(disk.backing.all[storedKey])
                assertNull(CourseColors.snapshot(wrapped,state).manual(key))
            }
        }
    }
    @Test fun businessOnlyEditDoesNotAttemptManualColorSave() = isolated { store,disk ->
        await<Unit> { store.put(entry("business"),it) }
        var transactions = 0
        ScheduleStores.overrideFactory = { object : ScheduleStore by store {
            override fun putClassOverrides(expected: ScheduleState, items: List<ClassOverride>, callback: (Result<Unit>) -> Unit) {
                transactions++; store.putClassOverrides(expected,items,callback)
            }
        } }
        launchPhoneTestActivity().use { scenario ->
            focus(scenario)
            lateinit var editor: PhoneEditors
            scenario.onActivity { editor=PhoneEditors(context(it,disk),it.source); editor.courseManagement() }
            try {
                onView(withText(startsWith("可靠性课程\n"))).inRoot(isDialog()).perform(click())
                onView(withText("统一修改课程名称")).perform(scrollTo(),click())
                onView(withContentDescription("课程名称")).perform(scrollTo(),replaceText("仅改业务"),closeSoftKeyboard())
                onView(withText("保存修改")).perform(click())
                onView(withText("确认保存")).perform(click())
                onView(withText("保存修改")).check(doesNotExist())
                onView(withText("颜色保存失败，草稿保留")).check(doesNotExist())
                assertEquals(1,transactions)
                assertEquals("仅改业务",await<ScheduleState>(store::load).classOverrides.single().title)
                assertTrue(disk.backing.all.isEmpty())
            } finally { scenario.onActivity { editor.close() } }
        }
    }
    @Test fun paletteThemeScreenshot() = isolated { store,_ ->
        await<Unit> { store.put(entry("palette"),it) }
        ScheduleStores.overrideFactory = { store }
        val dark=InstrumentationRegistry.getArguments().getString("dark")=="true"
        i.runOnMainSync { androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(if(dark) 2 else 1) }
        try { launchPhoneTestActivity().use { scenario ->
            focus(scenario)
            lateinit var paletteDialog: android.app.Dialog
            scenario.onActivity { activity ->
                val root=activity.scheduleColumn().apply {
                    setPadding(activity.dp(8),0,activity.dp(8),0)
                    setBackgroundColor(if(dark) 0xff121212.toInt() else 0xfffafafa.toInt())
                }
                root.addView(activity.scheduleLabel("16 色课程卡片 · ${if(dark) "深色" else "浅色"}",18f))
                val names=listOf("纯蓝","黄绿","玫红","蓝紫","金黄","青色","橙色","浅蓝","深红","墨绿","棕黄","薄荷","洋红","草绿","深绿","灰紫")
                val monday=LocalDate.now().with(DayOfWeek.MONDAY)
                CourseColors.palette.toList().chunked(2).forEachIndexed { rowIndex,slots ->
                    val row=android.widget.LinearLayout(activity)
                    slots.forEachIndexed { columnIndex,rgb ->
                        val index=rowIndex*2+columnIndex
                        val state=ScheduleState(entries=listOf(entry("slot$index").copy(title="${index+1} · ${names[index]}",location="#%06X".format(rgb),startMinute=480,endMinute=540)))
                        val item=ScheduleProjection.week(state,monday,ZoneId.systemDefault()).single()
                        row.addView(CourseCard(activity,listOf(item),ZoneId.systemDefault(),rgb=rgb) {},android.widget.LinearLayout.LayoutParams(0,activity.dp(78),1f).apply { setMargins(activity.dp(3),activity.dp(2),activity.dp(3),activity.dp(2)) })
                    }
                    root.addView(row)
                }
                paletteDialog=android.app.Dialog(activity)
                paletteDialog.setContentView(root)
                paletteDialog.window!!.setWindowAnimations(0)
                paletteDialog.show()
                paletteDialog.window!!.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT)
            }
            onView(withText("16 色课程卡片 · ${if(dark) "深色" else "浅色"}")).inRoot(isDialog()).check(matches(isDisplayed()))
            i.waitForIdleSync()
            Thread.sleep(350) // Let the compositor finish the Activity/theme transition.
            val screenshot=i.uiAutomation.takeScreenshot()
            java.io.File(c.getExternalFilesDir(null),"pr68-palette-${if(dark) "dark" else "light"}.png").outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
            screenshot.recycle()
            scenario.onActivity { paletteDialog.dismiss() }
        } } finally { i.runOnMainSync { androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(-1) } }
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
                dialogView(withContentDescription("预设颜色 #%06X".format(CourseColors.palette[1]))).perform(scrollTo(),click())
                dialogView(withText("取消")).perform(click())
                assertEquals(3,chosen.size)
            } finally { scenario.onActivity { dialog.dismiss() } }
        }
    }
}
