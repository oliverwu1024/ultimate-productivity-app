package com.ultiq.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ultiq.app.data.local.entity.AchievementEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AchievementDao {

    @Query("SELECT * FROM achievements ORDER BY earnedAt DESC")
    fun getAll(): Flow<List<AchievementEntity>>

    @Query("SELECT * FROM achievements")
    suspend fun getAllSnapshot(): List<AchievementEntity>

    @Query("SELECT id FROM achievements")
    suspend fun getEarnedIds(): List<String>

    // IGNORE preserves earnedAt for already-earned achievements
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(achievement: AchievementEntity)
}
