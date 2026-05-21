package com.ultiq.app.ui.alarms

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ultiq.app.alarm.WakeAlarmScheduler
import com.ultiq.app.alarm.mission.MathDifficulty
import com.ultiq.app.alarm.mission.MissionConfig
import com.ultiq.app.alarm.mission.ShakeIntensity
import com.ultiq.app.data.local.AppDatabase
import com.ultiq.app.data.local.entity.AlarmEntity
import com.ultiq.app.data.remote.RetrofitClient
import com.ultiq.app.data.repository.AlarmRepository
import com.ultiq.app.util.TokenManager
import com.ultiq.app.util.UserPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class AlarmsViewModel(application: Application) : AndroidViewModel(application) {

    private val tokenManager = TokenManager(application)
    private val api = RetrofitClient.create(tokenManager)
    private val userPreferences = UserPreferences(application)
    private val repo = AlarmRepository(
        context = application,
        alarmDao = AppDatabase.getInstance(application).alarmDao(),
        apiService = api,
    )

    val alarms: StateFlow<List<AlarmEntity>> = repo.getAlarms()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _editing = MutableStateFlow<AlarmEntity?>(null)
    val editing: StateFlow<AlarmEntity?> = _editing

    fun loadForEdit(id: String?) {
        viewModelScope.launch {
            _editing.value = if (id == null) {
                blankDraft()
            } else {
                repo.getAlarm(id) ?: blankDraft()
            }
        }
    }

    fun updateEditingDraft(transform: (AlarmEntity) -> AlarmEntity) {
        _editing.value = _editing.value?.let(transform)
    }

    fun save(onDone: () -> Unit) {
        val draft = _editing.value ?: return
        viewModelScope.launch {
            val isNew = repo.getAlarm(draft.id) == null
            if (isNew) repo.createAlarm(draft) else repo.updateAlarm(draft)
            _editing.value = null
            onDone()
        }
    }

    /**
     * Save the draft with [enabled] forced. "Set Alarm" passes true so the
     * alarm arms immediately; "Cancel" passes false so the time settings are
     * persisted but the alarm doesn't fire — the user can flick it back on
     * from the list later.
     */
    fun saveWithEnabled(enabled: Boolean, onDone: () -> Unit) {
        val draft = _editing.value ?: return
        viewModelScope.launch {
            val toSave = draft.copy(enabled = enabled, updatedAt = System.currentTimeMillis())
            val isNew = repo.getAlarm(toSave.id) == null
            if (isNew) repo.createAlarm(toSave) else repo.updateAlarm(toSave)
            _editing.value = null
            onDone()
        }
    }

    fun setEnabled(alarm: AlarmEntity, enabled: Boolean) {
        viewModelScope.launch { repo.setEnabled(alarm.id, enabled) }
    }

    fun delete(alarm: AlarmEntity) {
        viewModelScope.launch { repo.deleteAlarm(alarm.id) }
    }

    /**
     * Dev hook: schedule a one-shot test alarm ~75s in the future to validate
     * the pipeline + each mission type. Lives here (rather than on Settings)
     * because alarms now live on the Sleep tab. Remove the section that calls
     * this before the eventual Play Store production push.
     */
    fun scheduleTestAlarm(missionKind: String = "none") = viewModelScope.launch {
        val app = getApplication<Application>()
        val userId = tokenManager.getUserId().first() ?: "local-test"
        val now = System.currentTimeMillis()
        val triggerEpoch = ((now / 60_000L) + 2L) * 60_000L
        val triggerLocal = Instant.ofEpochMilli(triggerEpoch).atZone(ZoneId.systemDefault())
        val missionConfigJson = when (missionKind) {
            "math" -> MissionConfig.buildMath(MathDifficulty.MEDIUM, count = 3)
            "shake" -> MissionConfig.buildShake(ShakeIntensity.MEDIUM, shakesRequired = 30)
            else -> "{}"
        }
        val alarm = AlarmEntity(
            id = UUID.randomUUID().toString(),
            userId = userId,
            label = "Test alarm ($missionKind)",
            triggerHour = triggerLocal.hour,
            triggerMinute = triggerLocal.minute,
            daysOfWeekMask = 0,
            enabled = true,
            soundUri = null,
            volumePct = 80,
            volumeEscalates = true,
            vibration = true,
            snoozeMinutes = 9,
            snoozeMax = 3,
            missionKind = missionKind,
            missionConfigJson = missionConfigJson,
            createdAt = now,
            updatedAt = now,
            isSynced = false,
        )
        AppDatabase.getInstance(app).alarmDao().insertAlarm(alarm)
        val scheduledAt = WakeAlarmScheduler(app).schedule(alarm)
        val message = if (scheduledAt != null) {
            "$missionKind alarm scheduled for " + triggerLocal.toLocalTime()
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        } else {
            "Couldn't schedule — grant Exact Alarm permission in system settings"
        }
        Toast.makeText(app, message, Toast.LENGTH_LONG).show()
    }

    private suspend fun blankDraft(): AlarmEntity {
        val userId = tokenManager.getUserId().first() ?: "local"
        val now = System.currentTimeMillis()
        val settings = userPreferences.snapshot()
        // Suggested wake = bedtime + sleep goal. Falls back to 07:00 if the
        // computed time is the same minute as the bedtime (i.e. sleep goal = 0).
        val suggestedWake = settings.targetBedtime.plusMinutes(settings.sleepTargetMinutes.toLong())
        return AlarmEntity(
            id = UUID.randomUUID().toString(),
            userId = userId,
            label = null,
            triggerHour = suggestedWake.hour,
            triggerMinute = suggestedWake.minute,
            daysOfWeekMask = 0,
            enabled = true,
            soundUri = null,
            volumePct = 80,
            volumeEscalates = true,
            vibration = true,
            snoozeMinutes = 9,
            snoozeMax = 3,
            missionKind = "none",
            missionConfigJson = MissionConfig.buildMath(MathDifficulty.MEDIUM, 3),
            createdAt = now,
            updatedAt = now,
            isSynced = false,
        )
    }
}
