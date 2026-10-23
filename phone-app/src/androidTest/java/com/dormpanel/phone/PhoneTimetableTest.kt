package com.dormpanel.phone

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.schedule.*
import org.hamcrest.Matchers.*
import org.junit.*
import org.junit.Assert.*
import java.time.LocalDate
import java.util.UUID

class PhoneTimetableTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var dbName: String
    @Before fun setup() {
        for (name in listOf("TimetableActivity", "com.dormpanel.phone.TimetableActivity"))
            instrumentation.targetContext.getSharedPreferences(name, 0).edit().clear().commit()
        dbName = "phone-test-${UUID.randomUUID()}.db"
        ScheduleStores.overrideFactory = { context -> RoomScheduleStore(Room.databaseBuilder(context, ScheduleDatabase::class.java, dbName).build()) }
        TermScheduleProfileStore.overrideNamespace = "phone-test-${UUID.randomUUID()}"
        Intents.init()
        // Start and verify the test-only provider before handing a URI to the picker.
        instrumentation.context.contentResolver.query(Uri.parse("content://${instrumentation.context.packageName}.fixtures/wakeup.ics"), null, null, null, null)!!.use {
            assertTrue("Fixture provider must expose the requested file", it.moveToFirst())
        }
    }
    @After fun teardown() {
        Intents.release(); ScheduleStores.overrideFactory = null
        instrumentation.targetContext.deleteDatabase(dbName)
        TermScheduleProfileStore.overrideNamespace?.let { instrumentation.targetContext.getSharedPreferences(it, 0).edit().clear().commit() }
        TermScheduleProfileStore.overrideNamespace = null
    }
    private fun waitFor(s: ActivityScenario<TimetableActivity>, condition: (TimetableActivity) -> Boolean) {
        var ready = false
        val deadline = System.currentTimeMillis() + 10000
        while (!ready && System.currentTimeMillis() < deadline) { s.onActivity { ready = condition(it) }; if (!ready) Thread.sleep(40) }
        assertTrue("Timed out waiting for phone state", ready)
        instrumentation.waitForIdleSync()
    }
    private fun open() = launchPhoneTestActivity().also { scenario -> scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }; Thread.sleep(700); waitFor(scenario) { a -> a.source.ready }; onView(withText("日视图")).perform(click()) }
    private fun pick(filename: String) {
        Intents.intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(ActivityResult(Activity.RESULT_OK,
            Intent().setData(Uri.parse("content://${instrumentation.context.packageName}.fixtures/$filename"))))
        onView(withText("管理")).perform(click())
        onView(withText("导入 ICS / 湖北大学 CSV")).perform(click())
    }
    private fun preview(s: ActivityScenario<TimetableActivity>) {
        val deadline = System.currentTimeMillis() + 10000
        while (true) {
            try { onView(withText("确认导入")).check(matches(isDisplayed())); break }
            catch (e: androidx.test.espresso.NoMatchingViewException) {
                if (System.currentTimeMillis() >= deadline) throw e
                Thread.sleep(80)
            }
        }
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync(); Thread.sleep(350) // Let native dialog transitions finish before visual QA.
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "phone-$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }; bitmap.recycle()
    }
    // A time block can be taller than the viewport. Tap its visible portion, as a user does.
    private fun timeBlockClick() = object : androidx.test.espresso.ViewAction {
        override fun getConstraints() = isAssignableFrom(android.view.View::class.java)
        override fun getDescription() = "Reveal and tap visible course block"
        override fun perform(controller: androidx.test.espresso.UiController, view: android.view.View) {
            view.requestRectangleOnScreen(android.graphics.Rect(0, 0, view.width, minOf(view.height, view.context.dp(48))), true)
            controller.loopMainThreadUntilIdle()
            androidx.test.espresso.action.GeneralClickAction(androidx.test.espresso.action.Tap.SINGLE, { v ->
                val rect = android.graphics.Rect()
                check(v.getGlobalVisibleRect(rect))
                floatArrayOf(rect.exactCenterX(), rect.exactCenterY())
            }, androidx.test.espresso.action.Press.FINGER).perform(controller, view)
        }
    }
    @Test fun sevenColumnsRotateRefreshAndOpenDetail() {
        open().use { s ->
            s.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            Thread.sleep(1000); instrumentation.waitForIdleSync()
            onView(withText("今天")).perform(click())
            s.onActivity { a ->
                a.source.saveEntry("周视图测试", LocalDate.now().dayOfWeek.value, 480, 540, "测试教室")
            }
            waitFor(s) { it.source.state.entries.isNotEmpty() }
            onView(withText("周视图")).perform(click())
            fun checkColumns() {
                s.onActivity { a ->
                    fun find(v: android.view.View): PhoneWeekView? {
                        if (v is PhoneWeekView) return v
                        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
                        return null
                    }
                    val view = find(a.window.decorView)!!
                    assertEquals(8, view.childCount)
                    for (i in 1..7) {
                        val column = view.getChildAt(i)
                        assertTrue(column.width > 0)
                        assertTrue(column.right <= view.width)
                    }
                }
            }
            instrumentation.waitForIdleSync(); checkColumns(); screenshot("week-portrait")
            onView(withText(startsWith("周视图测试"))).perform(timeBlockClick())
            onView(withText("Edit")).check(matches(isDisplayed()))
            androidx.test.espresso.Espresso.pressBack()
            s.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            Thread.sleep(1000); instrumentation.waitForIdleSync(); checkColumns(); screenshot("week-landscape")
            s.recreate(); waitFor(s) { it.source.ready }; checkColumns()
            onView(withText("日视图")).perform(click())
            onView(withText(startsWith("周视图测试"))).check(matches(isDisplayed()))
            s.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
    }
    @Test fun overlappingInstancesLateScrollAndSyncRefresh() {
        open().use { s ->
            onView(withText("今天")).perform(click())
            s.onActivity { a ->
                val day = a.source.clock.today().dayOfWeek.value
                a.source.saveEntry("Overlap A", day, 480, 540, "A101")
                a.source.saveEntry("Overlap B", day, 510, 570, "B202")
                a.source.saveEntry("Late class", day, 1320, 1380, "C303")
            }
            waitFor(s) { it.source.state.entries.size == 3 }
            onView(withText("周视图")).perform(click())
            for (title in listOf("Overlap A", "Overlap B")) {
                onView(withText(startsWith("2 门重叠"))).perform(timeBlockClick())
                onView(withText(startsWith(title))).perform(click())
                onView(withText(title)).check(matches(isDisplayed()))
                onView(withText("Edit")).perform(scrollTo()).check(matches(isDisplayed()))
                androidx.test.espresso.Espresso.pressBack()
            }
            s.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            Thread.sleep(1000); instrumentation.waitForIdleSync()
            onView(withText(startsWith("Late class"))).perform(timeBlockClick())
            onView(withText("Late class")).check(matches(isDisplayed()))
            androidx.test.espresso.Espresso.pressBack()
            var offset = 0
            s.onActivity { a ->
                val scroller = a.window.decorView.findViewWithTag<android.widget.ScrollView>("timetable-scroll")
                assertTrue("Landscape course viewport", scroller.height >= a.dp(90))
                offset = scroller.scrollY
                assertTrue(offset > 0)
                a.sharedSync.changed?.invoke()
            }
            instrumentation.waitForIdleSync()
            s.onActivity { a -> assertEquals(offset, a.window.decorView.findViewWithTag<android.widget.ScrollView>("timetable-scroll").scrollY) }
            s.recreate(); waitFor(s) { it.source.ready }; instrumentation.waitForIdleSync()
            s.onActivity { a -> assertEquals(offset, a.window.decorView.findViewWithTag<android.widget.ScrollView>("timetable-scroll").scrollY) }
            screenshot("late-landscape")
            onView(withText("下周 ›")).perform(click())
            var date = ""
            s.onActivity { a ->
                date = a.window.decorView.findViewWithTag<android.widget.TextView>("week-range").text.toString()
                a.sharedSync.changed?.invoke()
            }
            instrumentation.waitForIdleSync()
            s.onActivity { a -> assertEquals(date, a.window.decorView.findViewWithTag<android.widget.TextView>("week-range").text.toString()) }
            s.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
    }
    @Test fun browseFolderSelectAndCancelAreDraftOnly() {
        val server = okhttp3.mockwebserver.MockWebServer()
        fun xml(path: String, child: String = "") = "<d:multistatus xmlns:d=\"DAV:\">" +
            listOf(path, child).filter { it.isNotEmpty() }.joinToString("") { "<d:response><d:href>$it</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" } + "</d:multistatus>"
        server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(207).setBody(xml("/dav/", "/dav/room/")))
        server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(207).setBody(xml("/dav/room/")))
        server.start()
        val serverRoot = server.url("/dav/").toString()
        try { open().use { s ->
            var selected: String? = null
            s.onActivity { WebDavFolderDialog.show(it, WebDavAccount(serverRoot, "user", "secret")) { value -> selected = value } }
            val deadline = System.currentTimeMillis() + 10000
            while (true) {
                try { onView(withText("▸ room")).perform(click()); break }
                catch (e: androidx.test.espresso.NoMatchingViewException) { if (System.currentTimeMillis() > deadline) throw e; Thread.sleep(50) }
            }
            Thread.sleep(500); instrumentation.waitForIdleSync()
            onView(withText("选择此文件夹")).perform(click())
            assertEquals(server.url("/dav/room/").toString(), selected)
            s.onActivity { assertNull(it.source.state.sync); assertTrue(it.source.state.entries.isEmpty()) }
            assertEquals("PROPFIND", server.takeRequest().method)
            assertEquals("PROPFIND", server.takeRequest().method)
        } } finally { server.shutdown() }
    }
    @Test fun emptyLocalEditorAndRestartPersistence() {
        open().use { s ->
            onView(withText(startsWith("今天没有课程"))).check(matches(isDisplayed()))
            screenshot("empty")
            onView(withText("＋ 课程")).perform(click())
            onView(withHint("Class title")).perform(replaceText("Phone local"), closeSoftKeyboard())
            onView(withText("Save")).perform(click())
            waitFor(s) { it.source.state.entries.size == 1 }
            screenshot("local")
        }
        open().use { s ->
            waitFor(s) { it.source.state.entries.singleOrNull()?.title == "Phone local" }
            onView(withText(startsWith("Phone local"))).perform(scrollTo(), click())
            onView(withText("Edit")).perform(scrollTo(), click())
            onView(withText("All weeks for this timetable item")).perform(click())
            onView(withHint("Course name")).perform(replaceText("Edited local"), closeSoftKeyboard())
            onView(withText("Save")).perform(click())
            waitFor(s) { it.source.state.classOverrides.size == 1 }
        }
        open().use { s ->
            waitFor(s) { it.source.state.classOverrides.singleOrNull()?.title == "Edited local" }
            onView(withText(startsWith("Edited local"))).perform(scrollTo(), click())
            onView(withText("Reset all-weeks edit")).perform(scrollTo(), click())
            onView(withText(startsWith("Phone local"))).check(matches(isDisplayed()))
        }
    }
    @Test fun csvPickerPreviewConfirmationAndRestart() {
        open().use { s ->
            pick("hubei_2026-2027-1.csv"); preview(s)
            s.onActivity { assertTrue(it.source.state.sources.isEmpty()) }
            screenshot("csv-preview")
            onView(withText("确认导入")).perform(click())
            waitFor(s) { it.source.state.imported.size == 131 }
            s.onActivity { assertEquals(13, it.source.state.imported.map { c -> c.seriesId }.distinct().size) }
            onView(withText("周视图")).perform(click())
            repeat(4) { onView(withText("下周 ›")).perform(click()) }
            repeat(8) {
                var hasCard = false
                s.onActivity { a ->
                    fun contains(v: android.view.View): Boolean = v is CourseCard || (v is android.view.ViewGroup && (0 until v.childCount).any { contains(v.getChildAt(it)) })
                    hasCard = contains(a.window.decorView)
                }
                if (!hasCard) onView(withText("下周 ›")).perform(click())
            }
            s.onActivity { a ->
                fun first(v: android.view.View): CourseCard? {
                    if (v is CourseCard) return v
                    if (v is android.view.ViewGroup) for (i in 0 until v.childCount) first(v.getChildAt(i))?.let { return it }
                    return null
                }
                first(a.window.decorView)?.let { it.requestRectangleOnScreen(android.graphics.Rect(0,0,it.width,it.height), true) }
            }
            screenshot("csv-week")
            s.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            Thread.sleep(1000); instrumentation.waitForIdleSync(); screenshot("csv-landscape")
            s.onActivity { a ->
                val scroll = a.window.decorView.findViewWithTag<android.widget.ScrollView>("timetable-scroll")
                assertTrue("Landscape timetable should occupy most of the window", scroll.height >= ((scroll.parent as android.view.View).height - (scroll.parent as android.view.View).paddingTop - (scroll.parent as android.view.View).paddingBottom) * .60)
            }
            s.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            Thread.sleep(1000); instrumentation.waitForIdleSync()
            onView(withText("日视图")).perform(click())
            onView(withText("日期调整")).perform(click())
            onView(withText("Cancel")).perform(click())
        }
        open().use { s -> waitFor(s) { it.source.state.imported.size == 131 }; screenshot("csv-reopened") }
    }
    @Test fun icsPickerAndInvalidFileLeaveExistingDataIntact() {
        open().use { s ->
            pick("wakeup.ics"); preview(s)
            onView(withText("确认导入")).perform(click())
            waitFor(s) { it.source.state.sources.size == 1 }
            var count = 0
            s.onActivity { count = it.source.state.imported.size; assertTrue(count > 0) }
            screenshot("ics")
            // A fresh intended result takes precedence after reinitialization.
            Intents.release(); Intents.init()
            pick("bad.ics")
            val deadline = System.currentTimeMillis() + 10000
            while (true) {
                try { onView(withText("确定")).check(matches(isDisplayed())); break }
                catch (e: androidx.test.espresso.NoMatchingViewException) { if (System.currentTimeMillis() > deadline) throw e }
                Thread.sleep(40)
            }
            onView(withText("确定")).perform(click())
            s.onActivity { assertEquals(1, it.source.state.sources.size); assertEquals(count, it.source.state.imported.size) }
        }
    }
    private fun spinner(index: Int) = object : androidx.test.espresso.ViewAction {
        override fun getConstraints() = isAssignableFrom(android.widget.Spinner::class.java)
        override fun getDescription() = "Choose spinner $index"
        override fun perform(controller: androidx.test.espresso.UiController, view: android.view.View) {
            (view as android.widget.Spinner).setSelection(index); controller.loopMainThreadUntilIdle()
        }
    }
    @Test fun importedScopesDayAdjustmentReplacementAndDelete() {
        open().use { s ->
            pick("wakeup.ics"); preview(s); onView(withText("确认导入")).perform(click())
            waitFor(s) { it.source.state.sources.size == 1 }
            var title = ""
            var id = ""
            s.onActivity { a ->
                title = a.source.state.imported.minBy { it.start }.title
                id = a.source.state.sources.single().id
            }
            onView(withText(startsWith(title))).perform(scrollTo(), click())
            onView(withText("Edit")).perform(scrollTo(), click())
            onView(withText("This week")).perform(click())
            onView(withHint("Course name")).perform(replaceText("Week only"), closeSoftKeyboard())
            onView(withText("Save")).perform(click())
            onView(withText(startsWith("Week only"))).perform(scrollTo(), click())
            onView(withText("Reset this week")).perform(scrollTo(), click())
            onView(withText(startsWith(title))).perform(scrollTo(), click())
            onView(withText("Edit")).perform(scrollTo(), click())
            onView(withText("All weeks for this timetable item")).perform(click())
            onView(withHint("Course name")).perform(replaceText("All weeks"), closeSoftKeyboard())
            onView(withText("Save")).perform(click())
            waitFor(s) { it.source.state.classOverrides.any { e -> e.anchorDate == null && e.title == "All weeks" } }
            onView(withText("日期调整")).perform(click())
            onView(withContentDescription("Day adjustment mode")).perform(spinner(1))
            onView(withText("Save")).perform(click())
            waitFor(s) { it.source.state.dayAdjustments.singleOrNull()?.mode == "none" }
            onView(withText("日期调整")).perform(click())
            onView(withText("Reset day to normal")).perform(click())
            onView(withText(startsWith("All weeks"))).check(matches(isDisplayed()))
            // Change profile-independent ICS source content while retaining stable UID/DTSTART identity.
            var original = ""
            instrumentation.context.assets.open("wakeup.ics").use { original = it.bufferedReader().readText() }
            val changed = IcsScheduleImporter().parse(ScheduleArtifact("changed.ics", original.replace("LOCATION:", "LOCATION:Updated ").toByteArray()))
            s.onActivity { a -> a.source.import(changed, "Replacement", id) { assertTrue(it.isSuccess) } }
            waitFor(s) { it.source.state.sources.single().filename == "changed.ics" }
            s.onActivity { a -> assertEquals(id, a.source.state.sources.single().id); assertEquals("All weeks", a.source.state.classOverrides.single().title) }
            onView(withText("管理")).perform(click())
            onView(withText("导入来源（替换 / 删除）")).perform(click())
            onView(withText(startsWith("Replacement ·"))).perform(scrollTo(), click())
            onView(withText("替换")).perform(click())
            preview(s)
            onView(withText("确认导入")).perform(click())
            waitFor(s) { it.source.state.sources.single().filename == "wakeup.ics" }
            s.onActivity { a -> assertEquals(id, a.source.state.sources.single().id); assertEquals("All weeks", a.source.state.classOverrides.single().title) }
            onView(withText("管理")).perform(click())
            onView(withText("导入来源（替换 / 删除）")).perform(click())
            onView(withText(startsWith("Replacement ·"))).perform(scrollTo(), click())
            onView(withText("删除")).perform(click())
            onView(withText("删除")).perform(click())
            waitFor(s) { it.source.state.sources.isEmpty() && it.source.state.imported.isEmpty() && it.source.state.classOverrides.isEmpty() }
        }
    }

    @Test fun missingCsvProfileCanBeConfiguredBeforeConfirmation() {
        open().use { s ->
            pick("future.csv"); waitFor(s) { !it.busy }
            onView(withContentDescription("第一周周一 yyyy-MM-dd")).perform(scrollTo(), replaceText("2027-08-30"), closeSoftKeyboard())
            onView(withContentDescription("分阶段节次")).perform(scrollTo(), replaceText(TermProfileForm.phasesToText(BuiltInProfiles.term2026)), closeSoftKeyboard())
            onView(withText("保存")).perform(object : androidx.test.espresso.ViewAction {
                override fun getConstraints() = isAssignableFrom(android.widget.Button::class.java)
                override fun getDescription() = "Confirm profile with native button action"
                override fun perform(controller: androidx.test.espresso.UiController, view: android.view.View) { view.performClick(); controller.loopMainThreadUntilIdle() }
            })
            preview(s)
            s.onActivity { assertTrue(it.source.state.imported.isEmpty()) }
            onView(withText("确认导入")).perform(click())
            waitFor(s) { it.source.state.imported.size == 131 }
            s.onActivity { assertEquals("2027-2028-1", it.source.state.sources.single().termKey) }
        }
    }

}
