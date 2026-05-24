package com.ultiq.app.ui.settings

import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhonelinkLock
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SettingsBrightness
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ultiq.app.ui.common.SectionHeader
import com.ultiq.app.ui.lockout.LockoutAdmin
import com.ultiq.app.ui.theme.ThemeMode
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToReminders: () -> Unit,
    onNavigateToReports: () -> Unit,
    onNavigateToChangePassword: () -> Unit,
    onNavigateToTerms: () -> Unit,
    onNavigateToAchievements: () -> Unit,
    onLogout: () -> Unit,
    onResetAccount: () -> Unit,
    onDeleteAccount: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val supportEmail = "support@ultiqapp.com"
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showReleaseNotes by remember { mutableStateOf(false) }

    if (showReleaseNotes) {
        ReleaseNotesDialog(onDismiss = { showReleaseNotes = false })
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshOverlayPermission()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        val user = uiState.user
        if (user == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) { Text("Loading...") }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SectionHeader("Appearance") }
            item { ThemeCard(current = uiState.themeMode, onSelect = viewModel::setThemeMode) }

            // Sleep goal, schedule, focus defaults, and per-mode lockout
            // toggles + grace now live on the Sleep and Focus tabs themselves
            // (closer to where they're used). Settings keeps only system-level
            // permissions and cross-cutting account / appearance / notifications.

            item { SectionHeader("Lock & overlay") }
            item {
                OverlayPermissionCard(
                    granted = uiState.canDrawOverlays,
                    onGrant = {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}"),
                        ).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                        context.startActivity(intent)
                    },
                )
            }
            item {
                StrictLockCard(
                    enabled = uiState.isStrictLockEnabled,
                    onEnable = {
                        val intent = LockoutAdmin.buildEnableIntent(
                            context,
                            "When you tap 'Stay locked' during a focus or sleep session, this " +
                                "lets the app lock the screen. Unlocking re-shows the lockout " +
                                "overlay, so you stay in a hard focus loop until the session ends.",
                        )
                        context.startActivity(intent)
                    },
                    onDisable = { viewModel.disableStrictLock() },
                )
            }

            item { SectionHeader("Notifications") }
            item {
                LinkRow(
                    icon = Icons.Default.Notifications,
                    title = "Reminders",
                    description = "Bedtime, focus, morning summary",
                    onClick = onNavigateToReminders,
                )
            }

            // §i18n (v2.13.9) — Show the detected device timezone so the
            // user can see what the server is bucketing their stats under.
            // Read-only for v2.13.9 — to override, change phone timezone in
            // system settings and log out + back in. A future iteration
            // could add an in-app override + an explicit "Resync to
            // server" button, but most users won't need either.
            item { SectionHeader("Region") }
            item { TimezoneInfoCard() }

            item { SectionHeader("Insights") }
            item {
                LinkRow(
                    icon = Icons.Default.Insights,
                    title = "Weekly report",
                    description = "Sleep, focus, and achievements",
                    onClick = onNavigateToReports,
                )
            }
            item {
                LinkRow(
                    icon = androidx.compose.material.icons.Icons.Default.EmojiEvents,
                    title = "Achievements",
                    description = "What you've unlocked",
                    onClick = onNavigateToAchievements,
                )
            }
            item {
                LinkRow(
                    icon = Icons.Default.Public,
                    title = "Web dashboard",
                    description = "Open app.ultiqapp.com — full analytics, correlations, reports",
                    onClick = {
                        val intent = android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://app.ultiqapp.com"),
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    },
                )
            }

            item { SectionHeader("Account") }
            item {
                var showResetDialog by remember { mutableStateOf(false) }
                var showDeleteDialog by remember { mutableStateOf(false) }
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Default.Email, null, tint = MaterialTheme.colorScheme.primary)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    uiState.email ?: "Signed in",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    "Signed in",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        OutlinedButton(onClick = onNavigateToChangePassword) {
                            Icon(Icons.Default.Lock, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Change password")
                        }
                        OutlinedButton(onClick = onLogout) {
                            Icon(Icons.AutoMirrored.Filled.Logout, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Log out")
                        }
                        OutlinedButton(onClick = { showResetDialog = true }) {
                            Text("Reset all data")
                        }
                        OutlinedButton(
                            onClick = { showDeleteDialog = true },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Text("Delete account")
                        }
                    }
                }

                if (showResetDialog) {
                    AlertDialog(
                        onDismissRequest = { showResetDialog = false },
                        title = { Text("Reset all data?") },
                        text = {
                            Text(
                                "Permanently deletes every sleep record, focus session, " +
                                    "checklist item, and calendar event tied to your account. " +
                                    "Your login stays — you can start fresh.",
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    showResetDialog = false
                                    onResetAccount()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                ),
                            ) { Text("Reset") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showResetDialog = false }) { Text("Cancel") }
                        },
                    )
                }

                if (showDeleteDialog) {
                    AlertDialog(
                        onDismissRequest = { showDeleteDialog = false },
                        title = { Text("Delete account?") },
                        text = {
                            Text(
                                "Permanently deletes your account and every record on it. " +
                                    "You'll be logged out and the app will reset to onboarding.",
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    showDeleteDialog = false
                                    onDeleteAccount()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                ),
                            ) { Text("Delete") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
                        },
                    )
                }
            }

            item { SectionHeader("About") }
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.clickable { showReleaseNotes = true },
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Ultiq",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                "Your daily productivity companion",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "Version ${uiState.versionName} (${uiState.versionCode}) · tap for what's new",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            Icons.Default.ChevronRight,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                LinkRow(
                    icon = Icons.Default.Info,
                    title = "Terms & conditions",
                    description = "What we collect, how we use it",
                    onClick = onNavigateToTerms,
                )
            }
            item {
                LinkRow(
                    icon = Icons.Default.Email,
                    title = "Contact support",
                    description = supportEmail,
                    onClick = {
                        val intent = Intent(Intent.ACTION_SENDTO).apply {
                            data = Uri.parse("mailto:$supportEmail")
                            putExtra(Intent.EXTRA_SUBJECT, "Ultiq support")
                        }
                        runCatching { context.startActivity(intent) }
                    },
                )
            }

            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeCard(current: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Theme", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            val options = listOf(
                ThemeMode.LIGHT to ("Light" to Icons.Default.LightMode),
                ThemeMode.SYSTEM to ("System" to Icons.Default.SettingsBrightness),
                ThemeMode.DARK to ("Dark" to Icons.Default.DarkMode),
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, (mode, labelAndIcon) ->
                    SegmentedButton(
                        selected = current == mode,
                        onClick = { onSelect(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                        icon = { Icon(labelAndIcon.second, null, modifier = Modifier.size(18.dp)) },
                        label = { Text(labelAndIcon.first) },
                    )
                }
            }
        }
    }
}

