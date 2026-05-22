package com.ultiq.app.fcm

import android.content.Context
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import com.ultiq.app.data.remote.RetrofitClient
import com.ultiq.app.data.remote.dto.RegisterDeviceTokenRequest
import com.ultiq.app.util.TokenManager
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.tasks.await

/// §9.8 — Pushes the device's current FCM registration token to the backend
/// so `device_tokens` knows where to deliver anomaly alerts. Idempotent: the
/// backend upserts on the token column.
///
/// Called from three places:
///  - After successful login / register (covers fresh installs)
///  - From [UltiqMessagingService.onNewToken] when Firebase rotates the token
///  - Optionally on app start if a session is already valid (handles upgrades
///    where the token already existed before this feature shipped)
class FcmTokenSyncer(context: Context) {

    private val appContext = context.applicationContext
    private val tokenManager = TokenManager(appContext)
    private val api by lazy { RetrofitClient.create(tokenManager) }

    /// Fetch the current token from Firebase and POST it. No-op when the
    /// user isn't logged in — the next login will pick up the same token.
    suspend fun syncIfLoggedIn() {
        val authToken = tokenManager.getToken().firstOrNull()
        if (authToken.isNullOrBlank()) {
            Log.d(TAG, "no auth token — skipping FCM sync until login")
            return
        }
        runCatching {
            val fcmToken = FirebaseMessaging.getInstance().token.await()
            api.registerDevice(
                RegisterDeviceTokenRequest(token = fcmToken, platform = "android"),
            )
            Log.i(TAG, "FCM token synced to backend (prefix=${fcmToken.take(16)}…)")
        }.onFailure { e ->
            // Non-fatal: a transient network failure here just means the
            // next sync attempt (next login, next token rotation) will
            // succeed. Don't crash, don't retry — keep it cheap.
            Log.w(TAG, "FCM token sync failed (non-fatal): ${e.message}")
        }
    }

    /// Variant for the [UltiqMessagingService.onNewToken] callback where
    /// Firebase already handed us the new token.
    suspend fun sync(fcmToken: String) {
        val authToken = tokenManager.getToken().firstOrNull()
        if (authToken.isNullOrBlank()) {
            Log.d(TAG, "onNewToken before login — will sync on next login instead")
            return
        }
        runCatching {
            api.registerDevice(
                RegisterDeviceTokenRequest(token = fcmToken, platform = "android"),
            )
            Log.i(TAG, "FCM token rotated + synced (prefix=${fcmToken.take(16)}…)")
        }.onFailure { e ->
            Log.w(TAG, "rotated FCM token sync failed (non-fatal): ${e.message}")
        }
    }

    /// Best-effort token scrub for logout. Failure here is non-fatal — at
    /// worst the prior owner keeps receiving pushes until FCM rotates the
    /// token, which is the existing pre-§9.8 behaviour.
    suspend fun unregister() {
        runCatching {
            val fcmToken = FirebaseMessaging.getInstance().token.await()
            api.unregisterDevice(
                RegisterDeviceTokenRequest(token = fcmToken, platform = "android"),
            )
        }.onFailure { e ->
            Log.w(TAG, "FCM unregister failed (non-fatal): ${e.message}")
        }
    }

    companion object {
        private const val TAG = "UltiqFcmSync"
    }
}
