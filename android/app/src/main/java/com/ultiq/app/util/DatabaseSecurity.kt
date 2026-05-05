package com.ultiq.app.util

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.security.SecureRandom

/**
 * Owns the SQLCipher passphrase for the Room DB and the one-shot migration
 * that drops the legacy unencrypted database file from v1.7 installs.
 *
 * The passphrase is a 32-byte CSPRNG-generated value stored in
 * [EncryptedSharedPreferences] (AES-256-GCM, master key in the Android
 * Keystore). It never appears on disk in plaintext.
 */
object DatabaseSecurity {
    private const val PREFS_NAME = "db_security_v1"
    private const val KEY_PASSPHRASE = "db_passphrase_b64"
    private const val KEY_LEGACY_DROPPED = "legacy_db_dropped"
    private const val DB_NAME = "productivity_db"

    @Volatile
    private var cached: ByteArray? = null

    /**
     * Returns the SQLCipher passphrase for the Room DB, generating it on first
     * call. Bytes are cached in process memory for the app's lifetime — the
     * Keystore-backed disk store is only hit once per cold start.
     */
    fun getOrCreatePassphrase(context: Context): ByteArray {
        cached?.let { return it.copyOf() }
        synchronized(this) {
            cached?.let { return it.copyOf() }
            val prefs = openPrefs(context)
            val existing = prefs.getString(KEY_PASSPHRASE, null)
            val bytes = if (existing != null) {
                Base64.decode(existing, Base64.NO_WRAP)
            } else {
                val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
                prefs.edit()
                    .putString(KEY_PASSPHRASE, Base64.encodeToString(fresh, Base64.NO_WRAP))
                    .apply()
                fresh
            }
            cached = bytes
            return bytes.copyOf()
        }
    }

    /**
     * One-shot migration for users upgrading from an installed v1.7 (which
     * had an unencrypted Room DB). On the first launch after upgrade, drop
     * the legacy database files so SQLCipher creates a fresh encrypted DB.
     * SyncManager will refetch from the backend on next login.
     */
    fun dropLegacyDbIfNeeded(context: Context) {
        val prefs = openPrefs(context)
        if (prefs.getBoolean(KEY_LEGACY_DROPPED, false)) return
        val dbDir = context.getDatabasePath(DB_NAME).parentFile ?: return
        listOf(
            File(dbDir, DB_NAME),
            File(dbDir, "$DB_NAME-shm"),
            File(dbDir, "$DB_NAME-wal"),
            File(dbDir, "$DB_NAME-journal"),
        ).forEach { f -> if (f.exists()) f.delete() }
        prefs.edit().putBoolean(KEY_LEGACY_DROPPED, true).apply()
    }

    private fun openPrefs(context: Context) = EncryptedSharedPreferences.create(
        context.applicationContext,
        PREFS_NAME,
        MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
}
