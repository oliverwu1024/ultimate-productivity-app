package com.ultiq.app.ui.settings

data class ReleaseNote(
    val versionName: String,
    val versionCode: Int,
    val summary: String,
)

object ReleaseNotes {
    val history: List<ReleaseNote> = listOf(
        ReleaseNote(
            versionName = "2.11.2",
            versionCode = 41,
            summary = "Hotfix: the app could crash when starting a sleep session if " +
                "audio tracking had been enabled and MediaPipe's classifier classes had " +
                "been stripped by R8 — an Error (not an Exception) propagated up through " +
                "the launched coroutine to the default uncaught-exception handler. Audio " +
                "init paths now catch every Throwable, the service's coroutine scope has " +
                "a backstop CoroutineExceptionHandler, and a failed classifier start just " +
                "disables audio tracking for that session instead of killing the app.",
        ),
        ReleaseNote(
            versionName = "2.11.1",
            versionCode = 40,
            summary = "Fixes on-device snore + cough detection in the release build. " +
                "R8 minification was stripping the MediaPipe result classes that the " +
                "YAMNet pipeline needs at runtime — events fired in dogfood (debug build) " +
                "but never reached the aggregator in production. Wider ProGuard keep rules " +
                "preserve MediaPipe + AutoValue + TFLite + protobuf machinery so release " +
                "builds now match debug behaviour.",
        ),
        ReleaseNote(
            versionName = "2.11.0",
            versionCode = 39,
            summary = "On-device snore + cough detection during sleep — toggle it on " +
                "in Sleep Preferences. Audio is analysed locally and never uploaded or " +
                "stored. End Sleep dialog now shows the per-pickup + snore + cough timeline " +
                "and offers a one-tap AI sleep quality rating (1–5 with a one-line reason). " +
                "Tap any past sleep record on the Sleep tab to expand the full timeline. " +
                "Last-night sounds card appears on the Sleep tab above the stats row.",
        ),
        ReleaseNote(
            versionName = "2.10.0",
            versionCode = 31,
            summary = "Coach can now log your sleep and set alarms via chat. The coach " +
                "mascot greets you on the Dashboard above the fold. Alarms full-screen " +
                "takeover when the phone is in active use. Plus a stack of polish in " +
                "2.10.1–2.10.7 — Monday-start weeks, unified swipe-to-delete across tabs, " +
                "sleep grid, week-unification, and a dozen smaller fixes from two audit waves.",
        ),
        ReleaseNote(
            versionName = "2.9.0",
            versionCode = 27,
            summary = "Coach can now use tools — read your data and propose calendar / " +
                "checklist writes that you confirm with one tap. Followed by 2.9.1–2.9.3 " +
                "polish: optimistic message insert, inline Markdown, smoother bubble " +
                "animations, and the mascot avatar on the Dashboard.",
        ),
        ReleaseNote(
            versionName = "2.8.0",
            versionCode = 26,
            summary = "Phase 9 close-out: in-app Coach chat for sleep + focus + planning, " +
                "and daily anomaly detection that sends a push when slow-burn patterns " +
                "appear (5+ short nights, focus collapse, sleep-window phone spirals).",
        ),
        ReleaseNote(
            versionName = "2.7",
            versionCode = 22,
            summary = "Checklist gets edit-while-completed, a recurring-task checkbox, an " +
                "include-today prompt, and full web-dashboard parity. Mobile session " +
                "debrief prompt after focus sessions feeds the weekly AI insight.",
        ),
        ReleaseNote(
            versionName = "2.6",
            versionCode = 21,
            summary = "One-tap in-app updates — tap the update banner and the new version " +
                "downloads + installs from the system prompt directly. Cuts the update flow " +
                "from five steps to two.",
        ),
        ReleaseNote(
            versionName = "2.5",
            versionCode = 20,
            summary = "Sleep tab remembers which sub-tab (Sleep / Alarms) you came from " +
                "when you back out of the alarm editor. Bedtime changes apply the same " +
                "night without needing the reminder to fire first. Alarm-editor Cancel " +
                "no longer leaves a disabled draft row behind.",
        ),
        ReleaseNote(
            versionName = "2.4",
            versionCode = 19,
            summary = "Math mission text auto-shrinks to fit. Math problem count is " +
                "configurable (3 / 5 / 10 / 20). Alarm editor replaces the checkmark with " +
                "explicit Set alarm / Cancel buttons. Checklist gains three schedule modes: " +
                "Today, Repeat days-of-week, or By due date.",
        ),
        ReleaseNote(
            versionName = "2.3",
            versionCode = 18,
            summary = "Removed the debug 'schedule test alarm' card from the " +
                "Alarms section in release builds. Same code in debug builds " +
                "keeps the dev shortcut.",
        ),
        ReleaseNote(
            versionName = "2.2",
            versionCode = 17,
            summary = "Quick-pick chips on the work-duration and sleep-target " +
                "steppers — tap 25m, 8h, etc. instead of mashing +/− to reach " +
                "common values. Applies to Focus Preferences, Sleep Preferences, " +
                "and the per-session work duration on the Focus tab.",
        ),
        ReleaseNote(
            versionName = "2.1",
            versionCode = 16,
            summary = "Wake-up alarms with dismiss missions: solve a math problem, " +
                "shake the phone, or photograph a fixed scene to silence — so " +
                "you actually get out of bed. Sleep and Focus settings moved " +
                "to their own tabs (tap 'Preferences' top right). Sleep tab " +
                "now has Sleep / Alarms sub-tabs. Settings sync across devices, " +
                "so a reinstall doesn't wipe your bedtime, wake time, or focus " +
                "defaults.",
        ),
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
