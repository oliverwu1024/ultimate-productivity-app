package com.ultiq.app.ui.alarms

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ultiq.app.R
import com.ultiq.app.alarm.mission.MissionConfig
import com.ultiq.app.alarm.mission.MissionPhotoStore
import com.ultiq.app.alarm.mission.PhotoReferenceSetup
import com.ultiq.app.data.local.entity.AlarmEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI

/**
 * In-app library of photo-mission reference photos. Photos are kept on this
 * device independently of alarms: users take or upload them here, pick one
 * per alarm, and delete them here (deleting an alarm never deletes a photo).
 *
 * @param selectedUri when non-null the library is in pick mode for an alarm:
 *   the current choice is highlighted, the preview offers "Use this photo",
 *   and a freshly taken / uploaded photo is selected immediately.
 * @param onPicked pick-mode callback; null = manage-only mode.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MissionPhotoLibrary(
    alarms: List<AlarmEntity>,
    selectedUri: String?,
    onPicked: ((String) -> Unit)?,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val importFailedMsg = stringResource(R.string.mission_photos_import_failed)
    var refresh by remember { mutableIntStateOf(0) }
    var showCamera by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var previewUri by remember { mutableStateOf<String?>(null) }
    var confirmDeleteUri by remember { mutableStateOf<String?>(null) }

    val photos by produceState<List<String>?>(initialValue = null, key1 = refresh) {
        value = withContext(Dispatchers.IO) { MissionPhotoStore.list(context) }
    }

    fun onNewPhoto(uri: String) {
        refresh++
        onPicked?.invoke(uri)
    }

    val pickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { picked ->
        if (picked == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching { MissionPhotoStore.importFromUri(context, picked) }.getOrNull()
            }
            importing = false
            if (saved != null) onNewPhoto(saved)
            else Toast.makeText(context, importFailedMsg, Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (onPicked != null) stringResource(R.string.mission_photos_choose_title)
                        else stringResource(R.string.mission_photos_title),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.mission_photos_tip),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(R.string.photo_local_only),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item(key = "take") {
                ActionTile(
                    icon = Icons.Default.PhotoCamera,
                    label = stringResource(R.string.mission_photos_take),
                    enabled = !importing,
                    onClick = { showCamera = true },
                )
            }
            item(key = "upload") {
                ActionTile(
                    icon = Icons.Default.PhotoLibrary,
                    label = stringResource(R.string.mission_photos_upload),
                    enabled = !importing,
                    busy = importing,
                    onClick = {
                        pickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                )
            }
            val list = photos
            if (list != null && list.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        stringResource(R.string.mission_photos_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    )
                }
            }
            items(list.orEmpty(), key = { it }) { uri ->
                PhotoTile(
                    uri = uri,
                    selected = onPicked != null && uri == selectedUri,
                    onClick = { previewUri = uri },
                )
            }
        }
    }

    previewUri?.let { uri ->
        val usage = usageCount(alarms, uri)
        AlertDialog(
            onDismissRequest = { previewUri = null },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PhotoThumbnail(
                        uri = uri,
                        targetPx = 1024,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).aspectRatio(0.75f),
                    )
                    if (usage > 0) {
                        Text(
                            pluralStringResource(R.plurals.mission_photos_used_by, usage, usage),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                if (onPicked != null) {
                    TextButton(onClick = {
                        previewUri = null
                        onPicked(uri)
                    }) { Text(stringResource(R.string.mission_photos_use)) }
                } else {
                    TextButton(onClick = { previewUri = null }) { Text(stringResource(R.string.action_back)) }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    previewUri = null
                    confirmDeleteUri = uri
                }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
        )
    }

    confirmDeleteUri?.let { uri ->
        val usage = usageCount(alarms, uri)
        AlertDialog(
            onDismissRequest = { confirmDeleteUri = null },
            title = { Text(stringResource(R.string.mission_photos_delete_title)) },
            text = {
                Text(
                    if (usage > 0) pluralStringResource(R.plurals.mission_photos_delete_in_use, usage, usage)
                    else stringResource(R.string.mission_photos_delete_body),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteUri = null
                    scope.launch {
                        withContext(Dispatchers.IO) { MissionPhotoStore.delete(context, uri) }
                        refresh++
                    }
                }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteUri = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    if (showCamera) {
        Dialog(
            onDismissRequest = { showCamera = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false,
            ),
        ) {
            PhotoReferenceSetup(
                onCaptured = { uri ->
                    showCamera = false
                    onNewPhoto(uri)
                },
                onCancel = { showCamera = false },
            )
        }
    }
}

/** Number of photo-mission alarms whose reference is [uri]. */
internal fun usageCount(alarms: List<AlarmEntity>, uri: String): Int =
    alarms.count {
        it.missionKind == "photo" && MissionConfig.parsePhoto(it.missionConfigJson).referenceUri == uri
    }

@Composable
private fun ActionTile(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    busy: Boolean = false,
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(8.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.padding(4.dp))
            } else {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun PhotoTile(uri: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (selected) {
                    Modifier.border(BorderStroke(3.dp, MaterialTheme.colorScheme.primary), RoundedCornerShape(12.dp))
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick),
    ) {
        PhotoThumbnail(uri = uri, targetPx = 320, modifier = Modifier.fillMaxSize())
        if (selected) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
            )
        }
    }
}

/** Sub-sampled async decode of a library photo; shows a placeholder while loading / if missing. */
@Composable
internal fun PhotoThumbnail(
    uri: String,
    targetPx: Int,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = uri) {
        value = withContext(Dispatchers.IO) { decodeSampled(uri, targetPx) }
    }
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = stringResource(R.string.photo_cd),
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        } ?: Text("…", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun decodeSampled(uri: String, targetPx: Int): Bitmap? = runCatching {
    val path = File(URI.create(uri)).absolutePath
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetPx) sample *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}.getOrNull()
