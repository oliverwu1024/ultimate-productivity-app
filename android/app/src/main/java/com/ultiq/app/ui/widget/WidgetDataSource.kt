package com.ultiq.app.ui.widget

import android.content.Context
import com.ultiq.app.data.local.AppDatabase
import com.ultiq.app.alarm.WakeAlarmScheduler
import com.ultiq.app.data.remote.RetrofitClient
import com.ultiq.app.data.repository.CalendarRepository
import com.ultiq.app.data.repository.ChecklistRepository
import com.ultiq.app.data.repository.SessionRepository
import com.ultiq.app.data.repository.SleepRepository
import com.ultiq.app.service.LiveFocusSessionStore
import com.ultiq.app.service.LiveSleepSessionStore
import com.ultiq.app.util.TokenManager
import com.ultiq.app.util.UserPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import java.time.LocalDate

/**
 * Shared, one-shot data access for the home-screen widgets. Constructed fresh
 * on each widget refresh / action callback — cheap, because [AppDatabase] is a
 * process-wide singleton (SQLCipher lib + passphrase already handled inside it,
 * so a widget needs no special decryption path). Mirrors the repo/db/api wiring
 * in `SyncWorker.doWork()`.
 *
 * Reads take a snapshot via `Flow.first()` — Glance renders from a value, not a
 * stream. Writes go through repositories so they inherit offline-first + SSE
 * echo-suppression exactly like the in-app screens.
 */
class WidgetDataSource(context: Context) {
    private val appContext = context.applicationContext
    val db = AppDatabase.getInstance(appContext)
    private val tokenManager = TokenManager(appContext)
    private val api = RetrofitClient.create(tokenManager)
    val prefs = UserPreferences(appContext)

    // No syncStateStore / achievementChecker: widgets never run sync(), and
    // achievement checks happen on the next in-app completion or sync pass.
    val checklistRepo = ChecklistRepository(db.checklistDao(), db.checklistCompletionDao(), api)
    val sessionRepo = SessionRepository(db.sessionDao(), api)
    val calendarRepo = CalendarRepository(db.calendarEventDao(), api)
    val sleepRepo = SleepRepository(db.sleepDao(), api)

    suspend fun userId(): String = tokenManager.getUserId().firstOrNull() ?: ""

    /** Today's checklist: open items to tick + done/total for the header count. */
    suspend fun todayChecklist(): ChecklistSnapshot {
        val today = LocalDate.now()
        val epochDay = today.toEpochDay()
        // Sun=0..Sat=6 to match recurrence_days_mask packing (see SessionsViewModel).
        val todayBit = 1 shl (today.dayOfWeek.value % 7)
        val items = checklistRepo.getByDate(epochDay, todayBit).first()
        val doneToday = checklistRepo.observeCompletions().first()
            .asSequence()
            .filter { it.epochDay == epochDay }
            .map { it.itemId }
            .toHashSet()
        // Same open/done partition the Focus tab uses: recurring rows are "done"
        // when a per-day completion exists; one-offs use the `completed` flag.
        val open = items.filter { item ->
            if (item.recurrenceDaysMask != 0) item.id !in doneToday else !item.completed
        }
        return ChecklistSnapshot(
            open = open.map { ChecklistItemView(it.id, it.title, it.priority, it.recurrenceDaysMask != 0) },
            total = items.size,
            doneCount = items.size - open.size,
        )
    }

    /**
     * Live focus session, if any. Read from the durable [LiveFocusSessionStore]
     * (SharedPreferences), NOT FocusTrackingService's in-memory statics — those
     * were unreliable when read from a widget refresh, so the timer never showed.
     * The store is written on session start and cleared on stop. Tag is best-effort
     * from the active session row.
     */
    suspend fun focus(): FocusSnapshot {
        val live = LiveFocusSessionStore(appContext).load()
            ?: return FocusSnapshot(active = false)
        val tag = sessionRepo.getActiveSessions().first().firstOrNull()?.tag ?: "Focus"
        return FocusSnapshot(
            active = true,
            startedAt = live.startMs,
            tag = tag,
            plannedMinutes = live.plannedMinutes,
        )
    }

    /**
     * Today's NOT-done events, soonest first — checklist-style: they stay until the
     * user ticks them (no "already ended" filter, no row cap), tapping marks done.
     * done/total drive the header count.
     */
    suspend fun todayCalendar(): CalendarSnapshot {
        val events = calendarRepo.getEventsForDay(LocalDate.now()).first()
        val open = events.filter { !it.isDone }.sortedBy { it.startTime }
        return CalendarSnapshot(
            open = open.map {
                CalendarEventView(it.id, it.title, it.startTime, it.category, it.isRecurring)
            },
            total = events.size,
            doneCount = events.size - open.size,
        )
    }

    /** Next enabled alarm + last night's sleep (or a live session, if running). */
    suspend fun sleepAlarm(): SleepAlarmSnapshot {
        val now = System.currentTimeMillis()
        val live = LiveSleepSessionStore(appContext).load()
        val sleepingElapsed = live?.startMs
            ?.takeIf { it > 0L }
            ?.let { ((now - it) / 60_000L).toInt().coerceAtLeast(0) }
        // Skip naps/test sessions; pick the most recent real overnight sleep.
        val lastNight = if (sleepingElapsed == null) {
            sleepRepo.getSleepRecords().first()
                .filter { !it.isNap && it.actualWakeTime > it.actualBedtime }
                .maxByOrNull { it.actualBedtime }
                ?.let {
                    LastNightView(
                        durationMinutes = ((it.actualWakeTime - it.actualBedtime) / 60_000L).toInt(),
                        quality = it.qualityRating,
                    )
                }
        } else null
        val nextAlarm = db.alarmDao().getEnabledAlarmsSync()
            .mapNotNull { alarm -> WakeAlarmScheduler.computeNextTrigger(alarm, now)?.let { it to alarm } }
            .minByOrNull { it.first }
            ?.let { NextAlarmView(triggerAt = it.first, label = it.second.label) }
        return SleepAlarmSnapshot(
            sleepingElapsedMinutes = sleepingElapsed,
            lastNight = lastNight,
            nextAlarm = nextAlarm,
        )
    }
}

data class ChecklistItemView(
    val id: String,
    val title: String,
    val priority: Int,
    val isRecurring: Boolean,
)

data class ChecklistSnapshot(
    val open: List<ChecklistItemView>,
    val total: Int,
    val doneCount: Int,
)

data class FocusSnapshot(
    val active: Boolean,
    val startedAt: Long = 0L,
    val tag: String = "",
    val plannedMinutes: Int = 0,
)

data class CalendarEventView(
    val id: String,
    val title: String,
    val startTime: Long,
    val category: String,
    val isRecurring: Boolean,
)

data class CalendarSnapshot(
    val open: List<CalendarEventView>,
    val total: Int,
    val doneCount: Int,
)

data class LastNightView(val durationMinutes: Int, val quality: Int)

data class NextAlarmView(val triggerAt: Long, val label: String?)

data class SleepAlarmSnapshot(
    val sleepingElapsedMinutes: Int?,
    val lastNight: LastNightView?,
    val nextAlarm: NextAlarmView?,
)
