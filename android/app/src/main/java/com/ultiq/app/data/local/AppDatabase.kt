package com.ultiq.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ultiq.app.data.local.dao.AchievementDao
import com.ultiq.app.data.local.dao.CalendarEventDao
import com.ultiq.app.data.local.dao.ChecklistDao
import com.ultiq.app.data.local.dao.SessionDao
import com.ultiq.app.data.local.dao.SleepDao
import com.ultiq.app.data.local.entity.AchievementEntity
import com.ultiq.app.data.local.entity.CalendarEventEntity
import com.ultiq.app.data.local.entity.ChecklistEntity
import com.ultiq.app.data.local.entity.SessionEntity
import com.ultiq.app.data.local.entity.SleepRecordEntity
import com.ultiq.app.util.DatabaseSecurity
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        SleepRecordEntity::class,
        SessionEntity::class,
        CalendarEventEntity::class,
        AchievementEntity::class,
        ChecklistEntity::class,
    ],
    version = 6,
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

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `calendar_events` " +
                        "ADD COLUMN `isDone` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: build(context).also { INSTANCE = it }
            }
        }

        private fun build(context: Context): AppDatabase {
            // One-shot legacy migration: pre-SQLCipher installs had a plain
            // Room DB; drop it on first launch so SQLCipher creates a fresh
            // encrypted file. SyncManager refetches data from the backend.
            DatabaseSecurity.dropLegacyDbIfNeeded(context.applicationContext)

            // SQLCipher native lib must be loaded once per process before any
            // DB operation goes through SupportOpenHelperFactory.
            System.loadLibrary("sqlcipher")
            val passphrase = DatabaseSecurity.getOrCreatePassphrase(context.applicationContext)
            val factory = SupportOpenHelperFactory(passphrase)

            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "productivity_db"
            )
                .openHelperFactory(factory)
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                )
                // Legacy DB has been dropped if it existed; if Room can't
                // open the file (corrupt / version mismatch from a prior
                // build), fall back to a fresh encrypted DB rather than
                // crashing the app.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
        }
    }
}
