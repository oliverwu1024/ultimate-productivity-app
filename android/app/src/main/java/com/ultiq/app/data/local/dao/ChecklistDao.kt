package com.ultiq.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ultiq.app.data.local.entity.ChecklistEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChecklistDao {

    @Query("SELECT * FROM checklist_items ORDER BY dueDateEpochDay ASC, priority DESC, createdAt ASC")
    fun getAll(): Flow<List<ChecklistEntity>>

    /**
     * Items to display on [epochDay]. Three modes coexist:
     *  1. One-off (recurrenceDaysMask = 0, showUntilDue = 0): match on dueDate.
     *  2. Show-until-due (recurrenceDaysMask = 0, showUntilDue = 1): visible
     *     every day from createdEpochDay through dueDate, until completed.
     *     A completed item still shows on dueDate itself so the user can see
     *     it under the day it was due — but not on later days where the row
     *     would otherwise have been carried.
     *  3. Recurring (recurrenceDaysMask != 0): bit for [dayOfWeekBit] set,
     *     and dueDate already reached (acts as a "start date"). The completed
     *     flag is ignored here — per-day done state lives in lastCompletedEpochDay
     *     and the ViewModel partitions open vs done.
     */
    @Query(
        "SELECT * FROM checklist_items WHERE " +
            "(recurrenceDaysMask = 0 AND showUntilDue = 0 AND dueDateEpochDay = :epochDay) " +
            "OR (recurrenceDaysMask = 0 AND showUntilDue = 1 AND dueDateEpochDay >= :epochDay " +
            "    AND (createdAt / 86400000) <= :epochDay " +
            "    AND (completed = 0 OR dueDateEpochDay = :epochDay)) " +
            "OR (recurrenceDaysMask != 0 AND (recurrenceDaysMask & :dayOfWeekBit) != 0 " +
            "    AND dueDateEpochDay <= :epochDay) " +
            "ORDER BY completed ASC, priority DESC, createdAt ASC"
    )
    fun getByDate(epochDay: Long, dayOfWeekBit: Int): Flow<List<ChecklistEntity>>

    @Query(
        "SELECT * FROM checklist_items WHERE dueDateEpochDay = :epochDay " +
            "ORDER BY completed ASC, priority DESC, createdAt ASC"
    )
    fun getByDueDateExact(epochDay: Long): Flow<List<ChecklistEntity>>

    /**
     * §fix-carryover-recurring — candidates for the "bring forward" banner.
     *
     * Two cases that qualify:
     *   1. Pure one-off open on `epochDay`: dueDate matches, no recurrence,
     *      no show-until-due, completed = 0.
     *   2. Recurring whose mask covers `epochDay`'s weekday but NOT today's
     *      weekday, and that wasn't ticked for `epochDay`. If today's mask
     *      already includes the row, it'll show on today's list naturally
     *      so we skip it here to avoid a redundant nudge.
     *
     * §024 — recurring "wasn't ticked for epochDay" check moved from the
     * old single `lastCompletedEpochDay` column to a NOT EXISTS against
     * checklist_completions, so a tick on today no longer hides yesterday
     * from the carry-forward list.
     *
     * `showUntilDue` items deliberately never appear — they persist across
     * days under their own rules, so "carry forward" doesn't apply.
     */
    @Query(
        "SELECT * FROM checklist_items WHERE " +
            "(recurrenceDaysMask = 0 AND showUntilDue = 0 " +
            " AND dueDateEpochDay = :epochDay AND completed = 0) " +
            "OR " +
            "(recurrenceDaysMask != 0 " +
            " AND (recurrenceDaysMask & :yesterdayBit) != 0 " +
            " AND (recurrenceDaysMask & :todayBit) = 0 " +
            " AND NOT EXISTS (SELECT 1 FROM checklist_completions " +
            "                 WHERE itemId = checklist_items.id " +
            "                   AND epochDay = :epochDay) " +
            " AND dueDateEpochDay <= :epochDay) " +
            "ORDER BY priority DESC, createdAt ASC"
    )
    fun getCarryoverCandidates(
        epochDay: Long,
        yesterdayBit: Int,
        todayBit: Int,
    ): Flow<List<ChecklistEntity>>

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

    /** Recurring-item completion stamp. Setting to null on the same day re-opens it. */
    @Query(
        "UPDATE checklist_items SET lastCompletedEpochDay = :epochDay, " +
            "updatedAt = :updatedAt, isSynced = 0 WHERE id = :id"
    )
    suspend fun setLastCompletedEpochDay(id: String, epochDay: Long?, updatedAt: Long)
}
