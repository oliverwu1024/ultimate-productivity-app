package com.ultiq.app.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Auth credential store, backed by [EncryptedSharedPreferences] (AES-256-GCM
 * for values, AES-256-SIV for keys, master key in the Android Keystore).
 *
 * Public API still exposes [Flow] for compatibility with the existing
 * collectors / `firstOrNull()` callsites.
 */
class TokenManager(context: Context) {
    private val appContext = context.applicationContext

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val prefs: SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            appContext,
            "auth_prefs_v2",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val _token = MutableStateFlow(prefs.getString(KEY_TOKEN, null))
    private val _userId = MutableStateFlow(prefs.getString(KEY_USER_ID, null))
    private val _email = MutableStateFlow(prefs.getString(KEY_EMAIL, null))

    init {
        // One-time cleanup of the legacy plaintext DataStore. Old tokens are
        // already invalidated server-side by the new JWT iss/aud requirement,
        // so we don't migrate values — just delete the file so a stale plaintext
        // JWT can't be lifted later via root/USB-debug.
        runCatching {
            val legacy = File(appContext.filesDir, "datastore/auth_prefs.preferences_pb")
            if (legacy.exists()) legacy.delete()
        }
    }

    fun getToken(): Flow<String?> = _token.asStateFlow()
    fun getUserId(): Flow<String?> = _userId.asStateFlow()
    fun getEmail(): Flow<String?> = _email.asStateFlow()

    suspend fun saveToken(token: String) = write(KEY_TOKEN, token, _token)
    suspend fun saveUserId(userId: String) = write(KEY_USER_ID, userId, _userId)
    suspend fun saveEmail(email: String) = write(KEY_EMAIL, email, _email)

    suspend fun clearToken() {
        withContext(Dispatchers.IO) {
            prefs.edit()
                .remove(KEY_TOKEN)
                .remove(KEY_USER_ID)
                .remove(KEY_EMAIL)
                .apply()
        }
        _token.value = null
        _userId.value = null
        _email.value = null
    }

    private suspend fun write(key: String, value: String, mirror: MutableStateFlow<String?>) {
        withContext(Dispatchers.IO) {
            prefs.edit().putString(key, value).apply()
        }
        mirror.value = value
    }

    companion object {
        private const val KEY_TOKEN = "jwt_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_EMAIL = "user_email"
    }
}
