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
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.material.icons.filled.Wifi
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ultiq.app.R
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

    // Pre-resolved for use inside non-composable lambdas below (dodges
    // LocalContextGetResourceValueCall).
    val strictLockExplanation = stringResource(R.string.settings_strict_lock_admin_explanation)
    val supportSubject = stringResource(R.string.settings_support_email_subject)

    if (showReleaseNotes) {
        ReleaseNotesDialog(onDismiss = { showReleaseNotes = false })
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshOverlayPermission()
                // Picks up an email verification that happened outside the
                // app (typical: tap link in Gmail → Chrome → dashboard).
                viewModel.refreshUserStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
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
            ) { Text(stringResource(R.string.common_loading)) }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SectionHeader(stringResource(R.string.settings_section_appearance)) }
            item { ThemeCard(current = uiState.themeMode, onSelect = viewModel::setThemeMode) }
            item {
                LanguageCard(
                    currentTag = com.ultiq.app.util.LocaleManager.currentTag(),
                    onSelect = viewModel::setAppLanguage,
                )
            }

            // Sleep goal, schedule, focus defaults, and per-mode lockout
            // toggles + grace now live on the Sleep and Focus tabs themselves
            // (closer to where they're used). Settings keeps only system-level
            // permissions and cross-cutting account / appearance / notifications.

            item { SectionHeader(stringResource(R.string.settings_section_lock_overlay)) }
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
                            strictLockExplanation,
                        )
                        context.startActivity(intent)
                    },
                    onDisable = { viewModel.disableStrictLock() },
                )
            }

            item { SectionHeader(stringResource(R.string.settings_section_notifications)) }
            item {
                LinkRow(
                    icon = Icons.Default.Notifications,
                    title = stringResource(R.string.reminders_title),
                    description = stringResource(R.string.settings_reminders_desc),
                    onClick = onNavigateToReminders,
                )
            }

            // §i18n (v2.13.9) — Show the detected device timezone so the
            // user can see what the server is bucketing their stats under.
            // Read-only for v2.13.9 — to override, change phone timezone in
            // system settings and log out + back in. A future iteration
            // could add an in-app override + an explicit "Resync to
            // server" button, but most users won't need either.
            item { SectionHeader(stringResource(R.string.settings_section_region)) }
            item { TimezoneInfoCard() }

            item { SectionHeader(stringResource(R.string.settings_section_insights)) }
            item {
                LinkRow(
                    icon = Icons.Default.Insights,
                    title = stringResource(R.string.settings_weekly_report_title),
                    description = stringResource(R.string.settings_weekly_report_desc),
                    onClick = onNavigateToReports,
                )
            }
            item {
                LinkRow(
                    icon = androidx.compose.material.icons.Icons.Default.EmojiEvents,
                    title = stringResource(R.string.achievements_title),
                    description = stringResource(R.string.settings_achievements_desc),
                    onClick = onNavigateToAchievements,
                )
            }
            item {
                LinkRow(
                    icon = Icons.Default.Public,
                    title = stringResource(R.string.settings_web_dashboard_title),
                    description = stringResource(R.string.settings_web_dashboard_desc),
                    onClick = {
                        val intent = android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://app.ultiqapp.com"),
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    },
                )
            }

            item { SectionHeader(stringResource(R.string.settings_section_account)) }
            item {
                var showResetDialog by remember { mutableStateOf(false) }
                var showDeleteDialog by remember { mutableStateOf(false) }
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Default.Email, null, tint = MaterialTheme.colorScheme.primary)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    uiState.email ?: stringResource(R.string.settings_signed_in),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    if (uiState.emailVerified) stringResource(R.string.settings_email_verified)
                                    else stringResource(R.string.settings_email_not_verified),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (uiState.emailVerified) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                )
                            }
                        }
                        if (!uiState.emailVerified) {
                            OutlinedButton(
                                onClick = { viewModel.resendVerificationEmail() },
                                enabled = !uiState.resendingVerification,
                            ) {
                                Text(
                                    if (uiState.resendingVerification) stringResource(R.string.settings_sending)
                                    else stringResource(R.string.settings_resend_verification)
                                )
                            }
                            uiState.verificationFeedback?.let { msg ->
                                Text(
                                    msg,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        OutlinedButton(onClick = onNavigateToChangePassword) {
                            Icon(Icons.Default.Lock, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.change_password))
                        }
                        OutlinedButton(onClick = onLogout) {
                            Icon(Icons.AutoMirrored.Filled.Logout, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.settings_log_out))
                        }
                        OutlinedButton(onClick = { showResetDialog = true }) {
                            Text(stringResource(R.string.settings_reset_all_data))
                        }
                        OutlinedButton(
                            onClick = { showDeleteDialog = true },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Text(stringResource(R.string.settings_delete_account))
                        }
                    }
                }

                if (showResetDialog) {
                    AlertDialog(
                        onDismissRequest = { showResetDialog = false },
                        title = { Text(stringResource(R.string.settings_reset_dialog_title)) },
                        text = {
                            Text(stringResource(R.string.settings_reset_dialog_body))
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
                            ) { Text(stringResource(R.string.settings_reset_confirm)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showResetDialog = false }) { Text(stringResource(R.string.action_cancel)) }
                        },
                    )
                }

                if (showDeleteDialog) {
                    AlertDialog(
                        onDismissRequest = { showDeleteDialog = false },
                        title = { Text(stringResource(R.string.settings_delete_dialog_title)) },
                        text = {
                            Text(stringResource(R.string.settings_delete_dialog_body))
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
                            ) { Text(stringResource(R.string.action_delete)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.action_cancel)) }
                        },
                    )
                }
            }

            item { SectionHeader(stringResource(R.string.settings_section_about)) }
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
                                stringResource(R.string.app_name),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                stringResource(R.string.settings_app_tagline),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                stringResource(
                                    R.string.settings_version_line,
                                    uiState.versionName,
                                    uiState.versionCode,
                                ),
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
                    title = stringResource(R.string.terms_title),
                    description = stringResource(R.string.settings_terms_desc),
                    onClick = onNavigateToTerms,
                )
            }
            item {
                // §2026-06-06 — Permanent quiet advisory so users hitting
                // an offline-degraded surface (clip playback, AI insight,
                // sync) know it's by design, not a bug. Pairs with the
                // one-shot dashboard hint card.
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            Icons.Default.Wifi,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        Column {
                            Text(
                                stringResource(R.string.settings_offline_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                stringResource(R.string.settings_offline_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
            item {
                LinkRow(
                    icon = Icons.Default.Email,
                    title = stringResource(R.string.settings_contact_support_title),
                    description = supportEmail,
                    onClick = {
                        val intent = Intent(Intent.ACTION_SENDTO).apply {
                            data = Uri.parse("mailto:$supportEmail")
                            putExtra(Intent.EXTRA_SUBJECT, supportSubject)
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
            Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            val options = listOf(
                ThemeMode.LIGHT to (stringResource(R.string.theme_light) to Icons.Default.LightMode),
                ThemeMode.SYSTEM to (stringResource(R.string.theme_system) to Icons.Default.SettingsBrightness),
                ThemeMode.DARK to (stringResource(R.string.theme_dark) to Icons.Default.DarkMode),
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, (mode, labelAndIcon) ->
                    SegmentedButton(
                        selected = current == mode,
                        onClick = { onSelect(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                        icon = { Icon(labelAndIcon.second, null, modifier = Modifier.size(18.dp)) },
                        label = {
                            Text(
                                labelAndIcon.first,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        },
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
                        stringResource(R.string.settings_overlay_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        if (granted) {
                            stringResource(R.string.settings_overlay_granted)
                        } else {
                            stringResource(R.string.settings_overlay_required)
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
                    Text(stringResource(R.string.settings_grant_permission))
                }
                Text(
                    stringResource(R.string.settings_overlay_restricted_hint),
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
                        stringResource(R.string.settings_strict_lock_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        if (enabled) {
                            stringResource(R.string.settings_strict_lock_on)
                        } else {
                            stringResource(R.string.settings_strict_lock_off)
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
                Text(
                    if (enabled) stringResource(R.string.settings_strict_lock_disable)
                    else stringResource(R.string.settings_strict_lock_enable)
                )
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
                Icon(Icons.Default.ChevronRight, stringResource(R.string.action_open))
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
                    stringResource(R.string.settings_timezone_desc),
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
        title = { Text(stringResource(R.string.settings_whats_new)) },
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
                            stringResource(note.summaryRes),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
    )
}
