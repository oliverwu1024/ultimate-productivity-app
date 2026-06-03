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
import retrofit2.HttpException
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
        // §v2.15.6 — Track which records' uploads actually succeeded in
        // THIS worker run. fetchAudioEventsForRecord below does a
        // deleteBySleepRecord then re-inserts from the server response,
        // so calling it for a record whose upload just failed would
        // wipe the local rows (server returns empty → local goes empty)
        // and the next retry has nothing to resend. Only refresh records
        // we successfully synced.
        val successfulRecordIds = mutableSetOf<String>()
        val sleepRecordDao = db.sleepDao()
        for ((sleepRecordId, batch) in unsynced.groupBy { it.sleepRecordId }) {
            try {
                // §v2.16.2 — Chunk events to stay under the AWS WAF
                // ManagedRulesCommonRuleSet SizeRestrictions_BODY 8KB
                // body-inspection limit. A heavy snoring night was producing
                // 57+ events ~200B each, exceeding 8KB in one batch, which
                // WAF blocked with 403 *before* reaching the backend — the
                // server never logged a thing, owns_sleep_record was never
                // called, and v2.16.1's self-heal then kept recreating
                // sleep_records on each pass because every batch upload
                // appeared to 403. Chunking at 25 keeps each payload ~5KB
                // with comfortable headroom; markSyncedBatch fires per
                // chunk so a mid-sequence failure doesn't lose earlier
                // progress.
                var chunkCount = 0
                for (chunk in batch.chunked(BATCH_CHUNK_SIZE)) {
                    api.batchCreateSleepAudioEvents(
                        BatchCreateSleepAudioEventsDto(
                            sleep_record_id = sleepRecordId,
                            events = chunk.map { it.toCreateDto() },
                        ),
                    )
                    dao.markSyncedBatch(chunk.map { it.id })
                    chunkCount++
                }
                successfulRecordIds.add(sleepRecordId)
                Log.i(TAG, "Uploaded ${batch.size} events for $sleepRecordId in $chunkCount chunk(s)")
            } catch (t: Throwable) {
                // §v2.16.14 — Removed v2.16.1 403-self-heal markUnsynced.
                // Spawned duplicate sleep_records: any 403 flipped the
                // parent unsynced, then a racing syncUnsyncedSleepRecords
                // POSTed it again, backend INSERTed a fresh row (no
                // idempotency). v2.16.2 chunking removed the original
                // WAF trigger; self-heal was net harm. Worker still
                // retries the events upload on next pass.
                if (t is HttpException && t.code() == 403) {
                    Log.w(TAG, "batch upload 403 for $sleepRecordId — no markUnsynced (v2.16.14)")
                } else {
                    Log.w(TAG, "batch upload failed for $sleepRecordId", t)
                }
                anyFailed = true
            }
        }

        // 3) Upload any orphan clip files. The repository's helper now
        //    (v2.15.7) deletes presign-404 files (orphans where the
        //    target event will never exist) and bails out on HTTP 429
        //    so we don't burn rate-limit budget. Returns false when it
        //    bailed; treat that as a retry so WorkManager backs off and
        //    we don't immediately do the step-4 refresh against the
        //    same rate-limited backend.
        val clipsComplete = try {
            repo.uploadOrphanClipFiles(applicationContext.cacheDir)
        } catch (t: Throwable) {
            Log.w(TAG, "uploadOrphanClipFiles failed", t)
            true // unexpected exception — proceed to step 4, don't get stuck
        }
        if (!clipsComplete) {
            Log.i(TAG, "clip upload hit rate limit — yielding to WorkManager backoff")
            return androidx.work.ListenableWorker.Result.retry()
        }

        // 4) §v2.15.5 — Refresh local Room from the backend for every
        //    sleep_record we *successfully* synced. The attachClip
        //    endpoint set has_clip=true server-side, but local rows
        //    still have has_clip=false until somebody pulls the
        //    canonical state back down. Without this, the user sees
        //    their just-uploaded events with timestamps but no ▶ play
        //    button — they have to kill the app and reopen to trigger
        //    sync() and see the icons. fetchAudioEventsForRecord
        //    replaces the per-record Room slice with what the server
        //    has now.
        //
        //    §v2.15.6 — Refresh ONLY successfulRecordIds, not every
        //    touched record. A failed-upload record has nothing on the
        //    server yet, so the GET-empty + delete-local path here
        //    would wipe the rows we're trying to retry.
        for (recordId in successfulRecordIds) {
            try {
                repo.fetchAudioEventsForRecord(recordId)
            } catch (t: Throwable) {
                Log.w(TAG, "fetchAudioEventsForRecord failed for $recordId", t)
                // Non-fatal — UI will catch up on next sync().
            }
            // §v2.15.7 — Brief pause between per-record fetches so we
            // don't pile on the rate limit after the clip-upload burst.
            kotlinx.coroutines.delay(200L)
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

        // §v2.16.2 — Per-batch chunk size. Each event serialises to ~200B
        // of JSON; 25 events ≈ 5KB, well under the 8KB WAF body limit.
        // Backend's MAX_BATCH is 2000, so we're nowhere near that ceiling.
        private const val BATCH_CHUNK_SIZE = 25

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
