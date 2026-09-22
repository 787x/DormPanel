package com.dormpanel.app.schedule

import android.os.Looper
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.*

class SchedulePersistenceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    @Test fun diskRoundTripMainCallbacksAndSelectedDeletes() {
        val context = instrumentation.targetContext
        val name = "test-schedule-${UUID.randomUUID()}.db"
        fun database() = Room.databaseBuilder(context, ScheduleDatabase::class.java, name).build()
        var executor = Executors.newSingleThreadExecutor()
        var store = RoomScheduleStore(database(), executor)
        val event = CalendarEvent("a", "Event", 1000, 2000, "note")
        val entry = TimetableEntry("a", "Class", 7, 540, 600, "Room 1")
        try {
            val saved = CountDownLatch(4)
            val callback: (Result<Unit>) -> Unit = { assertTrue(it.isSuccess); assertEquals(Looper.getMainLooper(), Looper.myLooper()); saved.countDown() }
            instrumentation.runOnMainSync { store.put(event, callback); store.put(event.copy(id = "b"), callback); store.put(entry, callback); store.put(entry.copy(id = "b"), callback) }
            assertTrue(saved.await(5, TimeUnit.SECONDS))
            val deleted = CountDownLatch(2)
            instrumentation.runOnMainSync { store.deleteEvent("b") { deleted.countDown() }; store.deleteEntry("b") { deleted.countDown() } }
            assertTrue(deleted.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { store.close() }; assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            executor = Executors.newSingleThreadExecutor(); store = RoomScheduleStore(database(), executor)
            val loaded = CountDownLatch(1)
            instrumentation.runOnMainSync { store.load { assertEquals(ScheduleState(listOf(event), listOf(entry)), it.getOrThrow()); loaded.countDown() } }
            assertTrue(loaded.await(5, TimeUnit.SECONDS))
        } finally {
            instrumentation.runOnMainSync { store.close() }; assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)); context.deleteDatabase(name)
        }
    }
    @Test fun acceptedWritesDrainCloseRejectsNewWorkAndLateCallbacks() {
        val database = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, ScheduleDatabase::class.java).build()
        val executor = Executors.newSingleThreadExecutor(); val gate = CountDownLatch(1)
        executor.execute { gate.await(5, TimeUnit.SECONDS) }
        val store = RoomScheduleStore(database, executor, closeDatabase = false)
        var callbacks = 0
        try {
            instrumentation.runOnMainSync {
                store.load { callbacks++ }
                store.put(CalendarEvent("accepted", "Saved", 1, 2)) { callbacks++ }
                store.put(TimetableEntry("accepted", "Saved", 1, 1, 2)) { callbacks++ }
                store.close()
                store.put(CalendarEvent("rejected", "No", 1, 2)) { callbacks++ }
                store.deleteEntry("accepted") { callbacks++ }
            }
            gate.countDown(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)); instrumentation.waitForIdleSync()
            assertEquals(0, callbacks); assertEquals("accepted", database.dao().events().single().id); assertEquals("accepted", database.dao().entries().single().id)
        } finally { gate.countDown(); store.close(); executor.awaitTermination(5, TimeUnit.SECONDS); database.close() }
    }
}
