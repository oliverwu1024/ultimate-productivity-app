package com.ultiq.app.ui.settings

import android.app.Application
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ultiq.app.BuildConfig
import com.ultiq.app.data.remote.RetrofitClient
import com.ultiq.app.data.remote.dto.UpdateProfileRequest
import com.ultiq.app.ui.lockout.LockoutAdmin
import com.ultiq.app.ui.theme.ThemeMode
import com.ultiq.app.util.ReminderPreferences
import com.ultiq.app.util.ThemePreference
import com.ultiq.app.util.TokenManager
import com.ultiq.app.util.UserPreferences
import com.ultiq.app.util.UserSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.LocalTime

data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val user: UserSettings? = null,
    val email: String? = null,
    val canDrawOverlays: Boolean = false,
    val isStrictLockEnabled: Boolean = false,
    val versionName: String = BuildConfig.VERSION_NAME,
    val versionCode: Int = BuildConfig.VERSION_CODE,
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val themePreference = ThemePreference(application)
    private val userPreferences = UserPreferences(application)
    private val reminderPreferences = ReminderPreferences(application)
    private val tokenManager = TokenManager(application)
    private val api = RetrofitClient.create(tokenManager)

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState

    init {
        viewModelScope.launch {
            themePreference.themeMode.collectLatest { mode ->
                _uiState.value = _uiState.value.copy(themeMode = mode)
            }
        }
        viewModelScope.launch {
            userPreferences.settings.collectLatest { settings ->
                _uiState.value = _uiState.value.copy(user = settings)
            }
        }
        viewModelScope.launch {
            tokenManager.getEmail().collectLatest { email ->
                _uiState.value = _uiState.value.copy(email = email)
            }
        }
        refreshOverlayPermission()
    }

    fun refreshOverlayPermission() {
        _uiState.value = _uiState.value.copy(
            canDrawOverlays = Settings.canDrawOverlays(getApplication()),
            isStrictLockEnabled = LockoutAdmin.isAdminActive(getApplication()),
        )
    }

    fun disableStrictLock() {
        LockoutAdmin.disableAdmin(getApplication())
        refreshOverlayPermission()
    }

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch {
        themePreference.setThemeMode(mode)
    }

    fun setTargetBedtime(time: LocalTime) = viewModelScope.launch {
        userPreferences.setTargetBedtime(time)
        // Keep the bedtime reminder time in sync with the user's target
        reminderPreferences.setBedtimeTime(time)
    }

    fun setTargetWakeTime(time: LocalTime) = viewModelScope.launch {
        userPreferences.setTargetWakeTime(time)
    }

    fun setDefaultWorkDuration(minutes: Int) = viewModelScope.launch {
        userPreferences.setDefaultWorkDuration(minutes)
    }

    fun setDefaultBreakDuration(minutes: Int) = viewModelScope.launch {
        userPreferences.setDefaultBreakDuration(minutes)
    }

    fun setLockoutForFocus(enabled: Boolean) = viewModelScope.launch {
        userPreferences.setLockoutForFocus(enabled)
    }

    fun setLockoutForSleep(enabled: Boolean) = viewModelScope.launch {
        userPreferences.setLockoutForSleep(enabled)
    }

    fun setShowPickupCountOnLockout(enabled: Boolean) = viewModelScope.launch {
        userPreferences.setShowPickupCountOnLockout(enabled)
    }

    fun setAllowEndSessionFromLockout(enabled: Boolean) = viewModelScope.launch {
        userPreferences.setAllowEndSessionFromLockout(enabled)
    }

    fun setLockoutGraceMinutes(minutes: Int) = viewModelScope.launch {
        userPreferences.setLockoutGraceMinutes(minutes)
    }

    fun setSleepTargetMinutes(minutes: Int) = viewModelScope.launch {
        // Local cache first for instant UI, then sync to backend in background.
        userPreferences.setSleepTargetMinutes(minutes)
        runCatching {
            api.updateProfile(UpdateProfileRequest(sleep_target_minutes = minutes))
        }
    }
}
