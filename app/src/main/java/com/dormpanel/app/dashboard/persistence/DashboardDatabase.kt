package com.dormpanel.app.dashboard.persistence

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [DashboardCardEntity::class, DashboardStateEntity::class, DashboardQuarantineEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class DashboardDatabase : RoomDatabase() {
    abstract fun dashboardDao(): DashboardDao

    companion object {
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS dashboard_quarantine (recoveryId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, id TEXT NOT NULL, providerType TEXT NOT NULL, columnIndex INTEGER NOT NULL, rowIndex INTEGER NOT NULL, columnSpan INTEGER NOT NULL, rowSpan INTEGER NOT NULL, configurationJson TEXT NOT NULL, reason TEXT NOT NULL)")
            }
        }

        @Volatile
        private var instance: DashboardDatabase? = null

        fun getInstance(context: Context): DashboardDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                DashboardDatabase::class.java,
                "dashboard.db",
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    }
}
