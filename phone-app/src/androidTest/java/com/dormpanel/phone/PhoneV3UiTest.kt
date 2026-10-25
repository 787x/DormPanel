package com.dormpanel.phone

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.schedule.*
import com.dormpanel.app.schedule.testing.V3UiFixture
import org.hamcrest.Matchers.containsString
import org.junit.Assert.*
import org.junit.Test

class PhoneV3UiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun waitFor(s: ActivityScenario<TimetableActivity>, condition: (TimetableActivity) -> Boolean) {
        val end = System.currentTimeMillis() + 90000
        while (System.currentTimeMillis() < end) { var done=false; s.onActivity { done=condition(it) }; if(done)return; Thread.sleep(100) }
        error("Phone v3 UI did not reach expected state")
    }
    private fun stage(text:String) { instrumentation.sendStatus(0,android.os.Bundle().apply { putString("stream","PR55 Phone UI: $text\n") }) }
    private fun launchPhone(): ActivityScenario<TimetableActivity> {
        val launched=java.util.concurrent.atomic.AtomicBoolean(false)
        val pkg=instrumentation.targetContext.packageName; check(pkg.endsWith(".testbed"))
        // This MIUI test device blocks ActivityScenario's background launch. Launch only
        // our testbed via the ordinary MAIN/LAUNCHER intent; do not change OS permissions.
        Thread {
            Thread.sleep(750)
            if(!launched.get()) {
                val launch=instrumentation.uiAutomation.executeShellCommand("am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10008000 -n $pkg/com.dormpanel.phone.TimetableActivity")
                android.os.ParcelFileDescriptor.AutoCloseInputStream(launch).use { it.readBytes() }
            }
        }.apply { isDaemon=true; start() }
        return ActivityScenario.launch(TimetableActivity::class.java).also { launched.set(true) }
    }
    @Test(timeout=240000) fun createEditAndJoinThroughProductionPhoneDialogs() {
        stage("start")
        V3UiFixture(instrumentation.targetContext).use { fixture ->
            var dataset = ""
            launchPhone().use { scenario ->
                stage("first Activity launched")
                waitFor(scenario) { it.source.ready && !it.sharedSync.busy }
                onView(withText("管理")).perform(click()); onView(withText("WebDAV 共享课表（双向同步）")).perform(click())
                onView(withText("创建 Sync v3 数据集（保留本地课程）")).perform(scrollTo(),click()); onView(withText("创建")).perform(click())
                waitFor(scenario) { !it.sharedSync.busy && it.source.state.sync != null }
                stage("v3 created")
                scenario.onActivity { dataset=it.source.state.sync!!.datasetId }
                onView(withText("关闭")).perform(click())
                onView(withText("＋ 课程")).perform(click()); onView(withContentDescription("Class title")).perform(replaceText("PR55 Phone UI"),closeSoftKeyboard())
                onView(withText("Save")).perform(click())
                waitFor(scenario) { it.source.state.entries.any { row->row.title=="PR55 Phone UI" } && !it.sharedSync.busy }
                waitFor(scenario) {
                    if (!it.sharedSync.busy && !it.source.applyingSnapshot) {
                        if (fixture.published("PR55 Phone UI")) true else { it.sharedSync.sync(); false }
                    } else false
                }
                stage("course publication confirmed")
            }
            stage("first Activity closed")
            fixture.nextWorkspace()
            stage("empty workspace selected")
            launchPhone().use { scenario ->
                stage("second Activity launched")
                waitFor(scenario) { it.source.ready && !it.sharedSync.busy }
                onView(withText("管理")).perform(click()); onView(withText("WebDAV 共享课表（双向同步）")).perform(click())
                onView(withText("发现并加入 Sync v3 数据集")).perform(scrollTo(),click())
                stage("discovery requested")
                waitFor(scenario) { !it.sharedSync.busy }
                onView(withText(containsString(dataset))).perform(click())
                for(round in 0..7) {
                    waitFor(scenario) { !it.sharedSync.busy }
                    var verified=false;scenario.onActivity { verified=it.sharedSync.status.contains("完整业务快照已验证") }
                    if(verified)break
                    onView(withText("继续预检")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click())
                }
                scenario.onActivity { assertFalse(it.source.syncStore!!.isV3());assertTrue(it.source.state.entries.isEmpty()) }
                onView(withText("加入并继续验证")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click())
                stage("join confirmed")
                waitFor(scenario) {
                    if (!it.sharedSync.busy && !it.source.applyingSnapshot && it.source.state.entries.isEmpty()) it.sharedSync.sync()
                    it.source.state.entries.any { row->row.title=="PR55 Phone UI" }
                }
                scenario.onActivity { assertEquals(dataset,it.source.state.sync!!.datasetId); assertFalse(it.source.readOnly) }
                val bitmap=instrumentation.uiAutomation.takeScreenshot()
                java.io.File(instrumentation.targetContext.getExternalFilesDir(null),"pr55-phone-v3-ui.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
                onView(withText("关闭")).perform(click())
            }
        }
    }
}
