package com.ultiq.app.alarm.mission

enum class MathDifficulty(val configKey: String) {
    EASY("easy"),
    MEDIUM("medium"),
    HARD("hard");

    companion object {
        fun fromConfig(value: String?): MathDifficulty = when (value?.lowercase()) {
            EASY.configKey -> EASY
            HARD.configKey -> HARD
            else -> MEDIUM
        }
    }
}
