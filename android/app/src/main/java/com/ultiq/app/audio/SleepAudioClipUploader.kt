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
import retrofit2.HttpException

/**
 * §10.x-fix (v2.15.7) — Outcome of one clip upload. Caller (worker)
 * dispatches on this to (a) skip orphan files on the next pass and
 * (b) back off the whole loop when the backend is rate-limiting us.
 */
sealed class ClipUploadOutcome {
    /** Presign + S3 PUT + attach all OK. File deleted by uploader. */
    object Success : ClipUploadOutcome()

    /** Backend says the target event doesn't exist (HTTP 404 on
     *  presign or attach). Local file has been deleted — the clip is
     *  permanently orphaned. Caller can continue with the next file. */
    object Orphaned : ClipUploadOutcome()

    /** Backend rate-limited us (HTTP 429). File stays on disk. Caller
     *  should BREAK the upload loop and let WorkManager retry the whole
     *  worker after backoff — hammering more requests in the same run
     *  just stacks more 429s. */
    object RateLimited : ClipUploadOutcome()

    /** Transient network / S3 / encoder failure. File stays on disk;
     *  caller continues with the next file, this one retries later. */
    object Failed : ClipUploadOutcome()
}

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

    /** Legacy boolean wrapper for callers that don't care about the
     *  distinction. New code should use [uploadDetailed]. */
    suspend fun upload(serverEventId: String, clipFile: File): Boolean =
        uploadDetailed(serverEventId, clipFile) == ClipUploadOutcome.Success

    /** Returns a detailed outcome so the worker can branch on it. */
    suspend fun uploadDetailed(
        serverEventId: String,
        clipFile: File,
    ): ClipUploadOutcome = withContext(Dispatchers.IO) {
        Log.i(TAG, "Upload start: serverId=$serverEventId file=${clipFile.name}")
        if (!clipFile.exists()) {
            // Log the filename only — absolutePath includes the per-user app
            // data dir which doesn't need to surface in logcat.
            Log.w(TAG, "Clip file gone before upload: ${clipFile.name}")
            return@withContext ClipUploadOutcome.Failed
        }
        val len = clipFile.length()
        if (len <= 0) {
            Log.w(TAG, "Empty clip file: ${clipFile.name}")
            try { clipFile.delete() } catch (_: Throwable) {}
            return@withContext ClipUploadOutcome.Failed
        }

        val presign = try {
            apiService.requestSleepAudioClipUploadUrl(
                ClipUploadUrlRequestDto(event_id = serverEventId),
            )
        } catch (e: Throwable) {
            // Common cases: 402 (free tier — Pro check failed), 404 (event
            // not on backend → orphan), 409 (event already has a clip),
            // 429 (rate-limited), 503 (bucket not configured).
            Log.w(TAG, "presign request failed for $serverEventId", e)
            return@withContext classifyHttpFailure(e, clipFile)
        }
        if (len > presign.max_bytes) {
            Log.w(TAG, "Clip $len bytes exceeds server max ${presign.max_bytes} — skipping")
            try { clipFile.delete() } catch (_: Throwable) {}
            return@withContext ClipUploadOutcome.Failed
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
        if (!putOk) return@withContext ClipUploadOutcome.Failed

        val durationMs = readM4aDurationMs(clipFile)
        val effectiveDuration = if (durationMs <= 0) {
            Log.w(TAG, "Couldn't read duration for ${clipFile.name}")
            // Best-effort fallback: server caps at 60s, so claim something
            // reasonable rather than failing the attach outright.
            10_000
        } else durationMs
        return@withContext attachClipDetailed(
            serverEventId, presign.s3_key, effectiveDuration, clipFile,
        )
    }

    private suspend fun attachClipDetailed(
        serverEventId: String,
        s3Key: String,
        durationMs: Int,
        clipFile: File,
    ): ClipUploadOutcome {
        return try {
            apiService.attachSleepAudioClip(
                serverEventId,
                AttachClipRequestDto(s3_key = s3Key, duration_ms = durationMs),
            )
            try { clipFile.delete() } catch (_: Throwable) {}
            ClipUploadOutcome.Success
        } catch (e: Throwable) {
            Log.w(TAG, "attachSleepAudioClip failed for $serverEventId", e)
            classifyHttpFailure(e, clipFile)
        }
    }

    /**
     * §v2.15.7 — Translate an HttpException into the right outcome:
     *  - 404 → Orphaned (event doesn't exist on backend, will never
     *    exist; delete the local file so we stop retrying it on every
     *    worker run).
     *  - 429 → RateLimited (caller should bail the loop).
     *  - 409 → Orphaned-equivalent (event already has a clip — also
     *    delete so we don't keep retrying).
     *  - Anything else → Failed (file stays for next retry).
     */
    private fun classifyHttpFailure(e: Throwable, clipFile: File): ClipUploadOutcome {
        val code = (e as? HttpException)?.code() ?: return ClipUploadOutcome.Failed
        return when (code) {
            404, 409 -> {
                try { clipFile.delete() } catch (_: Throwable) {}
                Log.i(TAG, "Deleted orphan clip (HTTP $code): ${clipFile.name}")
                ClipUploadOutcome.Orphaned
            }
            429 -> ClipUploadOutcome.RateLimited
            else -> ClipUploadOutcome.Failed
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
            Log.w(TAG, "MediaMetadataRetriever failed for ${file.name}", e)
            0
        } finally {
            try { retriever.release() } catch (_: Throwable) {}
        }
    }

    companion object {
        private const val TAG = "SleepAudioClipUploader"
    }
}
