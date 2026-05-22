package com.ultiq.app.fcm

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.ultiq.app.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/// §9.8 — FCM service entry point. Firebase invokes us in two cases:
///
/// - [onNewToken] when the FCM registration token is issued or rotated. We
///   forward it to the backend so the daily anomaly job can target this
///   device. No-op if the user isn't logged in — the next login picks up
///   the same token via [FcmTokenSyncer.syncIfLoggedIn].
///
/// - [onMessageReceived] when a push arrives. For "notification" payloads,
///   the system tray shows the notification automatically *only* when the
///   app is backgrounded — when foregrounded, we get the callback and must
///   surface the notification ourselves. For "data" payloads we're always
///   called regardless of app state.
class UltiqMessagingService : FirebaseMessagingService() {

    /// Service-scoped coroutine scope. Firebase's callbacks are sync, so we
    /// need our own scope to fire the suspending backend call.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i(TAG, "FCM token issued (prefix=${token.take(16)}…)")
        scope.launch {
            FcmTokenSyncer(applicationContext).sync(token)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        Log.i(
            TAG,
            "FCM message: from=${message.from} " +
                "notif=${message.notification?.title}/${message.notification?.body} " +
                "data=${message.data}",
        )
        // FCM auto-renders `notification` payloads only when the app is
        // backgrounded. When foregrounded — exactly the state a user is in
        // when they're working — `onMessageReceived` fires instead and
        // nothing appears unless we surface it ourselves. Build a local
        // notification from the payload so the tray entry is consistent
        // across foreground / background.
        val title = message.notification?.title
            ?: message.data["title"]
            ?: return
        val body = message.notification?.body
            ?: message.data["body"]
            ?: return
        NotificationHelper.showAnomalyAlert(applicationContext, title, body)
    }

    companion object {
        private const val TAG = "UltiqFcm"
    }
}
