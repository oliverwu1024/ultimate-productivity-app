package com.ultiq.app.alarm.mission

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import android.os.Build
import androidx.core.graphics.scale
import java.io.File
import java.net.URI
import java.util.UUID

/**
 * On-device library of reference photos for the photo dismiss mission.
 *
 * Photos are owned by the library, not by an alarm: an alarm's mission config
 * only points at one of them (`reference_uri` = `file://` URI), several alarms
 * can share a photo, and deleting an alarm never deletes its photo — the user
 * manages the library themselves. Nothing here is ever uploaded.
 *
 * New photos live under `filesDir/mission_photos/{uuid}.jpg`. Photos captured
 * by builds before the library existed live under `filesDir/alarm_refs/` and
 * are listed too, so existing alarms keep working without a migration.
 */
object MissionPhotoStore {

    private const val DIR = "mission_photos"
    private const val LEGACY_DIR = "alarm_refs"
    private const val MAX_EDGE_PX = 1280
    private const val JPEG_QUALITY = 85

    private fun libraryDir(context: Context): File =
        File(context.filesDir, DIR).apply { mkdirs() }

    private fun roots(context: Context): List<File> =
        listOf(File(context.filesDir, DIR), File(context.filesDir, LEGACY_DIR))

    /** All library photos as `file://` URIs, newest first. */
    fun list(context: Context): List<String> =
        roots(context)
            .flatMap { dir -> dir.listFiles { f -> f.isFile && f.name.endsWith(".jpg") }?.toList().orEmpty() }
            .sortedByDescending { it.lastModified() }
            .map { it.toURI().toString() }

    /** Save an upright [bitmap] (e.g. a camera capture) as a new library photo. */
    fun saveBitmap(context: Context, bitmap: Bitmap): String {
        val scaled = downscale(bitmap)
        val file = File(libraryDir(context), "${UUID.randomUUID()}.jpg")
        try {
            file.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
        return file.toURI().toString()
    }

    /**
     * Copy an image picked from the system photo picker into the library.
     * ImageDecoder applies EXIF orientation, so the stored JPEG is upright.
     * The picker's content:// grant is temporary, which is why the bytes are
     * copied rather than the URI stored.
     */
    fun importFromUri(context: Context, uri: Uri): String {
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width
                val h = info.size.height
                val longest = maxOf(w, h)
                if (longest > MAX_EDGE_PX) {
                    val scale = MAX_EDGE_PX.toFloat() / longest
                    decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                }
            }
        } else {
            decodeLegacy(context, uri)
        }
        return try {
            saveBitmap(context, bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /** API 26–27 path: BitmapFactory with subsampling + manual EXIF rotation. */
    private fun decodeLegacy(context: Context, uri: Uri): Bitmap {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE_PX) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IllegalArgumentException("Unreadable image")
        val degrees = resolver.openInputStream(uri)?.use { stream ->
            when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        if (degrees == 0f) return decoded
        val rotated = Bitmap.createBitmap(
            decoded, 0, 0, decoded.width, decoded.height,
            Matrix().apply { postRotate(degrees) }, true,
        )
        if (rotated !== decoded) decoded.recycle()
        return rotated
    }

    /** True when [uri] is a library photo that still exists on this device. */
    fun exists(context: Context, uri: String?): Boolean {
        val file = fileFor(context, uri) ?: return false
        return file.isFile
    }

    /** Delete a library photo. Refuses anything outside the library folders. */
    fun delete(context: Context, uri: String): Boolean {
        val file = fileFor(context, uri) ?: return false
        return file.delete()
    }

    /** Resolve [uri] to a file inside the library folders, or null. */
    private fun fileFor(context: Context, uri: String?): File? {
        if (uri.isNullOrBlank()) return null
        val file = runCatching { File(URI.create(uri)) }.getOrNull() ?: return null
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return null
        val inLibrary = roots(context).any { root ->
            canonical.parentFile?.path == runCatching { root.canonicalPath }.getOrNull()
        }
        return if (inLibrary) canonical else null
    }

    private fun downscale(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= MAX_EDGE_PX) return bitmap
        val scale = MAX_EDGE_PX.toFloat() / longest
        return bitmap.scale(
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
        )
    }
}
