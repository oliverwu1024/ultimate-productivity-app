package com.ultiq.app.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalTime

private val Context.userDataStore: DataStore<Preferences> by preferencesDataStore(name = "user_prefs")

data class UserSettings(
    val targetBedtime: LocalTime,
    val targetWakeTime: LocalTime,
    val defaultWorkDuration: Int,
    val onboardingCompleted: Boolean,
    val lockoutForFocus: Boolean,
    val lockoutForSleep: Boolean,
    val showPickupCountOnLockout: Boolean,
    val allowEndSessionFromLockout: Boolean,
    /** Minutes the foreground watcher stays silent after "I need my phone"
     *  during a sleep session. Previously shared with focus; split in v1.15
     *  so users can dial them independently. */
    val sleepLockoutGraceMinutes: Int,
    /** Minutes the foreground watcher stays silent during a focus session. */
    val focusLockoutGraceMinutes: Int,
    /** Encoded as `isoYear * 100 + weekOfYear` (e.g. 202617 for week 17 of 2026). */
    val lastPlanningPromptDismissedWeek: Int,
    /** Has the user dismissed the "what sleep tracking does" explainer? */
    val sleepExplainerSeen: Boolean,
    /** Optimal nightly sleep target in minutes. Drives debt + extra calculations. */
    val sleepTargetMinutes: Int,
    /** Has the user dismissed the "Ultiq is also on the web" hint on the dashboard? */
    val webDashboardHintSeen: Boolean,
    /** Has the user dismissed the "connect to internet for the full experience"
     *  one-shot hint on the dashboard? Added 2026-06-06 to set expectations
     *  for users who hit offline-degraded surfaces (clip playback, AI, sync). */
    val internetHintSeen: Boolean,
    /** Per-tab "settings have moved here, configure them below" hints — one-shot. */
    val sleepPrefsHintSeen: Boolean,
    val focusPrefsHintSeen: Boolean,
    val dashboardPrefsHintSeen: Boolean,
    val lockOverlayHintSeen: Boolean,
    /** Epoch day on which the user last dismissed the "unfinished from yesterday"
     *  carry-over banner. If equal to today's epoch day the banner stays hidden. */
    val lastCarryOverDismissedEpochDay: Long,
    /** §9.8 — Insight-id of the anomaly alert the user last dismissed from the
     *  Dashboard card. Empty when nothing's been dismissed. The Dashboard
     *  hides the card whenever this matches the current insight id, so the
     *  same alert doesn't keep reappearing within its 24h window. */
    val dismissedAnomalyInsightId: String,
    /** §10 — Enable on-device snore + cough detection during sleep sessions.
     *  Off by default. Toggling on requires the RECORD_AUDIO runtime
     *  permission; the SleepTrackingService only starts the mic loop when
     *  this flag is true AND the permission is granted at session start. */
    val audioTrackingEnabled: Boolean,
    /** §10.x — Independent on/off for YAMNet's `Speech` class. Sleep talk
     *  triggers far more false positives (TV / partner / podcast) than
     *  snore/cough, so it ships as a separate flag that defaults off — the
     *  classifier ignores the Speech class entirely until the user opts in. */
    val sleepTalkDetectionEnabled: Boolean,
    /** §10.x — Pro-tier master toggle for storing audio clips of detected
     *  events. Off by default. When on, the SleepTrackingService keeps a
     *  rolling PCM buffer and uploads a ~10 s clip per allowed event to the
     *  backend S3 bucket; clips auto-expire after 30 days. */
    val sleepAudioRecordingEnabled: Boolean,
    /** §10.x — Per-event-type filter for the recording master toggle. Each
     *  one is OR-gated with `sleepAudioRecordingEnabled`: a clip is captured
     *  only when both the master and the per-type flag are on. */
    val sleepAudioRecordSnore: Boolean,
    val sleepAudioRecordCough: Boolean,
    val sleepAudioRecordSleepTalk: Boolean,
    /** §10.x — Has the user accepted the recording consent dialog at least
     *  once? Gates the dialog so it only fires the first time the master
     *  toggle is flipped on per device. */
    val sleepAudioRecordingConsentSeen: Boolean,
    /** §13 (i18n) — selected app language as a BCP-47 tag (e.g. "es", "zh-Hans").
     *  Empty = follow the system language. Synced via /auth/me so a new device
     *  restores the choice; the applied locale itself lives in AppCompat's store. */
    val appLanguage: String,
)

