package com.dormpanel.phone

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import com.dormpanel.app.schedule.*
import org.junit.Assert.*
import org.junit.Test

class HeaderSyncButtonTest {
    @Test fun refreshSitsBeforeManagementAndFollowsExistingSyncAvailability() {
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        check(c.packageName.endsWith(".pr72testbed"))
        ActivityScenario.launch<TimetableActivity>(android.content.Intent(c,TimetableActivity::class.java)).use { scenario ->
            var ready=false
            val deadline=System.currentTimeMillis()+5000
            while(!ready && System.currentTimeMillis()<deadline) {
                scenario.onActivity { ready=it.source.ready && !it.source.error && !it.sharedSync.busy }
                if(!ready) Thread.sleep(50)
            }
            assertTrue("Local presentation is ready",ready)
            scenario.onActivity { activity ->
                val field=TimetableActivity::class.java.getDeclaredField("sharedRefreshButton").apply { isAccessible=true }
                val button=field.get(activity) as android.widget.Button
                val parent=button.parent as android.view.ViewGroup
                val next=parent.getChildAt(parent.indexOfChild(button)+1) as android.widget.Button
                assertEquals("管理",next.text.toString())
                val controller=activity.sharedSync
                val presentation=controller.javaClass.getDeclaredField("presentation").apply { isAccessible=true }
                val old=presentation.get(controller)
                val update=TimetableActivity::class.java.getDeclaredMethod("updateSharedStatus").apply { isAccessible=true }
                try {
                    presentation.set(controller,SharedSchedulePresentation(mode=SharedMode.UNCONFIGURED,ready=true))
                    update.invoke(activity); assertFalse(button.isEnabled)
                    presentation.set(controller,SharedSchedulePresentation(mode=SharedMode.V3,ready=true,recovery=true))
                    update.invoke(activity); assertFalse(button.isEnabled)
                    presentation.set(controller,SharedSchedulePresentation(mode=SharedMode.V3,ready=true))
                    update.invoke(activity); assertTrue(button.isEnabled)
                    val running=controller.javaClass.getDeclaredField("busy").apply { isAccessible=true }
                    val previous=running.getBoolean(controller)
                    try { running.setBoolean(controller,true); update.invoke(activity); assertFalse(button.isEnabled) }
                    finally { running.setBoolean(controller,previous) }
                    // No binding or network is created by this presentation-only test.
                    assertEquals("立即同步共享课表",button.contentDescription.toString())
                } finally { presentation.set(controller,old); update.invoke(activity) }
            }
        }
    }
}
