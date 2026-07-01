package com.ultiq.app.ui.widget

import android.content.Context
import android.content.Intent
import com.ultiq.app.MainActivity
import com.ultiq.app.util.NotificationHelper

/**
 * A MainActivity intent that deep-links to a tab (reuses the notification deep-link
 * plumbing). Used by the read-only Glance widgets (Calendar, Sleep/Alarm) and the
 * RemoteViews Checklist/Focus providers for tap-to-open.
 */
fun widgetOpenIntent(context: Context, deepLink: String): Intent =
    Intent(context.applicationContext, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(NotificationHelper.EXTRA_DEEP_LINK, deepLink)
    }
