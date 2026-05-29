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
import com.ultiq.app.data.local.dao.ChecklistCompletionDao
import com.ultiq.app.data.local.dao.ChecklistDao
import com.ultiq.app.data.local.dao.SessionDao
import com.ultiq.app.data.local.dao.SleepAudioEventDao
import com.ultiq.app.data.local.dao.SleepDao
import com.ultiq.app.data.local.entity.AchievementEntity
import com.ultiq.app.data.local.entity.AlarmEntity
import com.ultiq.app.data.local.entity.AlarmEventEntity
import com.ultiq.app.data.local.entity.AlarmTombstoneEntity
import com.ultiq.app.data.local.entity.CalendarEventEntity
import com.ultiq.app.data.local.entity.ChecklistCompletionEntity
import com.ultiq.app.data.local.entity.ChecklistEntity
import com.ultiq.app.data.local.entity.SessionEntity
import com.ultiq.app.data.local.entity.SleepAudioEventEntity
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
        ChecklistCompletionEntity::class,
        AlarmEntity::class,
        AlarmEventEntity::class,
        AlarmTombstoneEntity::class,
        SleepAudioEventEntity::class,
    ],
    version = 14,
    exportSchema = false
)
@androidx.room.TypeConverters(com.ultiq.app.data.local.converters.IntListConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sleepDao(): SleepDao
    abstract fun sessionDao(): SessionDao
    abstract fun calendarEventDao(): CalendarEventDao
    abstract fun achievementDao(): AchievementDao
    abstract fun checklistDao(): ChecklistDao
    abstract fun checklistCompletionDao(): ChecklistCompletionDao
    abstract fun alarmDao(): AlarmDao
    abstract fun sleepAudioEventDao(): SleepAudioEventDao

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

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `checklist_items` " +
                        "ADD COLUMN `recurrenceDaysMask` INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE `checklist_items` " +
                        "ADD COLUMN `showUntilDue` INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE `checklist_items` " +
                        "ADD COLUMN `lastCompletedEpochDay` INTEGER DEFAULT NULL"
                )
            }
        }

        // §10 — Sleep audio events (snore + cough). YAMNet runs on-device
        // during sleep sessions; events are debounced and persisted locally,
        // then batch-synced to the backend at session-end. Raw audio is
        // never written to disk.
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `sleep_audio_events` (
                        `id` TEXT NOT NULL,
                        `userId` TEXT NOT NULL,
                        `sleepRecordId` TEXT NOT NULL,
                        `eventType` TEXT NOT NULL,
                        `startedAt` INTEGER NOT NULL,
                        `endedAt` INTEGER NOT NULL,
                        `peakConfidence` REAL NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `isSynced` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`id`)
                    )"""
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sleep_audio_events_sleepRecordId_startedAt` " +
                        "ON `sleep_audio_events` (`sleepRecordId`, `startedAt`)"
                )
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // v2.13.0 — per-event reminder offset. NULL = client default
                // (currently 15 min in AlarmScheduler.scheduleEventReminder).
                db.execSQL("ALTER TABLE `calendar_events` ADD COLUMN `reminderMinutes` INTEGER")
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // v2.13.1 — multi-reminder support. Recreate calendar_events
                // with reminderMinutes as TEXT (comma-separated list of int
                // offsets) instead of single INTEGER. CASE in the SELECT
                // upgrades any v2.13.0 scalar value to its array form:
                //   NULL → NULL  (use client default)
                //   0    → ''    (explicit no-reminder)
                //   N    → "N"   (one reminder)
                // Table recreate (not ALTER COLUMN TYPE) because SQLite
                // doesn't support changing a column's affinity in place.
                db.execSQL(
                    """CREATE TABLE `calendar_events_new` (
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
                        `isDone` INTEGER NOT NULL DEFAULT 0,
                        `reminderMinutes` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `isSynced` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`id`)
                    )"""
                )
                db.execSQL(
                    """INSERT INTO `calendar_events_new`
                        (id, userId, title, description, startTime, endTime,
                         category, priority, isRecurring, recurrenceRule, color,
                         isDone, reminderMinutes, createdAt, updatedAt, isSynced)
                       SELECT
                         id, userId, title, description, startTime, endTime,
                         category, priority, isRecurring, recurrenceRule, color,
                         isDone,
                         CASE WHEN reminderMinutes IS NULL THEN NULL
                              WHEN reminderMinutes = 0 THEN ''
                              ELSE CAST(reminderMinutes AS TEXT)
                         END,
                         createdAt, updatedAt, isSynced
                       FROM `calendar_events`"""
                )
                db.execSQL("DROP TABLE `calendar_events`")
                db.execSQL("ALTER TABLE `calendar_events_new` RENAME TO `calendar_events`")
            }
        }

        // §10.x — Pro-tier clip metadata on sleep_audio_events. hasClip is
        // server-truth: a row only flips true after the backend confirms the
        // S3 attach. clipDurationMs is the encoded clip length so the playback
        // UI can render duration without round-tripping to S3.
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `sleep_audio_events` " +
                        "ADD COLUMN `hasClip` INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE `sleep_audio_events` " +
                        "ADD COLUMN `clipDurationMs` INTEGER"
                )
            }
        }

        // §024 — Per-day completion log for recurring checklist items.
        // The single `lastCompletedEpochDay` column could only remember
        // the latest tick, so completing the row on Tue overwrote Mon's
        // stamp and Mon's view silently un-ticked. New table stores one
        // row per (item, epoch_day) so every past tick survives.
        //
        // Backfill copies the existing single stamp into the new table
        // for every recurring row that had one. Old column is kept for
        // backward-compat with older clients sharing the same DB file
        // (e.g. after a rollback); nothing in this codebase reads it
        // anymore.
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `checklist_completions` (
                        `itemId` TEXT NOT NULL,
                        `epochDay` INTEGER NOT NULL,
                        `completedAtMs` INTEGER NOT NULL,
                        PRIMARY KEY(`itemId`, `epochDay`),
                        FOREIGN KEY(`itemId`) REFERENCES `checklist_items`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )"""
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_checklist_completions_item` " +
                        "ON `checklist_completions` (`itemId`)"
                )
                db.execSQL(
                    """INSERT OR IGNORE INTO `checklist_completions`
                        (itemId, epochDay, completedAtMs)
                       SELECT id, lastCompletedEpochDay, updatedAt
                       FROM `checklist_items`
                       WHERE lastCompletedEpochDay IS NOT NULL
                         AND recurrenceDaysMask != 0"""
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
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    MIGRATION_12_13,
                    MIGRATION_13_14,
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
