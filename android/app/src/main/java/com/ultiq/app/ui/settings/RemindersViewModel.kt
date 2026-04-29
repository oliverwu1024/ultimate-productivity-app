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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.LocalTime

data class RemindersUiState(
    val settings: ReminderSettings? = null,
    val canScheduleExact: Boolean = true,
    val notificationsEnabled: Boolean = true,
)

class RemindersViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = ReminderPreferences(application)
    private val scheduler = AlarmScheduler(application)

    private val _uiState = MutableStateFlow(RemindersUiState())
    val uiState: StateFlow<RemindersUiState> = _uiState

    init {
        NotificationHelper.ensureChannels(application)
        refreshPermissionState()
        viewModelScope.launch {
            prefs.settings.collectLatest { settings ->
                _uiState.value = _uiState.value.copy(settings = settings)
                scheduler.applyDailyReminders(settings)
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
