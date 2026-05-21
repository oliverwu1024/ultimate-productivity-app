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

        const val EXTRA_EVENT_ID = "event_id"
        const val EXTRA_EVENT_TITLE = "event_title"
        const val EXTRA_EVENT_START = "event_start"

        private const val REQ_BEDTIME = 11_001
        private const val REQ_FOCUS = 11_002
        private const val REQ_MORNING_SUMMARY = 11_003
        private const val REQ_EVENT_BASE = 12_000
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

    fun scheduleEventReminder(event: CalendarEventEntity) {
        val triggerMillis = event.startTime - EVENT_LEAD_MINUTES * 60_000L
        if (triggerMillis <= System.currentTimeMillis()) return

        val intent = Intent(context, EventReminderReceiver::class.java).apply {
            putExtra(EXTRA_EVENT_ID, event.id)
            putExtra(EXTRA_EVENT_TITLE, event.title)
            putExtra(EXTRA_EVENT_START, event.startTime)
        }
        scheduleExact(eventRequestCode(event.id), intent, triggerMillis)
    }

    fun cancelEventReminder(eventId: String) {
        cancel(eventRequestCode(eventId), EventReminderReceiver::class.java, eventId)
        NotificationHelper.cancelEventReminder(context, eventId)
    }

    fun rescheduleAllEventReminders(events: List<CalendarEventEntity>) {
        val now = System.currentTimeMillis()
        val cutoff = now + 30L * 86_400_000L // schedule events in next 30 days only
        events.asSequence()
            .filter { it.startTime in now..cutoff }
            .forEach { scheduleEventReminder(it) }
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

    private fun eventRequestCode(eventId: String): Int =
        REQ_EVENT_BASE + (eventId.hashCode() and 0x0fffffff)

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
