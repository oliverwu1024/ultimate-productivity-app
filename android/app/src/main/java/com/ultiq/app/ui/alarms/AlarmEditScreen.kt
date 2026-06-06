package com.ultiq.app.ui.alarms

import android.app.Activity
import android.app.TimePickerDialog
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ultiq.app.alarm.mission.MathDifficulty
import com.ultiq.app.alarm.mission.MissionConfig
import com.ultiq.app.alarm.mission.PhotoReferenceSetup
import com.ultiq.app.alarm.mission.ShakeIntensity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmEditScreen(
    alarmId: String?,
    onBack: () -> Unit,
    viewModel: AlarmsViewModel = viewModel(),
) {
    LaunchedEffect(alarmId) { viewModel.loadForEdit(alarmId) }
    val draft by viewModel.editing.collectAsState()
    val context = LocalContext.current
    var showPhotoSetup by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (alarmId == null) "New alarm" else "Edit alarm") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
        bottomBar = {
            // §UX: replaced the lone checkmark — users were swiping back and
            // assuming "save" had happened. Two explicit actions: Set Alarm
            // arms the time; Cancel keeps the time settings but turns the
            // alarm off (user toggles it back on from the list when ready).
            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            // New-draft Cancel: discard, don't create a disabled
                            // alarm. Existing-alarm Cancel: keep the row, save
                            // with enabled=false (user wanted "keep the time
                            // but don't ring tonight").
                            if (alarmId == null) onBack()
                            else viewModel.saveWithEnabled(enabled = false, onDone = onBack)
                        },
                        enabled = draft != null,
                        modifier = Modifier.weight(1f),
                    ) { Text("Cancel") }
                    Button(
                        onClick = { viewModel.saveWithEnabled(enabled = true, onDone = onBack) },
                        enabled = draft != null,
                        modifier = Modifier.weight(1f),
                    ) { Text("Set alarm") }
                }
            }
        },
    ) { inner ->
        val current = draft
        if (current == null) {
            Spacer(modifier = Modifier.padding(inner).fillMaxSize())
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.padding(inner).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Time card — single clickable surface, AM/PM display matches the
            // rest of the app's time pickers (Sleep / Settings).
            item {
                val displayTime = remember(current.triggerHour, current.triggerMinute) {
                    java.time.LocalTime.of(current.triggerHour, current.triggerMinute)
                        .format(java.time.format.DateTimeFormatter.ofPattern("h:mm a"))
                }
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    modifier = Modifier.clickable {
                        TimePickerDialog(
                            context,
                            { _, h, m ->
                                viewModel.updateEditingDraft {
                                    it.copy(triggerHour = h, triggerMinute = m)
                                }
                            },
                            current.triggerHour,
                            current.triggerMinute,
                            false, // §UX-1: AM/PM, consistent with Sleep settings
                        ).show()
                    },
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Time",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = displayTime,
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Tap to change",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Label
            item {
                OutlinedTextField(
                    value = current.label.orEmpty(),
                    onValueChange = { v ->
                        viewModel.updateEditingDraft { it.copy(label = v.ifBlank { null }) }
                    },
                    label = { Text("Label (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }

            // Repeat days
            item { SectionLabel("Repeat") }
            item { RepeatPicker(current.daysOfWeekMask) { mask ->
                viewModel.updateEditingDraft { it.copy(daysOfWeekMask = mask) }
            } }

            // Mission
            item { SectionLabel("Dismiss mission") }
            item {
                MissionPicker(current.missionKind) { kind ->
                    viewModel.updateEditingDraft { d ->
                        // Prime a sensible default config when switching kinds.
                        val configJson = when (kind) {
                            "math" -> MissionConfig.buildMath(MathDifficulty.MEDIUM, 3)
                            "shake" -> MissionConfig.buildShake(ShakeIntensity.MEDIUM, 30)
                            "photo" -> MissionConfig.buildPhoto(
                                referenceUri = null,
                                phash = 0L,
                                tolerance = MissionConfig.DEFAULT_PHOTO_TOLERANCE,
                            )
                            else -> d.missionConfigJson
                        }
                        d.copy(missionKind = kind, missionConfigJson = configJson)
                    }
                }
            }
            // Warn when "tap" is selected — it's not really a mission, just a
            // single-tap dismiss. Lets users keep it as an option without
            // pretending it'll force them out of bed.
            if (current.missionKind == "none") {
                item { TapDismissWarningCard() }
            }

            // Math-specific config
            if (current.missionKind == "math") {
                val cfg = MissionConfig.parseMath(current.missionConfigJson)
                item { SectionLabel("Math mission") }
                item {
                    DifficultyPicker(cfg.difficulty) { diff ->
                        viewModel.updateEditingDraft { d ->
                            d.copy(missionConfigJson = MissionConfig.buildMath(diff, cfg.count))
                        }
                    }
                }
                item {
                    MathCountPicker(current = cfg.count) { newCount ->
                        viewModel.updateEditingDraft { d ->
                            d.copy(missionConfigJson = MissionConfig.buildMath(cfg.difficulty, newCount))
                        }
                    }
                }
            }

            // Photo-specific config
            if (current.missionKind == "photo") {
                val cfg = MissionConfig.parsePhoto(current.missionConfigJson)
                item { SectionLabel("Photo mission") }
                item {
                    PhotoReferenceCard(
                        referenceUri = cfg.referenceUri,
                        onTakePhoto = { showPhotoSetup = true },
                    )
                }
            }

            // Shake-specific config
            if (current.missionKind == "shake") {
                val cfg = MissionConfig.parseShake(current.missionConfigJson)
                item { SectionLabel("Shake mission") }
                item {
                    IntensityPicker(cfg.intensity) { intensity ->
                        viewModel.updateEditingDraft { d ->
                            d.copy(
                                missionConfigJson = MissionConfig.buildShake(intensity, cfg.shakesRequired),
                            )
                        }
                    }
                }
                item {
                    // §2026-06-06 — Shake count was a +/- stepper that took up to
                    // ~90 taps to drag across the 10..100 range. Replaced with a
                    // Slider mirroring the max-volume control below — single drag
                    // covers the whole range. 10-shake increments (steps=8 → 10
                    // distinct values: 10, 20, …, 100) match the granularity the
                    // user actually cares about and snap-back nicely.
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Shakes required: ${cfg.shakesRequired}")
                            Slider(
                                value = cfg.shakesRequired.toFloat(),
                                onValueChange = { v ->
                                    viewModel.updateEditingDraft { d ->
                                        d.copy(
                                            missionConfigJson = MissionConfig.buildShake(
                                                cfg.intensity,
                                                v.toInt(),
                                            ),
                                        )
                                    }
                                },
                                valueRange = 10f..100f,
                                steps = 8,
                            )
                        }
                    }
                }
            }

            // Sound + vibration
            item { SectionLabel("Sound & vibration") }
            item {
                SoundPickerRow(
                    soundUri = current.soundUri,
                    onPicked = { uri ->
                        viewModel.updateEditingDraft { it.copy(soundUri = uri) }
                    },
                )
            }
            item {
                SwitchRow(
                    title = "Vibrate",
                    checked = current.vibration,
                ) { v -> viewModel.updateEditingDraft { it.copy(vibration = v) } }
            }
            item {
                SwitchRow(
                    title = "Volume ramps up",
                    description = "Starts soft, builds to full volume over 30 s",
                    checked = current.volumeEscalates,
                ) { v -> viewModel.updateEditingDraft { it.copy(volumeEscalates = v) } }
            }
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Max volume: ${current.volumePct}%")
                        Slider(
                            value = current.volumePct.toFloat(),
                            onValueChange = { v ->
                                viewModel.updateEditingDraft { it.copy(volumePct = v.toInt()) }
                            },
                            valueRange = 10f..100f,
                            steps = 8,
                        )
                    }
                }
            }

            // Snooze
            item { SectionLabel("Snooze") }
            item {
                Stepper(
                    label = "Snooze duration (minutes)",
                    value = current.snoozeMinutes,
                    range = 1..30,
                ) { v -> viewModel.updateEditingDraft { it.copy(snoozeMinutes = v) } }
            }
            item {
                Stepper(
                    label = "Snoozes allowed",
                    value = current.snoozeMax,
                    range = 0..10,
                ) { v -> viewModel.updateEditingDraft { it.copy(snoozeMax = v) } }
            }
        }
    }

    if (showPhotoSetup && draft != null) {
        Dialog(
            onDismissRequest = { showPhotoSetup = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false,
            ),
        ) {
            PhotoReferenceSetup(
                alarmId = draft!!.id,
                onCaptured = { uri, hash ->
                    viewModel.updateEditingDraft { d ->
                        val existing = MissionConfig.parsePhoto(d.missionConfigJson)
                        d.copy(
                            missionConfigJson = MissionConfig.buildPhoto(
                                referenceUri = uri,
                                phash = hash,
                                tolerance = existing.tolerance,
                            ),
                        )
                    }
                    showPhotoSetup = false
                },
                onCancel = { showPhotoSetup = false },
            )
        }
    }
}

