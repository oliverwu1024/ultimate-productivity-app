package com.ultiq.app.ui.onboarding

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ultiq.app.util.AlarmScheduler
import com.ultiq.app.util.ReminderPreferences
import com.ultiq.app.util.UserPreferences
import kotlinx.coroutines.launch
import java.time.LocalTime

data class OnboardingUiState(
    val step: Int = 0,
    val targetBedtime: LocalTime = LocalTime.of(22, 0),
    val targetWakeTime: LocalTime = LocalTime.of(6, 0),
    val workDuration: Int = 25,
)

class OnboardingViewModel(application: Application) : AndroidViewModel(application) {
    private val userPreferences = UserPreferences(application)
    private val reminderPreferences = ReminderPreferences(application)
    private val alarmScheduler = AlarmScheduler(application)

    private val _uiState = kotlinx.coroutines.flow.MutableStateFlow(OnboardingUiState())
    val uiState: kotlinx.coroutines.flow.StateFlow<OnboardingUiState> = _uiState

    fun setStep(step: Int) {
        // Keep in sync with STEP_COUNT in OnboardingScreen.kt (currently 5: welcome,
        // sleep, focus, permissions, all-set).
        _uiState.value = _uiState.value.copy(step = step.coerceIn(0, 4))
    }

    fun next() = setStep(_uiState.value.step + 1)
    fun back() = setStep(_uiState.value.step - 1)

    fun setTargetBedtime(time: LocalTime) {
        _uiState.value = _uiState.value.copy(targetBedtime = time)
    }

    fun setTargetWakeTime(time: LocalTime) {
        _uiState.value = _uiState.value.copy(targetWakeTime = time)
    }

    fun setWorkDuration(minutes: Int) {
        _uiState.value = _uiState.value.copy(workDuration = minutes.coerceIn(5, 240))
    }

    fun finish() {
        viewModelScope.launch {
            val s = _uiState.value
            userPreferences.setTargetBedtime(s.targetBedtime)
            userPreferences.setTargetWakeTime(s.targetWakeTime)
            userPreferences.setDefaultWorkDuration(s.workDuration)
            // Sync the bedtime reminder schedule to the user's chosen target
            reminderPreferences.setBedtimeTime(s.targetBedtime)
            val reminders = reminderPreferences.snapshot()
            alarmScheduler.applyDailyReminders(reminders)
            userPreferences.setOnboardingCompleted(true)
        }
    }
}
