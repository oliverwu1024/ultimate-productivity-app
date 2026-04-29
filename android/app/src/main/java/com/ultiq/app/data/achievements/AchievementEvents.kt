package com.ultiq.app.data.achievements

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Global event bus for newly-earned achievements.
 *
 * `AchievementChecker.checkAndStore()` emits here. ViewModels for screens that
 * trigger saves (Sleep, Sessions) collect and surface celebrations.
 */
object AchievementEvents {
    private val _newlyEarned = MutableSharedFlow<List<AchievementId>>(extraBufferCapacity = 8)
    val newlyEarned: SharedFlow<List<AchievementId>> = _newlyEarned.asSharedFlow()

    suspend fun emit(ids: List<AchievementId>) {
        if (ids.isNotEmpty()) _newlyEarned.emit(ids)
    }
}
