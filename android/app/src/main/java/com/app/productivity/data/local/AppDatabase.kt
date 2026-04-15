package com.app.productivity.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.app.productivity.data.local.dao.SessionDao
import com.app.productivity.data.local.dao.SleepDao
import com.app.productivity.data.local.entity.SessionEntity
import com.app.productivity.data.local.entity.SleepRecordEntity

@Database(
    entities = [SleepRecordEntity::class, SessionEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sleepDao(): SleepDao
    abstract fun sessionDao(): SessionDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `productivity_sessions` (
                        `id` TEXT NOT NULL,
                        `userId` TEXT NOT NULL,
                        `tag` TEXT NOT NULL,
                        `durationMinutes` INTEGER NOT NULL,
                        `workDuration` INTEGER NOT NULL,
                        `breakDuration` INTEGER NOT NULL,
                        `phonePickups` INTEGER NOT NULL,
                        `startedAt` INTEGER NOT NULL,
                        `endedAt` INTEGER,
                        `completed` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `isSynced` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`id`)
                    )"""
                )
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "productivity_db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
