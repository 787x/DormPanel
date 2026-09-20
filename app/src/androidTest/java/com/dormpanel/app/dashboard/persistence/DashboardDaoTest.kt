package com.dormpanel.app.dashboard.persistence

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DashboardDaoTest {
    private lateinit var database: DashboardDatabase
    private lateinit var dao: DashboardDao

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            DashboardDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.dashboardDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun cardSnapshotAndInitializationStateRoundTrip() {
        assertNull(dao.getState())
        assertEquals(emptyList<DashboardCardEntity>(), dao.getCards())

        dao.insertCards(
            listOf(
                DashboardCardEntity("stable-id", "mock.status", 2, 1, 2, 2, "{\"sample\":true}"),
            ),
        )
        dao.putState(DashboardStateEntity(1, 7))

        assertEquals(7L, dao.getState()?.revision)
        val restored = dao.getCards().single()
        assertEquals("stable-id", restored.id)
        assertEquals(2, restored.columnIndex)
        assertEquals(2, restored.columnSpan)
        assertEquals("{\"sample\":true}", restored.configurationJson)
    }

    @Test
    fun initializedEmptyDashboardRemainsDistinguishableFromFirstLaunch() {
        dao.putState(DashboardStateEntity(1, 1))
        dao.deleteCards()

        assertEquals(1L, dao.getState()?.revision)
        assertEquals(emptyList<DashboardCardEntity>(), dao.getCards())
    }
}