class UserPreferences(private val context: Context) {

    private object Keys {
        val TARGET_BEDTIME = stringPreferencesKey("target_bedtime")
        val TARGET_WAKE_TIME = stringPreferencesKey("target_wake_time")
        val DEFAULT_WORK = intPreferencesKey("default_work_duration")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_completed")
        val LOCKOUT_FOR_FOCUS = booleanPreferencesKey("lockout_for_focus")
        val LOCKOUT_FOR_SLEEP = booleanPreferencesKey("lockout_for_sleep")
        val SHOW_PICKUP_COUNT_ON_LOCKOUT = booleanPreferencesKey("show_pickup_count_on_lockout")
        val ALLOW_END_SESSION_FROM_LOCKOUT = booleanPreferencesKey("allow_end_session_from_lockout")
        // Pre-split (≤ v1.14) key. Kept for one-way read so existing installs
        // upgrade smoothly — see settings.fold logic below.
        val LOCKOUT_GRACE_MINUTES = intPreferencesKey("lockout_grace_minutes")
        val SLEEP_LOCKOUT_GRACE_MINUTES = intPreferencesKey("sleep_lockout_grace_minutes")
        val FOCUS_LOCKOUT_GRACE_MINUTES = intPreferencesKey("focus_lockout_grace_minutes")
        val LAST_PLANNING_DISMISSED_WEEK = intPreferencesKey("last_planning_dismissed_week")
        val SLEEP_EXPLAINER_SEEN = booleanPreferencesKey("sleep_explainer_seen")
        val SLEEP_TARGET_MINUTES = intPreferencesKey("sleep_target_minutes")
        val WEB_DASHBOARD_HINT_SEEN = booleanPreferencesKey("web_dashboard_hint_seen")
        val INTERNET_HINT_SEEN = booleanPreferencesKey("internet_hint_seen")
        val LAST_CARRYOVER_DISMISSED_DAY = longPreferencesKey("last_carryover_dismissed_day")
        val SLEEP_PREFS_HINT_SEEN = booleanPreferencesKey("sleep_prefs_hint_seen")
        val FOCUS_PREFS_HINT_SEEN = booleanPreferencesKey("focus_prefs_hint_seen")
        val DASHBOARD_PREFS_HINT_SEEN = booleanPreferencesKey("dashboard_prefs_hint_seen")
        val LOCK_OVERLAY_HINT_SEEN = booleanPreferencesKey("lock_overlay_hint_seen")
        val DISMISSED_ANOMALY_INSIGHT_ID = stringPreferencesKey("dismissed_anomaly_insight_id")
        val AUDIO_TRACKING_ENABLED = booleanPreferencesKey("audio_tracking_enabled")
        val SLEEP_TALK_DETECTION_ENABLED = booleanPreferencesKey("sleep_talk_detection_enabled")
        val SLEEP_AUDIO_RECORDING_ENABLED = booleanPreferencesKey("sleep_audio_recording_enabled")
        val SLEEP_AUDIO_RECORD_SNORE = booleanPreferencesKey("sleep_audio_record_snore")
        val SLEEP_AUDIO_RECORD_COUGH = booleanPreferencesKey("sleep_audio_record_cough")
        val SLEEP_AUDIO_RECORD_SLEEP_TALK = booleanPreferencesKey("sleep_audio_record_sleep_talk")
        val SLEEP_AUDIO_RECORDING_CONSENT_SEEN = booleanPreferencesKey("sleep_audio_recording_consent_seen")
        // §tz Phase B — last device timezone we PATCHed to the server, so the
        // foreground check only re-syncs when the device zone actually changed.
        val LAST_SYNCED_TIMEZONE = stringPreferencesKey("last_synced_timezone")
        val APP_LANGUAGE = stringPreferencesKey("app_language")
    }

