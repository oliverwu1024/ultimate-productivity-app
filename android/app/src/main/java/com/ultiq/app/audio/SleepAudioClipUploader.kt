package com.ultiq.app.audio

import android.media.MediaMetadataRetriever
import android.util.Log
import com.ultiq.app.data.remote.ApiService
import com.ultiq.app.data.remote.dto.AttachClipRequestDto
import com.ultiq.app.data.remote.dto.ClipUploadUrlRequestDto
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody

/**
 * §10.x — Two-step upload of a recorded sleep-audio clip to S3.
 *
 *   1. POST /sleep-audio-events/clip-upload-url → presigned PUT URL + s3_key
 *   2. PUT the AAC bytes to the URL (direct to S3, backend never sees audio)
 *   3. POST /sleep-audio-events/:id/clip → bind s3_key + duration to event
 *
 * Uses a dedicated OkHttp client (no auth interceptor) for step 2 so the
 * ultiq JWT never travels to S3. The presigned URL is the only auth.
 *
 * Failure handling: every step swallow-and-logs. A failed upload leaves
 * the event row clip-less server-side; the row itself is still recorded
 * (snore/cough counts still accurate). No retry — clips are time-bound
 * to the user's hearing the bug "I want to hear myself snore tonight",
 * not durable evidence.
 */
class SleepAudioClipUploader(
    private val apiService: ApiService,
) {
    private val s3Client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Returns true on the full happy path (presign + PUT + attach all OK). */
    suspend fun upload(serverEventId: String, clipFile: File): Boolean = withContext(Dispatchers.IO) {
        if (!clipFile.exists()) {
            Log.w(TAG, "Clip file gone before upload: ${clipFile.absolutePath}")
            return@withContext false
        }
        val len = clipFile.length()
        if (len <= 0) {
            Log.w(TAG, "Empty clip file: ${clipFile.absolutePath}")
            try { clipFile.delete() } catch (_: Throwable) {}
            return@withContext false
        }

        val presign = try {
            apiService.requestSleepAudioClipUploadUrl(
                ClipUploadUrlRequestDto(event_id = serverEventId),
            )
        } catch (e: Throwable) {
            // Common cases: 402 (free tier — Pro check failed), 409 (event
            // already has a clip), 503 (bucket not configured). All non-fatal
            // for the session, log + bail.
            Log.w(TAG, "presign request failed for $serverEventId", e)
            return@withContext false
        }
        if (len > presign.max_bytes) {
            Log.w(TAG, "Clip $len bytes exceeds server max ${presign.max_bytes} — skipping")
            try { clipFile.delete() } catch (_: Throwable) {}
            return@withContext false
        }

        val mediaType = presign.content_type.toMediaTypeOrNull()
        val body = clipFile.asRequestBody(mediaType)
        val putReq = Request.Builder()
            .url(presign.put_url)
            .put(body)
            .header("Content-Type", presign.content_type)
            .build()
        val putOk = try {
            s3Client.newCall(putReq).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "S3 PUT returned ${resp.code} for ${presign.s3_key}")
                    false
                } else true
            }
        } catch (e: Throwable) {
            Log.w(TAG, "S3 PUT threw for ${presign.s3_key}", e)
            false
        }
        if (!putOk) return@withContext false

        val durationMs = readM4aDurationMs(clipFile)
        if (durationMs <= 0) {
            Log.w(TAG, "Couldn't read duration for ${clipFile.absolutePath}")
            // Best-effort fallback: server caps at 60s, so claim something
            // reasonable rather than failing the attach outright.
            return@withContext attachClip(serverEventId, presign.s3_key, 10_000, clipFile)
        }
        return@withContext attachClip(serverEventId, presign.s3_key, durationMs, clipFile)
    }

    private suspend fun attachClip(
        serverEventId: String,
        s3Key: String,
        durationMs: Int,
        clipFile: File,
    ): Boolean {
        return try {
            apiService.attachSleepAudioClip(
                serverEventId,
                AttachClipRequestDto(s3_key = s3Key, duration_ms = durationMs),
            )
            try { clipFile.delete() } catch (_: Throwable) {}
            true
        } catch (e: Throwable) {
            Log.w(TAG, "attachSleepAudioClip failed for $serverEventId", e)
            false
        }
    }

    private fun readM4aDurationMs(file: File): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toIntOrNull()
                ?: 0
        } catch (e: Throwable) {
            Log.w(TAG, "MediaMetadataRetriever failed for ${file.absolutePath}", e)
            0
        } finally {
            try { retriever.release() } catch (_: Throwable) {}
        }
    }

    companion object {
        private const val TAG = "SleepAudioClipUploader"
    }
}