@Composable
private fun TapDismissWarningCard() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                "Tap-to-dismiss isn't really a mission — you'll just tap a button " +
                    "to silence the alarm. Pick Math, Shake, or Photo to actually " +
                    "force yourself out of bed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

@Composable
private fun SoundPickerRow(
    soundUri: String?,
    onPicked: (String?) -> Unit,
) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // EXTRA_RINGTONE_PICKED_URI is null when the user explicitly picked
            // "Silent" — we treat null as "fall back to system default" rather
            // than silent (alarms with no sound defeat the point).
            @Suppress("DEPRECATION")
            val uri: Uri? = result.data?.getParcelableExtra(
                RingtoneManager.EXTRA_RINGTONE_PICKED_URI,
            )
            onPicked(uri?.toString())
        }
    }
    val ringtoneName = remember(soundUri) {
        val uri = soundUri?.let(Uri::parse)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        runCatching {
            RingtoneManager.getRingtone(context, uri)?.getTitle(context)
        }.getOrNull() ?: "Default alarm sound"
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                    putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                    putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Alarm sound")
                    putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                    putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                    putExtra(
                        RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI,
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    )
                    putExtra(
                        RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                        soundUri?.let(Uri::parse),
                    )
                }
                launcher.launch(intent)
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Sound", style = MaterialTheme.typography.titleMedium)
                Text(
                    ringtoneName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Default.ChevronRight, contentDescription = "Pick sound")
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 4.dp, start = 4.dp),
    )
}

