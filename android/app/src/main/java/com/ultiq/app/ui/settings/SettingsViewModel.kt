package com.ultiq.app.ui.settings

import android.app.Application
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ultiq.app.BuildConfig
import com.ultiq.app.data.remote.RetrofitClient
import com.ultiq.app.ui.lockout.LockoutAdmin
import com.ultiq.app.ui.theme.ThemeMode
import com.ultiq.app.util.ThemePreference
import com.ultiq.app.util.TokenManager
import com.ultiq.app.util.UserPreferences
import com.ultiq.app.util.UserSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

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
}
