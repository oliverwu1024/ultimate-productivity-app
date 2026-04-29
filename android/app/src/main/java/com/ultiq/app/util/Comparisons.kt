package com.ultiq.app.util

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Helpers for "vs past self" framing. Bridge between raw stats and the feeling
 * of getting better — the kind of phrasing that makes Strava sticky.
 */
object Comparisons {

    /** Formats a minute delta with explicit sign. e.g. 18 -> "+18m", -65 -> "-1h 5m". */
    fun formatMinuteDelta(deltaMinutes: Int): String {
        if (deltaMinutes == 0) return "0"
        val a = abs(deltaMinutes)
        val h = a / 60
        val m = a % 60
        val mag = if (h > 0) "${h}h ${m}m" else "${m}m"
        return if (deltaMinutes > 0) "+$mag" else "-$mag"
    }

    /**
     * "+18m vs last week" / "-12m vs last week" / null if there's no history yet.
     * Both sides are in minutes.
     */
    fun vsLastWeekMinutes(thisValue: Int, lastValues: List<Int>): String? {
        if (lastValues.isEmpty()) return null
        val avg = lastValues.average().roundToInt()
        val delta = thisValue - avg
        return "${formatMinuteDelta(delta)} vs last week"
    }

    /** Same, for unit-less doubles (e.g. quality stars). */
    fun vsLastWeekDouble(thisValue: Double, lastValues: List<Double>, suffix: String = ""): String? {
        if (lastValues.isEmpty()) return null
        val avg = lastValues.average()
        val delta = thisValue - avg
        val sign = if (delta > 0.05) "+" else if (delta < -0.05) "−" else "±"
        val mag = String.format("%.1f", abs(delta))
        return "$sign$mag$suffix vs last week"
    }

    /**
     * 1-based rank of `value` within `history + value`. Ties resolve to better rank.
     */
    fun rankAmong(value: Double, history: List<Double>, largerIsBetter: Boolean = true): Int {
        val all = (history + value).let {
            if (largerIsBetter) it.sortedDescending() else it.sorted()
        }
        return all.indexOfFirst { it == value } + 1
    }

    /** "your best this period" / "second-best" / "third-best" / null otherwise. */
    fun rankPhrase(rank: Int, period: String = "this month"): String? = when (rank) {
        1 -> "your best $period"
        2 -> "second-best $period"
        3 -> "third-best $period"
        else -> null
    }

    fun isPersonalBest(value: Double, history: List<Double>, largerIsBetter: Boolean = true): Boolean {
        if (history.isEmpty()) return false
        return if (largerIsBetter) history.all { value > it } else history.all { value < it }
    }
}
