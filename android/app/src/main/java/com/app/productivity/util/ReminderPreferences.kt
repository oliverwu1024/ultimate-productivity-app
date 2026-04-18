package com.app.productivity.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalTime

private val Context.reminderDataStore: DataStore<Preferences> by preferencesDataStore(name = "reminder_prefs")

data class ReminderSettings(
    val bedtimeEnabled: Boolean,
    val bedtimeTime: LocalTime,
    val focusEnabled: Boolean,
    val focusTime: LocalTime,
    val morningSummaryEnabled: Boolean,
    val morningSummaryTime: LocalTime,
) {
    companion object {
        const val BEDTIME_LEAD_MINUTES = 30
    }
}

class ReminderPreferences(private val context: Context) {

    private object Keys {
        val BEDTIME_ENABLED = booleanPreferencesKey("bedtime_enabled")
        val BEDTIME_TIME = stringPreferencesKey("bedtime_time")
        val FOCUS_ENABLED = booleanPreferencesKey("focus_enabled")
        val FOCUS_TIME = stringPreferencesKey("focus_time")
        val SUMMARY_ENABLED = booleanPreferencesKey("summary_enabled")
        val SUMMARY_TIME = stringPreferencesKey("summary_time")
    }

    private val defaults = ReminderSettings(
        bedtimeEnabled = true,
        bedtimeTime = LocalTime.of(22, 0),
        focusEnabled = false,
        focusTime = LocalTime.of(14, 0),
        morningSummaryEnabled = true,
        morningSummaryTime = LocalTime.of(8, 0),
    )

    val settings: Flow<ReminderSettings> = context.reminderDataStore.data.map { prefs ->
        ReminderSettings(
            bedtimeEnabled = prefs[Keys.BEDTIME_ENABLED] ?: defaults.bedtimeEnabled,
            bedtimeTime = prefs[Keys.BEDTIME_TIME]?.let(::parseTime) ?: defaults.bedtimeTime,
            focusEnabled = prefs[Keys.FOCUS_ENABLED] ?: defaults.focusEnabled,
            focusTime = prefs[Keys.FOCUS_TIME]?.let(::parseTime) ?: defaults.focusTime,
            morningSummaryEnabled = prefs[Keys.SUMMARY_ENABLED] ?: defaults.morningSummaryEnabled,
            morningSummaryTime = prefs[Keys.SUMMARY_TIME]?.let(::parseTime) ?: defaults.morningSummaryTime,
        )
    }

    suspend fun snapshot(): ReminderSettings = settings.first()

    suspend fun setBedtimeEnabled(enabled: Boolean) {
        context.reminderDataStore.edit { it[Keys.BEDTIME_ENABLED] = enabled }
    }

    suspend fun setBedtimeTime(time: LocalTime) {
        context.reminderDataStore.edit { it[Keys.BEDTIME_TIME] = formatTime(time) }
    }

    suspend fun setFocusEnabled(enabled: Boolean) {
        context.reminderDataStore.edit { it[Keys.FOCUS_ENABLED] = enabled }
    }

    suspend fun setFocusTime(time: LocalTime) {
        context.reminderDataStore.edit { it[Keys.FOCUS_TIME] = formatTime(time) }
    }

    suspend fun setMorningSummaryEnabled(enabled: Boolean) {
        context.reminderDataStore.edit { it[Keys.SUMMARY_ENABLED] = enabled }
    }

    suspend fun setMorningSummaryTime(time: LocalTime) {
        context.reminderDataStore.edit { it[Keys.SUMMARY_TIME] = formatTime(time) }
    }

    private fun parseTime(value: String): LocalTime = try {
        LocalTime.parse(value)
    } catch (_: Exception) {
        LocalTime.MIDNIGHT
    }

    private fun formatTime(time: LocalTime): String =
        "%02d:%02d".format(time.hour, time.minute)
}
