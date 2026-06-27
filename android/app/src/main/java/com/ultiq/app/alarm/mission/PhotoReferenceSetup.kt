package com.ultiq.app.alarm.mission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Full-screen photo-reference setup overlay (§8.9). Requests CAMERA at runtime,
 * shows a CameraX preview, captures a JPEG on tap, persists the photo to
 * `Context.filesDir/alarm_refs/{alarmId}.jpg`, and calls [onCaptured] with the
 * saved URI. The reference embedding is computed lazily at mission time from
 * this JPEG (see [PhotoEmbedder]), so setup itself stays model-free.
 */
@Composable
fun PhotoReferenceSetup(
    alarmId: String,
    onCaptured: (uri: String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

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
        PermissionGate(
            message = "Ultiq needs the camera to set up your photo dismiss mission.",
            onRetry = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            onCancel = onCancel,
        )
        return
    }

    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var capturing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val previewView = remember { PreviewView(context) }
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
        // §M6: release the camera binding when the setup overlay closes so
        // re-opening setup gets a fresh binding rather than queueing behind
        // a stale one.
        onDispose { cameraProvider?.unbindAll() }
    }

    CameraOverlay(
        previewView = previewView,
        headerTitle = "Reference photo",
        headerBody = "Hold the camera steady on a fixed scene — your bathroom sink, " +
            "fridge, doorway. You'll have to point at the same scene to dismiss " +
            "the alarm.",
        captureEnabled = imageCapture != null && !capturing,
        captureLabel = if (capturing) "Saving…" else "Capture",
        onCancel = onCancel,
        onCapture = {
            val capture = imageCapture ?: return@CameraOverlay
            capturing = true
            error = null
            capture.takePicture(
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        scope.launch {
                            // §H2: close the ImageProxy in finally so a
                            // failure during toBitmap() or save doesn't leak.
                            try {
                                val bitmap = withContext(Dispatchers.IO) { image.toBitmap() }
                                val uri = withContext(Dispatchers.IO) {
                                    val u = saveReferencePhoto(context, alarmId, bitmap)
                                    bitmap.recycle()
                                    u
                                }
                                capturing = false
                                onCaptured(uri)
                            } catch (e: Exception) {
                                capturing = false
                                error = e.message ?: "Couldn't save the photo"
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
        footerError = error,
    )
}

@Composable
internal fun CameraOverlay(
    previewView: PreviewView,
    headerTitle: String,
    headerBody: String,
    captureEnabled: Boolean,
    captureLabel: String,
    onCancel: () -> Unit,
    onCapture: () -> Unit,
    footerError: String? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Column {
                Text(
                    headerTitle,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    headerBody,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (footerError != null) {
                Text(
                    footerError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                ) { Text("Cancel") }
                Button(
                    onClick = onCapture,
                    enabled = captureEnabled,
                    modifier = Modifier.weight(2f),
                ) { Text(captureLabel) }
            }
        }
    }
}

@Composable
private fun PermissionGate(
    message: String,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Camera permission needed",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Grant access") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
    }
}

/** Persist [bitmap] as a JPEG under `filesDir/alarm_refs/{alarmId}.jpg`. */
internal fun saveReferencePhoto(context: Context, alarmId: String, bitmap: Bitmap): String {
    val dir = File(context.filesDir, "alarm_refs").apply { mkdirs() }
    val file = File(dir, "$alarmId.jpg")
    file.outputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
    }
    return file.toURI().toString()
}
