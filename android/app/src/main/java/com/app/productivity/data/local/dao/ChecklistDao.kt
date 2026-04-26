package com.app.productivity.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.app.productivity.data.local.entity.ChecklistEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChecklistDao {

    @Query("SELECT * FROM checklist_items ORDER BY dueDateEpochDay ASC, priority DESC, createdAt ASC")
    fun getAll(): Flow<List<ChecklistEntity>>

    @Query(
        "SELECT * FROM checklist_items WHERE dueDateEpochDay = :epochDay " +
            "ORDER BY completed ASC, priority DESC, createdAt ASC"
    )
    fun getByDate(epochDay: Long): Flow<List<ChecklistEntity>>

    @Query(
        "SELECT * FROM checklist_items WHERE dueDateEpochDay = :epochDay AND completed = 0 " +
            "ORDER BY priority DESC, createdAt ASC"
    )
    fun getOpenForDate(epochDay: Long): Flow<List<ChecklistEntity>>

    @Query(
        "SELECT * FROM checklist_items " +
            "WHERE dueDateEpochDay BETWEEN :startEpochDay AND :endEpochDay " +
            "ORDER BY dueDateEpochDay ASC, priority DESC, createdAt ASC"
    )
    fun getInRange(startEpochDay: Long, endEpochDay: Long): Flow<List<ChecklistEntity>>

    @Query("SELECT * FROM checklist_items WHERE id = :id")
    suspend fun getById(id: String): ChecklistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: ChecklistEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<ChecklistEntity>)

    @Delete
    suspend fun delete(item: ChecklistEntity)

    @Query("DELETE FROM checklist_items WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM checklist_items WHERE isSynced = 0")
    suspend fun getUnsynced(): List<ChecklistEntity>

    @Query("SELECT id FROM checklist_items WHERE isSynced = 1")
    suspend fun getSyncedIds(): List<String>

    @Query("UPDATE checklist_items SET isSynced = 1 WHERE id = :id")
    suspend fun markSynced(id: String)

    @Query(
        "UPDATE checklist_items SET completed = 1, completedAt = :completedAt, " +
            "updatedAt = :updatedAt, isSynced = 0 WHERE id = :id"
    )
    suspend fun markCompletedLocally(id: String, completedAt: Long, updatedAt: Long)

    @Query(
        "UPDATE checklist_items SET completed = 0, completedAt = NULL, " +
            "updatedAt = :updatedAt, isSynced = 0 WHERE id = :id"
    )
    suspend fun markIncompleteLocally(id: String, updatedAt: Long)
}
