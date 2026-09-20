package com.dormpanel.app.dashboard.persistence

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.dormpanel.app.dashboard.model.CardSize
import com.dormpanel.app.dashboard.model.PlacedCard
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class StoredDashboard(
    val initialized: Boolean,
    val revision: Long,
    val cards: List<PlacedCard>,
)

interface DashboardStore {
    fun load(callback: (Result<StoredDashboard>) -> Unit)
    fun save(cards: List<PlacedCard>, callback: (Result<Unit>) -> Unit = {})
    fun close()
}

class RoomDashboardStore(
    context: Context,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) : DashboardStore {
    private val database = DashboardDatabase.getInstance(context)
    private val dao = database.dashboardDao()

    override fun load(callback: (Result<StoredDashboard>) -> Unit) {
        executor.execute {
            val result = runCatching {
                val state = dao.getState()
                StoredDashboard(
                    initialized = state != null,
                    revision = state?.revision ?: 0L,
                    cards = dao.getCards().map { it.toModel() },
                )
            }
            mainHandler.post { callback(result) }
        }
    }

    override fun save(cards: List<PlacedCard>, callback: (Result<Unit>) -> Unit) {
        val immutableSnapshot = cards.toList()
        executor.execute {
            val result = runCatching {
                database.runInTransaction {
                    val nextRevision = (dao.getState()?.revision ?: 0L) + 1L
                    dao.deleteCards()
                    if (immutableSnapshot.isNotEmpty()) {
                        dao.insertCards(immutableSnapshot.map { it.toEntity() })
                    }
                    dao.putState(DashboardStateEntity(STATE_ID, nextRevision))
                }
            }
            mainHandler.post { callback(result) }
        }
    }

    override fun close() {
        // shutdown() drains already committed writes instead of cancelling them.
        executor.shutdown()
    }

    private fun DashboardCardEntity.toModel() = PlacedCard(
        id = id,
        providerType = providerType,
        column = columnIndex,
        row = rowIndex,
        size = CardSize(columnSpan, rowSpan),
        configurationJson = configurationJson,
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
