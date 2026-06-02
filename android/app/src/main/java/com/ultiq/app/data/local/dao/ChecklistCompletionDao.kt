package com.ultiq.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ultiq.app.data.local.entity.ChecklistCompletionEntity
import kotlinx.coroutines.flow.Flow

/// §024 — DAO for the per-day completion log on recurring checklist
/// items. See [ChecklistCompletionEntity] for the schema rationale.
@Dao
interface ChecklistCompletionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(completion: ChecklistCompletionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(completions: List<ChecklistCompletionEntity>)

    @Query("DELETE FROM checklist_completions WHERE itemId = :itemId AND epochDay = :epochDay")
    suspend fun delete(itemId: String, epochDay: Long)

    @Query("DELETE FROM checklist_completions WHERE itemId = :itemId")
    suspend fun deleteAllForItem(itemId: String)

    /// Delete the item's stale rows — i.e. rows whose epochDay is NOT in
    /// [keepDays]. Used by the sync path to converge the local mirror on
    /// the server's `completed_epoch_days` set without first wiping the
    /// whole item (which would briefly empty the table and flip the row
    /// from "done today" → "open" → "done today" in the partitioned UI).
    @Query(
        "DELETE FROM checklist_completions " +
            "WHERE itemId = :itemId AND epochDay NOT IN (:keepDays)"
    )
    suspend fun deleteForItemExcept(itemId: String, keepDays: List<Long>)

    @Query("SELECT epochDay FROM checklist_completions WHERE itemId = :itemId")
    suspend fun getEpochDaysForItem(itemId: String): List<Long>

    /** Stream all (item, day) tuples so any UI listening to checklist data
     *  re-renders the moment a tick is added/removed locally. */
    @Query("SELECT * FROM checklist_completions")
    fun observeAll(): Flow<List<ChecklistCompletionEntity>>

    /** True when the given (item, day) pair has been ticked. Suspend
     *  variant for callsites that just need a yes/no answer. */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM checklist_completions " +
            "WHERE itemId = :itemId AND epochDay = :epochDay)"
    )
    suspend fun isCompleted(itemId: String, epochDay: Long): Boolean
}