@Composable
private fun SwitchRow(
    title: String,
    description: String? = null,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (description != null) {
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepeatPicker(mask: Int, onChange: (Int) -> Unit) {
    val labels = listOf("S", "M", "T", "W", "T", "F", "S")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { bit, letter ->
            val on = (mask shr bit) and 1 == 1
            FilterChip(
                selected = on,
                onClick = {
                    val newMask = if (on) mask and (1 shl bit).inv() else mask or (1 shl bit)
                    onChange(newMask)
                },
                label = { Text(letter) },
                colors = FilterChipDefaults.filterChipColors(),
            )
        }
    }
    if (mask == 0) {
        Text(
            "Fires once on the next occurrence, then disables itself.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MissionPicker(current: String, onChange: (String) -> Unit) {
    val options = listOf(
        "none" to "Tap",
        "math" to "Math",
        "shake" to "Shake",
        "photo" to "Photo",
    )
    SingleChoiceSegmentedButtonRow {
        options.forEachIndexed { idx, (kind, label) ->
            SegmentedButton(
                selected = current == kind,
                onClick = { onChange(kind) },
                shape = SegmentedButtonDefaults.itemShape(index = idx, count = options.size),
            ) { Text(label) }
        }
    }
}

@Composable
private fun DifficultyPicker(current: MathDifficulty, onChange: (MathDifficulty) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            MathDifficulty.entries.forEach { diff ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = current == diff, onClick = { onChange(diff) })
                    Spacer(Modifier.height(0.dp))
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text(
                            when (diff) {
                                MathDifficulty.EASY -> "Easy"
                                MathDifficulty.MEDIUM -> "Medium"
                                MathDifficulty.HARD -> "Hard"
                            },
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            when (diff) {
                                MathDifficulty.EASY -> "Single-digit addition or subtraction"
                                MathDifficulty.MEDIUM -> "Two-digit, two-operation arithmetic"
                                MathDifficulty.HARD -> "Order-of-operations with three-digit numbers"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (diff != MathDifficulty.entries.last()) HorizontalDivider()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MathCountPicker(current: Int, onChange: (Int) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Problems required", style = MaterialTheme.typography.titleMedium)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                // §2026-06-06 — Default FilterChip styling sits on the
                // surfaceVariant card and is hard to read in both themes
                // (low-contrast collision). Selected state now goes
                // filled-primary with bold label + a 2dp primary border —
                // unmistakable regardless of theme.
                MissionConfig.MATH_COUNT_OPTIONS.forEach { n ->
                    val isSelected = current == n
                    FilterChip(
                        selected = isSelected,
                        onClick = { onChange(n) },
                        label = {
                            Text(
                                "$n",
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = isSelected,
                            selectedBorderColor = MaterialTheme.colorScheme.primary,
                            selectedBorderWidth = 2.dp,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun PhotoReferenceCard(
    referenceUri: String?,
    onTakePhoto: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (referenceUri == null) {
                Text(
                    "Take a reference photo of a fixed scene (bathroom sink, desk, kettle). " +
                        "You'll have to point at the same scene to dismiss the alarm.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = onTakePhoto,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Take reference photo") }
            } else {
                val bitmap by produceState<android.graphics.Bitmap?>(
                    initialValue = null,
                    key1 = referenceUri,
                ) {
                    value = try {
                        val file = java.io.File(java.net.URI.create(referenceUri))
                        android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                    } catch (_: Exception) {
                        null
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center,
                    ) {
                        bitmap?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = "Reference photo",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } ?: Text("…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Reference photo saved", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Stored on this device only — never uploaded.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                OutlinedButton(
                    onClick = onTakePhoto,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Retake") }
            }
        }
    }
}

@Composable
private fun IntensityPicker(current: ShakeIntensity, onChange: (ShakeIntensity) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            ShakeIntensity.entries.forEach { intensity ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = current == intensity, onClick = { onChange(intensity) })
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text(
                            when (intensity) {
                                ShakeIntensity.LOW -> "Low"
                                ShakeIntensity.MEDIUM -> "Medium"
                                ShakeIntensity.HIGH -> "High"
                            },
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            when (intensity) {
                                ShakeIntensity.LOW -> "Firm shake (≈ 12 m/s²)"
                                ShakeIntensity.MEDIUM -> "Hard shake (≈ 18 m/s²)"
                                ShakeIntensity.HIGH -> "Vigorous shake (≈ 25 m/s²)"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (intensity != ShakeIntensity.entries.last()) HorizontalDivider()
            }
        }
    }
}

@Composable
private fun Stepper(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                Text(
                    value.toString(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            androidx.compose.material3.OutlinedIconButton(
                onClick = { onChange((value - 1).coerceIn(range)) },
                enabled = value > range.first,
            ) { Text("−") }
            Spacer(Modifier.height(0.dp))
            androidx.compose.material3.OutlinedIconButton(
                onClick = { onChange((value + 1).coerceIn(range)) },
                enabled = value < range.last,
                modifier = Modifier.padding(start = 8.dp),
            ) { Text("+") }
        }
    }
}
