package com.ultiq.app.data.repository

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ultiq.app.data.local.AppDatabase
import com.ultiq.app.data.remote.RetrofitClient
import com.ultiq.app.data.remote.dto.BatchCreateSleepAudioEventsDto
import com.ultiq.app.data.remote.dto.toCreateDto
import com.ultiq.app.util.TokenManager
import java.util.concurrent.TimeUnit

/**
 * §10.x-fix — Cross-session retry of any sleep_audio_events still
 * `isSynced=false` (and any orphan clip files left on disk after a
 * failed in-session upload).
 *
 * Scheduled at session-end regardless of whether the in-session retry
 * succeeded — cheap no-op when there's nothing to do, durable safety
 * net when there is. NetworkType.CONNECTED + exponential backoff means
 * the worker keeps trying until either (a) the upload succeeds or (b)
 * the user uninstalls.
 */
class SleepAudioUploadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): androidx.work.ListenableWorker.Result {
        val tokenManager = TokenManager(applicationContext)
        val api = RetrofitClient.create(tokenManager)
        val db = AppDatabase.getInstance(applicationContext)
        val dao = db.sleepAudioEventDao()
        val repo = SleepRepository(
            sleepDao = db.sleepDao(),
            apiService = api,
            sleepAudioEventDao = dao,
        )

        // 1) §v2.15.3 — Sync any local-fallback sleep_records first. If the
        //    user's network was down at End Sleep, createSleepRecord fell
        //    back to a local-only row and audio events were stamped with
        //    its random UUID. We need the backend to know about the
        //    sleep_record before the audio events can be uploaded —
        //    otherwise the owns_sleep_record check on the batch endpoint
        //    returns 404 and the events stay stuck forever.
        //    syncUnsyncedSleepRecords also cascade-rewrites audio event
        //    references to the new server-issued id.
        try {
            repo.syncUnsyncedSleepRecords()
        } catch (t: Throwable) {
            Log.w(TAG, "syncUnsyncedSleepRecords failed", t)
            // Continue anyway — the events upload below will surface any
            // remaining mismatch via retry.
        }

        // 1b) §v2.15.4 — Sweep events whose parent sleep_record was deleted
        //     or never made it to the backend. Without this, orphan events
        //     keep the banner stuck and the upload below keeps 404-ing for
        //     them forever.
        try {
            val n = repo.cleanupOrphanedAudioEvents()
            if (n > 0) Log.i(TAG, "Swept $n orphan audio events")
        } catch (t: Throwable) {
            Log.w(TAG, "cleanupOrphanedAudioEvents failed", t)
        }

        // 2) Upload any unsynced events, batched per sleep_record. Skip
        //    placeholder rows (sleepRecordId starts with "pending-") — those
        //    belong to a live session and will be relinked at End Sleep.
        val unsynced = try {
            dao.getUnsynced().filter { !it.sleepRecordId.startsWith("pending-") }
        } catch (t: Throwable) {
            Log.w(TAG, "getUnsynced failed", t)
            return androidx.work.ListenableWorker.Result.retry()
        }

        var anyFailed = false
        for ((sleepRecordId, batch) in unsynced.groupBy { it.sleepRecordId }) {
            try {
                api.batchCreateSleepAudioEvents(
                    BatchCreateSleepAudioEventsDto(
                        sleep_record_id = sleepRecordId,
                        events = batch.map { it.toCreateDto() },
                    ),
                )
                dao.markSyncedBatch(batch.map { it.id })
                Log.i(TAG, "Uploaded ${batch.size} events for $sleepRecordId")
            } catch (t: Throwable) {
                Log.w(TAG, "batch upload failed for $sleepRecordId", t)
                anyFailed = true
            }
        }

        // 3) Upload any orphan clip files. The repository's helper scans
        //    cacheDir/audio-clips/, attempts each upload, deletes on
        //    success. Failures (404, 409, transient network) stay on
        //    disk; SleepAudioClipCapture.pruneStale evicts them after
        //    24h at the next session start.
        try {
            repo.uploadOrphanClipFiles(applicationContext.cacheDir)
        } catch (t: Throwable) {
            Log.w(TAG, "uploadOrphanClipFiles failed", t)
            // Not retry-worthy on its own — clip uploads are best-effort
            // and the file lifecycle is already bounded by pruneStale.
        }

        return if (anyFailed) {
            androidx.work.ListenableWorker.Result.retry()
        } else {
            androidx.work.ListenableWorker.Result.success()
        }
    }

    companion object {
        private const val TAG = "SleepAudioUploadWorker"
        private const val UNIQUE_WORK_NAME = "sleep-audio-upload"

        /**
         * Enqueue (or replace) the unique sleep-audio upload job. KEEP
         * policy means rapidly-firing end-of-session calls don't pile
         * up — if one is already pending, the existing one continues.
         * Use REPLACE when the caller explicitly wants a fresh attempt
         * (e.g. user retried manually).
         */
        fun enqueue(context: Context, replace: Boolean = false) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            // §v2.15.4 — LINEAR (not EXPONENTIAL) backoff so successive
            // retries stay close together instead of doubling out to
            // 5+ minutes. EXPONENTIAL was punishing the airplane-mode
            // case: by the time the user disabled airplane mode the
            // next retry was scheduled many minutes out.
            val request = OneTimeWorkRequestBuilder<SleepAudioUploadWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    30, TimeUnit.SECONDS,
                )
                .build()
            val policy = if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, policy, request)
        }
    }
}
