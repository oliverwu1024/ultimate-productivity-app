package com.ultiq.app.ui.settings

import android.app.Application
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ultiq.app.BuildConfig
import com.ultiq.app.R
import com.ultiq.app.data.remote.RetrofitClient
import com.ultiq.app.ui.lockout.LockoutAdmin
import com.ultiq.app.ui.theme.ThemeMode
import com.ultiq.app.util.ThemePreference
import com.ultiq.app.util.TokenManager
import com.ultiq.app.util.UserPreferences
import com.ultiq.app.util.UserSettings
import com.ultiq.app.util.toUserMessage
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
    /// Mirrored from TokenManager. Default true so existing sessions don't
    /// flash an "unverified" row before the cached value is read.
    val emailVerified: Boolean = true,
    val resendingVerification: Boolean = false,
    val verificationFeedback: String? = null,
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
        viewModelScope.launch {
            tokenManager.getEmailVerified().collectLatest { verified ->
                _uiState.value = _uiState.value.copy(emailVerified = verified)
            }
        }
        refreshOverlayPermission()
    }

    /// Re-fetches /auth/me to pick up a verification that happened
    /// outside the app (typical: Gmail → Chrome → dashboard /verify-email).
    fun refreshUserStatus() {
        viewModelScope.launch {
            runCatching { api.getMe() }
                .onSuccess { me ->
                    tokenManager.saveEmailVerified(me.email_verified)
                    _uiState.value = _uiState.value.copy(emailVerified = me.email_verified)
                }
        }
    }

    fun resendVerificationEmail() {
        if (_uiState.value.resendingVerification) return
        _uiState.value = _uiState.value.copy(
            resendingVerification = true,
            verificationFeedback = null,
        )
        viewModelScope.launch {
            runCatching { api.resendVerificationEmail() }
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        resendingVerification = false,
                        verificationFeedback = getApplication<Application>()
                            .getString(R.string.settings_verification_sent),
                    )
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        resendingVerification = false,
                        verificationFeedback = e.toUserMessage(
                            getApplication<Application>()
                                .getString(R.string.settings_verification_error)
                        ),
                    )
                }
        }
    }

    fun clearVerificationFeedback() {
        _uiState.value = _uiState.value.copy(verificationFeedback = null)
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

    /** §13 (i18n) — apply + persist + sync the chosen language. setLanguage
     *  recreates the activity so the whole UI re-resolves in the new locale;
     *  Compose Navigation restores the back stack, so the user stays put. */
    fun setAppLanguage(tag: String) {
        com.ultiq.app.util.LocaleManager.setLanguage(tag)
        viewModelScope.launch {
            userPreferences.setAppLanguage(tag)
            runCatching {
                api.updateProfile(
                    com.ultiq.app.data.remote.dto.UpdateProfileRequest(
                        preferences = com.google.gson.JsonObject().apply {
                            addProperty("app_language", tag)
                        },
                    ),
                )
            }
        }
    }
}
