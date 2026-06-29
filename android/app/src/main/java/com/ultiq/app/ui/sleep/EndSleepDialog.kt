package com.ultiq.app.ui.sleep

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ultiq.app.data.local.entity.SleepAudioEventEntity
import com.ultiq.app.service.PickupEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EndSleepDialog(
    durationMinutes: Long,
    pickupEvents: List<PickupEvent>,
    audioEvents: List<SleepAudioEventEntity>,
    aiRatingLoading: Boolean,
    aiRatingResult: AiSleepRating?,
    aiRatingError: String?,
    onRequestAiRating: (isNap: Boolean) -> Unit,
    onSave: (qualityRating: Int, notes: String?, isNap: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    // Reject the Hidden state via gesture so swipe-down can't lose the session.
    // Combined with `onDismissRequest = {}` below, the only exits are Save / Cancel.
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden },
    )
    var qualityRating by remember { mutableIntStateOf(0) }
    var notes by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var showDiscardConfirm by remember { mutableStateOf(false) }

    val hours = durationMinutes / 60
    val mins = durationMinutes % 60
    val totalPhoneSeconds = pickupEvents.sumOf { it.durationSeconds }
    val totalPhoneMinutes = totalPhoneSeconds / 60
    val timeFormat = DateTimeFormatter.ofPattern("hh:mm a")
    val zone = ZoneId.systemDefault()

    // §last-night — pre-select Nap when the just-ended session looks like a
    // daytime nap (short + started during the day). wake ≈ now at session end,
    // so reconstruct the start from the duration. Only surfaced when nap-likely.
    val napLikely = remember(durationMinutes) {
        val wakeMs = System.currentTimeMillis()
        looksLikeNap(wakeMs - durationMinutes * 60_000L, wakeMs)
    }
    var isNap by remember { mutableStateOf(napLikely) }

    ModalBottomSheet(
        // Tapping the scrim or pressing back is ignored — the user must explicitly
        // Save or Cancel. Prevents losing a session by fumbling on a dizzy wake-up.
        onDismissRequest = {},
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Good morning!", style = MaterialTheme.typography.headlineSmall)

            // Summary
            Text(
                "You slept ${hours}h ${mins}m",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            // §last-night — always offer the Night/Nap choice; pre-checked when
            // the session looks like a daytime nap (short + daytime start), but
            // the user can mark any session (e.g. a long afternoon nap) too.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Daytime nap?", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Keeps it out of your \"last night\" summary",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = isNap, onCheckedChange = { isNap = it })
            }

            if (pickupEvents.isEmpty()) {
                Text(
                    "No phone pickups — nice!",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF4CAF50)
                )
            } else {
                Text(
                    "${pickupEvents.size} pickup${if (pickupEvents.size != 1) "s" else ""} — ${totalPhoneMinutes}m ${totalPhoneSeconds % 60}s total",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // Individual pickup details
                pickupEvents.forEachIndexed { index, event ->
                    val time = Instant.ofEpochMilli(event.pickedUpAt)
                        .atZone(zone).format(timeFormat)
                    val durMins = event.durationSeconds / 60
                    val durSecs = event.durationSeconds % 60
                    val durText = if (durMins > 0) "${durMins}m ${durSecs}s" else "${durSecs}s"

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.PhoneAndroid,
                                null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "  #${index + 1} at $time",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Text(
                            durText,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            }

            // §10 — Snore + cough + sleep-talk breakdown. Hidden when no
            // events were captured (audio tracking off, or a quiet night).
            // §10.x-fix (v2.14.3) — Sleep-talk was missing from this
            // summary in v2.14.0-v2.14.2 even though it appeared in the
            // past-records expansion; users with sleep-talk detection on
            // saw events vanish from the End Sleep dialog and reappear
            // later in the Sleep tab. Now mirrored alongside snore + cough.
            val snoreEvents = audioEvents.filter { it.eventType == "snore" }
            val coughEvents = audioEvents.filter { it.eventType == "cough" }
            val sleepTalkEvents = audioEvents.filter { it.eventType == "sleep_talk" }
            if (snoreEvents.isNotEmpty() || coughEvents.isNotEmpty() || sleepTalkEvents.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.GraphicEq,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("Sounds during sleep", style = MaterialTheme.typography.labelLarge)
                }
                if (snoreEvents.isNotEmpty()) {
                    val totalSnoreSecs =
                        snoreEvents.sumOf { (it.endedAt - it.startedAt) / 1000L }
                    Text(
                        "${snoreEvents.size} snoring episode${if (snoreEvents.size != 1) "s" else ""} — ${totalSnoreSecs}s total",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    snoreEvents.forEachIndexed { index, event ->
                        AudioEventRow(
                            label = "Snore #${index + 1}",
                            startedAt = event.startedAt,
                            durationSec = ((event.endedAt - event.startedAt) / 1000L).coerceAtLeast(1L),
                            zone = zone,
                            timeFormat = timeFormat,
                        )
                    }
                }
                if (coughEvents.isNotEmpty()) {
                    val totalCoughSecs =
                        coughEvents.sumOf { (it.endedAt - it.startedAt) / 1000L }
                    Text(
                        "${coughEvents.size} coughing episode${if (coughEvents.size != 1) "s" else ""} — ${totalCoughSecs}s total",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    coughEvents.forEachIndexed { index, event ->
                        AudioEventRow(
                            label = "Cough #${index + 1}",
                            startedAt = event.startedAt,
                            durationSec = ((event.endedAt - event.startedAt) / 1000L).coerceAtLeast(1L),
                            zone = zone,
                            timeFormat = timeFormat,
                        )
                    }
                }
                if (sleepTalkEvents.isNotEmpty()) {
                    val totalTalkSecs =
                        sleepTalkEvents.sumOf { (it.endedAt - it.startedAt) / 1000L }
                    Text(
                        "${sleepTalkEvents.size} sleep-talk episode${if (sleepTalkEvents.size != 1) "s" else ""} — ${totalTalkSecs}s total",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    sleepTalkEvents.forEachIndexed { index, event ->
                        AudioEventRow(
                            label = "Sleep-talk #${index + 1}",
                            startedAt = event.startedAt,
                            durationSec = ((event.endedAt - event.startedAt) / 1000L).coerceAtLeast(1L),
                            zone = zone,
                            timeFormat = timeFormat,
                        )
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            }

            // §10 — AI rating section. User can either tap "Get AI rating"
            // for a Haiku-suggested 1-5 + one-line reason, or skip straight
            // to the self-rate stars below.
            AiRatingSection(
                loading = aiRatingLoading,
                result = aiRatingResult,
                error = aiRatingError,
                onRequest = { onRequestAiRating(isNap) },
                onAccept = { qualityRating = it },
            )

            // Quality rating
            Text("How did you sleep?", style = MaterialTheme.typography.labelLarge)
            Row {
                (1..5).forEach { star ->
                    IconButton(onClick = { qualityRating = star }) {
                        Icon(
                            imageVector = if (star <= qualityRating) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            contentDescription = "Star $star",
                            tint = if (star <= qualityRating) com.ultiq.app.ui.theme.QualityStar else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
            }

            // Notes
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Notes (optional)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4
            )

            if (error != null) {
                Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
            ) {
                OutlinedButton(onClick = { showDiscardConfirm = true }) { Text("Cancel") }
                Button(onClick = {
                    if (qualityRating < 1) {
                        error = "Please rate your sleep quality"
                    } else {
                        onSave(qualityRating, notes.ifBlank { null }, isNap)
                    }
                }) { Text("Save") }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showDiscardConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text("Discard sleep session?") },
            text = {
                Text("You'll lose the duration and pickup data we tracked. This can't be undone.")
            },
            confirmButton = {
                Button(onClick = {
                    showDiscardConfirm = false
                    onDismiss()
                }) { Text("Discard") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDiscardConfirm = false }) { Text("Keep") }
            },
        )
    }
}

/// §10 — One row per snore/cough event in the End Sleep dialog. Mirrors the
/// pickup row layout for visual consistency.
@Composable
private fun AudioEventRow(
    label: String,
    startedAt: Long,
    durationSec: Long,
    zone: ZoneId,
    timeFormat: DateTimeFormatter,
) {
    val time = Instant.ofEpochMilli(startedAt).atZone(zone).format(timeFormat)
    val durText = if (durationSec >= 60) {
        "${durationSec / 60}m ${durationSec % 60}s"
    } else {
        "${durationSec}s"
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.GraphicEq,
                null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "  $label at $time",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            durText,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/// §10 — AI sleep rating section for the End Sleep dialog.
///
/// Three visual states driven by the parent ViewModel:
/// 1. Idle (no result, no loading): "Get AI rating" outlined button.
/// 2. Loading: indeterminate spinner + label.
/// 3. Result: rounded card showing the integer rating + one-line reasoning,
///    plus a "Use this rating" button that fills the self-rate stars.
///
/// Errors render as a short text line with a Retry button.
@Composable
private fun AiRatingSection(
    loading: Boolean,
    result: AiSleepRating?,
    error: String?,
    onRequest: () -> Unit,
    onAccept: (Int) -> Unit,
) {
    when {
        loading -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
                Text(
                    "Asking AI to rate your sleep…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        result != null -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            "AI suggests ${result.rating}/5",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        result.reasoning,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        Box(modifier = Modifier.weight(1f))
                        TextButton(onClick = onRequest) { Text("Re-rate") }
                        Button(onClick = { onAccept(result.rating) }) {
                            Text("Use this rating")
                        }
                    }
                }
            }
        }
        error != null -> {
            Column {
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(onClick = onRequest) {
                    Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Try AI rating again")
                }
            }
        }
        else -> {
            OutlinedButton(onClick = onRequest, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(8.dp))
                Text("Get AI rating")
            }
        }
    }
}
