package com.ultiq.app.audio

import com.ultiq.app.data.local.entity.SleepAudioEventEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Debounces a stream of per-window YAMNet classifications into discrete
 * [SleepAudioEventEntity] rows. Each window arrives via [onClassification];
 * an event is emitted via [onEventReady] only after its run of high-confidence
 * hits ends (gap exceeds [SleepAudioConfig.EVENT_GAP_MS]) AND has at least
 * [SleepAudioConfig.MIN_WINDOWS_PER_EVENT] hits — short spikes are discarded.
 *
 * Snore and cough are independent state machines: one ending doesn't reset
 * the other. [flushAll] finalises any in-flight event — call at session end.
 *
 * Thread-safe: MediaPipe delivers classifications on its own worker thread
 * while [flushAll] is called from the service stop path; the internal mutex
 * serialises both.
 *
 * The [userId] and [sleepRecordId] are placeholders during the session
 * because the sleep_record row isn't created until session-end. Callers may
 * pass empty strings and re-write them when persisting to Room with the
 * actual sleep_record id.
 */
class SleepAudioEventAggregator(
    private val userId: String,
    private val sleepRecordId: String,
    private val onEventReady: suspend (SleepAudioEventEntity) -> Unit,
) {
    private data class ActiveEvent(
        val type: String,
        val startedAt: Long,
        var lastHitAt: Long,
        var endedAt: Long,
        var peakConfidence: Float,
        var hitCount: Int,
    )

    private val active = mutableMapOf<String, ActiveEvent>()
    private val mutex = Mutex()

    suspend fun onClassification(
        timestampMs: Long,
        snoreConf: Float,
        coughConf: Float,
    ) {
        mutex.withLock {
            processInternal("snore", snoreConf, SleepAudioConfig.SNORE_CONF_THRESHOLD, timestampMs)
            processInternal("cough", coughConf, SleepAudioConfig.COUGH_CONF_THRESHOLD, timestampMs)
            val stale = active.values.filter { timestampMs - it.lastHitAt > SleepAudioConfig.EVENT_GAP_MS }
            stale.forEach { event ->
                finalize(event)
                active.remove(event.type)
            }
        }
    }

    suspend fun flushAll() {
        mutex.withLock {
            active.values.toList().forEach { event ->
                finalize(event)
                active.remove(event.type)
            }
        }
    }

    private fun processInternal(type: String, conf: Float, threshold: Float, nowMs: Long) {
        if (conf < threshold) return
        val existing = active[type]
        if (existing != null) {
            existing.lastHitAt = nowMs
            existing.endedAt = nowMs + SleepAudioConfig.WINDOW_DURATION_MS
            if (conf > existing.peakConfidence) existing.peakConfidence = conf
            existing.hitCount += 1
        } else {
            active[type] = ActiveEvent(
                type = type,
                startedAt = nowMs,
                lastHitAt = nowMs,
                endedAt = nowMs + SleepAudioConfig.WINDOW_DURATION_MS,
                peakConfidence = conf,
                hitCount = 1,
            )
        }
    }

    private suspend fun finalize(event: ActiveEvent) {
        if (event.hitCount < SleepAudioConfig.MIN_WINDOWS_PER_EVENT) return
        onEventReady(
            SleepAudioEventEntity(
                id = UUID.randomUUID().toString(),
                userId = userId,
                sleepRecordId = sleepRecordId,
                eventType = event.type,
                startedAt = event.startedAt,
                endedAt = event.endedAt,
                peakConfidence = event.peakConfidence,
                createdAt = System.currentTimeMillis(),
                isSynced = false,
            )
        )
    }
}
