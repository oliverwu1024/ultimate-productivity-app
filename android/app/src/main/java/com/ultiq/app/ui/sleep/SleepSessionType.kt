package com.ultiq.app.ui.sleep

import java.time.Instant
import java.time.ZoneId

/**
 * §last-night — Heuristic that pre-selects the Night/Nap toggle (and decides
 * whether to even surface it) at sleep-save time. A session "looks like a nap"
 * only when it BOTH started during the day AND was short — that pairing catches
 * afternoon naps and quick test sessions while leaving genuine overnight sleep
 * (early risers, after-midnight bedtimes, shift workers' long daytime sleeps)
 * classified as a night. It only sets the default; the user always confirms.
 */
fun looksLikeNap(
    bedtimeEpochMs: Long,
    wakeEpochMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): Boolean {
    val durationMin = (wakeEpochMs - bedtimeEpochMs) / 60_000L
    val startHour = Instant.ofEpochMilli(bedtimeEpochMs).atZone(zone).hour
    val startedInDaytime = startHour in 8..18
    return durationMin in 1 until 180 && startedInDaytime
}
