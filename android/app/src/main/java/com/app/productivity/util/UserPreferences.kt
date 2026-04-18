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
)

class UserPreferences(private val context: Context) {

    private object Keys {
        val TARGET_BEDTIME = stringPreferencesKey("target_bedtime")
        val TARGET_WAKE_TIME = stringPreferencesKey("target_wake_time")
        val DEFAULT_WORK = intPreferencesKey("default_work_duration")
        val DEFAULT_BREAK = intPreferencesKey("default_break_duration")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_completed")
    }

    private val defaults = UserSettings(
        targetBedtime = LocalTime.of(22, 0),
        targetWakeTime = LocalTime.of(6, 0),
        defaultWorkDuration = 25,
        defaultBreakDuration = 5,
        onboardingCompleted = false,
    )

    val settings: Flow<UserSettings> = context.userDataStore.data.map { prefs ->
        UserSettings(
            targetBedtime = prefs[Keys.TARGET_BEDTIME]?.let(::parseTime) ?: defaults.targetBedtime,
            targetWakeTime = prefs[Keys.TARGET_WAKE_TIME]?.let(::parseTime) ?: defaults.targetWakeTime,
            defaultWorkDuration = prefs[Keys.DEFAULT_WORK] ?: defaults.defaultWorkDuration,
            defaultBreakDuration = prefs[Keys.DEFAULT_BREAK] ?: defaults.defaultBreakDuration,
            onboardingCompleted = prefs[Keys.ONBOARDING_DONE] ?: defaults.onboardingCompleted,
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

    private fun parseTime(value: String): LocalTime = try {
        LocalTime.parse(value)
    } catch (_: Exception) {
        LocalTime.MIDNIGHT
    }

    private fun formatTime(time: LocalTime): String =
        "%02d:%02d".format(time.hour, time.minute)
}
