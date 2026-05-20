package com.ultiq.app.alarm.mission

/**
 * Magnitude thresholds (m/s²) above which an accelerometer sample counts
 * as a "shake" for the §8.8 dismiss mission. Tuned for `TYPE_LINEAR_ACCELERATION`,
 * which already subtracts gravity, so a phone sitting flat reads ~0.
 */
enum class ShakeIntensity(val configKey: String, val thresholdMs2: Float) {
    LOW("low", 12f),
    MEDIUM("medium", 18f),
    HIGH("high", 25f);

    companion object {
        fun fromConfig(value: String?): ShakeIntensity = when (value?.lowercase()) {
            LOW.configKey -> LOW
            HIGH.configKey -> HIGH
            else -> MEDIUM
        }
    }
}
