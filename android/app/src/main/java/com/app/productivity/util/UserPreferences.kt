package com.app.productivity.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
    val defaultBreakDuration: Int,
    val onboardingCompleted: Boolean,
    val lockoutForFocus: Boolean,
    val lockoutForSleep: Boolean,
    val showPickupCountOnLockout: Boolean,
    val allowEndSessionFromLockout: Boolean,
)

class UserPreferences(private val context: Context) {

    private object Keys {
        val TARGET_BEDTIME = stringPreferencesKey("target_bedtime")
        val TARGET_WAKE_TIME = stringPreferencesKey("target_wake_time")
        val DEFAULT_WORK = intPreferencesKey("default_work_duration")
        val DEFAULT_BREAK = intPreferencesKey("default_break_duration")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_completed")
        val LOCKOUT_FOR_FOCUS = booleanPreferencesKey("lockout_for_focus")
        val LOCKOUT_FOR_SLEEP = booleanPreferencesKey("lockout_for_sleep")
        val SHOW_PICKUP_COUNT_ON_LOCKOUT = booleanPreferencesKey("show_pickup_count_on_lockout")
        val ALLOW_END_SESSION_FROM_LOCKOUT = booleanPreferencesKey("allow_end_session_from_lockout")
    }

    private val defaults = UserSettings(
        targetBedtime = LocalTime.of(22, 0),
        targetWakeTime = LocalTime.of(6, 0),
        defaultWorkDuration = 25,
        defaultBreakDuration = 5,
        onboardingCompleted = false,
        lockoutForFocus = true,
        lockoutForSleep = false,
        showPickupCountOnLockout = true,
        allowEndSessionFromLockout = true,
    )

    val settings: Flow<UserSettings> = context.userDataStore.data.map { prefs ->
        UserSettings(
            targetBedtime = prefs[Keys.TARGET_BEDTIME]?.let(::parseTime) ?: defaults.targetBedtime,
            targetWakeTime = prefs[Keys.TARGET_WAKE_TIME]?.let(::parseTime) ?: defaults.targetWakeTime,
            defaultWorkDuration = prefs[Keys.DEFAULT_WORK] ?: defaults.defaultWorkDuration,
            defaultBreakDuration = prefs[Keys.DEFAULT_BREAK] ?: defaults.defaultBreakDuration,
            onboardingCompleted = prefs[Keys.ONBOARDING_DONE] ?: defaults.onboardingCompleted,
            lockoutForFocus = prefs[Keys.LOCKOUT_FOR_FOCUS] ?: defaults.lockoutForFocus,
            lockoutForSleep = prefs[Keys.LOCKOUT_FOR_SLEEP] ?: defaults.lockoutForSleep,
            showPickupCountOnLockout = prefs[Keys.SHOW_PICKUP_COUNT_ON_LOCKOUT] ?: defaults.showPickupCountOnLockout,
            allowEndSessionFromLockout = prefs[Keys.ALLOW_END_SESSION_FROM_LOCKOUT] ?: defaults.allowEndSessionFromLockout,
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
        context.userDataStore.edit { it[Keys.DEFAULT_WORK] = minutes.coerceIn(5, 120) }
    }

    suspend fun setDefaultBreakDuration(minutes: Int) {
        context.userDataStore.edit { it[Keys.DEFAULT_BREAK] = minutes.coerceIn(1, 60) }
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

    private fun parseTime(value: String): LocalTime = try {
        LocalTime.parse(value)
    } catch (_: Exception) {
        LocalTime.MIDNIGHT
    }

    private fun formatTime(time: LocalTime): String =
        "%02d:%02d".format(time.hour, time.minute)
}
