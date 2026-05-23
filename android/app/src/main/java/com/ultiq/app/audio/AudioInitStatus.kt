package com.ultiq.app.audio

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * §10.x (v2.11.4) — Surfaces the last audio-tracking init outcome in-app.
 *
 * Background: release-build audio init can silently fail in ways that
 * `adb logcat` would normally diagnose, but we can't always assume the user
 * has a USB cable handy. This single shared status flow gets written at
 * every step of the init pipeline (by [com.ultiq.app.service.SleepTrackingService]
 * and [SleepAudioClassifier]); the Sleep Preferences screen renders it as
 * a small subtitle under the audio toggle so the user can read the failure
 * reason on the phone itself and screenshot it back to us.
 */
object AudioInitStatus {
    val current: MutableStateFlow<String> =
        MutableStateFlow("Audio tracking not yet attempted this session.")

    /**
     * Write a single status line. Also emits the same text at Log.i so it
     * shows up in adb logcat when one IS available.
     */
    fun set(text: String) {
        Log.i("AudioInitStatus", text)
        current.value = text
    }

    /** Convenience: format an exception as "ClassName: message" so the
     *  status line is one short, screenshot-friendly piece of text. */
    fun setError(stage: String, t: Throwable) {
        set("$stage failed → ${t::class.java.simpleName}: ${t.message ?: "(no message)"}")
    }
}
