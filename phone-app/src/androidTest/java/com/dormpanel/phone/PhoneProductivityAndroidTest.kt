package com.dormpanel.phone

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.dormpanel.app.schedule.*
import org.hamcrest.Matchers.allOf
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class PhoneProductivityAndroidTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val context = instrument.targetContext
    private val data get() = PhoneProductivity.data

    @Test fun localFileVersionsRetainOriginalContentAndRejectStaleDeletes() {
        val name = "pr88-local-${AndroidProductivity.id()}"
        val local = LocalProductivity(context, name)
        val access = AndroidProductivity(context, null, local)
        val list = ProductivityRecord.TodoList(AndroidProductivity.id(), "期末")
        val task = ProductivityRecord.Task(AndroidProductivity.id(), list.id, "保留空白 ", true, 1)
        val memo = ProductivityRecord.Memo(AndroidProductivity.id(), "宿舍", "\n原文\r\n")
        try {
            val initial = await(local::load).getOrThrow().version!!
            await<Unit> { access.save(initial, listOf(list, task, memo).map(ProductivityEdit::Create), it) }.getOrThrow()
            val reopened = await(LocalProductivity(context, name)::load).getOrThrow()
            assertEquals(listOf(task), reopened.content.tasks(list.id))
            assertEquals(memo, reopened.content.memos.single())
            assertTrue(await<Unit> { access.save(initial, listOf(ProductivityEdit.DeleteList(list.id, setOf(task.id))), it) }.isFailure)
            assertTrue(await<Unit> { access.save(reopened.version!!, listOf(ProductivityEdit.DeleteList(list.id)), it) }.isFailure)
            assertTrue(await<Unit> {
                access.save(reopened.version!!, listOf(ProductivityEdit.EditMemo(memo.id, "中".repeat(12_000))), it)
            }.isFailure)
            assertEquals(reopened, await(local::load).getOrThrow())
            val bindingsName = "pr88-bindings-${AndroidProductivity.id()}"
            try {
                val bindings = ProductivityBindings(context, bindingsName)
                bindings.setIf("widget", null, "first")
                assertTrue(runCatching { bindings.setIf("widget", null, "other") }.isFailure)
                assertEquals("first", ProductivityBindings(context, bindingsName).get("widget"))
            } finally { context.deleteFile(bindingsName) }
        } finally { context.deleteFile(name) }
    }

    @Test fun batchImportPreviewIsBoundedAndKeepsOriginals() {
        val url = InstrumentationRegistry.getArguments().getString("pr88ReviewImportUrl")
        assumeNotNull(url)
        check(context.packageName.endsWith(".pr88review"))
        check(Regex("http://127[.]0[.]0[.]1:[0-9]+/pr88-[0-9a-f-]{36}/").matches(requireNotNull(url)))
        val store = requireNotNull(data.shared)
        val account = WebDavAccount(url, "fixture", "fixture")
        store.prepareV3(url, sharedFormat = true)
        settle(store, account)
        val shared = await(store::loadProductivity).getOrThrow()
        val dataset = shared.version!!.datasetId
        val lists = List(5) { ProductivityRecord.TodoList(AndroidProductivity.id(), "导入清单 $it") }
        val tasks = lists.flatMap { list ->
            List(16) { i -> ProductivityRecord.Task(AndroidProductivity.id(), list.id,
                "任务 $i\r\n" + "长".repeat(400) + "\n任务末尾", i % 2 == 0, i) }
        }
        val memos = List(5) { ProductivityRecord.Memo(AndroidProductivity.id(), "导入备忘 $it",
            "首行\r\n" + "正文".repeat(1100) + "\n备忘末尾") }
        val version = load(null).version!!
        await<Unit> { data.save(version, (lists + tasks + memos).map(ProductivityEdit::Create), it) }.getOrThrow()
        val local = load(null)
        val previousMode = PhoneProductivity.bindings.get(PhoneProductivity.MODE)
        try {
            ActivityScenario.launch<ProductivityActivity>(ProductivityActivity.intent(context, ProductivityKind.TODO)).use {
                fun preview() {
                    onView(withText("数据来源")).perform(click())
                    onView(withText("预览并导入本机 Todo 和备忘")).perform(click())
                    waitText("确认导入并切换")
                    onView(withId(android.R.id.message)).check { view, missing ->
                        if (missing != null) throw missing
                        val text = (view as TextView).text.toString()
                        assertTrue(text.length < 5_000)
                        assertTrue(text.contains("另有 2 张清单未展示"))
                        assertTrue(text.contains("另有 2 条备忘未展示"))
                        assertTrue(text.contains("16 项任务，8 项已完成，8 项未完成"))
                        assertTrue(text.contains("另有 10 项任务未展示"))
                        assertTrue(text.contains("正文已省略"))
                        assertFalse(text.contains("任务末尾"))
                        assertFalse(text.contains("备忘末尾"))
                    }
                }
                preview()
                onView(withText("取消")).perform(click())
                assertEquals(previousMode, PhoneProductivity.bindings.get(PhoneProductivity.MODE))
                assertEquals(shared, load(dataset))
                assertEquals(local, load(null))
                preview()
                onView(withText("确认导入并切换")).perform(click())
                waitFor { PhoneProductivity.mode() == dataset }
                val imported = load(dataset).content
                assertEquals(local.content, imported)
                assertEquals(local, load(null))
            }
            val host = AppWidgetHost(context, 8889)
            val widget = host.allocateAppWidgetId()
            try {
                val manager = AppWidgetManager.getInstance(context)
                check(manager.bindAppWidgetIdIfAllowed(widget, ComponentName(context, TodoWidgetProvider::class.java)))
                val imported = load(dataset)
                await<Unit> {
                    ProductivityWidgets.bind(context, widget, ProductivityKind.TODO,
                        ProductivityTarget(dataset, lists.first().id), imported.version!!, it)
                }.getOrThrow()
                assertNotNull(PhoneProductivity.bindings.get("widget-$widget"))
                host.deleteAppWidgetId(widget)
                waitFor { PhoneProductivity.bindings.get("widget-$widget") == null }
                assertEquals(imported, load(dataset))
            } finally {
                host.deleteAppWidgetId(widget)
            }
        } finally {
            PhoneProductivity.bindings.set(PhoneProductivity.MODE, previousMode)
        }
    }

    @Test fun widgetDispatchAndDeleteWithHostStillReportingInstance() {
        org.junit.Assume.assumeTrue(context.packageName.endsWith(".pr88review"))
        val suffix = AndroidProductivity.id().take(8)
        val list = ProductivityRecord.TodoList(AndroidProductivity.id(), "目标清单 $suffix")
        val other = ProductivityRecord.TodoList(AndroidProductivity.id(), "另一清单 $suffix")
        val task = ProductivityRecord.Task(AndroidProductivity.id(), list.id, "打开这条任务 $suffix", false, 0)
        val memo = ProductivityRecord.Memo(AndroidProductivity.id(), "备忘 $suffix", "保留正文")
        val initialVersion = load(null).version!!
        await<Unit> { data.save(initialVersion, listOf(list, other, task, memo).map(ProductivityEdit::Create), it) }.getOrThrow()

        val manager = AppWidgetManager.getInstance(context)
        val host = AppWidgetHost(context, 8888)
        val ids = mutableListOf<Int>()
        ActivityScenario.launch<ProductivityActivity>(ProductivityActivity.intent(context, ProductivityKind.TODO)).use { scenario ->
            fun configure(kind: ProductivityKind, target: ProductivityRecord): Int {
                val id = host.allocateAppWidgetId().also(ids::add)
                val provider = if (kind == ProductivityKind.TODO) TodoWidgetProvider::class.java else MemoWidgetProvider::class.java
                check(manager.bindAppWidgetIdIfAllowed(id, ComponentName(context, provider)))
                scenario.onActivity { host.startAppWidgetConfigureActivityForResult(it, id, 0, 8801, null) }
                waitText("选择小组件数据来源")
                onView(allOf(withText("仅本机"), isDisplayed())).perform(click())
                waitText(target.title)
                onView(allOf(withText(target.title), isDisplayed())).perform(click())
                waitText("数据来源")
                waitFor { PhoneProductivity.bindings.get("widget-$id")?.contains(target.id) == true }
                return id
            }

            try {
                val todo = configure(ProductivityKind.TODO, list)
                val otherTodo = configure(ProductivityKind.TODO, other)
                val memoId = configure(ProductivityKind.MEMO, memo)
                val before = load(null)
                scenario.onActivity { activity ->
                    host.startListening()
                    activity.setContentView(host.createView(activity, todo, manager.getAppWidgetInfo(todo)))
                }
                waitText(task.text)
                val routed = java.util.concurrent.atomic.AtomicInteger()
                val lifecycle = ActivityLifecycleMonitorRegistry.getInstance()
                val router = ActivityLifecycleCallback { activity, stage ->
                    if (stage == Stage.RESUMED && activity is ProductivityWidgetClickActivity) routed.incrementAndGet()
                }
                instrument.runOnMainSync { lifecycle.addLifecycleCallback(router) }
                try {
                    onView(allOf(withId(R.id.productivity_check), hasSibling(withText(task.text)), isDisplayed())).perform(click())
                    waitFor { load(null).content.tasks(list.id).single().completed }
                    waitFor {
                        var focused = false
                        scenario.onActivity { focused = it.hasWindowFocus() }
                        focused
                    }
                    assertEquals(1, routed.get())
                    val completed = load(null)
                    assertTrue(completed.version!!.revision > before.version!!.revision)
                    onView(allOf(withId(R.id.productivity_task), withText(task.text), isDisplayed())).perform(click())
                    waitFor {
                        runCatching {
                            onView(allOf(withHint("任务正文"), withText(task.text))).check(matches(isDisplayed()))
                        }.isSuccess
                    }
                    assertEquals(2, routed.get())
                    assertEquals(completed, load(null))
                    onView(withText("取消")).perform(click())
                    onView(withText("返回 · Todo")).perform(click())
                } finally {
                    instrument.runOnMainSync { lifecycle.removeLifecycleCallback(router) }
                }
                val snapshot = ProductivityWidgets.rows(todo)!!
                fun clickSnapshot(id: Int, binding: String, desired: Boolean) =
                    Intent(ProductivityWidgets.ROW, Uri.parse("dormpanel-productivity://row/$id"))
                        .putExtra("binding", binding).putExtra("task", task.id)
                        .putExtra("revision", snapshot.version.revision).putExtra("hash", snapshot.version.businessHash)
                        .putExtra("completed", desired)
                fun action(click: Intent) {
                    await<Unit> { done ->
                        ProductivityWidgets.rowAction(context, ProductivityKind.TODO, click) { done(Result.success(Unit)) }
                    }.getOrThrow()
                }
                val completed = load(null)
                action(clickSnapshot(todo, snapshot.binding, true))
                action(clickSnapshot(otherTodo, snapshot.binding, false))
                action(clickSnapshot(todo, snapshot.binding, false).putExtra("revision", snapshot.version.revision - 1))
                assertEquals(completed, load(null))

                val rebound = ProductivityTarget(PhoneProductivity.LOCAL, other.id)
                val version = completed.version!!
                await<Unit> { ProductivityWidgets.bind(context, todo, ProductivityKind.TODO, rebound, version, it) }.getOrThrow()
                action(clickSnapshot(todo, snapshot.binding, false))
                val stale = clickSnapshot(todo, snapshot.binding, false).apply { removeExtra("completed") }
                await<Intent?> { callback -> ProductivityWidgets.openTask(context, stale) { callback(Result.success(it)) } }
                    .getOrThrow().also { assertNull(it) }
                assertEquals(completed, load(null))

                assertNotNull(manager.getAppWidgetInfo(todo))
                val competing = LinkedBlockingQueue<Result<Unit>>()
                await<Unit> { deleted ->
                    ProductivityWidgets.bind(context, todo, ProductivityKind.TODO,
                        ProductivityTarget(PhoneProductivity.LOCAL, list.id), version) { competing.add(it) }
                    ProductivityWidgets.delete(context, ProductivityKind.TODO, intArrayOf(todo, memoId, todo)) {
                        deleted(Result.success(Unit))
                    }
                }.getOrThrow()
                assertTrue(competing.poll(10, TimeUnit.SECONDS)!!.isFailure)
                assertNotNull(manager.getAppWidgetInfo(todo))
                waitFor { PhoneProductivity.bindings.get("widget-$todo") == null && ProductivityWidgets.rows(todo) == null }
                assertNotNull(PhoneProductivity.bindings.get("widget-$otherTodo"))
                assertNotNull(PhoneProductivity.bindings.get("widget-$memoId"))
                host.deleteAppWidgetId(otherTodo)
                waitFor { PhoneProductivity.bindings.get("widget-$otherTodo") == null }
                assertNotNull(PhoneProductivity.bindings.get("widget-$memoId"))
                assertEquals(completed, load(null))
            } finally {
                instrument.runOnMainSync {
                    host.stopListening()
                    ids.forEach(host::deleteAppWidgetId)
                }
            }
        }
    }

    @Test fun managementOpensBothLocalEditors() {
        org.junit.Assume.assumeTrue(context.packageName.endsWith(".pr88testbed"))
        val before = PhoneProductivity.bindings.get(PhoneProductivity.MODE)
        PhoneProductivity.bindings.set(PhoneProductivity.MODE, PhoneProductivity.LOCAL)
        val list = "本机清单 ${AndroidProductivity.id().take(8)}"
        val memo = "本机备忘 ${AndroidProductivity.id().take(8)}"
        try {
            ActivityScenario.launch(TimetableActivity::class.java).use {
                waitText("管理")
                onView(withText("管理")).perform(click())
                onView(withText("Todo")).perform(click())
                waitText("仅本机")
                onView(withText("新建清单")).perform(scrollTo(), click())
                onView(withHint("名称")).perform(replaceText(list), closeSoftKeyboard())
                onView(withText("保存")).perform(click())
                waitText(list)
                onView(withText("返回 · Todo")).perform(click())
                onView(withText("管理")).perform(click())
                onView(withText("备忘")).perform(click())
                waitText("仅本机")
                onView(withText("新建备忘")).perform(scrollTo(), click())
                onView(withHint("名称")).perform(replaceText(memo), closeSoftKeyboard())
                onView(withText("保存")).perform(click())
                waitText(memo)
                onView(withText("返回 · 备忘")).perform(click())
            }
            val view = load(null)
            assertTrue(view.content.lists.any { it.name == list })
            assertEquals("", view.content.memos.single { it.title == memo }.body)
        } finally {
            PhoneProductivity.bindings.set(PhoneProductivity.MODE, before)
        }
    }

    @Test fun joinSharedEditAndOperateRealWidgetInstances() {
        val url = InstrumentationRegistry.getArguments().getString("pr88Url")
        assumeNotNull(url)
        check(context.packageName.endsWith(".pr88testbed"))
        check(Regex("http://127[.]0[.]0[.]1:[0-9]+/pr88-[0-9a-f-]{36}/").matches(requireNotNull(url)))
        val store = requireNotNull(data.shared)
        val account = WebDavAccount(url, "fixture", "fixture")
        val remote = V3WebDavStore(account, url, V3Budget(), sharedFormat = true)
        val descriptor = requireNotNull(remote.read(remote.list("").flatMap { remote.list(it) }
            .first { it.substringAfterLast('/').startsWith("descriptor-") }))
        if (!store.isV3()) {
            var preview: UnboundV3JoinPreview? = null
            repeat(8) { if (preview == null) preview = store.previewUnboundV3Join(account, url, descriptor) }
            store.joinVerifiedV3(requireNotNull(preview), account)
        }
        settle(store, account)
        val shared = await(store::loadProductivity).getOrThrow()
        val dataset = shared.version!!.datasetId
        val x08eList = shared.content.lists.single { shared.content.tasks(it.id).any { t -> t.text == "整理下周课表" } }

        PhoneProductivity.bindings.set(PhoneProductivity.MODE, PhoneProductivity.LOCAL)
        val list = ProductivityRecord.TodoList(AndroidProductivity.id(), "开学准备")
        val tasks = listOf("领取教材", "确认实验室开放时间", "整理参考资料", "预约自习室", "更新课程备注", "归还图书").mapIndexed { index, text ->
            ProductivityRecord.Task(AndroidProductivity.id(), list.id, text, index == 5, index)
        }
        val memo = ProductivityRecord.Memo(AndroidProductivity.id(), "宿舍检查",
            "周五 18:00\n\n整理窗台与书桌。\n把备用钥匙放进蓝色收纳盒。\n检查插座电源，离开前关窗。")
        val before = load(null)
        await<Unit> { data.save(before.version!!, (listOf(list, memo) + tasks).map(ProductivityEdit::Create), it) }.getOrThrow()
        val localContent = load(null)
        val localVersion = localContent.version!!
        await<Unit> { PhoneProductivity.local.transfer(store, localVersion, shared.version!!, false, {}, it) }.getOrThrow()
        assertEquals(shared.version, load(dataset).version)
        await<Unit> { PhoneProductivity.local.transfer(store, localVersion, shared.version!!, true, {
            PhoneProductivity.bindings.set(PhoneProductivity.MODE, dataset)
        }, it) }.getOrThrow()
        assertEquals(localContent, load(null))
        assertTrue(load(dataset).pending)
        assertTrue(await<Unit> { PhoneProductivity.local.transfer(store, localVersion, shared.version!!, true, {}, it) }.isFailure)
        assertEquals(memo.body, load(dataset).content.memos.single { it.id == memo.id }.body)
        settle(store, account)

        ActivityScenario.launch<ProductivityActivity>(ProductivityActivity.intent(context, ProductivityKind.TODO,
            ProductivityTarget(dataset, list.id))).use {
            waitText(list.name)
            onView(withText("添加任务")).perform(scrollTo(), click())
            onView(withHint("任务正文")).perform(replaceText("确认打印机状态"), closeSoftKeyboard())
            onView(withText("保存")).perform(click())
            waitFor { load(dataset).content.tasks(list.id).size == 7 }
            screenshot("phone-todo")
        }
        ActivityScenario.launch<ProductivityActivity>(ProductivityActivity.intent(context, ProductivityKind.MEMO,
            ProductivityTarget(dataset, memo.id))).use {
            waitText(memo.title)
            onView(withText("编辑备忘")).perform(scrollTo(), click())
            onView(withHint("备忘正文")).perform(replaceText("中".repeat(11_000)), closeSoftKeyboard())
            onView(withText("保存")).perform(click())
            onView(withHint("备忘正文")).check(matches(isDisplayed()))
            assertEquals(memo.body, load(dataset).content.memos.single { it.id == memo.id }.body)
            onView(withText("取消")).perform(click())
            screenshot("phone-memo")
        }

        val manager = AppWidgetManager.getInstance(context)
        val host = AppWidgetHost(context, 8800)
        val ids = IntArray(3)
        var retain = false
        fun bind(index: Int, kind: ProductivityKind, target: ProductivityRecord): Int {
            val id = host.allocateAppWidgetId()
            ids[index] = id
            val component = ComponentName(context, if (kind == ProductivityKind.TODO) TodoWidgetProvider::class.java else MemoWidgetProvider::class.java)
            check(manager.bindAppWidgetIdIfAllowed(id, component)) { "请为隔离 testbed 授予 AppWidget 测试 Host 绑定权限" }
            ActivityScenario.launch<ProductivityActivity>(ProductivityActivity.intent(context, kind)).use { scenario ->
                scenario.onActivity { activity ->
                    host.startAppWidgetConfigureActivityForResult(activity, id, 0, 8801, null)
                }
                waitText("选择小组件数据来源")
                onView(allOf(withText("共享"), isDisplayed())).perform(click())
                waitText(target.title)
                onView(allOf(withText(target.title), isDisplayed())).perform(click())
                waitFor {
                    runCatching { PhoneProductivity.bindings.get("widget-$id")?.contains(target.id) == true }.getOrDefault(false)
                }
                waitText("数据来源")
                instrument.waitForIdleSync()
            }
            return id
        }

        try {
            val todoId = bind(0, ProductivityKind.TODO, list)
            val anotherTodo = bind(1, ProductivityKind.TODO, x08eList)
            val memoId = bind(2, ProductivityKind.MEMO, memo)
            waitFor {
                ProductivityWidgets.rows(todoId)?.tasks?.size == 7 &&
                    ProductivityWidgets.rows(anotherTodo)?.tasks?.any { it.text == "整理下周课表" } == true
            }
            val oldRows = ProductivityWidgets.rows(todoId)!!
            val oldTask = oldRows.tasks.first()
            fun widgetIntent(snapshot: ProductivityWidgets.Rows, done: Boolean, id: Int = todoId) =
                Intent(ProductivityWidgets.ROW, Uri.parse("dormpanel-productivity://row/$id"))
                    .putExtra("binding", snapshot.binding).putExtra("task", oldTask.id).putExtra("completed", done)
                    .putExtra("revision", snapshot.version.revision).putExtra("hash", snapshot.version.businessHash)
            fun action(intent: Intent) {
                await<Unit> { callback ->
                    ProductivityWidgets.rowAction(context, ProductivityKind.TODO, intent) { callback(Result.success(Unit)) }
                }.getOrThrow()
            }
            action(widgetIntent(oldRows, true, anotherTodo))
            assertEquals(oldRows.version, load(dataset).version)
            action(widgetIntent(oldRows, true))
            val changed = load(dataset)
            assertTrue(changed.content.tasks.single { it.id == oldTask.id }.completed)
            action(widgetIntent(oldRows, true))
            action(widgetIntent(oldRows, false))
            assertEquals(changed.version, load(dataset).version)

            ActivityScenario.launch<ProductivityActivity>(ProductivityActivity.intent(context, ProductivityKind.TODO)).use { scenario ->
                lateinit var holder: LinearLayout
                lateinit var todo: android.appwidget.AppWidgetHostView
                lateinit var memoWidget: android.appwidget.AppWidgetHostView
                scenario.onActivity { activity ->
                    holder = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(36, 100, 36, 12)
                    }
                    host.startListening()
                    todo = host.createView(activity, todoId, manager.getAppWidgetInfo(todoId))
                    memoWidget = host.createView(activity, memoId, manager.getAppWidgetInfo(memoId))
                    holder.addView(todo)
                    holder.addView(memoWidget)
                    activity.setContentView(holder)
                }
                fun size(width: Int, height: Int) {
                    scenario.onActivity { activity ->
                        fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
                        listOf(todo to todoId, memoWidget to memoId).forEach { (widget, id) ->
                            widget.layoutParams = LinearLayout.LayoutParams(dp(width), dp(height)).apply { bottomMargin = dp(14) }
                            manager.updateAppWidgetOptions(id, Bundle().apply {
                                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, width)
                                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, height)
                            })
                        }
                    }
                    try {
                        waitText(list.name)
                        waitText(memo.title)
                    } catch (error: Exception) {
                        screenshot("phone-widgets-diagnostic")
                        throw error
                    }
                }
                size(195, 135)
                screenshot("phone-widgets-compact")
                size(362, 270)
                screenshot("phone-widgets-expanded")
                waitFor {
                    var displayed = false
                    scenario.onActivity {
                        displayed = (todo.findViewById<android.widget.ListView>(R.id.productivity_tasks)?.count ?: 0) > 1 &&
                            memoWidget.findViewById<TextView>(R.id.productivity_body).text.contains("\n")
                    }
                    displayed
                }
                val next = tasks[1]
                waitText(next.text)
                onView(allOf(withId(R.id.productivity_check), hasSibling(withText(next.text)), isDisplayed())).perform(click())
                waitFor { load(dataset).content.tasks.single { it.id == next.id }.completed }
            }
            assertTrue(load(dataset).pending)
            settle(store, account)
            assertFalse(load(dataset).pending)
            assertTrue(load(dataset).content.tasks.single { it.id == oldTask.id }.completed)
            assertEquals(localContent, load(null))

            // The widget host Activity is closed. Receive another Writer's change through WebDAV.
            val peerDatabase = androidx.room.Room.databaseBuilder(context, ScheduleDatabase::class.java,
                "pr88-widget-peer-${AndroidProductivity.id()}.db").build()
            val peer = RoomScheduleStore(peerDatabase, profiles = TermScheduleProfileStore(context))
            try {
                var preview: UnboundV3JoinPreview? = null
                repeat(8) { if (preview == null) preview = peer.previewUnboundV3Join(account, url, descriptor) }
                peer.joinVerifiedV3(requireNotNull(preview), account)
                val incoming = tasks.first { it.text == "预约自习室" }
                val peerVersion = await(peer::loadProductivity).getOrThrow().version!!
                await<Unit> { peer.editProductivity(peerVersion, ProductivityEdit.SetTaskCompleted(incoming.id, true), it) }.getOrThrow()
                settle(peer, account)
                assertFalse(load(dataset).content.tasks.single { it.id == incoming.id }.completed)
                settle(store, account)
                waitFor {
                    ProductivityWidgets.rows(todoId)?.tasks?.singleOrNull { it.id == incoming.id }?.completed == true
                }
            } finally {
                peer.close()
            }
            if (InstrumentationRegistry.getArguments().getString("pr88KeepWidgets") == "true") {
                context.getFileStreamPath("pr88-widget-fixture").writeText(ids.joinToString(",") + "\n$dataset")
                retain = true
            }
        } finally {
            instrument.runOnMainSync {
                host.stopListening()
                if (!retain) ids.filter { it != 0 }.forEach(host::deleteAppWidgetId)
            }
        }
    }

    @Test fun coldWidgetProcessReadsAndReceivesWithoutAnActivity() {
        val arguments = InstrumentationRegistry.getArguments()
        val url = arguments.getString("pr88ColdUrl")
        assumeNotNull(url)
        check(context.packageName.endsWith(".pr88testbed"))
        check(Regex("http://127[.]0[.]0[.]1:[0-9]+/pr88-[0-9a-f-]{36}/").matches(requireNotNull(url)))
        val (ids, dataset) = context.getFileStreamPath("pr88-widget-fixture").readLines()
        val widgets = ids.split(',').map(String::toInt)
        val store = requireNotNull(data.shared)
        val account = WebDavAccount(url, "fixture", "fixture")
        val target = load(dataset).content.memos.single { it.title == "宿舍检查" }
        val mode = arguments.getString("pr88ColdPhase")
        waitFor { ProductivityWidgets.rows(widgets[0])?.version?.datasetId == dataset }
        if (mode == "offline") {
            val initial = load(dataset)
            assertTrue(runCatching { store.synchronizeV3(account) }.isFailure)
            await<Unit> {
                data.save(initial.version!!, listOf(ProductivityEdit.EditMemo(target.id, "离线修改\n原样保留")), it)
            }.getOrThrow()
            assertTrue(load(dataset).pending)
            assertEquals("离线修改\n原样保留", load(dataset).content.memos.single { it.id == target.id }.body)
        } else {
            assertEquals("离线修改\n原样保留", target.body)
            settle(store, account)
            assertTrue(load(dataset).baselineReadback)
            assertFalse(load(dataset).pending)
            val before = load(dataset)
            val host = AppWidgetHost(context, 8800)
            instrument.runOnMainSync { widgets.forEach(host::deleteAppWidgetId) }
            waitFor { widgets.all { PhoneProductivity.bindings.get("widget-$it") == null } }
            assertEquals(before, load(dataset))
        }
    }

    @Test fun receivesFnOsAndClicksWidget() {
        val url = InstrumentationRegistry.getArguments().getString("pr89Url")
        assumeNotNull(url)
        check(context.packageName.endsWith(".pr89testbed"))
        check(Regex("http://127[.]0[.]0[.]1:[0-9]+/pr89-[0-9a-f-]{36}/").matches(requireNotNull(url)))
        val account = WebDavAccount(url, "fixture", "fixture")
        val store = requireNotNull(data.shared)
        if (!store.isV3()) {
            val remote = V3WebDavStore(account, url, V3Budget(), sharedFormat = true)
            val descriptor = requireNotNull(remote.read(remote.list("").flatMap { remote.list(it) }
                .single { it.substringAfterLast('/').startsWith("descriptor-") }))
            var preview: UnboundV3JoinPreview? = null
            repeat(8) { if (preview == null) preview = store.previewUnboundV3Join(account, url, descriptor) }
            store.joinVerifiedV3(requireNotNull(preview), account)
        }
        settle(store, account)
        val state = await(store::loadProductivity).getOrThrow()
        val dataset = state.version!!.datasetId
        val list = state.content.lists.single { state.content.tasks(it.id).any { task -> task.text == "fnOS 新增的共享任务" } }
        val tasks = state.content.tasks(list.id)
        val target = tasks.single { it.text == "fnOS 新增的共享任务" }
        assertFalse(target.completed)
        val memo = state.content.memos.single { it.title == "fnOS 编辑的共享备忘" }
        assertEquals("  fnOS 编辑\r\n第二行🙂\n  ", memo.body)
        val manager = AppWidgetManager.getInstance(context)
        val host = AppWidgetHost(context, 8900)
        val ids = mutableListOf<Int>()
        fun configure(kind: ProductivityKind, record: ProductivityRecord): Int {
            val id = host.allocateAppWidgetId().also(ids::add)
            val component = ComponentName(context, if (kind == ProductivityKind.TODO) TodoWidgetProvider::class.java else MemoWidgetProvider::class.java)
            check(manager.bindAppWidgetIdIfAllowed(id, component))
            ActivityScenario.launch<ProductivityActivity>(ProductivityActivity.intent(context, kind)).use { scenario ->
                scenario.onActivity { host.startAppWidgetConfigureActivityForResult(it, id, 0, 8901, null) }
                waitText("选择小组件数据来源")
                onView(allOf(withText("共享"), isDisplayed())).perform(click())
                waitText(record.title)
                onView(allOf(withText(record.title), isDisplayed())).perform(click())
                waitFor { PhoneProductivity.bindings.get("widget-$id")?.contains(record.id) == true }
                instrument.waitForIdleSync()
            }
            return id
        }
        try {
            val todoId = configure(ProductivityKind.TODO, list)
            val memoId = configure(ProductivityKind.MEMO, memo)
            val remaining = tasks.count { !it.completed }
            ActivityScenario.launch<ProductivityActivity>(ProductivityActivity.intent(context, ProductivityKind.TODO)).use { scenario ->
                lateinit var todo: android.appwidget.AppWidgetHostView
                lateinit var memoWidget: android.appwidget.AppWidgetHostView
                scenario.onActivity { activity ->
                    val holder = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                    host.startListening()
                    todo = host.createView(activity, todoId, manager.getAppWidgetInfo(todoId))
                    memoWidget = host.createView(activity, memoId, manager.getAppWidgetInfo(memoId))
                    holder.addView(memoWidget, LinearLayout.LayoutParams(-1, 400))
                    holder.addView(todo, LinearLayout.LayoutParams(-1, 0, 1f))
                    activity.setContentView(holder)
                }
                waitFor {
                    var ready = false
                    scenario.onActivity {
                        ready = todo.findViewById<android.widget.ListView>(R.id.productivity_tasks)?.count == tasks.size &&
                            todo.findViewById<TextView>(R.id.productivity_status)?.text?.contains("剩余 $remaining 项") == true &&
                            memoWidget.findViewById<TextView>(R.id.productivity_body)?.text?.toString() == memo.body
                    }
                    ready
                }
                val widgetPosition = ProductivityWidgets.rows(todoId)!!.tasks.indexOfFirst { it.id == target.id }
                check(widgetPosition >= 0)
                androidx.test.espresso.Espresso.onData(org.hamcrest.Matchers.anything()).inAdapterView(withId(R.id.productivity_tasks))
                    .atPosition(widgetPosition).onChildView(withId(R.id.productivity_check)).perform(click())
                waitFor { load(dataset).content.tasks(list.id).single { it.id == target.id }.completed }
                waitFor {
                    var refreshed = false
                    scenario.onActivity {
                        refreshed = todo.findViewById<TextView>(R.id.productivity_status)?.text?.contains("剩余 ${remaining-1} 项") == true
                    }
                    refreshed
                }
                screenshot("pr89-widget-confirmed")
                println("PR89_WIDGET_COUNT=$remaining->${remaining-1}")
            }
            settle(store, account)
            val result = load(dataset)
            assertFalse(result.pending)
            println("PR89_PHONE_HASH=${result.version!!.businessHash}")
        } finally {
            instrument.runOnMainSync {
                host.stopListening()
                ids.forEach(host::deleteAppWidgetId)
            }
        }
    }

    private fun load(dataset: String?) = await<ProductivityView> { data.load(dataset, it) }.getOrThrow()

    private fun <T> await(call: ((Result<T>) -> Unit) -> Unit): Result<T> {
        val result = LinkedBlockingQueue<Result<T>>()
        instrument.runOnMainSync { call { result.add(it) } }
        return requireNotNull(result.poll(20, TimeUnit.SECONDS))
    }

    private fun settle(store: RoomScheduleStore, account: WebDavAccount) {
        repeat(8) { if (store.synchronizeV3(account) == "OBSERVED") return }
        error("共享测试端点未收敛")
    }

    private fun waitText(text: String) = waitFor {
        runCatching { onView(allOf(withText(text), isDisplayed())).check(matches(isDisplayed())) }.isSuccess
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(120)
        }
        error("等待界面或共享数据超时")
    }

    private fun screenshot(name: String) {
        instrument.waitForIdleSync()
        Thread.sleep(350)
        val bitmap = instrument.uiAutomation.takeScreenshot()
        context.getExternalFilesDir(null)!!.resolve("$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
