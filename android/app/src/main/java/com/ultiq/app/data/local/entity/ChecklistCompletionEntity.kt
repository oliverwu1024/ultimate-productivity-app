package com.ultiq.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/// §024 — One row per (item, epoch_day) completion for recurring
/// checklist items. The single `lastCompletedEpochDay` column on
/// [ChecklistEntity] could only remember the most-recent tick, so
/// marking Tuesday done overwrote Monday's stamp and Monday's row
/// silently flipped back to "open". This table fixes that by giving
/// every per-day tick its own row.
///
/// Non-recurring rows still use [ChecklistEntity.completed]; this table
/// is only consulted when `recurrenceDaysMask != 0`.
///
/// FK is ON DELETE CASCADE so deleting a checklist item cleans up its
/// completion history automatically.
@Entity(
    tableName = "checklist_completions",
    primaryKeys = ["itemId", "epochDay"],
    foreignKeys = [
        ForeignKey(
            entity = ChecklistEntity::class,
            parentColumns = ["id"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["itemId"], name = "idx_checklist_completions_item")],
)
data class ChecklistCompletionEntity(
    val itemId: String,
    val epochDay: Long,
    /// Epoch millis when the tick happened locally. Used as a tiebreaker
    /// during sync merges; not currently surfaced in any UI.
    val completedAtMs: Long,
)
