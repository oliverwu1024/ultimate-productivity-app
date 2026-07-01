package com.ultiq.app.ui.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.ultiq.app.MainActivity
import com.ultiq.app.util.NotificationHelper

/**
 * A MainActivity intent that deep-links to a tab (reuses the notification deep-link
 * plumbing). Used by all four widget providers for tap-to-open.
 */
fun widgetOpenIntent(context: Context, deepLink: String): Intent =
    Intent(context.applicationContext, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(NotificationHelper.EXTRA_DEEP_LINK, deepLink)
    }

/**
 * A distinct open-app PendingIntent per deep-link. The request code is keyed on the
 * deepLink because PendingIntent equality IGNORES extras — without this the four
 * widgets' requestCode-0 activity PendingIntents collide into one, and they all open
 * whichever tab was wired last (the "random tab" bug).
 */
fun widgetOpenPendingIntent(context: Context, deepLink: String): PendingIntent =
    PendingIntent.getActivity(
        context,
        deepLink.hashCode(),
        widgetOpenIntent(context, deepLink),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
