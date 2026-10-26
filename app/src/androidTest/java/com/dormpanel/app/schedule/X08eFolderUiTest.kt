package com.dormpanel.app.schedule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.IsolatedDashboardRule
import com.dormpanel.app.MainActivity
import com.dormpanel.app.dashboard.DashboardViewModel
import com.dormpanel.app.schedule.testing.*
import org.junit.Rule
import org.junit.Test
class X08eFolderUiTest {
    @get:Rule val persistence=IsolatedDashboardRule()
    @Test fun sharedDraftAndImportNavigateWithoutWrites() {
        V3UiFixture(InstrumentationRegistry.getInstrumentation().targetContext).use { f->
            FolderUiChecks.installListing(f)
            ActivityScenario.launch(MainActivity::class.java).use { s->
                val end=System.currentTimeMillis()+20000
                while(true) { var ready=false;s.onActivity { ready=ViewModelProvider(it)[DashboardViewModel::class.java].schedule.ready };if(ready)break;check(System.currentTimeMillis()<end);Thread.sleep(80) }
                FolderUiChecks.firstDirectoryRequired(f,{ action->s.onActivity { action(it) } },SharedScheduleSettings(InstrumentationRegistry.getInstrumentation().targetContext),{
                    s.onActivity { a->val vm=ViewModelProvider(a)[DashboardViewModel::class.java];SharedScheduleDialog.show(a,vm.schedule,vm.sharedSchedule,true) }
                },{a->ViewModelProvider(a as MainActivity)[DashboardViewModel::class.java].sharedSchedule},"x08e")
                FolderUiChecks.sharedDraft(f,{ action->s.onActivity { action(it) } },SharedScheduleSettings(InstrumentationRegistry.getInstrumentation().targetContext),{
                    s.onActivity { a->val vm=ViewModelProvider(a)[DashboardViewModel::class.java];SharedScheduleDialog.show(a,vm.schedule,vm.sharedSchedule,true) }
                },{a->ViewModelProvider(a as MainActivity)[DashboardViewModel::class.java].schedule},{a->ViewModelProvider(a as MainActivity)[DashboardViewModel::class.java].sharedSchedule},"x08e")
            }
        }
    }
}