    private val defaults = UserSettings(
        targetBedtime = LocalTime.of(22, 0),
        targetWakeTime = LocalTime.of(6, 0),
        defaultWorkDuration = 25,
        onboardingCompleted = false,
        lockoutForFocus = true,
        lockoutForSleep = false,
        showPickupCountOnLockout = true,
        allowEndSessionFromLockout = true,
        sleepLockoutGraceMinutes = 5,
        focusLockoutGraceMinutes = 5,
        lastPlanningPromptDismissedWeek = 0,
        sleepExplainerSeen = false,
        sleepTargetMinutes = 480,
        webDashboardHintSeen = false,
        internetHintSeen = false,
        lastCarryOverDismissedEpochDay = 0L,
        sleepPrefsHintSeen = false,
        focusPrefsHintSeen = false,
        dashboardPrefsHintSeen = false,
        lockOverlayHintSeen = false,
        dismissedAnomalyInsightId = "",
        audioTrackingEnabled = false,
        sleepTalkDetectionEnabled = false,
        sleepAudioRecordingEnabled = false,
        sleepAudioRecordSnore = true,
        sleepAudioRecordCough = true,
        sleepAudioRecordSleepTalk = true,
        sleepAudioRecordingConsentSeen = false,
        appLanguage = "",
    )

    val settings: Flow<UserSettings> = context.userDataStore.data.map { prefs ->
        UserSettings(
            targetBedtime = prefs[Keys.TARGET_BEDTIME]?.let(::parseTime) ?: defaults.targetBedtime,
            targetWakeTime = prefs[Keys.TARGET_WAKE_TIME]?.let(::parseTime) ?: defaults.targetWakeTime,
            defaultWorkDuration = prefs[Keys.DEFAULT_WORK] ?: defaults.defaultWorkDuration,
            onboardingCompleted = prefs[Keys.ONBOARDING_DONE] ?: defaults.onboardingCompleted,
            lockoutForFocus = prefs[Keys.LOCKOUT_FOR_FOCUS] ?: defaults.lockoutForFocus,
            lockoutForSleep = prefs[Keys.LOCKOUT_FOR_SLEEP] ?: defaults.lockoutForSleep,
            showPickupCountOnLockout = prefs[Keys.SHOW_PICKUP_COUNT_ON_LOCKOUT] ?: defaults.showPickupCountOnLockout,
            allowEndSessionFromLockout = prefs[Keys.ALLOW_END_SESSION_FROM_LOCKOUT] ?: defaults.allowEndSessionFromLockout,
            // §M-grace-split: if the new per-mode key is missing but the old
            // shared key is present (existing install upgrading), copy the
            // old value into both. New users get the default 5.
            sleepLockoutGraceMinutes = prefs[Keys.SLEEP_LOCKOUT_GRACE_MINUTES]
                ?: prefs[Keys.LOCKOUT_GRACE_MINUTES]
                ?: defaults.sleepLockoutGraceMinutes,
            focusLockoutGraceMinutes = prefs[Keys.FOCUS_LOCKOUT_GRACE_MINUTES]
                ?: prefs[Keys.LOCKOUT_GRACE_MINUTES]
                ?: defaults.focusLockoutGraceMinutes,
            lastPlanningPromptDismissedWeek = prefs[Keys.LAST_PLANNING_DISMISSED_WEEK] ?: defaults.lastPlanningPromptDismissedWeek,
            sleepExplainerSeen = prefs[Keys.SLEEP_EXPLAINER_SEEN] ?: defaults.sleepExplainerSeen,
            sleepTargetMinutes = prefs[Keys.SLEEP_TARGET_MINUTES] ?: defaults.sleepTargetMinutes,
            webDashboardHintSeen = prefs[Keys.WEB_DASHBOARD_HINT_SEEN] ?: defaults.webDashboardHintSeen,
            internetHintSeen = prefs[Keys.INTERNET_HINT_SEEN] ?: defaults.internetHintSeen,
            lastCarryOverDismissedEpochDay = prefs[Keys.LAST_CARRYOVER_DISMISSED_DAY] ?: defaults.lastCarryOverDismissedEpochDay,
            sleepPrefsHintSeen = prefs[Keys.SLEEP_PREFS_HINT_SEEN] ?: defaults.sleepPrefsHintSeen,
            focusPrefsHintSeen = prefs[Keys.FOCUS_PREFS_HINT_SEEN] ?: defaults.focusPrefsHintSeen,
            dashboardPrefsHintSeen = prefs[Keys.DASHBOARD_PREFS_HINT_SEEN] ?: defaults.dashboardPrefsHintSeen,
            lockOverlayHintSeen = prefs[Keys.LOCK_OVERLAY_HINT_SEEN] ?: defaults.lockOverlayHintSeen,
            dismissedAnomalyInsightId = prefs[Keys.DISMISSED_ANOMALY_INSIGHT_ID] ?: defaults.dismissedAnomalyInsightId,
            audioTrackingEnabled = prefs[Keys.AUDIO_TRACKING_ENABLED] ?: defaults.audioTrackingEnabled,
            sleepTalkDetectionEnabled = prefs[Keys.SLEEP_TALK_DETECTION_ENABLED] ?: defaults.sleepTalkDetectionEnabled,
            sleepAudioRecordingEnabled = prefs[Keys.SLEEP_AUDIO_RECORDING_ENABLED] ?: defaults.sleepAudioRecordingEnabled,
            sleepAudioRecordSnore = prefs[Keys.SLEEP_AUDIO_RECORD_SNORE] ?: defaults.sleepAudioRecordSnore,
            sleepAudioRecordCough = prefs[Keys.SLEEP_AUDIO_RECORD_COUGH] ?: defaults.sleepAudioRecordCough,
            sleepAudioRecordSleepTalk = prefs[Keys.SLEEP_AUDIO_RECORD_SLEEP_TALK] ?: defaults.sleepAudioRecordSleepTalk,
            sleepAudioRecordingConsentSeen = prefs[Keys.SLEEP_AUDIO_RECORDING_CONSENT_SEEN] ?: defaults.sleepAudioRecordingConsentSeen,
            appLanguage = prefs[Keys.APP_LANGUAGE] ?: defaults.appLanguage,
        )
    }

