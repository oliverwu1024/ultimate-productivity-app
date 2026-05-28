package com.ultiq.app.audio

/**
 * Tuning constants for on-device snore + cough detection via YAMNet.
 *
 * YAMNet expects 16 kHz mono PCM in ~0.96 s windows. MediaPipe's Audio
 * Classifier handles windowing + mel-spectrogram pre-processing internally,
 * so we feed it raw float PCM and consume one classification result per
 * window via the stream-mode result listener.
 */
object SleepAudioConfig {
    /** Path inside assets/. The APK must include this file for the classifier
     *  to initialise — see app/src/main/assets/yamnet.tflite. */
    const val MODEL_ASSET = "yamnet.tflite"

    const val SAMPLE_RATE_HZ = 16_000

    /** YAMNet's native AudioSet ontology label strings. These come back
     *  verbatim from MediaPipe in each result's category names. */
    const val LABEL_SNORE = "Snoring"
    const val LABEL_COUGH = "Cough"
    /** §10.x — Sleep-talk piggybacks on YAMNet's generic `Speech` class.
     *  It's far noisier than snore/cough (TV / partner / podcast all
     *  trigger it), so its threshold + min-window count are set higher
     *  than the other two — and the whole class is gated by the user's
     *  `sleepTalkDetectionEnabled` pref before it's even evaluated. */
    const val LABEL_SLEEP_TALK = "Speech"

    /** Per-event-type confidence floors. Cough needs a higher floor because
     *  YAMNet's `Cough` label fires for TV / speaker coughs through the room;
     *  snore is steadier so a lower threshold catches more without flooding.
     *  Sleep-talk is highest because YAMNet's `Speech` class is the most
     *  permissive label in the ontology — anything human-vocal trips it. */
    const val SNORE_CONF_THRESHOLD = 0.3f
    const val COUGH_CONF_THRESHOLD = 0.6f
    const val SLEEP_TALK_CONF_THRESHOLD = 0.75f

    /** Debounce: ≥ N consecutive (or near-consecutive) high-confidence
     *  windows form one event. 2 windows ≈ 1.9 s — enough to reject a
     *  one-off creaky-bed spike or a single spoken word. Sleep-talk uses 3
     *  (≈ 2.9 s) so a single TV sentence doesn't surface as an event. */
    const val MIN_WINDOWS_PER_EVENT = 2
    const val MIN_WINDOWS_PER_SLEEP_TALK_EVENT = 3

    /** §10.x — Pro-tier audio clip parameters. Clips are bounded so the
     *  encoder runs in a known time + bandwidth budget; oversized events
     *  (long snore runs) are truncated to CLIP_MAX_DURATION_MS rather than
     *  filling the rolling buffer. */
    const val CLIP_MAX_DURATION_MS = 10_000L
    const val CLIP_PRE_PAD_MS = 2_000L
    /** Rolling PCM buffer kept by the classifier so a clip can grab audio
     *  from before the event finalised (the aggregator only emits after a
     *  5 s gap-close, so the event's startedAt is up to ~10 s in the past
     *  by emit time). 30 s × 16 kHz × float = 1.92 MB — modest, fits in
     *  the foreground service comfortably. */
    const val CLIP_RING_BUFFER_MS = 30_000L

    /** Bridge gaps within an event. A snoring episode has rhythmic gaps
     *  between snores; 5 s keeps them stitched as one episode rather than
     *  fragmenting into dozens of tiny events. */
    const val EVENT_GAP_MS = 5_000L

    /** YAMNet's window stride in ms. Used to convert "windows" to "duration"
     *  when finalising an event's ended_at. */
    const val WINDOW_DURATION_MS = 960L

    /** Read chunk for AudioRecord. ~1 s of PCM at 16 kHz keeps the read loop
     *  cheap (low wake-up rate) without adding much classification latency
     *  (MediaPipe internally re-windows to 0.96 s anyway). */
    const val AUDIO_READ_SAMPLES = SAMPLE_RATE_HZ
}
