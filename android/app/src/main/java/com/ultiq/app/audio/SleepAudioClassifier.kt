package com.ultiq.app.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioRecord
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.mediapipe.tasks.audio.audioclassifier.AudioClassifier
import com.google.mediapipe.tasks.audio.audioclassifier.AudioClassifierResult
import com.google.mediapipe.tasks.audio.core.RunningMode
import com.google.mediapipe.tasks.components.containers.AudioData
import com.google.mediapipe.tasks.core.BaseOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Captures microphone audio during a sleep session and feeds it to
 * MediaPipe's Audio Classifier loaded with YAMNet. Per-window classifications
 * are unpacked into snore + cough confidences and forwarded to a
 * [SleepAudioEventAggregator] for debouncing.
 *
 * Raw audio is never written to disk and never leaves the device. The
 * recording lives only in the [AudioRecord] ring buffer and the short-lived
 * [AudioData] handed to MediaPipe; both are GC'd as soon as classification
 * returns.
 *
 * Lifecycle: caller must hold [Manifest.permission.RECORD_AUDIO] before
 * calling [create]; the factory returns null if not. [start] kicks off the
 * capture loop, [stop] tears it down and forces a final aggregator flush.
 */
class SleepAudioClassifier private constructor(
    private val context: Context,
    private val aggregator: SleepAudioEventAggregator,
) {
    companion object {
        private const val TAG = "SleepAudioClassifier"

        fun isMicPermitted(context: Context): Boolean =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED

        /** Returns null if RECORD_AUDIO isn't granted. Other init failures
         *  (missing asset, AudioRecord rejection) surface inside [start]
         *  with logged errors but no crash. */
        fun create(context: Context, aggregator: SleepAudioEventAggregator): SleepAudioClassifier? {
            if (!isMicPermitted(context)) {
                Log.w(TAG, "RECORD_AUDIO not granted — audio tracking skipped")
                return null
            }
            return SleepAudioClassifier(context, aggregator)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var classifier: AudioClassifier? = null
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private var streamStartedAt: Long = 0L

    /**
     * Initialise MediaPipe + AudioRecord and start the capture loop.
     *
     * Returns true when the pipeline is actually live (classifier built,
     * AudioRecord recording, capture loop scheduled). Returns false on any
     * failure — caller should treat the classifier as dead and revert any
     * UI state that assumed audio tracking is active. Uses Log.i for the
     * happy-path breadcrumbs so they survive R8's stripping of Log.d in
     * release builds (added in v2.11.3 after the previous Log.d-only
     * pipeline was undebuggable from production logcat).
     */
    fun start(): Boolean {
        Log.i(TAG, "start() entered")
        val cls = try {
            val options = AudioClassifier.AudioClassifierOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetPath(SleepAudioConfig.MODEL_ASSET)
                        .build()
                )
                .setRunningMode(RunningMode.AUDIO_STREAM)
                .setResultListener { result -> onClassifierResult(result) }
                .setErrorListener { error -> Log.e(TAG, "MediaPipe error listener fired", error) }
                .build()
            Log.i(TAG, "AudioClassifierOptions built")
            AudioClassifier.createFromOptions(context, options)
        } catch (e: Throwable) {
            Log.e(TAG, "MediaPipe classifier init failed — yamnet.tflite missing from assets?", e)
            return false
        }
        classifier = cls
        Log.i(TAG, "AudioClassifier created from options")

        // Use MediaPipe's factory so the AudioRecord matches the model's
        // expected sample rate, channel layout, and buffer size exactly.
        // Manual AudioRecord construction was hitting "Unable to retrieve
        // AudioRecord object" JNI floods on Android 14 — MediaPipe's
        // createAudioRecord() picks parameters that the framework + native
        // AudioFlinger accept.
        val recorder = try {
            cls.createAudioRecord()
        } catch (e: Throwable) {
            Log.e(TAG, "createAudioRecord() threw", e)
            teardown()
            return false
        }
        audioRecord = recorder
        Log.i(TAG, "createAudioRecord() returned, state=${recorder.state}")

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to init (state=${recorder.state})")
            teardown()
            return false
        }

        streamStartedAt = System.currentTimeMillis()
        try {
            recorder.startRecording()
        } catch (e: Throwable) {
            Log.e(TAG, "AudioRecord.startRecording() threw", e)
            teardown()
            return false
        }
        Log.i(TAG, "AudioRecord.startRecording() returned, recordingState=${recorder.recordingState}")

        captureJob = scope.launch {
            // Read parameters from the AudioRecord MediaPipe gave us — they're
            // tuned to the model's expectations and may differ from our config
            // constants. Critically, MediaPipe.createAudioRecord() configures
            // the recorder as ENCODING_PCM_FLOAT (YAMNet's native format),
            // which means we MUST read into a FloatArray. Reading into a
            // ShortArray returns ERROR_INVALID_OPERATION (-3) immediately
            // because AudioRecord checks the recorder's mAudioFormat field
            // against PCM_16BIT in the short overload.
            val sampleRate = recorder.sampleRate
            val channelCount = recorder.channelCount
            val readSamples = (sampleRate / 4) * channelCount  // ~250 ms / read
            Log.i(
                TAG,
                "capture loop started: sampleRate=$sampleRate channelCount=$channelCount " +
                    "readSamples=$readSamples bufferSizeFrames=${recorder.bufferSizeInFrames} " +
                    "audioFormat=${recorder.audioFormat}",
            )
            val floatBuf = FloatArray(readSamples)
            val format = AudioData.AudioDataFormat.builder()
                .setNumOfChannels(channelCount)
                .setSampleRate(sampleRate.toFloat())
                .build()

            var iterations = 0
            while (true) {
                val read = recorder.read(floatBuf, 0, floatBuf.size, AudioRecord.READ_BLOCKING)
                if (read <= 0) {
                    // Native peer is gone (e.g. AudioFlinger reclaimed) —
                    // break out instead of the previous `continue` which spun
                    // the JNI flood we saw in dogfood.
                    Log.w(TAG, "recorder.read returned $read after $iterations iterations — exiting loop")
                    break
                }
                val data = try {
                    AudioData.create(format, read)
                } catch (e: Throwable) {
                    Log.w(TAG, "AudioData.create failed for $read samples", e)
                    continue
                }
                data.load(floatBuf, 0, read)
                val streamMs = System.currentTimeMillis() - streamStartedAt
                try {
                    classifier?.classifyAsync(data, streamMs)
                } catch (e: Throwable) {
                    Log.e(TAG, "classifyAsync failed at iter=$iterations", e)
                    break
                }
                iterations += 1
                if (iterations == 1 || iterations % 20 == 0) {
                    Log.i(TAG, "classifyAsync iter=$iterations streamMs=$streamMs read=$read")
                }
            }
            Log.i(TAG, "capture loop exited (iterations=$iterations)")
        }
        Log.i(TAG, "start() complete — pipeline live")
        return true
    }

    /**
     * Full teardown: stops capture, releases audio + classifier, runs the
     * aggregator's final flush **synchronously**, and cancels the scope.
     *
     * The synchronous flush matters — SleepTrackingService.onDestroy is the
     * only caller and the ViewModel's saveSessionRecord reads
     * `pendingAudioEvents` immediately after; if we launched the flush into
     * `scope` and then cancelled `scope`, the in-flight event(s) would be
     * lost. runBlocking is safe here because the aggregator only holds its
     * mutex for microseconds per classification.
     */
    fun shutdown() {
        captureJob?.cancel()
        captureJob = null

        val recorder = audioRecord
        audioRecord = null
        try {
            if (recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                recorder.stop()
            }
            recorder?.release()
        } catch (e: Throwable) {
            Log.w(TAG, "AudioRecord cleanup failed", e)
        }

        val cls = classifier
        classifier = null
        try {
            cls?.close()
        } catch (e: Throwable) {
            Log.w(TAG, "AudioClassifier close failed", e)
        }

        runBlocking {
            try {
                aggregator.flushAll()
            } catch (e: Throwable) {
                Log.w(TAG, "Final aggregator flush failed", e)
            }
        }

        scope.cancel()
    }

    /** Best-effort teardown without flush — used on init-failure paths. */
    private fun teardown() {
        captureJob?.cancel()
        captureJob = null
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { classifier?.close() } catch (_: Exception) {}
        classifier = null
    }

    private fun onClassifierResult(result: AudioClassifierResult) {
        // AUDIO_STREAM mode: classificationResults() is a list of per-window
        // results. Each window has one Classifications head whose categories()
        // gives the labelled probabilities. We pluck Snoring + Cough scores;
        // everything else (Silence / Speech / 519 other classes) is ignored.
        // ClassificationResult.timestampMs() is Optional<Long> in the
        // MediaPipe Java API; default to 0 if not provided.
        val results = result.classificationResults()
        for (r in results) {
            val resultMs = r.timestampMs().orElse(0L)
            val timestampMs = streamStartedAt + resultMs
            val cats = r.classifications().firstOrNull()?.categories() ?: continue
            var snoreConf = 0f
            var coughConf = 0f
            for (cat in cats) {
                when (cat.categoryName()) {
                    SleepAudioConfig.LABEL_SNORE -> snoreConf = cat.score()
                    SleepAudioConfig.LABEL_COUGH -> coughConf = cat.score()
                }
            }
            if (snoreConf <= 0f && coughConf <= 0f) continue
            scope.launch {
                try {
                    aggregator.onClassification(timestampMs, snoreConf, coughConf)
                } catch (e: Throwable) {
                    Log.w(TAG, "aggregator.onClassification failed", e)
                }
            }
        }
    }
}