    suspend fun snapshot(): UserSettings = settings.first()

    suspend fun setTargetBedtime(time: LocalTime) {
        context.userDataStore.edit { it[Keys.TARGET_BEDTIME] = formatTime(time) }
    }

    suspend fun setTargetWakeTime(time: LocalTime) {
        context.userDataStore.edit { it[Keys.TARGET_WAKE_TIME] = formatTime(time) }
    }

    suspend fun setDefaultWorkDuration(minutes: Int) {
        context.userDataStore.edit { it[Keys.DEFAULT_WORK] = minutes.coerceIn(5, 240) }
    }

    suspend fun setOnboardingCompleted(done: Boolean) {
        context.userDataStore.edit { it[Keys.ONBOARDING_DONE] = done }
    }

    suspend fun setLockoutForFocus(enabled: Boolean) {
        context.userDataStore.edit { it[Keys.LOCKOUT_FOR_FOCUS] = enabled }
    }

    suspend fun setLockoutForSleep(enabled: Boolean) {
        context.userDataStore.edit { it[Keys.LOCKOUT_FOR_SLEEP] = enabled }
    }

    suspend fun setShowPickupCountOnLockout(enabled: Boolean) {
        context.userDataStore.edit { it[Keys.SHOW_PICKUP_COUNT_ON_LOCKOUT] = enabled }
    }

    suspend fun setAllowEndSessionFromLockout(enabled: Boolean) {
        context.userDataStore.edit { it[Keys.ALLOW_END_SESSION_FROM_LOCKOUT] = enabled }
    }

    suspend fun setSleepLockoutGraceMinutes(minutes: Int) {
        context.userDataStore.edit {
            it[Keys.SLEEP_LOCKOUT_GRACE_MINUTES] = minutes.coerceIn(1, 10)
        }
    }

    suspend fun setFocusLockoutGraceMinutes(minutes: Int) {
        context.userDataStore.edit {
            it[Keys.FOCUS_LOCKOUT_GRACE_MINUTES] = minutes.coerceIn(1, 10)
        }
    }

    suspend fun setLastPlanningPromptDismissedWeek(encodedYearWeek: Int) {
        context.userDataStore.edit { it[Keys.LAST_PLANNING_DISMISSED_WEEK] = encodedYearWeek }
    }

    suspend fun setSleepExplainerSeen(seen: Boolean) {
        context.userDataStore.edit { it[Keys.SLEEP_EXPLAINER_SEEN] = seen }
    }

