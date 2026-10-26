package com.dormpanel.phone
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.schedule.*
import com.dormpanel.app.schedule.testing.*
import org.junit.Test
class PhoneFolderUiTest {
    @Test fun productionSharedEntryAndImportNavigateWithoutWrites() {
        val i=InstrumentationRegistry.getInstrumentation()
        V3UiFixture(i.targetContext).use { f->
            FolderUiChecks.installListing(f)
            val launched=java.util.concurrent.atomic.AtomicBoolean(false)
            Thread { Thread.sleep(750);if(!launched.get())android.os.ParcelFileDescriptor.AutoCloseInputStream(i.uiAutomation.executeShellCommand("am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10008000 -n ${i.targetContext.packageName}/com.dormpanel.phone.TimetableActivity")).use { it.readBytes() } }.apply { isDaemon=true;start() }
            ActivityScenario.launch(TimetableActivity::class.java).use { s->
                launched.set(true)
                val end=System.currentTimeMillis()+20000
                while(true) { var ready=false;s.onActivity { ready=it.source.ready };if(ready)break;check(System.currentTimeMillis()<end);Thread.sleep(80) }
                FolderUiChecks.sharedDraft(f,{ action->s.onActivity { action(it) } },SharedScheduleSettings(i.targetContext),{
                    onView(withText("管理")).perform(click());onView(withText("WebDAV 共享课表（双向同步）")).perform(click())
                },{a->(a as TimetableActivity).source},{a->(a as TimetableActivity).sharedSync},"phone")
            }
        }
    }
}
