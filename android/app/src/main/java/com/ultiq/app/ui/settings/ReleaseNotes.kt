package com.ultiq.app.ui.settings

data class ReleaseNote(
    val versionName: String,
    val versionCode: Int,
    val summary: String,
)

object ReleaseNotes {
    val history: List<ReleaseNote> = listOf(
        ReleaseNote(
            versionName = "1.14",
            versionCode = 15,
            summary = "Play Store users no longer see the sideload update banner — " +
                "Play handles updates directly. Tap the version row in Settings to " +
                "view release notes history.",
        ),
        ReleaseNote(
            versionName = "1.13",
            versionCode = 14,
            summary = "Focus tab now shows your running session when you reopen the app. " +
                "Overtime is clearly marked on the lockout popup with a prominent OVERTIME " +
                "pill once you pass your planned focus time.",
        ),
        ReleaseNote(
            versionName = "1.12",
            versionCode = 13,
            summary = "Focus session duration stays accurate when the app runs in the " +
                "background. Removed the break/rest phase in favour of a single focus " +
                "block with an Overtime indicator that counts up past your planned time.",
        ),
        ReleaseNote(
            versionName = "1.10",
            versionCode = 11,
            summary = "Tap a recent focus session to expand its details. Checklist " +
                "carry-over banner brings unfinished items forward from yesterday. " +
                "Mark past calendar events as done.",
        ),
    )
}