    suspend fun setSleepTargetMinutes(minutes: Int) {
        context.userDataStore.edit { it[Keys.SLEEP_TARGET_MINUTES] = minutes.coerceIn(180, 900) }
    }

    suspend fun setWebDashboardHintSeen(seen: Boolean) {
        context.userDataStore.edit { it[Keys.WEB_DASHBOARD_HINT_SEEN] = seen }
    }

    suspend fun setInternetHintSeen(seen: Boolean) {
        context.userDataStore.edit { it[Keys.INTERNET_HINT_SEEN] = seen }
    }

    suspend fun setLastCarryOverDismissedEpochDay(epochDay: Long) {
        context.userDataStore.edit { it[Keys.LAST_CARRYOVER_DISMISSED_DAY] = epochDay }
    }

    suspend fun setSleepPrefsHintSeen(seen: Boolean) {
        context.userDataStore.edit { it[Keys.SLEEP_PREFS_HINT_SEEN] = seen }
    }

    suspend fun setFocusPrefsHintSeen(seen: Boolean) {
        context.userDataStore.edit { it[Keys.FOCUS_PREFS_HINT_SEEN] = seen }
    }

    suspend fun setDashboardPrefsHintSeen(seen: Boolean) {
        context.userDataStore.edit { it[Keys.DASHBOARD_PREFS_HINT_SEEN] = seen }
    }

    suspend fun setLockOverlayHintSeen(seen: Boolean) {
        context.userDataStore.edit { it[Keys.LOCK_OVERLAY_HINT_SEEN] = seen }
    }

    suspend fun setDismissedAnomalyInsightId(id: String) {
        context.userDataStore.edit { it[Keys.DISMISSED_ANOMALY_INSIGHT_ID] = id }
    }

    suspend fun setAudioTrackingEnabled(enabled: Boolean) {
        context.userDataStore.edit { it[Keys.AUDIO_TRACKING_ENABLED] = enabled }
    }

    suspend fun setSleepTalkDetectionEnabled(enabled: Boolean) {
        context.userDataStore.edit { it[Keys.SLEEP_TALK_DETECTION_ENABLED] = enabled }
    }

    suspend fun setSleepAudioRecordingEnabled(enabled: Boolean) {
        context.userDataStore.edit { it[Keys.SLEEP_AUDIO_RECORDING_ENABLED] = enabled }
    }

    suspend fun setSleepAudioRecordSnore(enabled: Boolean) {
        context.userDataStore.edit { it[Keys.SLEEP_AUDIO_RECORD_SNORE] = enabled }
    }

    suspend fun setSleepAudioRecordCough(enabled: Boolean) {
        context.userDataStore.edit { it[Keys.SLEEP_AUDIO_RECORD_COUGH] = enabled }
    }

    suspend fun setSleepAudioRecordSleepTalk(enabled: Boolean) {
        context.userDataStore.edit { it[Keys.SLEEP_AUDIO_RECORD_SLEEP_TALK] = enabled }
    }

    suspend fun setSleepAudioRecordingConsentSeen(seen: Boolean) {
        context.userDataStore.edit { it[Keys.SLEEP_AUDIO_RECORDING_CONSENT_SEEN] = seen }
    }

    /** §13 (i18n) — persist the selected app language (BCP-47 tag; "" = system). */
    suspend fun setAppLanguage(tag: String) {
        context.userDataStore.edit { it[Keys.APP_LANGUAGE] = tag }
    }

    /** §tz Phase B — the device timezone last synced to the server (null until
     *  the first sync). Read/written directly (not via UserSettings) since it's
     *  app-plumbing, not a user-facing setting. */
    suspend fun getLastSyncedTimezone(): String? =
        context.userDataStore.data.first()[Keys.LAST_SYNCED_TIMEZONE]

    suspend fun setLastSyncedTimezone(tz: String) {
        context.userDataStore.edit { it[Keys.LAST_SYNCED_TIMEZONE] = tz }
    }

    /** Wipe every preference back to defaults — used when deleting the account. */
    suspend fun clearAll() {
        context.userDataStore.edit { it.clear() }
    }

