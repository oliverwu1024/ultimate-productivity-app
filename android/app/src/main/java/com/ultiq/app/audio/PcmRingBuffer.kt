package com.ultiq.app.audio

/**
 * §10.x — Time-indexed ring buffer of PCM audio chunks.
 *
 * The YAMNet classifier captures audio in ~250 ms FloatArray chunks; we
 * keep the most recent ~30 s in memory so the Pro-tier clip recorder can
 * grab audio from *before* an event finalised. The aggregator only emits
 * an event after a 5 s gap-close, so the event's `startedAt` timestamp is
 * already several seconds in the past by the time the service wants to
 * record a clip — without the rolling buffer there'd be no way to capture
 * the start of a snore that began before the user even fell asleep.
 *
 * Thread-safe: classifier worker writes via [append] while the service's
 * event-ready callback reads via [slice].
 *
 * Memory: 30 s × 16 kHz × 4 B/float = 1.92 MB. Sits in the foreground
 * service heap; no risk of OOM on any device that runs the rest of Ultiq.
 */
class PcmRingBuffer(private val maxDurationMs: Long) {
    /** One read from AudioRecord. Owns its sample array — the classifier's
     *  reused read buffer must NOT be referenced here, hence [append]
     *  copies on the way in. */
    private data class Chunk(
        val samples: FloatArray,
        val startWallClockMs: Long,
        val sampleRate: Int,
    )

    private val chunks = ArrayDeque<Chunk>()
    private val lock = Any()

    fun append(samples: FloatArray, count: Int, wallClockMs: Long, sampleRate: Int) {
        if (count <= 0) return
        // Copy: the caller (classifier capture loop) reuses the same
        // FloatArray every iteration, so a stored reference would get
        // overwritten by the next read.
        val copied = samples.copyOfRange(0, count)
        val chunk = Chunk(copied, wallClockMs, sampleRate)
        synchronized(lock) {
            chunks.addLast(chunk)
            val cutoff = wallClockMs - maxDurationMs
            while (chunks.isNotEmpty()) {
                val first = chunks.first()
                val firstEnd = first.startWallClockMs +
                    (first.samples.size.toLong() * 1000L / first.sampleRate.coerceAtLeast(1))
                if (firstEnd < cutoff) chunks.removeFirst() else break
            }
        }
    }

    /**
     * Returns concatenated PCM covering [startMs] to [endMs] wall-clock,
     * or null if the buffer doesn't cover the requested range (chunks
     * evicted, or stream hasn't reached endMs yet). The returned array is
     * always 16 kHz mono float — the same format MediaPipe asked AudioRecord
     * for, which is also what YAMNet uses.
     */
    fun slice(startMs: Long, endMs: Long): SlicedPcm? {
        if (endMs <= startMs) return null
        synchronized(lock) {
            if (chunks.isEmpty()) return null
            val sampleRate = chunks.first().sampleRate
            // Bail if the requested range starts before what we have, or
            // ends after what we have — better to skip the clip than
            // produce one with silence padding the caller didn't ask for.
            val firstStart = chunks.first().startWallClockMs
            val lastEnd = chunks.last().let {
                it.startWallClockMs + (it.samples.size.toLong() * 1000L / it.sampleRate.coerceAtLeast(1))
            }
            if (startMs < firstStart || endMs > lastEnd) return null

            // First pass: figure out the exact sample count to pre-allocate.
            var totalSamples = 0
            for (c in chunks) {
                val chunkStart = c.startWallClockMs
                val chunkEnd = chunkStart + (c.samples.size.toLong() * 1000L / c.sampleRate.coerceAtLeast(1))
                if (chunkEnd <= startMs) continue
                if (chunkStart >= endMs) break
                val sliceStartIdx = if (chunkStart < startMs) {
                    ((startMs - chunkStart) * c.sampleRate / 1000L).toInt().coerceAtLeast(0)
                } else 0
                val sliceEndIdx = if (chunkEnd > endMs) {
                    ((endMs - chunkStart) * c.sampleRate / 1000L).toInt().coerceAtMost(c.samples.size)
                } else c.samples.size
                totalSamples += (sliceEndIdx - sliceStartIdx).coerceAtLeast(0)
            }
            if (totalSamples <= 0) return null

            val out = FloatArray(totalSamples)
            var writeIdx = 0
            for (c in chunks) {
                val chunkStart = c.startWallClockMs
                val chunkEnd = chunkStart + (c.samples.size.toLong() * 1000L / c.sampleRate.coerceAtLeast(1))
                if (chunkEnd <= startMs) continue
                if (chunkStart >= endMs) break
                val sliceStartIdx = if (chunkStart < startMs) {
                    ((startMs - chunkStart) * c.sampleRate / 1000L).toInt().coerceAtLeast(0)
                } else 0
                val sliceEndIdx = if (chunkEnd > endMs) {
                    ((endMs - chunkStart) * c.sampleRate / 1000L).toInt().coerceAtMost(c.samples.size)
                } else c.samples.size
                val len = sliceEndIdx - sliceStartIdx
                if (len > 0) {
                    System.arraycopy(c.samples, sliceStartIdx, out, writeIdx, len)
                    writeIdx += len
                }
            }
            return SlicedPcm(samples = out, sampleRate = sampleRate)
        }
    }
}

data class SlicedPcm(val samples: FloatArray, val sampleRate: Int) {
    fun durationMs(): Long = samples.size.toLong() * 1000L / sampleRate.coerceAtLeast(1)
}
