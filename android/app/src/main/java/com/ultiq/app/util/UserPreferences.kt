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
        val LAST_CARRYOVER_DISMISSED_DAY = longPreferencesKey("last_carryover_dismissed_day")
        val SLEEP_PREFS_HINT_SEEN = booleanPreferencesKey("sleep_prefs_hint_seen")
        val FOCUS_PREFS_HINT_SEEN = booleanPreferencesKey("focus_prefs_hint_seen")
        val DASHBOARD_PREFS_HINT_SEEN = booleanPreferencesKey("dashboard_prefs_hint_seen")
        val LOCK_OVERLAY_HINT_SEEN = booleanPreferencesKey("lock_overlay_hint_seen")
        val DISMISSED_ANOMALY_INSIGHT_ID = stringPreferencesKey("dismissed_anomaly_insight_id")
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
        lastCarryOverDismissedEpochDay = 0L,
        sleepPrefsHintSeen = false,
        focusPrefsHintSeen = false,
        dashboardPrefsHintSeen = false,
        lockOverlayHintSeen = false,
        dismissedAnomalyInsightId = "",
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
            lastCarryOverDismissedEpochDay = prefs[Keys.LAST_CARRYOVER_DISMISSED_DAY] ?: defaults.lastCarryOverDismissedEpochDay,
            sleepPrefsHintSeen = prefs[Keys.SLEEP_PREFS_HINT_SEEN] ?: defaults.sleepPrefsHintSeen,
            focusPrefsHintSeen = prefs[Keys.FOCUS_PREFS_HINT_SEEN] ?: defaults.focusPrefsHintSeen,
            dashboardPrefsHintSeen = prefs[Keys.DASHBOARD_PREFS_HINT_SEEN] ?: defaults.dashboardPrefsHintSeen,
            lockOverlayHintSeen = prefs[Keys.LOCK_OVERLAY_HINT_SEEN] ?: defaults.lockOverlayHintSeen,
            dismissedAnomalyInsightId = prefs[Keys.DISMISSED_ANOMALY_INSIGHT_ID] ?: defaults.dismissedAnomalyInsightId,
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
