package com.ultiq.app.ui.settings

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ultiq.app.util.AlarmScheduler
import com.ultiq.app.util.NotificationHelper
import com.ultiq.app.util.ReminderPreferences
import com.ultiq.app.util.ReminderSettings
import com.ultiq.app.util.UserPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.LocalTime

data class RemindersUiState(
    val settings: ReminderSettings? = null,
    val canScheduleExact: Boolean = true,
    val notificationsEnabled: Boolean = true,
    /// §fix-bedtime-unified — read-only display of the bedtime managed
    /// in Sleep settings. The toggle here only controls the reminder
    /// being scheduled at all; the time itself isn't editable here.
    val targetBedtime: LocalTime = LocalTime.of(22, 0),
)

class RemindersViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = ReminderPreferences(application)
    private val userPrefs = UserPreferences(application)
    private val scheduler = AlarmScheduler(application)

    private val _uiState = MutableStateFlow(RemindersUiState())
    val uiState: StateFlow<RemindersUiState> = _uiState

    init {
        NotificationHelper.ensureChannels(application)
        refreshPermissionState()
        viewModelScope.launch {
            prefs.settings.collectLatest { settings ->
                val targetBedtime = userPrefs.snapshot().targetBedtime
                _uiState.value = _uiState.value.copy(
                    settings = settings,
                    targetBedtime = targetBedtime,
                )
                // §fix-bedtime-unified — the bedtime time always reads from
                // UserPreferences.targetBedtime, never from ReminderSettings.
                scheduler.applyDailyReminders(settings, targetBedtime)
            }
        }
        // Pick up live updates to the target bedtime so the read-only
        // display refreshes immediately when the user changes it from
        // Sleep settings without leaving Reminders.
        viewModelScope.launch {
            userPrefs.settings.collectLatest { user ->
                _uiState.value = _uiState.value.copy(targetBedtime = user.targetBedtime)
            }
        }
    }

    fun refreshPermissionState() {
        val app = getApplication<Application>()
        val alarmManager = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager?.canScheduleExactAlarms() == true
        } else true
        val notificationsEnabled = androidx.core.app.NotificationManagerCompat
            .from(app).areNotificationsEnabled()
        _uiState.value = _uiState.value.copy(
            canScheduleExact = canExact,
            notificationsEnabled = notificationsEnabled,
        )
    }

    fun setBedtimeEnabled(enabled: Boolean) = viewModelScope.launch {
        prefs.setBedtimeEnabled(enabled)
    }

    // §fix-bedtime-unified — kept for back-compat callers, but the
    // Reminders screen no longer exposes a time picker for bedtime.
    // The canonical bedtime lives on UserPreferences.targetBedtime now.
    @Deprecated("Bedtime time is controlled by Sleep settings; toggle only here.")
    fun setBedtimeTime(time: LocalTime) = viewModelScope.launch {
        prefs.setBedtimeTime(time)
    }

    fun setFocusEnabled(enabled: Boolean) = viewModelScope.launch {
        prefs.setFocusEnabled(enabled)
    }

    fun setFocusTime(time: LocalTime) = viewModelScope.launch {
        prefs.setFocusTime(time)
    }

    fun setMorningSummaryEnabled(enabled: Boolean) = viewModelScope.launch {
        prefs.setMorningSummaryEnabled(enabled)
    }

    fun setMorningSummaryTime(time: LocalTime) = viewModelScope.launch {
        prefs.setMorningSummaryTime(time)
    }
}
