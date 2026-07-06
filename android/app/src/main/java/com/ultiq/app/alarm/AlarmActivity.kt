package com.ultiq.app.alarm

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ultiq.app.R
import com.ultiq.app.util.LocaleManager
import com.ultiq.app.alarm.mission.MathMissionScreen
import com.ultiq.app.alarm.mission.MissionConfig
import com.ultiq.app.alarm.mission.PhotoMissionScreen
import com.ultiq.app.alarm.mission.ShakeMissionScreen
import com.ultiq.app.data.local.AppDatabase
import com.ultiq.app.data.local.entity.AlarmEntity
import com.ultiq.app.ui.theme.ThemeMode
import com.ultiq.app.ui.theme.UltiqTheme
import com.ultiq.app.util.ThemePreference
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Full-screen alarm UI. Launches over the lock screen via
 * `setShowWhenLocked` + `setTurnScreenOn`, keeps the screen awake while
 * shown. v0 has no missions — just a big Dismiss button.
 */
class AlarmActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Block screenshots + screen recording. Math missions, photo dismiss
        // captures, and the dismiss button itself shouldn't end up in a
        // screen-recording session that runs through the alarm fire.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        val themePref = ThemePreference(applicationContext)

        setContent {
            val themeMode by themePref.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            val ringingId by AlarmRingService.currentAlarmId.collectAsState()

            // If the service stops while the activity is up, close.
            LaunchedEffect(ringingId) {
                if (ringingId == null) finishAndRemoveTask()
            }

            val alarm by produceState<AlarmEntity?>(initialValue = null, key1 = ringingId) {
                val id = ringingId ?: return@produceState
                value = AppDatabase.getInstance(applicationContext).alarmDao().getAlarmById(id)
            }

            UltiqTheme(themeMode = themeMode) {
                AlarmRoute(
                    alarm = alarm,
                    onDismiss = { dismiss() },
                    onForceDismiss = { forceDismiss() },
                )
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // No back-out — alarm must be dismissed via the button.
    }

    private fun dismiss() {
        val intent = Intent(this, AlarmRingService::class.java).apply {
            action = AlarmRingService.ACTION_DISMISS
        }
        startService(intent)
        finishAndRemoveTask()
    }

    /** §8.10 escape hatch — same effect as dismiss, but logs dismiss_method='force'. */
    private fun forceDismiss() {
        val intent = Intent(this, AlarmRingService::class.java).apply {
            action = AlarmRingService.ACTION_FORCE_DISMISS
        }
        startService(intent)
        finishAndRemoveTask()
    }
}

@Composable
private fun AlarmRoute(
    alarm: AlarmEntity?,
    onDismiss: () -> Unit,
    onForceDismiss: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (alarm == null) {
            NoneMissionScreen(alarm = null, onDismiss = onDismiss)
        } else when (alarm.missionKind) {
            "math" -> {
                val cfg = remember(alarm.id, alarm.missionConfigJson) {
                    MissionConfig.parseMath(alarm.missionConfigJson)
                }
                MathMissionScreen(
                    difficulty = cfg.difficulty,
                    targetCount = cfg.count,
                    onComplete = onDismiss,
                )
            }
            "shake" -> {
                val cfg = remember(alarm.id, alarm.missionConfigJson) {
                    MissionConfig.parseShake(alarm.missionConfigJson)
                }
                ShakeMissionScreen(
                    intensity = cfg.intensity,
                    targetShakes = cfg.shakesRequired,
                    onComplete = onDismiss,
                )
            }
            "photo" -> {
                val cfg = remember(alarm.id, alarm.missionConfigJson) {
                    MissionConfig.parsePhoto(alarm.missionConfigJson)
                }
                PhotoMissionScreen(
                    config = cfg,
                    onComplete = onDismiss,
                )
            }
            else -> NoneMissionScreen(alarm = alarm, onDismiss = onDismiss)
        }

        // §8.10 force-dismiss escape hatch. After 3 minutes of failed mission
        // attempts, surface a low-key "Force dismiss" button so the user can
        // never be locked out of turning the alarm off.
        ForceDismissOverlay(onForceDismiss = onForceDismiss)
    }
}

@Composable
private fun ForceDismissOverlay(onForceDismiss: () -> Unit) {
    // §L7: previously polled every second just to check elapsed time against
    // a 3-min threshold. A single suspended delay is equivalent and costs no
    // CPU while waiting.
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(FORCE_DISMISS_AFTER_MS)
        show = true
    }
    if (!show) return

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        TextButton(
            onClick = onForceDismiss,
            modifier = Modifier.padding(bottom = 12.dp),
        ) {
            Text(
                stringResource(R.string.alarm_force_dismiss),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

private const val FORCE_DISMISS_AFTER_MS: Long = 3 * 60 * 1000

@Composable
private fun NoneMissionScreen(
    alarm: AlarmEntity?,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(PaddingValues(horizontal = 32.dp, vertical = 48.dp)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.alarm_wake_up),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                LiveClock()

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val label = alarm?.label?.takeIf { it.isNotBlank() } ?: stringResource(R.string.alarm_default_label)
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    alarm?.let {
                        Text(
                            text = stringResource(
                                R.string.alarm_set_for,
                                LocalTime.of(it.triggerHour, it.triggerMinute)
                                    .format(DateTimeFormatter.ofPattern("HH:mm", LocaleManager.currentLocale())),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Text(stringResource(R.string.alarm_dismiss), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun LiveClock() {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    val text = remember(now) {
        val t = java.time.Instant.ofEpochMilli(now)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalTime()
        t.format(DateTimeFormatter.ofPattern("HH:mm"))
    }
    Text(
        text = text,
        fontSize = 96.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
    )
}
