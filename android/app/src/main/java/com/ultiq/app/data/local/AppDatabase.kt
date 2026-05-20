package com.ultiq.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ultiq.app.data.local.dao.AchievementDao
import com.ultiq.app.data.local.dao.AlarmDao
import com.ultiq.app.data.local.dao.CalendarEventDao
import com.ultiq.app.data.local.dao.ChecklistDao
import com.ultiq.app.data.local.dao.SessionDao
import com.ultiq.app.data.local.dao.SleepDao
import com.ultiq.app.data.local.entity.AchievementEntity
import com.ultiq.app.data.local.entity.AlarmEntity
import com.ultiq.app.data.local.entity.AlarmEventEntity
import com.ultiq.app.data.local.entity.AlarmTombstoneEntity
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
        AlarmEntity::class,
        AlarmEventEntity::class,
        AlarmTombstoneEntity::class,
    ],
    version = 8,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sleepDao(): SleepDao
    abstract fun sessionDao(): SessionDao
    abstract fun calendarEventDao(): CalendarEventDao
    abstract fun achievementDao(): AchievementDao
    abstract fun checklistDao(): ChecklistDao
    abstract fun alarmDao(): AlarmDao

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

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `alarm_tombstones` (
                        `id` TEXT NOT NULL,
                        `deletedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )"""
                )
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `alarms` (
                        `id` TEXT NOT NULL,
                        `userId` TEXT NOT NULL,
                        `label` TEXT,
                        `triggerHour` INTEGER NOT NULL,
                        `triggerMinute` INTEGER NOT NULL,
                        `daysOfWeekMask` INTEGER NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `soundUri` TEXT,
                        `volumePct` INTEGER NOT NULL,
                        `volumeEscalates` INTEGER NOT NULL,
                        `vibration` INTEGER NOT NULL,
                        `snoozeMinutes` INTEGER NOT NULL,
                        `snoozeMax` INTEGER NOT NULL,
                        `missionKind` TEXT NOT NULL,
                        `missionConfigJson` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `isSynced` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`id`)
                    )"""
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `alarm_events` (
                        `id` TEXT NOT NULL,
                        `userId` TEXT NOT NULL,
                        `alarmId` TEXT,
                        `firedAt` INTEGER NOT NULL,
                        `dismissedAt` INTEGER,
                        `dismissMethod` TEXT,
                        `snoozeCount` INTEGER NOT NULL,
                        `missionKind` TEXT,
                        `missionAttempts` INTEGER NOT NULL,
                        `missionDurationMs` INTEGER,
                        `createdAt` INTEGER NOT NULL,
                        `isSynced` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`alarmId`) REFERENCES `alarms`(`id`)
                            ON UPDATE NO ACTION ON DELETE SET NULL
                    )"""
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_alarm_events_user_fired` " +
                        "ON `alarm_events` (`userId`, `firedAt`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_alarm_events_alarm_fired` " +
                        "ON `alarm_events` (`alarmId`, `firedAt`)"
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
                    MIGRATION_6_7,
                    MIGRATION_7_8,
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