@Composable
private fun OverlayPermissionCard(
    granted: Boolean,
    onGrant: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (granted) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    Icons.Default.PhonelinkLock,
                    null,
                    tint = if (granted) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    },
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Display over other apps",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        if (granted) {
                            "Granted — lockout will take over the screen"
                        } else {
                            "Required so the lockout can take over the screen on unlock"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (granted) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        },
                    )
                }
            }
            if (!granted) {
                OutlinedButton(
                    onClick = onGrant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Grant permission")
                }
                Text(
                    "If the toggle is grayed out (\"Restricted setting\"), tap the ⋮ menu " +
                        "in App info → Allow restricted settings → come back and turn it on.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f),
                )
            }
        }
    }
}

@Composable
private fun StrictLockCard(
    enabled: Boolean,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.primary)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Strict lock (Device Admin)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        if (enabled) {
                            "Enabled — 'Stay locked' will lock the screen during sessions"
                        } else {
                            "Off — 'Stay locked' just dismisses the overlay. Enable to lock the screen instead."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            OutlinedButton(
                onClick = if (enabled) onDisable else onEnable,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (enabled) "Disable strict lock" else "Enable strict lock")
            }
        }
    }
}

@Composable
private fun LinkRow(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onClick) {
                Icon(Icons.Default.ChevronRight, "Open")
            }
        }
        HorizontalDivider(thickness = 0.dp)
    }
}

/// §i18n (v2.13.9) — Read-only display of the device's detected IANA
/// timezone. The string here is what AuthViewModel pushed to the server
/// on the last login (ZoneId.systemDefault().id). To change it, the user
/// changes their phone's system timezone and logs out + back in. A
/// future iteration could add an in-app picker + an explicit "Resync"
/// button if anyone reports issues; for now the device's setting is the
/// single source of truth.
@Composable
private fun TimezoneInfoCard() {
    val tz = remember { java.time.ZoneId.systemDefault().id }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Default.Schedule, null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    tz,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "Detected from your device. Your daily stats and the morning anomaly check use this timezone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ReleaseNotesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What's new") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(ReleaseNotes.history.size) { index ->
                    val note = ReleaseNotes.history[index]
                    Column {
                        Text(
                            "v${note.versionName}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            note.summary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
