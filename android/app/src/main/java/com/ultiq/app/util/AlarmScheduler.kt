package com.ultiq.app.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.ultiq.app.data.local.entity.CalendarEventEntity
import com.ultiq.app.receiver.BedtimeReminderReceiver
import com.ultiq.app.receiver.EventReminderReceiver
import com.ultiq.app.receiver.FocusReminderReceiver
import com.ultiq.app.receiver.MorningSummaryReceiver
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class AlarmScheduler(private val context: Context) {

    companion object {
        const val EVENT_LEAD_MINUTES = 15
        /// v2.13.1 — Full set of offsets the picker exposes, in minutes.
        /// Used to enumerate possible request codes when cancelling all
        /// reminders for an event. Keep aligned with reminderOptions in
        /// AddEventDialog so we don't miss any scheduled alarm.
        val KNOWN_REMINDER_OFFSETS: List<Int> =
            listOf(5, 15, 30, 60, 120, 240, 1440, 2880, 10080)

        const val EXTRA_EVENT_ID = "event_id"
        const val EXTRA_EVENT_TITLE = "event_title"
        const val EXTRA_EVENT_START = "event_start"
        const val EXTRA_REMINDER_MINUTES = "reminder_minutes"

        private const val REQ_BEDTIME = 11_001
        private const val REQ_FOCUS = 11_002
        private const val REQ_MORNING_SUMMARY = 11_003
        private const val REQ_EVENT_BASE = 12_000

        /// §2026-06-09 — Forward horizon for recurring-event expansion when
        /// scheduling. Past this, alarms must be re-armed by BootReceiver,
        /// the periodic SyncWorker, or an incoming SSE update. 30 days
        /// mirrors the previous filter inside rescheduleAllEventReminders.
        private const val EVENT_SCHEDULE_WINDOW_DAYS = 30L

        /// §2026-06-09 — Cancel walks a wider window than schedule writes,
        /// to also reach slots an earlier-version install or a longer-horizon
        /// reminder offset may have registered. Cancelling a slot that was
        /// never set is a no-op (FLAG_NO_CREATE), so over-cancelling is cheap.
        private const val CANCEL_WINDOW_PAST_DAYS = 1L
        private const val CANCEL_WINDOW_FUTURE_DAYS = 35L
        private const val MILLIS_PER_DAY = 86_400_000L
    }

    private val alarmManager: AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun applyDailyReminders(settings: ReminderSettings, targetBedtime: LocalTime) {
        // §fix-bedtime-unified — the bedtime *time* is canonically the
        // user's target bedtime (UserPreferences). Reminders only owns
        // the on/off toggle now; the duplicate time picker was removed.
        if (settings.bedtimeEnabled) scheduleBedtime(targetBedtime)
        else cancel(REQ_BEDTIME, BedtimeReminderReceiver::class.java)

        if (settings.focusEnabled) scheduleFocus(settings.focusTime)
        else cancel(REQ_FOCUS, FocusReminderReceiver::class.java)

        if (settings.morningSummaryEnabled) scheduleMorningSummary(settings.morningSummaryTime)
        else cancel(REQ_MORNING_SUMMARY, MorningSummaryReceiver::class.java)
    }

    fun scheduleBedtime(targetBedtime: LocalTime) {
        val triggerTime = targetBedtime.minusMinutes(ReminderSettings.BEDTIME_LEAD_MINUTES.toLong())
        val triggerMillis = nextOccurrenceMillis(triggerTime)
        val intent = Intent(context, BedtimeReminderReceiver::class.java)
        scheduleExact(REQ_BEDTIME, intent, triggerMillis)
    }

    fun scheduleFocus(focusTime: LocalTime) {
        val triggerMillis = nextOccurrenceMillis(focusTime)
        val intent = Intent(context, FocusReminderReceiver::class.java)
        scheduleExact(REQ_FOCUS, intent, triggerMillis)
    }

    fun scheduleMorningSummary(summaryTime: LocalTime) {
        val triggerMillis = nextOccurrenceMillis(summaryTime)
        val intent = Intent(context, MorningSummaryReceiver::class.java)
        scheduleExact(REQ_MORNING_SUMMARY, intent, triggerMillis)
    }

    /// §2026-06-09 — Recurrence-aware: a recurring master row is expanded
    /// into per-occurrence instances over the next 30 days and each
    /// occurrence gets its own alarm. Pre-fix this method scheduled
    /// against `master.startTime` only — i.e., the first ever occurrence,
    /// which for an active series is in the past — so the inner past-time
    /// guard silently dropped the alarm and no reminder ever fired for a
    /// recurring event after its first day. One-shot events flow through
    /// unchanged.
    fun scheduleEventReminder(event: CalendarEventEntity) {
        if (event.isRecurring && !event.recurrenceRule.isNullOrBlank()) {
            val now = System.currentTimeMillis()
            val windowEnd = now + EVENT_SCHEDULE_WINDOW_DAYS * MILLIS_PER_DAY
            for (occ in CalendarRecurrence.expandWindow(listOf(event), now, windowEnd)) {
                scheduleOccurrenceReminder(occ)
            }
        } else {
            scheduleOccurrenceReminder(event)
        }
    }

    /// Single-occurrence scheduling. Caller is responsible for picking
    /// the right occurrence (one-shot rows pass themselves; recurring
    /// rows go through scheduleEventReminder, which expands first).
    private fun scheduleOccurrenceReminder(event: CalendarEventEntity) {
        // v2.13.1 — Multi-reminder per event. NULL = client default (single
        // 15-min reminder, the pre-2.13 behaviour). Empty list = opt-out.
        // Non-empty list = one alarm per offset. Each offset gets its own
        // request code derived from (eventId, minutes, occurrenceDate) so
        // cancellation hits exactly the right alarm and per-occurrence
        // alarms of a recurring series don't overwrite each other.
        val offsets = event.reminderMinutes ?: listOf(EVENT_LEAD_MINUTES)
        for (minutes in offsets) {
            if (minutes <= 0) continue
            val triggerMillis = event.startTime - minutes * 60_000L
            if (triggerMillis <= System.currentTimeMillis()) continue

            val intent = Intent(context, EventReminderReceiver::class.java).apply {
                putExtra(EXTRA_EVENT_ID, event.id)
                putExtra(EXTRA_EVENT_TITLE, event.title)
                putExtra(EXTRA_EVENT_START, event.startTime)
                putExtra(EXTRA_REMINDER_MINUTES, minutes)
            }
            scheduleExact(eventRequestCode(event.id, minutes, event.startTime), intent, triggerMillis)
        }
    }

    fun cancelEventReminder(eventId: String) {
        // §2026-06-09 — Walks every (date × offset) slot scheduleEventReminder
        // could have registered, because the request code now varies by
        // occurrence date. Cancelling a slot that was never registered is
        // a no-op (PendingIntent.FLAG_NO_CREATE returns null), so the
        // wider window is just defensive — covers the schedule horizon
        // plus a small buffer for legacy pre-fix slots and timezone
        // drift around midnight.
        val todayEpochDay = System.currentTimeMillis() / MILLIS_PER_DAY
        val firstDay = todayEpochDay - CANCEL_WINDOW_PAST_DAYS
        val lastDay = todayEpochDay + CANCEL_WINDOW_FUTURE_DAYS
        var day = firstDay
        while (day <= lastDay) {
            val occurrenceMillis = day * MILLIS_PER_DAY
            for (minutes in KNOWN_REMINDER_OFFSETS) {
                cancel(
                    eventRequestCode(eventId, minutes, occurrenceMillis),
                    EventReminderReceiver::class.java,
                    eventId,
                )
            }
            day++
        }
        NotificationHelper.cancelEventReminder(context, eventId)
    }

    /// §2026-06-09 — Input is master rows from Room; expansion happens
    /// internally so callers don't need to know about recurring vs.
    /// one-shot. Pre-fix this took an already-expanded list (only
    /// BootReceiver bothered to expand), and even then the request-code
    /// scheme collapsed every expanded occurrence onto a single
    /// PendingIntent slot — so a "weekly Monday" event ended up with
    /// at most one armed alarm (the furthest-future occurrence in the
    /// window), and every nearer Monday silently lost its alarm.
    fun rescheduleAllEventReminders(masters: List<CalendarEventEntity>) {
        val now = System.currentTimeMillis()
        val cutoff = now + EVENT_SCHEDULE_WINDOW_DAYS * MILLIS_PER_DAY
        for (occ in CalendarRecurrence.expandWindow(masters, now, cutoff)) {
            scheduleOccurrenceReminder(occ)
        }
    }

    private fun scheduleExact(requestCode: Int, intent: Intent, triggerAtMillis: Long) {
        val pi = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()

        if (canExact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        }
    }

    private fun cancel(requestCode: Int, receiver: Class<*>, eventId: String? = null) {
        val intent = Intent(context, receiver).apply {
            if (eventId != null) putExtra(EXTRA_EVENT_ID, eventId)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pi != null) {
            alarmManager.cancel(pi)
            pi.cancel()
        }
    }

    /// §2026-06-09 — Request code now folds in the occurrence date so each
    /// expanded instance of a recurring series maps to its own slot.
    /// Before this, every occurrence shared the same (eventId, minutes)
    /// code, so the scheduling loop kept overwriting earlier instances
    /// onto a single PendingIntent — only the furthest-future occurrence
    /// in the window ever fired. Legacy alarms scheduled by pre-fix
    /// builds remain at the old code path; they fire once for some
    /// historical future date then disappear (one stray notification
    /// during the upgrade window, same migration pattern as v2.13.1).
    private fun eventRequestCode(eventId: String, minutes: Int, occurrenceMillis: Long): Int {
        val epochDay = (occurrenceMillis / MILLIS_PER_DAY).toInt()
        var h = eventId.hashCode()
        h = h * 31 + minutes
        h = h * 31 + epochDay
        return REQ_EVENT_BASE + ((h and 0x7fffffff) % (Int.MAX_VALUE - REQ_EVENT_BASE - 1))
    }

    private fun nextOccurrenceMillis(time: LocalTime): Long {
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        var candidate = LocalDateTime.of(LocalDate.now(zone), time)
        if (!candidate.isAfter(now)) {
            candidate = candidate.plusDays(1)
        }
        return candidate.atZone(zone).toInstant().toEpochMilli()
    }
}
