package com.app.productivity.ui.sleep

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import com.app.productivity.service.PickupEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EndSleepDialog(
    durationMinutes: Long,
    pickupEvents: List<PickupEvent>,
    onSave: (qualityRating: Int, notes: String?) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var qualityRating by remember { mutableIntStateOf(0) }
    var notes by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val hours = durationMinutes / 60
    val mins = durationMinutes % 60
    val totalPhoneSeconds = pickupEvents.sumOf { it.durationSeconds }
    val totalPhoneMinutes = totalPhoneSeconds / 60
    val timeFormat = DateTimeFormatter.ofPattern("hh:mm a")
    val zone = ZoneId.systemDefault()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
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

            // Quality rating
            Text("How did you sleep?", style = MaterialTheme.typography.labelLarge)
            Row {
                (1..5).forEach { star ->
                    IconButton(onClick = { qualityRating = star }) {
                        Icon(
                            imageVector = if (star <= qualityRating) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            contentDescription = "Star $star",
                            tint = if (star <= qualityRating) Color(0xFFFFD700) else MaterialTheme.colorScheme.onSurfaceVariant,
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
                OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                Button(onClick = {
                    if (qualityRating < 1) {
                        error = "Please rate your sleep quality"
                    } else {
                        onSave(qualityRating, notes.ifBlank { null })
                    }
                }) { Text("Save") }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
