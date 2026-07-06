package com.ultiq.app.ui.onboarding

import android.Manifest
import android.app.AlarmManager
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhonelinkLock
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ultiq.app.R
import com.ultiq.app.ui.theme.MascotSleepingBook
import com.ultiq.app.util.LocaleManager
import com.ultiq.app.util.PhoneUsageTracker
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private const val STEP_COUNT = 5

@Composable
fun OnboardingScreen(
    onFinish: () -> Unit,
    viewModel: OnboardingViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        bottomBar = {
            BottomBar(
                step = uiState.step,
                onBack = { viewModel.back() },
                onNext = {
                    if (uiState.step == STEP_COUNT - 1) {
                        viewModel.finish()
                        onFinish()
                    } else {
                        viewModel.next()
                    }
                },
            )
        }
    ) { padding ->
        Column(modifier = Modifier
            .fillMaxSize()
            .padding(padding)) {
            ProgressDots(current = uiState.step, total = STEP_COUNT)

            AnimatedContent(
                targetState = uiState.step,
                transitionSpec = {
                    val dir = if (targetState > initialState)
                        AnimatedContentTransitionScope.SlideDirection.Left
                    else
                        AnimatedContentTransitionScope.SlideDirection.Right
                    slideIntoContainer(dir, tween(300)) togetherWith
                        slideOutOfContainer(dir, tween(300))
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                label = "onboarding-step",
            ) { step ->
                when (step) {
                    0 -> WelcomeStep()
                    1 -> SleepTargetsStep(
                        bedtime = uiState.targetBedtime,
                        wakeTime = uiState.targetWakeTime,
                        onBedtimeChange = viewModel::setTargetBedtime,
                        onWakeChange = viewModel::setTargetWakeTime,
                    )
                    2 -> FocusPrefsStep(
                        work = uiState.workDuration,
                        onWorkChange = viewModel::setWorkDuration,
                    )
                    3 -> PermissionsStep()
                    else -> AllSetStep()
                }
            }
        }
    }
}

// ── Steps ───────────────────────────────────────────────────────────────

@Composable
private fun WelcomeStep() {
    StepContainer {
        MascotSleepingBook(size = 128.dp)
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.onboarding_welcome_title), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.onboarding_welcome_body),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SleepTargetsStep(
    bedtime: LocalTime,
    wakeTime: LocalTime,
    onBedtimeChange: (LocalTime) -> Unit,
    onWakeChange: (LocalTime) -> Unit,
) {
    StepContainer {
        BigIcon(Icons.Default.Bedtime)
        Text(stringResource(R.string.onboarding_sleep_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.onboarding_sleep_body),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        TimeRow(label = stringResource(R.string.settings_target_bedtime), time = bedtime, onChange = onBedtimeChange)
        Spacer(Modifier.height(12.dp))
        TimeRow(label = stringResource(R.string.settings_target_wake), time = wakeTime, onChange = onWakeChange)
    }
}

@Composable
private fun FocusPrefsStep(
    work: Int,
    onWorkChange: (Int) -> Unit,
) {
    StepContainer {
        BigIcon(Icons.Default.Timer)
        Text(stringResource(R.string.onboarding_focus_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.onboarding_focus_body),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        StepperRow(label = stringResource(R.string.sessions_work), value = work, suffix = stringResource(R.string.unit_min), step = 5, range = 5..240, onChange = onWorkChange)
    }
}

@Composable
private fun PermissionsStep() {
    val context = LocalContext.current
    var refreshKey by remember { mutableIntStateOf(0) }

    // Refresh permission states each time the activity resumes (user returning from Settings).
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshKey++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
    }

    val notificationsGranted = remember(refreshKey) {
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    val overlayGranted = remember(refreshKey) { Settings.canDrawOverlays(context) }
    val usageGranted = remember(refreshKey) { PhoneUsageTracker(context).hasPermission() }
    val exactAlarmGranted = remember(refreshKey) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
        } else true
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refreshKey++ }

    StepContainer {
        BigIcon(Icons.Default.Insights)
        Text(
            stringResource(R.string.onboarding_perms_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.onboarding_perms_body),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PermissionRow(
                icon = Icons.Default.Notifications,
                label = stringResource(R.string.reminders_title),
                description = stringResource(R.string.onboarding_perm_reminders_desc),
                granted = notificationsGranted,
                onGrant = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        openAppNotificationSettings(context)
                    }
                },
            )
            PermissionRow(
                icon = Icons.Default.Insights,
                label = stringResource(R.string.onboarding_perm_usage_title),
                description = stringResource(R.string.onboarding_perm_usage_desc),
                granted = usageGranted,
                onGrant = { PhoneUsageTracker(context).openPermissionSettings() },
            )
            PermissionRow(
                icon = Icons.Default.PhonelinkLock,
                label = stringResource(R.string.settings_overlay_title),
                description = stringResource(R.string.onboarding_perm_overlay_desc),
                granted = overlayGranted,
                onGrant = { openOverlaySettings(context) },
                hint = stringResource(R.string.onboarding_perm_overlay_hint),
            )
            PermissionRow(
                icon = Icons.Default.Alarm,
                label = stringResource(R.string.onboarding_perm_alarms_title),
                description = stringResource(R.string.onboarding_perm_alarms_desc),
                granted = exactAlarmGranted,
                onGrant = { openExactAlarmSettings(context) },
            )
        }
    }
}

