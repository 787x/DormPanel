package com.dormpanel.app.schedule

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.IsolatedDashboardRule
import com.dormpanel.app.MainActivity
import com.dormpanel.app.dashboard.DashboardViewModel
import com.dormpanel.app.schedule.testing.V3UiFixture
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class X08eV3UiTest {
    @get:Rule val persistence=IsolatedDashboardRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private fun waitFor(s:ActivityScenario<MainActivity>, condition:(DashboardViewModel)->Boolean) {
        val end=System.currentTimeMillis()+90000
        while(System.currentTimeMillis()<end) { var done=false; s.onActivity { done=condition(ViewModelProvider(it)[DashboardViewModel::class.java]) }; if(done)return; Thread.sleep(100) }
        error("X08E v3 UI did not reach expected state")
    }
    @Test fun dashboardSharedDialogCreatesV3AndRealEditorSavesToActiveStore() {
        V3UiFixture(instrumentation.targetContext).use { fixture ->
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { it.schedule.ready && !it.sharedSchedule.busy }
                scenario.onActivity { activity -> val vm=ViewModelProvider(activity)[DashboardViewModel::class.java]; SharedScheduleDialog.show(activity,vm.schedule,vm.sharedSchedule,true) }
                onView(withText("创建 Sync v3 数据集（保留本地课程）")).perform(scrollTo(),click()); onView(withText("创建")).perform(click())
                waitFor(scenario) { !it.sharedSchedule.busy && it.schedule.state.sync!=null }
                onView(withText("关闭")).perform(click())
                var editors:ScheduleEditors?=null
                scenario.onActivity { activity -> val vm=ViewModelProvider(activity)[DashboardViewModel::class.java]; editors=ScheduleEditors(activity,vm.schedule,vm.appearance); editors!!.entry() }
                onView(withContentDescription("Class title")).perform(replaceText("PR55 X08E UI"),closeSoftKeyboard()); onView(withText("Save")).perform(click())
                waitFor(scenario) {
                    if (!it.sharedSchedule.busy && !it.schedule.applyingSnapshot) {
                        if (it.sharedSchedule.status.contains("已由指定远端回读") || it.sharedSchedule.status.contains("DAG 已合并")) true else { it.sharedSchedule.sync(); false }
                    } else false
                }
                scenario.onActivity { activity ->
                    val vm=ViewModelProvider(activity)[DashboardViewModel::class.java]
                    assertTrue(vm.schedule.state.entries.any { it.title=="PR55 X08E UI" }); assertTrue(vm.schedule.syncStore!!.isV3()); editors?.close()
                    SharedScheduleDialog.show(activity,vm.schedule,vm.sharedSchedule,true)
                }
                val bitmap=instrumentation.uiAutomation.takeScreenshot()
                java.io.File(instrumentation.targetContext.getExternalFilesDir(null),"pr55-x08e-v3-ui.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
                onView(withText("关闭")).perform(click())
            }
            assertTrue(fixture.files.values.mapNotNull { runCatching { SyncV3Codec.decode(it) }.getOrNull() }.any { it.type==3 && it.state?.entries?.any { row->row.title=="PR55 X08E UI" }==true })
        }
    }
}
