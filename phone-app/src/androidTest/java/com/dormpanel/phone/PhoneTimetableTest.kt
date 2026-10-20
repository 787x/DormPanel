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
        dbName = "phone-test-${UUID.randomUUID()}.db"
        ScheduleStores.overrideFactory = { context -> RoomScheduleStore(Room.databaseBuilder(context, ScheduleDatabase::class.java, dbName).build()) }
        TermScheduleProfileStore.overrideNamespace = "phone-test-${UUID.randomUUID()}"
        Intents.init()
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
    private fun open() = ActivityScenario.launch(TimetableActivity::class.java).also { waitFor(it) { a -> a.source.ready } }
    private fun pick(filename: String) {
        Intents.intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(ActivityResult(Activity.RESULT_OK,
            Intent().setData(Uri.parse("content://com.dormpanel.timetable.test.fixtures/$filename"))))
        onView(withText("管理")).perform(click())
        onView(withText("导入 ICS / 湖北大学 CSV")).perform(click())
    }
    private fun preview(s: ActivityScenario<TimetableActivity>) {
        waitFor(s) { !it.busy }
        onView(withText("确认导入")).check(matches(isDisplayed()))
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync(); Thread.sleep(350) // Let native dialog transitions finish before visual QA.
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "phone-$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }; bitmap.recycle()
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
            onView(withText(startsWith("Phone local"))).perform(click())
            onView(withText("Edit")).perform(click())
            onView(withText("All weeks for this timetable item")).perform(click())
            onView(withHint("Course name")).perform(replaceText("Edited local"), closeSoftKeyboard())
            onView(withText("Save")).perform(click())
            waitFor(s) { it.source.state.classOverrides.size == 1 }
        }
        open().use { s ->
            waitFor(s) { it.source.state.classOverrides.singleOrNull()?.title == "Edited local" }
            onView(withText(startsWith("Edited local"))).perform(click())
            onView(withText("Reset all-weeks edit")).perform(click())
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
            screenshot("csv-week")
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
            onView(withText("Edit")).perform(click())
            onView(withText("This week")).perform(click())
            onView(withHint("Course name")).perform(replaceText("Week only"), closeSoftKeyboard())
            onView(withText("Save")).perform(click())
            onView(withText(startsWith("Week only"))).perform(click())
            onView(withText("Reset this week")).perform(click())
            onView(withText(startsWith(title))).perform(scrollTo(), click())
            onView(withText("Edit")).perform(click())
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
            onView(withText(startsWith("Replacement ·"))).perform(click())
            onView(withText("替换")).perform(click())
            preview(s)
            onView(withText("确认导入")).perform(click())
            waitFor(s) { it.source.state.sources.single().filename == "wakeup.ics" }
            s.onActivity { a -> assertEquals(id, a.source.state.sources.single().id); assertEquals("All weeks", a.source.state.classOverrides.single().title) }
            onView(withText("管理")).perform(click())
            onView(withText("导入来源（替换 / 删除）")).perform(click())
            onView(withText(startsWith("Replacement ·"))).perform(click())
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
            onView(withText("保存")).perform(click())
            preview(s)
            s.onActivity { assertTrue(it.source.state.imported.isEmpty()) }
            onView(withText("确认导入")).perform(click())
            waitFor(s) { it.source.state.imported.size == 131 }
            s.onActivity { assertEquals("2027-2028-1", it.source.state.sources.single().termKey) }
        }
    }

}
