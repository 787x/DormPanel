package com.dormpanel.app.productivity

import android.os.Looper
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.*

class ProductivityPersistenceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    @Test fun isolatedFileRoundTripAndCallbacksOnMain() {
        val context = instrumentation.targetContext
        val name = "productivity-test-${UUID.randomUUID()}.db"
        fun database() = Room.databaseBuilder(context, ProductivityDatabase::class.java, name).build()
        var executor = Executors.newSingleThreadExecutor()
        var store = RoomProductivityStore(database(), executor)
        val expected = ProductivityState(listOf(TodoItem("z", "First"), TodoItem("a", "Second", true)), "line 1\nline 2",
            TimerState(60000, 40000, TimerStatus.RUNNING, System.currentTimeMillis() + 40000))
        try {
            val saved = CountDownLatch(1)
            instrumentation.runOnMainSync { store.save("owner", expected) { assertTrue(it.isSuccess); assertEquals(Looper.getMainLooper(), Looper.myLooper()); saved.countDown() } }
            assertTrue(saved.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { store.close() }
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            executor = Executors.newSingleThreadExecutor(); store = RoomProductivityStore(database(), executor)
            val loaded = CountDownLatch(1)
            instrumentation.runOnMainSync { store.load { result -> assertEquals(expected, result.getOrThrow()["owner"]); loaded.countDown() } }
            assertTrue(loaded.await(5, TimeUnit.SECONDS))
        } finally {
            instrumentation.runOnMainSync { store.close() }
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            // This test owns only its uniquely named file. Never clear/restore production databases.
            context.deleteDatabase(name)
        }
    }
    @Test fun closeDrainsQueuedWritesAndDropsLateCompletions() {
        val database = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, ProductivityDatabase::class.java).build()
        val executor = Executors.newSingleThreadExecutor()
        val gate = CountDownLatch(1)
        executor.execute { gate.await(5, TimeUnit.SECONDS) }
        val store = RoomProductivityStore(database, executor, closeDatabase = false)
        var callbacks = 0
        instrumentation.runOnMainSync {
            store.load { callbacks++ }
            store.save("owner", ProductivityState(memo = "drained")) { callbacks++ }
            store.close()
            store.save("ignored", ProductivityState()) { callbacks++ }
        }
        gate.countDown()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
        assertEquals(0, callbacks)
        assertEquals("drained", database.dao().owners().single().memo)
        database.close()
    }
}
