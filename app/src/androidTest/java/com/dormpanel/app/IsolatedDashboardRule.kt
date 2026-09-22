package com.dormpanel.app

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.dormpanel.app.dashboard.persistence.*
import org.junit.Assert.assertTrue
import org.junit.rules.ExternalResource
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Owns only in-memory persistence. ActivityScenario must close before this rule tears down. */
class IsolatedDashboardRule : ExternalResource() {
    private lateinit var scheduleDatabase: com.dormpanel.app.schedule.ScheduleDatabase
    private lateinit var productivityDatabase: com.dormpanel.app.productivity.ProductivityDatabase
    private lateinit var database: DashboardDatabase
    private val executors = mutableListOf<ExecutorService>()
    override fun before() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        scheduleDatabase = Room.inMemoryDatabaseBuilder(context, com.dormpanel.app.schedule.ScheduleDatabase::class.java).build()
        com.dormpanel.app.schedule.ScheduleStores.overrideFactory = {
            val executor = Executors.newSingleThreadExecutor()
            executors += executor
            com.dormpanel.app.schedule.RoomScheduleStore(scheduleDatabase, executor, closeDatabase = false)
        }
        database = Room.inMemoryDatabaseBuilder(context, DashboardDatabase::class.java).build()
        productivityDatabase = Room.inMemoryDatabaseBuilder(context, com.dormpanel.app.productivity.ProductivityDatabase::class.java).build()
        com.dormpanel.app.productivity.ProductivityStores.overrideFactory = {
            val executor = Executors.newSingleThreadExecutor()
            executors += executor
            com.dormpanel.app.productivity.RoomProductivityStore(productivityDatabase, executor, closeDatabase = false)
        }
        DashboardStores.overrideFactory = {
            val executor = Executors.newSingleThreadExecutor()
            executors += executor
            RoomDashboardStore(database, executor)
        }
    }
    override fun after() {
        com.dormpanel.app.schedule.ScheduleStores.overrideFactory = null
        com.dormpanel.app.productivity.ProductivityStores.overrideFactory = null
        DashboardStores.overrideFactory = null
        // ViewModel destruction shuts down each store without blocking the UI thread.
        try {
            executors.forEach {
                assertTrue("Activity/ViewModel did not close its store", it.isShutdown)
                assertTrue("Store did not drain", it.awaitTermination(10, TimeUnit.SECONDS))
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        } finally {
            executors.forEach { it.shutdown() }
            database.close()
            productivityDatabase.close()
            scheduleDatabase.close()
        }
    }
}
