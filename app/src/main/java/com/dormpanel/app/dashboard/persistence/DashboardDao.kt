package com.dormpanel.app.dashboard.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DashboardDao {
    @Query("SELECT * FROM dashboard_quarantine ORDER BY recoveryId")
    fun getQuarantine(): List<DashboardQuarantineEntity>

    @Insert
    fun insertQuarantine(cards: List<DashboardQuarantineEntity>)

    @Query("DELETE FROM dashboard_quarantine")
    fun deleteQuarantine()

    @Query("SELECT * FROM dashboard_state WHERE id = 1")
    fun getState(): DashboardStateEntity?

    @Query("SELECT * FROM dashboard_cards ORDER BY rowIndex, columnIndex, id")
    fun getCards(): List<DashboardCardEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putState(state: DashboardStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertCards(cards: List<DashboardCardEntity>)

    @Query("DELETE FROM dashboard_cards")
    fun deleteCards()
}
