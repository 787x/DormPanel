package com.dormpanel.phone

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.schedule.*
import com.dormpanel.app.schedule.testing.FolderUiChecks
import com.dormpanel.app.schedule.testing.V3UiFixture
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class PhoneSharedScheduleReviewUiTest {
    private fun waitFor(s: ActivityScenario<TimetableActivity>, condition: (TimetableActivity) -> Boolean) {
        val deadline = System.currentTimeMillis() + 20000
        while (System.currentTimeMillis() < deadline) {
            var done = false; s.onActivity { done = condition(it) }
            if (done) return
            Thread.sleep(50)
        }
        error("Review confirmation did not settle")
    }
    @Test fun openCreationConfirmationCannotBecomeAnotherProtocolOperation() {
        for (legacy in listOf(true, false)) {
            V3UiFixture(InstrumentationRegistry.getInstrumentation().targetContext).use { f ->
                launchPhoneTestActivity().use { s ->
                    waitFor(s) { it.source.ready && !it.sharedSync.busy && it.sharedSync.presentation.mode == SharedMode.UNBOUND }
                    onView(withText("管理")).perform(click())
                    onView(withText("WebDAV 共享课表（双向同步）")).perform(click())
                    if (legacy) FolderUiChecks.manage("旧版兼容与升级", "兼容旧客户端：创建 v2 课表")
                    else onView(withText("创建新的共享课表")).perform(scrollTo(), click())
                    // Keep the real confirmation open while changing only the isolated binding.
                    s.onActivity {
                        val store = it.source.syncStore!!
                        if (legacy) store.prepareV3(f.account.baseUrl)
                        else store.v2Start(ScheduleCommit(UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(), emptyList(), ScheduleState()), true)
                        it.source.reload()
                    }
                    waitFor(s) { !it.source.applyingSnapshot && !it.sharedSync.busy && it.sharedSync.presentation.mode == if (legacy) SharedMode.V3 else SharedMode.V2 }
                    val before = f.checkpoints(); val requestCount = f.requests.size
                    onView(withText(if (legacy) "创建旧版课表" else "创建")).perform(click())
                    waitFor(s) { !it.sharedSync.busy && it.sharedSync.status.contains("状态已变化") }
                    assertEquals(before, f.checkpoints()); assertEquals(requestCount, f.requests.size)
                    assertTrue(f.files.isEmpty())
                    onView(withText("关闭")).perform(click())
                }
            }
        }
    }
}
