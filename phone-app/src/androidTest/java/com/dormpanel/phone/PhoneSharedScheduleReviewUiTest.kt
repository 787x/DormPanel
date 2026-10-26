package com.dormpanel.phone

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.espresso.assertion.ViewAssertions.*
import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.schedule.*
import com.dormpanel.app.schedule.testing.FolderUiChecks
import com.dormpanel.app.schedule.testing.V3UiFixture
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import org.hamcrest.Matchers.containsString

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
                    s.onActivity { assertTrue(it.sharedSync.presentation.confirmationRejected) }
                    onView(withText(containsString("原操作未执行"))).check(matches(isDisplayed()))
                    assertEquals(before, f.checkpoints()); assertEquals(requestCount, f.requests.size)
                    assertTrue(f.files.isEmpty())
                    onView(withText("关闭")).perform(click())
                }
            }
        }
    }
    @Test fun localReadFailureHidesWriteEntrancesAndRecoveryRestoresEditing() {
        V3UiFixture(InstrumentationRegistry.getInstrumentation().targetContext).use { f ->
            launchPhoneTestActivity().use { s ->
                waitFor(s) { it.source.editable && !it.sharedSync.busy }
                s.onActivity { assertTrue(it.source.saveEntry("原课程", 1, 480, 530)) }
                val end = System.currentTimeMillis() + 10000
                while (f.entries().isEmpty()) { check(System.currentTimeMillis() < end); Thread.sleep(40) }
                val original = f.entries(); val restore = f.injectReadFailure()
                s.onActivity { it.source.reload() }
                waitFor(s) { it.source.error && !it.source.applyingSnapshot }
                onView(withText(containsString("暂不可编辑"))).check(matches(isDisplayed()))
                onView(withText("重新读取本机课表")).check(matches(isDisplayed()))
                onView(withText("周视图")).perform(click())
                onView(withText("重新读取本机课表")).check(matches(isDisplayed()))
                onView(withText("日视图")).perform(click())
                onView(withText("＋ 课程")).check(doesNotExist())
                onView(withText("日期调整")).check(doesNotExist())
                s.onActivity {
                    assertFalse(it.source.saveEntry("不应新增", 1, 480, 530))
                    it.source.deleteEntry(original.single().id)
                }
                assertEquals(original, f.entries())
                onView(withText("重新读取本机课表")).perform(click())
                waitFor(s) { it.source.error && !it.source.retryingLocalRead && !it.source.applyingSnapshot }
                onView(withText("重新读取本机课表")).check(matches(isEnabled()))
                assertEquals(original, f.entries())
                val checkpoints = f.checkpoints().minus(1); val requestCount = f.requests.size
                restore(); onView(withText("重新读取本机课表")).perform(click())
                waitFor(s) { it.source.editable && !it.sharedSync.busy }
                onView(withText("重新读取本机课表")).check(doesNotExist())
                assertEquals(checkpoints, f.checkpoints()); assertEquals(requestCount, f.requests.size)
                onView(withText("＋ 课程")).check(matches(isDisplayed()))
                s.onActivity { assertTrue(it.source.saveEntry("恢复后课程", 1, 480, 530)) }
                val savedEnd = System.currentTimeMillis() + 10000
                while (f.entries().size != 2) { check(System.currentTimeMillis() < savedEnd); Thread.sleep(40) }
                assertTrue(f.entries().any { it.title == "原课程" })
            }
        }
    }
    @Test fun initialFailureRetryDoesNotContactTheBoundServerOrChangeEvidence() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        V3UiFixture(context, initialReadFailure = true).use { f ->
            launchPhoneTestActivity().use { s ->
                waitFor(s) { it.source.error && !it.source.ready }
                onView(withText("重新读取本机课表")).check(matches(isDisplayed()))
                f.repairInitialReadFailure()
                val before = f.checkpoints(); val count = f.requests.size
                val settings = SharedScheduleSettings(context); val history = settings.lastResult to settings.lastSuccess
                onView(withText("重新读取本机课表")).perform(click())
                waitFor(s) { it.source.ready && !it.source.error && !it.source.retryingLocalRead && !it.sharedSync.busy }
                onView(withText("重新读取本机课表")).check(doesNotExist())
                assertEquals("首次读取后可见课程", f.entries().single().title)
                assertEquals(before, f.checkpoints()); assertEquals(count, f.requests.size)
                assertEquals(history, settings.lastResult to settings.lastSuccess)
            }
        }
    }
}
