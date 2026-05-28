package com.ultiq.app.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * §10.x — Encodes a [SlicedPcm] (float mono PCM from [PcmRingBuffer]) to an
 * AAC-LC track inside an MP4 (.m4a) container.
 *
 * Why AAC instead of WAV: the same 10 s clip is ~80 KB AAC vs ~320 KB
 * 16-bit WAV. Multiply by snore-heavy nights (dozens of events) and the
 * bandwidth gap is the difference between "background-trivial" and
 * "noticeably eats data plans" — we pay the MediaCodec complexity once
 * here so every upload after is cheap.
 *
 * Sync-mode encoding loop: queueInputBuffer until PCM exhausted, then
 * BUFFER_FLAG_END_OF_STREAM, drain remaining output. Timestamps are
 * generated from sample index → microseconds, not wall-clock, so the
 * encoded duration matches the input PCM exactly.
 */
object SleepAudioClipEncoder {
    private const val TAG = "SleepAudioClipEncoder"
    private const val MIME = MediaFormat.MIMETYPE_AUDIO_AAC
    private const val BIT_RATE = 64_000
    private const val DEQUEUE_TIMEOUT_US = 10_000L

    /**
     * Encode [pcm] into a .m4a file at [outPath]. Returns the file on
     * success or null on any encoder failure (logged). Caller is
     * responsible for deleting the file when upload completes.
     */
    fun encode(pcm: SlicedPcm, outPath: File): File? {
        val sampleRate = pcm.sampleRate
        val format = MediaFormat.createAudioFormat(MIME, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            // MediaCodec rejects input bigger than this without a hint; one
            // 250 ms PCM chunk × 16 kHz × 2 B = 8000 bytes, so 16 KB is
            // comfortable headroom.
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }

        val codec = try {
            MediaCodec.createEncoderByType(MIME)
        } catch (e: Throwable) {
            Log.e(TAG, "createEncoderByType failed", e)
            return null
        }

        var muxer: MediaMuxer? = null
        var trackIndex = -1
        var muxerStarted = false

        return try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            muxer = MediaMuxer(outPath.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // Convert FloatArray → ShortArray (16-bit PCM) once up front.
            // MediaCodec's PCM input is interleaved bytes; we feed it via a
            // ByteBuffer view of the short array.
            val pcm16 = ShortArray(pcm.samples.size) { i ->
                val s = pcm.samples[i].coerceIn(-1f, 1f)
                (s * 32767f).toInt().toShort()
            }
            val pcmBytes = ByteBuffer.allocate(pcm16.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (s in pcm16) pcmBytes.putShort(s)
            pcmBytes.flip()

            val bufferInfo = MediaCodec.BufferInfo()
            var samplesConsumed = 0
            var eosQueued = false
            var eosSeen = false

            while (!eosSeen) {
                if (!eosQueued) {
                    val inIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex) ?: continue
                        inBuf.clear()
                        val cap = inBuf.capacity()
                        val remainingBytes = pcmBytes.remaining()
                        if (remainingBytes <= 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, sampleIndexToUs(samplesConsumed, sampleRate),
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            eosQueued = true
                        } else {
                            val toCopy = minOf(cap, remainingBytes)
                            val slice = ByteArray(toCopy)
                            pcmBytes.get(slice)
                            inBuf.put(slice)
                            val samples = toCopy / 2 // 16-bit mono
                            codec.queueInputBuffer(
                                inIndex, 0, toCopy,
                                sampleIndexToUs(samplesConsumed, sampleRate),
                                0,
                            )
                            samplesConsumed += samples
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (muxerStarted) {
                            Log.w(TAG, "OUTPUT_FORMAT_CHANGED after muxer started — encoder reused?")
                        }
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // No output ready — keep looping; if eosQueued is
                        // true we'll eventually flush.
                    }
                    outIndex >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIndex)
                        if (outBuf != null && bufferInfo.size > 0 && muxerStarted &&
                            (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        ) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, outBuf, bufferInfo)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            eosSeen = true
                        }
                    }
                }
            }
            outPath
        } catch (e: Throwable) {
            Log.e(TAG, "AAC encode failed", e)
            try { outPath.delete() } catch (_: Throwable) {}
            null
        } finally {
            try { codec.stop() } catch (_: Throwable) {}
            try { codec.release() } catch (_: Throwable) {}
            if (muxerStarted) {
                try { muxer?.stop() } catch (_: Throwable) {}
            }
            try { muxer?.release() } catch (_: Throwable) {}
        }
    }

    private fun sampleIndexToUs(samples: Int, sampleRate: Int): Long =
        samples.toLong() * 1_000_000L / sampleRate.coerceAtLeast(1).toLong()
}
