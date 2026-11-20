package com.dormpanel.app.dashboard.persistence

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dormpanel.app.dashboard.card.WeatherCardConfiguration
import com.dormpanel.app.dashboard.card.SWITCH_GROUP_CARD
import com.dormpanel.app.dashboard.card.SwitchCardConfiguration
import com.dormpanel.app.dashboard.card.SwitchChannelBinding
import com.dormpanel.app.dashboard.model.CardSize
import com.dormpanel.app.dashboard.model.PlacedCard
import com.dormpanel.app.data.SwitchIdentity
import com.dormpanel.app.weather.WeatherSourceRef
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test fun groupedSwitchConfigurationSurvivesDiskReopenBesideLegacyCard() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "switch-cards-${UUID.randomUUID()}.db"
        val group = SwitchCardConfiguration("ha-source", "wall", listOf(
            SwitchChannelBinding(SwitchIdentity("ha:switch.right", "wall", "right-entry"), "Hall"),
            SwitchChannelBinding(SwitchIdentity("ha:switch.left", "wall", "left-entry")),
        ), "Wall switch")
        val cards = listOf(PlacedCard("legacy", "clock", 0, 0, CardSize(2, 1)),
            PlacedCard("switch", SWITCH_GROUP_CARD, 3, 2, CardSize(1, 2), group.encode()))
        try {
            val original = Room.databaseBuilder(context, DashboardDatabase::class.java, name).build()
            val executor = Executors.newSingleThreadExecutor()
            val saved = CompletableFuture<Result<Unit>>()
            try {
                val store = RoomDashboardStore(original, executor)
                store.save(cards) { saved.complete(it) }
                saved.get(10, TimeUnit.SECONDS).getOrThrow()
                store.close()
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
            } finally {
                executor.shutdown()
                original.close()
            }
            val reopened = Room.databaseBuilder(context, DashboardDatabase::class.java, name).build()
            val reader = RoomDashboardStore(reopened)
            try {
                val loaded = CompletableFuture<Result<StoredDashboard>>()
                reader.load { loaded.complete(it) }
                val recovered = loaded.get(10, TimeUnit.SECONDS).getOrThrow().cards.associateBy { it.id }
                assertEquals(cards.associateBy { it.id }, recovered)
                assertEquals(group, SwitchCardConfiguration.decode(recovered.getValue("switch").configurationJson))
            } finally {
                reader.close()
                reopened.close()
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun repairQuarantineSurvivesOrdinarySaveAndRawMalformedLoad() {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val store = RoomDashboardStore(database, executor)
        val raw = RawDashboardCard("missing", "future.provider", -8, 99, 0, -2, "opaque original config")
        val quarantined = QuarantinedCard(raw, RecoveryReason.MALFORMED)
        val card = com.dormpanel.app.dashboard.model.PlacedCard("valid", "clock", 0, 0, com.dormpanel.app.dashboard.model.CardSize(2, 1))
        store.saveRepair(listOf(card), listOf(quarantined)) {}
        store.save(emptyList()) {}
        store.close()
        org.junit.Assert.assertTrue(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(emptyList<DashboardCardEntity>(), dao.getCards())
        assertEquals(listOf(quarantined), dao.getQuarantine().map { it.toModel() })
        // Bad dimensions must reach compatibility repair instead of failing the complete load.
        dao.insertCards(listOf(DashboardCardEntity(raw.id, raw.providerType, raw.column, raw.row, raw.columnSpan, raw.rowSpan, raw.configurationJson)))
        val readerExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val reader = RoomDashboardStore(database, readerExecutor)
        val loaded = java.util.concurrent.atomic.AtomicReference<Result<StoredDashboard>>()
        val callback = java.util.concurrent.CountDownLatch(1)
        reader.load { loaded.set(it); callback.countDown() }
        reader.close()
        org.junit.Assert.assertTrue(readerExecutor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS))
        org.junit.Assert.assertTrue(callback.await(10, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(listOf(raw), loaded.get().getOrThrow().rawCards)
        assertEquals(listOf(quarantined), loaded.get().getOrThrow().quarantine)
    }

    @Test fun closeDrainsAcceptedQueueWithoutBlockingAndRejectsNewSubmissions() {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        executor.execute { entered.countDown(); release.await() }
        org.junit.Assert.assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
        val store = RoomDashboardStore(database, executor)
        try {
            store.save(emptyList()) {}
            store.save(emptyList()) {}
            store.close(); store.close()
            org.junit.Assert.assertFalse(executor.isTerminated)
            assertNull(dao.getState())
            org.junit.Assert.assertThrows(IllegalStateException::class.java) { store.save(emptyList()) {} }
            org.junit.Assert.assertThrows(IllegalStateException::class.java) { store.load {} }
        } finally { release.countDown() }
        org.junit.Assert.assertTrue(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(2L, dao.getState()?.revision)
    }

    @Test fun explicitVersionOneMigrationPreservesExistingLayout() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-${java.util.UUID.randomUUID()}.db"
        try {
            val file = context.getDatabasePath(name)
            android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
                old.execSQL("CREATE TABLE dashboard_cards (id TEXT NOT NULL PRIMARY KEY, providerType TEXT NOT NULL, columnIndex INTEGER NOT NULL, rowIndex INTEGER NOT NULL, columnSpan INTEGER NOT NULL, rowSpan INTEGER NOT NULL, configurationJson TEXT NOT NULL)")
                old.execSQL("CREATE TABLE dashboard_state (id INTEGER NOT NULL PRIMARY KEY, revision INTEGER NOT NULL)")
                old.execSQL("INSERT INTO dashboard_cards VALUES ('existing', 'clock', 0, 0, 2, 1, '{}')")
                old.execSQL("INSERT INTO dashboard_state VALUES (1, 42)")
                old.version = 1
            }
            val migrated = Room.databaseBuilder(context, DashboardDatabase::class.java, name)
                .addMigrations(DashboardDatabase.MIGRATION_1_2).build()
            try {
                    assertEquals("existing", migrated.dashboardDao().getCards().single().id)
                    assertEquals(42L, migrated.dashboardDao().getState()?.revision)
                    assertEquals(emptyList<DashboardQuarantineEntity>(), migrated.dashboardDao().getQuarantine())
            } finally { migrated.close() }
        } finally { context.deleteDatabase(name) }
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

    @Test fun mixedWeatherLayoutSurvivesDiskDatabaseReopen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "weather-layout-${UUID.randomUUID()}.db"
        val config = WeatherCardConfiguration.Bound("city", WeatherSourceRef.QWeatherCity("101010100")).encode()
        val cards = listOf(
            PlacedCard("seed-weather", "weather", 4, 0, CardSize(4, 3)),
            PlacedCard("compact", "weather_compact_2x3", 0, 0, CardSize(2, 3), config),
            PlacedCard("classic", "weather_classic", 1, 3, CardSize(3, 3), config),
        )
        fun withStore(run: (RoomDashboardStore) -> Unit) {
            val db = Room.databaseBuilder(context, DashboardDatabase::class.java, name).build()
            val executor = Executors.newSingleThreadExecutor()
            val store = RoomDashboardStore(db, executor)
            try {
                run(store)
            } finally {
                store.close()
                try { assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)) } finally { db.close() }
            }
        }
        try {
            withStore { store ->
                val saved = CompletableFuture<Result<Unit>>()
                store.save(cards) { saved.complete(it) }
                saved.get(10, TimeUnit.SECONDS).getOrThrow()
            }
            withStore { store ->
                val restored = CompletableFuture<Result<StoredDashboard>>()
                store.load { restored.complete(it) }
                val snapshot = restored.get(10, TimeUnit.SECONDS).getOrThrow()
                assertEquals(cards.associateBy { it.id }, snapshot.cards.associateBy { it.id })
                assertEquals(emptyList<QuarantinedCard>(), snapshot.quarantine)
            }
        } finally { context.deleteDatabase(name) }
    }
}
