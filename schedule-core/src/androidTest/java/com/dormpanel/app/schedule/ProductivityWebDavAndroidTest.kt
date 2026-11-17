package com.dormpanel.app.schedule

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.json.JSONObject
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class ProductivityWebDavAndroidTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val context = instrument.targetContext
    private val list = ProductivityRecord.TodoList("00000000-0000-0000-0000-000000000011", "Android 清单")
    private val task = ProductivityRecord.Task("00000000-0000-0000-0000-000000000012", list.id, "Android 新任务", false, 0)
    private val memo = ProductivityRecord.Memo("00000000-0000-0000-0000-000000000013", "Android 备忘", "由 Android 建立\r\n第二行")
    private val mirrors = AtomicInteger()
    private var fault: String? = null

    private fun <T> await(call: ((Result<T>) -> Unit) -> Unit): Result<T> {
        val result = LinkedBlockingQueue<Result<T>>()
        call { result.put(it) }
        return requireNotNull(result.poll(20, TimeUnit.SECONDS))
    }

    @Test fun isolatedWebDavTwoPhases() {
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("productivityUrl")
        assumeTrue("requires isolated WebDAV test endpoint", url != null)
        require(Regex("^https?://[^/]+/pr87-isolated-[0-9a-f-]{36}/$").matches(url!!))
        val storage = "pr87-${url.substringAfterLast("pr87-isolated-").trimEnd('/')}.db"
        val account = WebDavAccount(url, "fixture", "fixture")
        var worker = Executors.newSingleThreadExecutor()
        fun open(): RoomScheduleStore = RoomScheduleStore(Room.databaseBuilder(context, ScheduleDatabase::class.java, storage).build(),
            executor = worker, profiles = TermScheduleProfileStore(context), afterSnapshotRows = { mirrors.incrementAndGet() }, v3Fault = { if (it == fault) error("injected $it") })
        fun settle(store: RoomScheduleStore) {
            repeat(4) { if (store.synchronizeV3(account) == "OBSERVED") return }
            error("WebDAV did not settle")
        }

        var store = open()
        val phase = args.getString("productivityPhase")
        fun read() = await(store::loadProductivity).getOrThrow()
        fun save(edit: ProductivityEdit) = await<Unit> { store.editProductivity(readVersion(store), edit, it) }.getOrThrow()
        try {
            when (phase) {
                "seed" -> {
                    assertNull(read().version)
                    assertTrue(await<Unit> { store.editProductivity(ProductivityVersion(list.id, 0, ""), ProductivityEdit.Create(list), it) }.isFailure)
                    store.prepareV3(url, sharedFormat = true)
                    settle(store)
                    val preEdit = readVersion(store)
                    val courseMirrors = mirrors.get()
                    save(ProductivityEdit.Create(list))
                    assertTrue(await<Unit> { store.editProductivity(preEdit, ProductivityEdit.Create(task), it) }.isFailure)
                    fault = "before-commit"
                    assertTrue(await<Unit> { store.editProductivity(readVersion(store), ProductivityEdit.Create(task), it) }.isFailure)
                    fault = null
                    assertTrue(read().content.tasks.isEmpty())
                    save(ProductivityEdit.Create(task))
                    save(ProductivityEdit.Create(memo))
                    assertEquals(courseMirrors, mirrors.get())
                    assertTrue(read().pending)

                    val budget = V3Budget()
                    val remote = V3WebDavStore(account, url, budget, sharedFormat = true)
                    var attempted: String? = null
                    val interrupted = object : V3PublicationStore by remote {
                        override fun putOnce(path: String, raw: ByteArray) {
                            remote.putOnce(path, raw)
                            attempted = path
                        }
                        override fun read(path: String): ByteArray? {
                            check(path != attempted) { "injected readback interruption" }
                            return remote.read(path)
                        }
                    }
                    assertThrows(Exception::class.java) { store.synchronizeV3(remote, interrupted, budget) }
                    assertNotNull(attempted)
                    val pending = store.v3Control()!!.getJSONObject("pending")
                    assertEquals(attempted, pending.getString("path"))
                    assertTrue(read().pending)
                    // Test the same durable path after closing both the journal and Room.
                    store.close()
                    // The next verification phase also starts in a fresh instrumentation process.
                    assertTrue(worker.awaitTermination(20, TimeUnit.SECONDS))
                    worker = Executors.newSingleThreadExecutor()
                    store = open()
                    assertEquals(task, read().content.tasks.single())
                    assertEquals(memo, read().content.memos.single())
                    assertEquals(attempted, store.v3Control()!!.getJSONObject("pending").getString("path"))
                    settle(store)
                    assertEquals(attempted, store.v3Control()!!.getJSONObject("receipt").getString("path"))
                    val binding = store.v3Control()!!.getJSONObject("binding")
                    result("seed", JSONObject().put("binding", binding).put("hash", readVersion(store).businessHash))
                }
                "receive" -> {
                    assertEquals(task, read().content.tasks.single())
                    val received = CountDownLatch(1)
                    var subscription: AutoCloseable? = null
                    instrument.runOnMainSync {
                        subscription = store.observeProductivity { result ->
                            if (result.getOrNull()?.content?.tasks?.singleOrNull()?.completed == true) received.countDown()
                        }
                    }
                    try {
                        val before = mirrors.get()
                        settle(store)
                        assertTrue(received.await(20, TimeUnit.SECONDS))
                        val view = read()
                        assertEquals(task.copy(text = "Node 已编辑", completed = true), view.content.tasks.single())
                        assertEquals(memo.copy(body = "Node 正文\n末尾\n"), view.content.memos.single())
                        assertFalse(view.pending)
                        assertEquals(before, mirrors.get())
                        result("receive", JSONObject().put("hash", view.version!!.businessHash))
                    } finally { instrument.runOnMainSync { subscription?.close() } }
                }
                else -> error("需要明确的隔离验证阶段")
            }
        } finally { store.close() }
    }

    private fun readVersion(store: RoomScheduleStore) = requireNotNull(await(store::loadProductivity).getOrThrow().version)
    private fun result(phase: String, value: JSONObject) = File(context.filesDir, "pr87-$phase.json").writeText(value.toString())
}
