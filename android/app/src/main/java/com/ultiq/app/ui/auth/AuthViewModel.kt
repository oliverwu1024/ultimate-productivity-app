package com.ultiq.app.ui.auth

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ultiq.app.UltiqApp
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
                // §i18n (v2.13.9) — Push the device's IANA timezone to the
                // server so all "today" bucketing + the anomaly scheduler use
                // the user's local clock instead of UTC. Fire-and-forget;
                // failure leaves the server on its previous value (UTC for
                // a fresh signup, the last-pushed zone otherwise).
                syncTimezone()
                // §fresh-login-sync (v2.13.5): hydrate Room before Dashboard
                // mounts. Without this, the user sees empty screens until
                // either per-tab lazy loads finish racing the first compose,
                // or SSE's onConnected callback fires (which itself doesn't
                // fire on this path — see hydrateAfterAuth comment).
                hydrateAfterAuth()
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
                // §i18n (v2.13.9) — Same as login. For a brand-new account
                // this is what actually populates the timezone column on the
                // server (which would otherwise stay at the 'UTC' default
                // until the user opened Settings).
                syncTimezone()
                // Same hydrate-then-route path as login; for a brand-new
                // account every domain pull returns empty, but it still kicks
                // SSE so future events stream without waiting for the next
                // foreground.
                hydrateAfterAuth()
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

    /** §i18n (v2.13.9) — Push the device's IANA timezone to the server.
     *  Wrapped in runCatching because a transient network failure here is
     *  acceptable: the backend will still bucket against the previous
     *  value (UTC fallback for brand-new accounts, last-known-good
     *  otherwise), and the next login fires this again. */
    private suspend fun syncTimezone() {
        runCatching {
            val tz = java.time.ZoneId.systemDefault().id
            api.updateProfile(
                com.ultiq.app.data.remote.dto.UpdateProfileRequest(timezone = tz)
            )
        }
    }

    /** §fresh-login-sync (v2.13.5) — Pull every domain repo once on the
     *  caller's coroutine before the UI navigates, then kick SSE so future
     *  events stream in real time. SSE's lifecycle-driven connect() already
     *  ran at app start when there was no token; without this explicit
     *  reconnect it never retries on its own when the user logs in mid-session. */
    private suspend fun hydrateAfterAuth() {
        val app = getApplication<Application>() as? UltiqApp ?: return
        runCatching {
            app.syncManager.syncAll()
        }
        runCatching {
            app.syncEventClient.connect()
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
