package com.app.productivity.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.app.productivity.data.local.dao.AchievementDao
import com.app.productivity.data.local.dao.CalendarEventDao
import com.app.productivity.data.local.dao.ChecklistDao
import com.app.productivity.data.local.dao.SessionDao
import com.app.productivity.data.local.dao.SleepDao
import com.app.productivity.data.local.entity.AchievementEntity
import com.app.productivity.data.local.entity.CalendarEventEntity
import com.app.productivity.data.local.entity.ChecklistEntity
import com.app.productivity.data.local.entity.SessionEntity
import com.app.productivity.data.local.entity.SleepRecordEntity

@Database(
    entities = [
        SleepRecordEntity::class,
        SessionEntity::class,
        CalendarEventEntity::class,
        AchievementEntity::class,
        ChecklistEntity::class,
    ],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sleepDao(): SleepDao
    abstract fun sessionDao(): SessionDao
    abstract fun calendarEventDao(): CalendarEventDao
    abstract fun achievementDao(): AchievementDao
    abstract fun checklistDao(): ChecklistDao

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

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `calendar_events` (
                        `id` TEXT NOT NULL,
                        `userId` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `description` TEXT,
                        `startTime` INTEGER NOT NULL,
                        `endTime` INTEGER NOT NULL,
                        `category` TEXT NOT NULL,
                        `priority` TEXT NOT NULL,
                        `isRecurring` INTEGER NOT NULL,
                        `recurrenceRule` TEXT,
                        `color` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `isSynced` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`id`)
                    )"""
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `achievements` (
                        `id` TEXT NOT NULL,
                        `earnedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )"""
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `checklist_items` (
                        `id` TEXT NOT NULL,
                        `userId` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `description` TEXT,
                        `dueDateEpochDay` INTEGER NOT NULL,
                        `estimatedMinutes` INTEGER,
                        `priority` INTEGER NOT NULL,
                        `completed` INTEGER NOT NULL,
                        `completedAt` INTEGER,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `isSynced` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`id`)
                    )"""
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_checklist_user_due` " +
                        "ON `checklist_items` (`userId`, `dueDateEpochDay`)"
                )
                db.execSQL(
                    "ALTER TABLE `productivity_sessions` " +
                        "ADD COLUMN `checklistItemId` TEXT DEFAULT NULL"
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
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                    )
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
