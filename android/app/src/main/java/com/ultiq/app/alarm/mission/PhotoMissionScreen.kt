package com.ultiq.app.alarm.mission

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI

private const val FALLBACK_AFTER_FAILURES = 5

/**
 * Photo dismiss mission (§8.9). Compares the captured frame's pHash against
 * the reference's via Hamming distance; ≤ [PhotoConfigState.tolerance] →
 * onComplete. After 5 failed attempts, transparently falls back to a math
 * mission so the user isn't locked out of their morning.
 */
@Composable
fun PhotoMissionScreen(
    config: MissionConfig.Photo,
    onComplete: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var failures by remember { mutableIntStateOf(0) }
    var capturing by remember { mutableStateOf(false) }
    var lastDistance by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    if (failures >= FALLBACK_AFTER_FAILURES) {
        MathMissionScreen(
            difficulty = MathDifficulty.MEDIUM,
            targetCount = 3,
            onComplete = onComplete,
        )
        return
    }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted -> hasCameraPermission = granted },
    )
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    if (!hasCameraPermission) {
        // Without camera, jump straight to math fallback so the user can still
        // turn the alarm off.
        MathMissionScreen(
            difficulty = MathDifficulty.MEDIUM,
            targetCount = 3,
            onComplete = onComplete,
        )
        return
    }

    val previewView = remember { PreviewView(context) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    DisposableEffect(Unit) {
        scope.launch {
            try {
                val provider = ProcessCameraProvider.awaitInstance(context)
                cameraProvider = provider
                val preview = Preview.Builder().build().apply {
                    surfaceProvider = previewView.surfaceProvider
                }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    capture,
                )
                imageCapture = capture
            } catch (e: Exception) {
                error = e.message ?: "Couldn't open the camera"
            }
        }
        // §M6: unbind the camera when the mission screen leaves composition so
        // we don't accumulate phantom bindings (which on some OEMs eventually
        // refuse new bindings).
        onDispose { cameraProvider?.unbindAll() }
    }

    val referenceBitmap by produceState<android.graphics.Bitmap?>(
        initialValue = null,
        key1 = config.referenceUri,
    ) {
        val uri = config.referenceUri ?: return@produceState
        value = withContext(Dispatchers.IO) {
            try {
                val file = File(URI.create(uri))
                // §M10: decode at ~1/4 resolution. The thumbnail target is
                // 96dp; a full-resolution 1080×1920 JPEG decodes to ~8 MB and
                // is wasted memory for a thumbnail.
                BitmapFactory.decodeFile(
                    file.absolutePath,
                    BitmapFactory.Options().apply { inSampleSize = 4 },
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(width = 96.dp, height = 96.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                val ref = referenceBitmap
                if (ref != null) {
                    Image(
                        bitmap = ref.asImageBitmap(),
                        contentDescription = "Reference photo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        "Ref",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Photograph the scene",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Point at the same place as your reference photo, then capture.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val remaining = FALLBACK_AFTER_FAILURES - failures
                Spacer(Modifier.height(4.dp))
                Text(
                    "Attempt ${failures + 1} of $FALLBACK_AFTER_FAILURES" +
                        if (failures > 0) " · $remaining left before math fallback" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(androidx.compose.ui.graphics.Color.Black),
        ) {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize(),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val matchText = when (val d = lastDistance) {
                null -> ""
                else -> "Last match: $d bits different (need ≤ ${config.tolerance})"
            }
            if (matchText.isNotEmpty()) {
                Text(
                    matchText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (error != null) {
                Text(
                    error!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            androidx.compose.material3.Button(
                onClick = {
                    val capture = imageCapture ?: return@Button
                    capturing = true
                    error = null
                    capture.takePicture(
                        ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageCapturedCallback() {
                            override fun onCaptureSuccess(image: ImageProxy) {
                                scope.launch {
                                    // §H2: always close the ImageProxy, even
                                    // if toBitmap() or the pHash throws.
                                    // Otherwise the camera's image queue
                                    // fills up and silently blocks further
                                    // captures.
                                    try {
                                        val bitmap = withContext(Dispatchers.IO) { image.toBitmap() }
                                        val liveHash = withContext(Dispatchers.IO) {
                                            PerceptualHash.compute(bitmap)
                                        }
                                        bitmap.recycle()
                                        val distance = PerceptualHash.hammingDistance(liveHash, config.phash)
                                        lastDistance = distance
                                        capturing = false
                                        if (distance <= config.tolerance) {
                                            onComplete()
                                        } else {
                                            failures += 1
                                        }
                                    } catch (e: Exception) {
                                        capturing = false
                                        error = e.message ?: "Couldn't process the frame"
                                    } finally {
                                        image.close()
                                    }
                                }
                            }

                            override fun onError(exc: ImageCaptureException) {
                                capturing = false
                                error = exc.message ?: "Capture failed"
                            }
                        },
                    )
                },
                enabled = imageCapture != null && !capturing,
                modifier = Modifier.fillMaxWidth().height(60.dp),
            ) {
                Text(if (capturing) "Checking…" else "Capture")
            }
        }
    }
}
