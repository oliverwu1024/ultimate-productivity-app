package com.app.productivity.ui.auth

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.app.productivity.data.local.AppDatabase
import com.app.productivity.data.remote.ApiService
import com.app.productivity.data.remote.RetrofitClient
import com.app.productivity.data.remote.dto.LoginRequest
import com.app.productivity.data.remote.dto.RegisterRequest
import com.app.productivity.util.TokenManager
import com.app.productivity.util.UserPreferences
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
)

class AuthViewModel(application: Application) : AndroidViewModel(application) {
    private val tokenManager = TokenManager(application)
    private val api: ApiService = RetrofitClient.create(tokenManager)
    private val userPreferences = UserPreferences(application)

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
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isLoggedIn = true,
                    isCheckingAuth = false,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Login failed"
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
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isLoggedIn = true,
                    isCheckingAuth = false,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Registration failed"
                )
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
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
                    error = e.message ?: "Failed to delete account",
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
                    error = e.message ?: "Failed to reset account",
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
}