@Composable
private fun PermissionRow(
    icon: ImageVector,
    label: String,
    description: String,
    granted: Boolean,
    onGrant: () -> Unit,
    hint: String? = null,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (granted) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !granted, onClick = onGrant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            if (granted) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.secondaryContainer,
                            CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        icon,
                        null,
                        modifier = Modifier.size(20.dp),
                        tint = if (granted) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    if (granted) Icons.Default.Check else Icons.Default.ChevronRight,
                    contentDescription = if (granted) stringResource(R.string.onboarding_granted) else stringResource(R.string.onboarding_tap_grant),
                    tint = if (granted) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!granted && hint != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                )
            }
        }
    }
}

private fun openAppNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

private fun openOverlaySettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

private fun openExactAlarmSettings(context: Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            .setData(Uri.parse("package:${context.packageName}"))
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))
    }
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

@Composable
private fun AllSetStep() {
    StepContainer {
        MascotSleepingBook(size = 128.dp)
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.onboarding_done_title), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.onboarding_done_body),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── Helpers ─────────────────────────────────────────────────────────────

@Composable
private fun StepContainer(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        content = content,
    )
}

@Composable
private fun BigIcon(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(96.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun TimeRow(label: String, time: LocalTime, onChange: (LocalTime) -> Unit) {
    val context = LocalContext.current
    val fmt = DateTimeFormatter.ofPattern("h:mm a", LocaleManager.currentLocale())
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = {
            TimePickerDialog(
                context,
                { _, h, m -> onChange(LocalTime.of(h, m)) },
                time.hour, time.minute, false,
            ).show()
        }) {
            Text(time.format(fmt), style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun StepperRow(
    label: String,
    value: Int,
    suffix: String,
    step: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { onChange((value - step).coerceAtLeast(range.first)) },
                enabled = value > range.first,
            ) { Icon(Icons.Default.Remove, stringResource(R.string.action_decrease)) }
            Text(
                "$value $suffix",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.width(96.dp),
                textAlign = TextAlign.Center,
            )
            IconButton(
                onClick = { onChange((value + step).coerceAtMost(range.last)) },
                enabled = value < range.last,
            ) { Icon(Icons.Default.Add, stringResource(R.string.action_increase)) }
        }
    }
}

@Composable
private fun ProgressDots(current: Int, total: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        repeat(total) { i ->
            val color = if (i == current) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(8.dp)
                    .background(color, CircleShape)
            )
        }
    }
}

@Composable
private fun BottomBar(step: Int, onBack: () -> Unit, onNext: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(PaddingValues(horizontal = 24.dp, vertical = 16.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        if (step > 0) {
            TextButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.action_back))
            }
        } else {
            Spacer(Modifier.width(80.dp))
        }
        Button(onClick = onNext) {
            Text(if (step == STEP_COUNT - 1) stringResource(R.string.onboarding_lets_go) else stringResource(R.string.action_continue))
        }
    }
}
