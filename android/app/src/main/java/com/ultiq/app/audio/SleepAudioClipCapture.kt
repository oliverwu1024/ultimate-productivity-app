package com.ultiq.app.audio

import android.util.Log
import com.ultiq.app.data.local.entity.SleepAudioEventEntity
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * §10.x — Holds locally-encoded sleep-audio clips between the moment the
 * aggregator finalises an event and the moment the session ends and
 * [com.ultiq.app.data.repository.SleepRepository.saveAudioEvents] runs.
 *
 * The aggregator emits with local UUIDs because the backend's
 * server-side IDs aren't known until the batch INSERT returns. We keep
 * the encoded AAC file on disk keyed by that local id; at session-end,
 * after backend assigns server ids, the caller pairs (local → server)
 * positionally and uploads each clip against its server id.
 *
 * Files live in the app's cache dir under `audio-clips/`. They are
 * deleted on successful upload, on session-end clear, or on
 * [pruneStale] (called at session start to clean up orphans from a
 * previous crashed session).
 */
object SleepAudioClipCapture {
    private const val TAG = "SleepAudioClipCapture"
    private const val SUBDIR = "audio-clips"
    private const val MAX_AGE_MS = 24L * 60L * 60L * 1000L

    private val mutex = Mutex()
    private val capturedClips: MutableMap<String, File> = mutableMapOf()

    fun clipDir(cacheRoot: File): File {
        val dir = File(cacheRoot, SUBDIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Encode the PCM slice for [event] to disk and remember the path under
     * the event's local id. Returns the file on success, null when the
     * encoder fails — the caller should treat the event as clip-less in
     * either case (the row still uploads, just without a clip attached).
     */
    suspend fun captureClip(
        event: SleepAudioEventEntity,
        pcm: SlicedPcm,
        cacheRoot: File,
    ): File? {
        val outFile = File(clipDir(cacheRoot), "clip-${event.id}.m4a")
        val encoded = SleepAudioClipEncoder.encode(pcm, outFile)
        if (encoded == null) {
            Log.w(TAG, "Clip encode failed for ${event.eventType} id=${event.id}")
            return null
        }
        mutex.withLock { capturedClips[event.id] = encoded }
        // §10.x-fix (v2.14.4) — Success log so adb logcat shows the full
        // chain: "Audio event" → "Clip captured" → "Upload start" → "Clip
        // uploaded". Without it the success path was silent and a sparse
        // log looked indistinguishable from a broken pipeline.
        Log.i(
            TAG,
            "Clip captured: ${event.eventType} id=${event.id} " +
                "duration=${pcm.durationMs()}ms file=${encoded.length()}B pending=${capturedClips.size}",
        )
        return encoded
    }

    suspend fun takeClipFor(localEventId: String): File? =
        mutex.withLock { capturedClips.remove(localEventId) }

    suspend fun pendingCount(): Int = mutex.withLock { capturedClips.size }

    /** Clear all captured clips. Called at the very end of the upload pass
     *  so any failed uploads still get their file deleted (the server can
     *  always be retried, but the clip itself doesn't need to linger on
     *  disk forever). */
    suspend fun clearAll() {
        mutex.withLock {
            capturedClips.values.forEach { runCatching { it.delete() } }
            capturedClips.clear()
        }
    }

    /** Walk the clip dir and delete files older than [MAX_AGE_MS]. Run at
     *  session-start so a crashed-mid-night session doesn't leave files
     *  accumulating in the cache forever. */
    fun pruneStale(cacheRoot: File) {
        val dir = clipDir(cacheRoot)
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        dir.listFiles()?.forEach { f ->
            try {
                if (f.lastModified() < cutoff) f.delete()
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to prune ${f.name}", e)
            }
        }
    }
}
