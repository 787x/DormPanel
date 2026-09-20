package com.dormpanel.app.dashboard.persistence

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [DashboardCardEntity::class, DashboardStateEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class DashboardDatabase : RoomDatabase() {
    abstract fun dashboardDao(): DashboardDao

    companion object {
        @Volatile
        private var instance: DashboardDatabase? = null

        fun getInstance(context: Context): DashboardDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                DashboardDatabase::class.java,
                "dashboard.db",
            ).build().also { instance = it }
        }
    }
}
