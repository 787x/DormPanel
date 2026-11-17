package com.dormpanel.app

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.longClick
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.dashboard.DashboardViewModel
import com.dormpanel.app.productivity.ProductivityCardView
import com.dormpanel.app.schedule.*
import org.hamcrest.CoreMatchers.equalTo
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class ProductivitySharedCardTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrument.targetContext
    private val memoBody = "周五宿舍检查\n记得带钥匙\r\n" + "备用".repeat(1100) + "\n备忘末尾"
    private val longTask = "多行任务\r\n" + "长".repeat(500) + "\n任务末尾"

    @Test fun importConnectAndReturnToLegacy() {
        val url = InstrumentationRegistry.getArguments().getString("pr88Url")
        assumeNotNull(url)
        check(context.packageName in setOf("com.dormpanel.app.testbed", "com.dormpanel.app.pr89testbed"))
        val prefix = if (context.packageName.endsWith(".pr89testbed")) "pr89" else "pr88"
        check(Regex("http://127[.]0[.]0[.]1:[0-9]+/$prefix-[0-9a-f-]{36}/").matches(requireNotNull(url)))
        val account = WebDavAccount(url, "fixture", "fixture")
        val store = ScheduleStores.create(context) as RoomScheduleStore
        if (!store.isV3()) store.prepareV3(url, sharedFormat = true)
        settle(store, account)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var ids: List<String>
            waitFor {
                var ready = false
                scenario.onActivity { activity ->
                    val vm = ViewModelProvider(activity)[DashboardViewModel::class.java]
                    ready = vm.stateHolder.state.loaded && vm.productivity.ready
                }
                ready
            }
            scenario.onActivity { activity ->
                val vm = ViewModelProvider(activity)[DashboardViewModel::class.java]
                vm.stateHolder.state.cards.forEach { vm.stateHolder.delete(it.id) }
                ids = listOf("todo", "todo", "memo", "timer").map { kind ->
                    vm.stateHolder.add(vm.catalog.candidates.first { it.providerType == kind })
                    vm.stateHolder.state.cards.last().id
                }
                vm.productivity.addTodo(ids[0], "检查 WebDAV 回执")
                vm.productivity.addTodo(ids[0], "整理下周课表")
                repeat(10) { vm.productivity.addTodo(ids[0], "$it $longTask") }
                vm.productivity.addTodo(ids[1], "第二张卡片的本机内容")
                vm.productivity.saveMemo(ids[2], memoBody)
                vm.productivity.configureTimer(ids[3], 300_000)
            }
            fun card(index: Int) = onView(withTagValue(equalTo(ids[index] as Any)))
            fun description(index: Int): String {
                var text = ""
                scenario.onActivity { activity ->
                    text = activity.window.decorView.findViewWithTag<ProductivityCardView>(ids[index]).contentDescription.toString()
                }
                return text
            }
            waitFor { "检查 WebDAV 回执" in description(0) && "记得带钥匙" in description(2) }
            screenshot("x08e-local")
            card(0).perform(longClick())
            onView(withText("数据来源")).check(matches(isDisplayed()))
            screenshot("x08e-source")
            onView(withText("导入这张卡片的本地内容")).perform(click())
            waitText("确认导入并绑定")
            checkPreview("12 项任务，0 项已完成，12 项未完成", "另有 6 项任务未展示")
            onView(withText("取消")).perform(click())
            assertTrue(load(store).content.lists.isEmpty())
            assertFalse("共享" in description(0))
            card(0).perform(longClick())
            onView(withText("导入这张卡片的本地内容")).perform(click())
            waitText("确认导入并绑定")
            screenshot("x08e-import-review")
            onView(withText("确认导入并绑定")).perform(click())
            waitFor { "共享" in description(0) }
            val view = load(store)
            val list = view.content.lists.single { view.content.tasks(it.id).any { task -> task.text == "检查 WebDAV 回执" } }
            card(1).perform(longClick())
            onView(withText("连接已有共享Todo")).perform(click())
            waitText(list.name)
            onView(withText(list.name)).perform(click())
            onView(withText("连接已有内容")).perform(click())
            waitFor { "检查 WebDAV 回执" in description(1) && "共享" in description(1) }

            card(2).perform(longClick())
            onView(withText("导入这张卡片的本地内容")).perform(click())
            waitText("确认导入并绑定")
            checkPreview("字符", "正文已省略")
            onView(withText("确认导入并绑定")).perform(click())
            waitFor { "共享" in description(2) }
            val updated = load(store)
            assertEquals(memoBody, updated.content.memos.single().body)
            assertEquals(List(10) { "$it $longTask" }, updated.content.tasks(list.id).drop(2).map { it.text })
            val task = updated.content.tasks(list.id).first { it.text == "检查 WebDAV 回执" }
            card(0).perform(click())
            onView(org.hamcrest.Matchers.allOf(withText("标为完成"), isDescendantOfA(withTagValue(equalTo(task.id as Any))))).perform(click())
            waitFor { load(store).content.tasks(list.id).first { it.id == task.id }.completed }
            instrument.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            waitFor { "剩余 11 项" in description(0) && "剩余 11 项" in description(1) }
            screenshot("x08e-shared")

            card(1).perform(longClick())
            onView(withText("切回本地")).perform(click())
            waitFor { "第二张卡片的本机内容" in description(1) }
            scenario.onActivity { activity ->
                val vm = ViewModelProvider(activity)[DashboardViewModel::class.java]
                assertEquals(300_000L, vm.productivity.state(ids[3]).timer.duration)
                assertEquals(12, vm.productivity.state(ids[0]).todos.size)
                assertEquals(memoBody, vm.productivity.state(ids[2]).memo)
                vm.stateHolder.delete(ids[1])
            }
            assertEquals(12, load(store).content.tasks(list.id).size)
            settle(store, account)
            assertFalse(load(store).pending)
        }
    }

    @Test fun receivesPhoneWidgetAndOfflineMemo() {
        val url = InstrumentationRegistry.getArguments().getString("pr88PeerUrl")
        assumeNotNull(url)
        check(context.packageName == "com.dormpanel.app.testbed")
        check(Regex("http://127[.]0[.]0[.]1:[0-9]+/pr88-[0-9a-f-]{36}/").matches(requireNotNull(url)))
        val store = ScheduleStores.create(context) as RoomScheduleStore
        settle(store, WebDavAccount(url, "fixture", "fixture"))
        val view = load(store)
        val phoneList = view.content.lists.single { it.name == "开学准备" }
        assertTrue(view.content.tasks(phoneList.id).single { it.text == "确认实验室开放时间" }.completed)
        assertEquals("离线修改\n原样保留", view.content.memos.single { it.title == "宿舍检查" }.body)
    }

    @Test fun receivesFnOsEditAndPhoneWidget() {
        val url = InstrumentationRegistry.getArguments().getString("pr89Url")
        assumeNotNull(url)
        check(context.packageName.endsWith(".pr89testbed"))
        check(Regex("http://127[.]0[.]0[.]1:[0-9]+/pr89-[0-9a-f-]{36}/").matches(requireNotNull(url)))
        val store = ScheduleStores.create(context) as RoomScheduleStore
        settle(store, WebDavAccount(url, "fixture", "fixture"))
        val view = load(store)
        val task = view.content.lists.flatMap { view.content.tasks(it.id) }.single { it.text == "fnOS 新增的共享任务" }
        assertTrue(task.completed)
        assertEquals("  fnOS 编辑\r\n第二行🙂\n  ", view.content.memos.single { it.title == "fnOS 编辑的共享备忘" }.body)
        println("PR89_X08E_HASH=${view.version!!.businessHash}")
    }

    private fun checkPreview(vararg fragments: String) {
        onView(withId(android.R.id.message)).check { view, missing ->
            if (missing != null) throw missing
            val text = (view as android.widget.TextView).text.toString()
            assertTrue(text.length < 1_200)
            fragments.forEach { assertTrue(text.contains(it)) }
            assertFalse(text.contains("任务末尾"))
            assertFalse(text.contains("备忘末尾"))
        }
    }

    private fun load(store: RoomScheduleStore): ProductivityView {
        val data = LinkedBlockingQueue<Result<ProductivityView>>()
        store.loadProductivity { data.add(it) }
        return requireNotNull(data.poll(10, TimeUnit.SECONDS)).getOrThrow()
    }

    private fun settle(store: RoomScheduleStore, account: WebDavAccount) {
        repeat(8) { if (store.synchronizeV3(account) == "OBSERVED") return }
        error("共享测试端点未收敛")
    }

    private fun waitText(text: String) = waitFor {
        runCatching { onView(withText(text)).check(matches(isDisplayed())) }.isSuccess
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        error("等待界面或共享数据超时")
    }

    private fun screenshot(name: String) {
        instrument.waitForIdleSync()
        Thread.sleep(250)
        val bitmap = instrument.uiAutomation.takeScreenshot()
        context.getExternalFilesDir(null)!!.resolve("$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
