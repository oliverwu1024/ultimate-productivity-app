package com.ultiq.app.alarm.mission

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy

/**
 * Decode a captured [ImageProxy] to an **upright** Bitmap.
 *
 * CameraX's [ImageProxy.toBitmap] returns the raw sensor buffer — landscape-
 * native on virtually all phones — without baking in the device rotation. The
 * correction (typically 90° when the phone is held in portrait) is delivered
 * separately as `imageInfo.rotationDegrees`. Honour it here, otherwise a
 * portrait capture is stored / shown / overlaid sideways (§8.9 photo mission).
 *
 * Applied at both capture sites (reference setup + live dismiss) so the saved
 * reference JPEG and the live frame are both upright — keeping the embedding
 * comparison consistent as well as the imagery correct.
 */
fun ImageProxy.toUprightBitmap(): Bitmap {
    val raw = toBitmap()
    val degrees = imageInfo.rotationDegrees
    if (degrees == 0) return raw
    val rotated = Bitmap.createBitmap(
        raw, 0, 0, raw.width, raw.height,
        Matrix().apply { postRotate(degrees.toFloat()) },
        true,
    )
    if (rotated !== raw) raw.recycle()
    return rotated
}
