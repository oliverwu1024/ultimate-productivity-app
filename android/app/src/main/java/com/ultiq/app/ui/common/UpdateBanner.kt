package com.ultiq.app.ui.common

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ultiq.app.util.ApkUpdater
import com.ultiq.app.util.UpdateChecker
import com.ultiq.app.util.UpdateInfo

@Composable
fun UpdateBanner(info: UpdateInfo) {
    val context = LocalContext.current
    val updater by ApkUpdater.state.collectAsState()

    val (label, clickable) = when (updater.stage) {
        ApkUpdater.Stage.Downloading -> {
            val pct = if (updater.totalBytes > 0) {
                (updater.downloadedBytes * 100 / updater.totalBytes).toInt()
            } else null
            val msg = if (pct != null) "Downloading Ultiq ${info.versionName}… $pct%"
            else "Downloading Ultiq ${info.versionName}…"
            msg to false
        }
        ApkUpdater.Stage.AwaitingConfirm -> "System prompt is open — tap Install there" to false
        // §fix-restart-after-install — the new APK is on disk but the
        // running process is still the old code on most OEMs. Make the
        // banner clickable and have it kill+relaunch so the launcher
        // brings up the freshly-installed version.
        ApkUpdater.Stage.Installing -> "Update installed — tap to restart Ultiq" to true
        ApkUpdater.Stage.Failed -> "Update failed — tap to retry" to true
        ApkUpdater.Stage.Idle -> "Ultiq ${info.versionName} is available — tap to install" to true
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .then(
                if (clickable) Modifier.clickable {
                    when (updater.stage) {
                        ApkUpdater.Stage.Installing -> ApkUpdater.relaunchApp(context)
                        else -> {
                            // The platform requires per-app "Install unknown apps"
                            // grant (API 26+). If we don't have it, send the user
                            // straight to the right Settings page — bouncing through
                            // the OS install prompt without it just fails silently.
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                                !context.packageManager.canRequestPackageInstalls()
                            ) {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:${context.packageName}"),
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            } else {
                                ApkUpdater.startUpdate(context, info.downloadUrl)
                            }
                        }
                    }
                } else Modifier,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.SystemUpdate,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            IconButton(onClick = {
                ApkUpdater.reset()
                UpdateChecker.dismiss()
            }) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Dismiss",
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
        if (updater.stage == ApkUpdater.Stage.Downloading) {
            Spacer(Modifier.size(6.dp))
            if (updater.totalBytes > 0) {
                LinearProgressIndicator(
                    progress = {
                        (updater.downloadedBytes.toFloat() / updater.totalBytes.toFloat())
                            .coerceIn(0f, 1f)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
