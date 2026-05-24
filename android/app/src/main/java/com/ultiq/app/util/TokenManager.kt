package com.ultiq.app.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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

    // §multi-instance-fix (v2.13.6) — Every ViewModel + UltiqApp + SyncWorker
    // constructs its own TokenManager. The previous implementation cached
    // the current value in a per-instance MutableStateFlow initialised at
    // construction; AuthViewModel.saveToken() only updated its own instance,
    // so the syncManager constructed inside UltiqApp.wireRealtimeSync()
    // (with a separate TokenManager) kept reading `null` on the first
    // login. Sync silently 401'd → empty Room → empty Dashboard.
    //
    // Fix: read fresh from EncryptedSharedPreferences on every emission.
    // The underlying file is the shared source of truth, so any instance's
    // write becomes visible to every instance immediately. All current
    // callers use `first()` / `firstOrNull()` / `collectLatest` and only
    // need the present value, so we don't lose observability — and even
    // collectLatest fires once with the current value, which matches the
    // single-process usage (e.g. SettingsViewModel populating the email
    // field on first composition).
    fun getToken(): Flow<String?> = readFresh(KEY_TOKEN)
    fun getUserId(): Flow<String?> = readFresh(KEY_USER_ID)
    fun getEmail(): Flow<String?> = readFresh(KEY_EMAIL)

    suspend fun saveToken(token: String) = write(KEY_TOKEN, token)
    suspend fun saveUserId(userId: String) = write(KEY_USER_ID, userId)
    suspend fun saveEmail(email: String) = write(KEY_EMAIL, email)

    suspend fun clearToken() {
        withContext(Dispatchers.IO) {
            prefs.edit()
                .remove(KEY_TOKEN)
                .remove(KEY_USER_ID)
                .remove(KEY_EMAIL)
                .apply()
        }
    }

    private fun readFresh(key: String): Flow<String?> = flow {
        emit(withContext(Dispatchers.IO) { prefs.getString(key, null) })
    }

    private suspend fun write(key: String, value: String) {
        withContext(Dispatchers.IO) {
            prefs.edit().putString(key, value).apply()
        }
    }

    companion object {
        private const val KEY_TOKEN = "jwt_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_EMAIL = "user_email"
    }
}