    /**
     * §sync-prefs: apply a server-provided preferences blob into local
     * DataStore. Each key is optional; only keys that are present override
     * the local value. Called on login + on every successful background sync
     * so a fresh install on a new device picks up the user's existing config.
     *
     * Wire-format keys mirror the DataStore key names (snake_case).
     */
    suspend fun applyServerPreferences(server: com.google.gson.JsonObject) {
        context.userDataStore.edit { prefs ->
            server.get("target_bedtime")?.takeIf { !it.isJsonNull }?.asString?.let {
                prefs[Keys.TARGET_BEDTIME] = it
            }
            server.get("target_wake_time")?.takeIf { !it.isJsonNull }?.asString?.let {
                prefs[Keys.TARGET_WAKE_TIME] = it
            }
            server.get("default_work_duration")?.takeIf { !it.isJsonNull }?.asInt?.let {
                prefs[Keys.DEFAULT_WORK] = it.coerceIn(5, 240)
            }
            server.get("lockout_for_focus")?.takeIf { !it.isJsonNull }?.asBoolean?.let {
                prefs[Keys.LOCKOUT_FOR_FOCUS] = it
            }
            server.get("lockout_for_sleep")?.takeIf { !it.isJsonNull }?.asBoolean?.let {
                prefs[Keys.LOCKOUT_FOR_SLEEP] = it
            }
            server.get("show_pickup_count_on_lockout")?.takeIf { !it.isJsonNull }?.asBoolean?.let {
                prefs[Keys.SHOW_PICKUP_COUNT_ON_LOCKOUT] = it
            }
            server.get("allow_end_session_from_lockout")?.takeIf { !it.isJsonNull }?.asBoolean?.let {
                prefs[Keys.ALLOW_END_SESSION_FROM_LOCKOUT] = it
            }
            server.get("sleep_lockout_grace_minutes")?.takeIf { !it.isJsonNull }?.asInt?.let {
                prefs[Keys.SLEEP_LOCKOUT_GRACE_MINUTES] = it.coerceIn(1, 10)
            }
            server.get("focus_lockout_grace_minutes")?.takeIf { !it.isJsonNull }?.asInt?.let {
                prefs[Keys.FOCUS_LOCKOUT_GRACE_MINUTES] = it.coerceIn(1, 10)
            }
            server.get("sleep_target_minutes")?.takeIf { !it.isJsonNull }?.asInt?.let {
                prefs[Keys.SLEEP_TARGET_MINUTES] = it.coerceIn(180, 900)
            }
            server.get("audio_tracking_enabled")?.takeIf { !it.isJsonNull }?.asBoolean?.let {
                prefs[Keys.AUDIO_TRACKING_ENABLED] = it
            }
            server.get("sleep_talk_detection_enabled")?.takeIf { !it.isJsonNull }?.asBoolean?.let {
                prefs[Keys.SLEEP_TALK_DETECTION_ENABLED] = it
            }
            server.get("sleep_audio_recording_enabled")?.takeIf { !it.isJsonNull }?.asBoolean?.let {
                prefs[Keys.SLEEP_AUDIO_RECORDING_ENABLED] = it
            }
            server.get("sleep_audio_record_snore")?.takeIf { !it.isJsonNull }?.asBoolean?.let {
                prefs[Keys.SLEEP_AUDIO_RECORD_SNORE] = it
            }
            server.get("sleep_audio_record_cough")?.takeIf { !it.isJsonNull }?.asBoolean?.let {
                prefs[Keys.SLEEP_AUDIO_RECORD_COUGH] = it
            }
            server.get("sleep_audio_record_sleep_talk")?.takeIf { !it.isJsonNull }?.asBoolean?.let {
                prefs[Keys.SLEEP_AUDIO_RECORD_SLEEP_TALK] = it
            }
            server.get("app_language")?.takeIf { !it.isJsonNull }?.asString?.let {
                prefs[Keys.APP_LANGUAGE] = it
            }
        }
    }

    private fun parseTime(value: String): LocalTime = try {
        LocalTime.parse(value)
    } catch (_: Exception) {
        LocalTime.MIDNIGHT
    }

    private fun formatTime(time: LocalTime): String =
        "%02d:%02d".format(time.hour, time.minute)
}
