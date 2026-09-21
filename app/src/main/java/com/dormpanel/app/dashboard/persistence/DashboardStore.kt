package com.dormpanel.app.dashboard.persistence

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.dormpanel.app.dashboard.model.PlacedCard
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class StoredDashboard(
    val initialized: Boolean,
    val revision: Long,
    val cards: List<PlacedCard>,
    val rawCards: List<RawDashboardCard> = cards.map(RawDashboardCard::from),
    val quarantine: List<QuarantinedCard> = emptyList(),
)

/** Owner calls and completion callbacks are serialized on the UI scheduler. */
interface DashboardStore {
    fun load(callback: (Result<StoredDashboard>) -> Unit)
    fun save(cards: List<PlacedCard>, callback: (Result<Unit>) -> Unit = {})
    /** Atomically replaces active and recovery snapshots after compatibility repair. */
    fun saveRepair(cards: List<PlacedCard>, quarantine: List<QuarantinedCard>, callback: (Result<Unit>) -> Unit)
    fun close()
}

class RoomDashboardStore(
    private val database: DashboardDatabase,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) : DashboardStore {
    constructor(context: Context) : this(DashboardDatabase.getInstance(context))
    private var closed = false
    private val dao = database.dashboardDao()

    @Synchronized
    override fun load(callback: (Result<StoredDashboard>) -> Unit) {
        check(!closed) { "Dashboard store is closed" }
        executor.execute {
            val result = runCatching {
                database.runInTransaction<StoredDashboard> {
                    val state = dao.getState()
                    val raw = dao.getCards().map { it.toRaw() }
                    StoredDashboard(
                        initialized = state != null,
                        revision = state?.revision ?: 0L,
                        cards = raw.mapNotNull { runCatching { it.toModel() }.getOrNull() },
                        rawCards = raw,
                        quarantine = dao.getQuarantine().map { it.toModel() },
                    )
                }
            }
            mainHandler.post { callback(result) }
        }
    }

    override fun save(cards: List<PlacedCard>, callback: (Result<Unit>) -> Unit) {
        write(cards, null, callback)
    }

    override fun saveRepair(cards: List<PlacedCard>, quarantine: List<QuarantinedCard>, callback: (Result<Unit>) -> Unit) {
        write(cards, quarantine, callback)
    }

    @Synchronized
    private fun write(cards: List<PlacedCard>, quarantine: List<QuarantinedCard>?, callback: (Result<Unit>) -> Unit) {
        check(!closed) { "Dashboard store is closed" }
        val recoverySnapshot = quarantine?.toList()
        val immutableSnapshot = cards.toList()
        executor.execute {
            val result = runCatching {
                database.runInTransaction {
                    val nextRevision = (dao.getState()?.revision ?: 0L) + 1L
                    dao.deleteCards()
                    if (immutableSnapshot.isNotEmpty()) {
                        dao.insertCards(immutableSnapshot.map { it.toEntity() })
                    }
                    if (recoverySnapshot != null) {
                        dao.deleteQuarantine()
                        dao.insertQuarantine(recoverySnapshot.map { DashboardQuarantineEntity.from(it) })
                    }
                    dao.putState(DashboardStateEntity(STATE_ID, nextRevision))
                }
            }
            mainHandler.post { callback(result) }
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        // shutdown() drains already committed writes instead of cancelling them.
        executor.shutdown()
    }

    private fun DashboardCardEntity.toRaw() = RawDashboardCard(
        id, providerType, columnIndex, rowIndex, columnSpan, rowSpan, configurationJson,
    )

    private fun PlacedCard.toEntity() = DashboardCardEntity(
        id,
        providerType,
        column,
        row,
        size.columnSpan,
        size.rowSpan,
        configurationJson,
    )

    private companion object {
        const val STATE_ID = 1
    }
}
