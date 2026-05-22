package com.ultiq.app.ui.auth

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ultiq.app.data.local.AppDatabase
import com.ultiq.app.data.remote.ApiService
import com.ultiq.app.data.remote.RetrofitClient
import com.ultiq.app.data.remote.dto.ForgotPasswordRequest
import com.ultiq.app.data.remote.dto.LoginRequest
import com.ultiq.app.data.remote.dto.RegisterRequest
import com.ultiq.app.data.remote.dto.ResetPasswordRequest
import com.ultiq.app.fcm.FcmTokenSyncer
import com.ultiq.app.util.TokenManager
import com.ultiq.app.util.UserPreferences
import com.ultiq.app.util.toUserMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AuthUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val isLoggedIn: Boolean = false,
    val isCheckingAuth: Boolean = true,
    val onboardingCompleted: Boolean = true, // default true to avoid flicker — overwritten once loaded
    val isCheckingOnboarding: Boolean = true,
    val forgotPasswordEmailSent: Boolean = false,
    val resetPasswordSuccess: Boolean = false,
)

class AuthViewModel(application: Application) : AndroidViewModel(application) {
    private val tokenManager = TokenManager(application)
    private val api: ApiService = RetrofitClient.create(tokenManager)
    private val userPreferences = UserPreferences(application)
    private val fcmTokenSyncer = FcmTokenSyncer(application)

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState

    init {
        checkAuth()
        observeOnboarding()
    }

    private fun observeOnboarding() {
        viewModelScope.launch {
            userPreferences.settings.collectLatest { settings ->
                _uiState.value = _uiState.value.copy(
                    onboardingCompleted = settings.onboardingCompleted,
                    isCheckingOnboarding = false,
                )
            }
        }
    }

    fun checkAuth() {
        viewModelScope.launch {
            val token = tokenManager.getToken().firstOrNull()
            if (token != null) {
                try {
                    val me = api.getMe()
                    // Backfill cached email for users logged in before email persistence landed
                    if (tokenManager.getEmail().firstOrNull().isNullOrBlank()) {
                        tokenManager.saveEmail(me.email)
                    }
                    _uiState.value = _uiState.value.copy(
                        isLoggedIn = true,
                        isCheckingAuth = false,
                    )
                    // §9.8 — Existing session: re-sync the FCM token so users
                    // who installed Ultiq before this feature shipped get
                    // registered the first time they relaunch.
                    fcmTokenSyncer.syncIfLoggedIn()
                } catch (_: Exception) {
                    tokenManager.clearToken()
                    _uiState.value = _uiState.value.copy(
                        isLoggedIn = false,
                        isCheckingAuth = false,
                    )
                }
            } else {
                _uiState.value = _uiState.value.copy(
                    isLoggedIn = false,
                    isCheckingAuth = false,
                )
            }
        }
    }

    fun login(email: String, password: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val response = api.login(LoginRequest(email, password))
                tokenManager.saveToken(response.token)
                tokenManager.saveUserId(response.user.id)
                tokenManager.saveEmail(response.user.email)
                // §sync-prefs: pull server's preferences into local DataStore so
                // a fresh install on a new device picks up the user's existing
                // bedtime/wake/focus config rather than starting from defaults.
                response.user.preferences?.let { userPreferences.applyServerPreferences(it) }
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isLoggedIn = true,
                    isCheckingAuth = false,
                )
                // §9.8 — Tell the backend where to deliver future anomaly
                // push notifications for this device. Non-blocking; failure
                // is logged + retried on the next login / token rotation.
                fcmTokenSyncer.syncIfLoggedIn()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.toUserMessage("Login failed. Try again."),
                )
            }
        }
    }

    fun register(email: String, password: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val response = api.register(RegisterRequest(email, password))
                tokenManager.saveToken(response.token)
                tokenManager.saveUserId(response.user.id)
                tokenManager.saveEmail(response.user.email)
                // §sync-prefs: a brand-new account has an empty preferences
                // blob server-side, so this is a no-op. The hook is here for
                // symmetry with login (and so that account-merge flows work
                // when we eventually have them).
                response.user.preferences?.let { userPreferences.applyServerPreferences(it) }
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isLoggedIn = true,
                    isCheckingAuth = false,
                )
                // §9.8 — Same as login: register this device for push.
                fcmTokenSyncer.syncIfLoggedIn()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.toUserMessage("Registration failed. Try again."),
                )
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            // §9.8 — Scrub this device's FCM row before clearing the auth
            // token so the unregister call has a JWT to authenticate with.
            // Non-fatal if it fails.
            fcmTokenSyncer.unregister()
            tokenManager.clearToken()
            _uiState.value = _uiState.value.copy(
                isLoggedIn = false,
                isCheckingAuth = false,
            )
        }
    }

    fun deleteAccount() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                api.deleteAccount()
                scrubLocalState(includeAuth = true, includePreferences = true)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isLoggedIn = false,
                    isCheckingAuth = false,
                    onboardingCompleted = false,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.toUserMessage("Couldn't delete account. Try again."),
                )
            }
        }
    }

    fun resetAccount() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                api.resetAccount()
                scrubLocalState(includeAuth = false, includePreferences = false)
                _uiState.value = _uiState.value.copy(isLoading = false)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.toUserMessage("Couldn't reset account. Try again."),
                )
            }
        }
    }

    private suspend fun scrubLocalState(includeAuth: Boolean, includePreferences: Boolean) {
        withContext(Dispatchers.IO) {
            AppDatabase.getInstance(getApplication()).clearAllTables()
        }
        if (includeAuth) {
            tokenManager.clearToken()
        }
        if (includePreferences) {
            userPreferences.clearAll()
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun forgotPassword(email: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                error = null,
                forgotPasswordEmailSent = false,
            )
            try {
                api.forgotPassword(ForgotPasswordRequest(email))
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    forgotPasswordEmailSent = true,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.toUserMessage("Couldn't send reset email. Try again."),
                )
            }
        }
    }

    fun resetPassword(token: String, newPassword: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                error = null,
                resetPasswordSuccess = false,
            )
            try {
                api.resetPassword(ResetPasswordRequest(token, newPassword))
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    resetPasswordSuccess = true,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.toUserMessage("Couldn't reset password. The link may have expired."),
                )
            }
        }
    }

    fun consumeForgotPasswordSent() {
        _uiState.value = _uiState.value.copy(forgotPasswordEmailSent = false)
    }

    fun consumeResetPasswordSuccess() {
        _uiState.value = _uiState.value.copy(resetPasswordSuccess = false)
    }
}
