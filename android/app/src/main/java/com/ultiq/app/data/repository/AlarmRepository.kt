package com.ultiq.app.data.repository

import android.content.Context
import android.util.Log
import com.ultiq.app.alarm.AlarmRingService
import com.ultiq.app.alarm.WakeAlarmScheduler
import com.ultiq.app.data.local.dao.AlarmDao
import com.ultiq.app.data.local.entity.AlarmEntity
import com.ultiq.app.data.local.entity.AlarmTombstoneEntity
import com.ultiq.app.data.remote.ApiService
import com.ultiq.app.data.remote.dto.toCreateDto
import com.ultiq.app.data.remote.dto.toEntity
import kotlinx.coroutines.flow.Flow
import retrofit2.HttpException
import java.io.IOException
import java.util.UUID

/**
 * Offline-first CRUD for wake-up alarms. Mirrors [SleepRepository] in shape.
 *
 * Every CRUD also reschedules via [WakeAlarmScheduler] so the local
 * `AlarmManager` queue stays in sync with what's in Room.
 *
 * §H1/M4: client-generated UUIDs survive the sync round-trip (server upserts
 * on the client id), so child rows in `alarm_events` keep their FK reference.
 * §M5: deletes write a local tombstone so a server-side row that we already
 * told the server to delete doesn't get pulled back on the next sync.
 *
 * Writes are optimistic local-first (Room + AlarmManager before network),
 * matching ChecklistRepository / SleepRepository. A server 4xx no longer
 * silently aborts the toggle — see v2.16.13 (alarm toggle/save did nothing
 * when the server rejected the payload because the local row never updated).
 * The HTTP body is logged on rejection so the actual cause can be greppped
 * out of logcat; `sync()` retries unsynced rows on the next cycle.
 */
class AlarmRepository(
    private val context: Context,
    private val alarmDao: AlarmDao,
    private val apiService: ApiService,
) {
    private val scheduler: WakeAlarmScheduler get() = WakeAlarmScheduler(context)

    fun getAlarms(): Flow<List<AlarmEntity>> = alarmDao.getAllAlarms()

    suspend fun getAlarm(id: String): AlarmEntity? = alarmDao.getAlarmById(id)

    suspend fun createAlarm(template: AlarmEntity): Result<AlarmEntity> {
        val now = System.currentTimeMillis()
        val withId = template.copy(
            id = template.id.ifBlank { UUID.randomUUID().toString() },
            createdAt = if (template.createdAt == 0L) now else template.createdAt,
            updatedAt = now,
            isSynced = false,
        )

        // Optimistic local write — Room + AlarmManager are the source of truth
        // for whether the phone rings. The Switch in the list and the alarm
        // appearing after Save both depend on this row existing locally, and
        // both must happen even if the server is unreachable or rejects the
        // payload. Server reconciliation happens below; if it fails we keep
        // the row as unsynced and sync() retries on the next cycle.
        alarmDao.insertAlarm(withId)
        scheduler.schedule(withId)

        return try {
            val server = apiService.createAlarm(withId.toCreateDto())
            val entity = server.toEntity()
            alarmDao.insertAlarm(entity)
            Result.success(entity)
        } catch (e: HttpException) {
            Log.w(TAG, "Server rejected create ${withId.id}: HTTP ${e.code()} body=${errorBody(e)}")
            Result.success(withId)
        } catch (e: IOException) {
            Log.d(TAG, "Offline create ${withId.id}; will retry via sync()")
            Result.success(withId)
        } catch (e: Exception) {
            Log.w(TAG, "Unexpected error on create ${withId.id}: ${e.message}")
            Result.success(withId)
        }
    }

    suspend fun updateAlarm(updated: AlarmEntity): Result<AlarmEntity> {
        val now = System.currentTimeMillis()
        val isRinging = AlarmRingService.currentAlarmId.value == updated.id
        val existing = alarmDao.getAlarmById(updated.id)
        val withTimestamp = updated.copy(
            updatedAt = now,
            createdAt = existing?.createdAt ?: updated.createdAt,
            isSynced = false,
        )

        // Optimistic local write — see createAlarm for rationale. Critically,
        // the Switch's `checked` binding only flips when the StateFlow emits
        // a new value, which only happens when Room is written. Pre-network
        // writes here mean a toggle takes effect the instant the user taps,
        // and AlarmManager is rescheduled even if the server later rejects.
        alarmDao.insertAlarm(withTimestamp)
        if (!isRinging) scheduler.schedule(withTimestamp)

        return try {
            val server = apiService.updateAlarm(updated.id, withTimestamp.toCreateDto())
            val entity = server.toEntity()
            alarmDao.insertAlarm(entity)
            Result.success(entity)
        } catch (e: HttpException) {
            Log.w(TAG, "Server rejected update ${updated.id}: HTTP ${e.code()} body=${errorBody(e)}")
            Result.success(withTimestamp)
        } catch (e: IOException) {
            Log.d(TAG, "Offline update ${updated.id}; will retry via sync()")
            Result.success(withTimestamp)
        } catch (e: Exception) {
            Log.w(TAG, "Unexpected error on update ${updated.id}: ${e.message}")
            Result.success(withTimestamp)
        }
    }

    private fun errorBody(e: HttpException): String =
        runCatching { e.response()?.errorBody()?.string().orEmpty() }
            .getOrDefault("<unreadable>")
            .take(500)

    suspend fun setEnabled(id: String, enabled: Boolean): Result<AlarmEntity> {
        val current = alarmDao.getAlarmById(id)
            ?: return Result.failure(IllegalStateException("alarm $id not found"))
        return updateAlarm(current.copy(enabled = enabled, updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteAlarm(id: String): Result<Unit> {
        scheduler.cancel(id)
        // §M5: write the tombstone BEFORE deleting the row so a sync that
        // races with delete will see the tombstone and skip restoring it.
        alarmDao.insertTombstone(AlarmTombstoneEntity(id, System.currentTimeMillis()))
        alarmDao.deleteAlarmById(id)
        return try {
            apiService.deleteAlarm(id)
            alarmDao.deleteTombstone(id) // server confirmed; tombstone done.
            Result.success(Unit)
        } catch (e: HttpException) {
            if (e.code() == 404) {
                alarmDao.deleteTombstone(id) // never existed there; tombstone done.
            }
            // Anything else: keep the tombstone, sync will retry.
            Result.success(Unit)
        } catch (_: IOException) {
            // Offline; tombstone remains, sync will retry.
            Result.success(Unit)
        }
    }

    suspend fun sync() {
        try {
            // 1. Retry server-side deletes for any pending tombstones.
            val tombstones = alarmDao.getAllTombstones()
            for (t in tombstones) {
                try {
                    apiService.deleteAlarm(t.id)
                    alarmDao.deleteTombstone(t.id)
                } catch (e: HttpException) {
                    if (e.code() == 404) alarmDao.deleteTombstone(t.id)
                } catch (_: IOException) {
                    // try next sync
                }
            }

            // 2. Push unsynced local alarms. Since the server now upserts on
            //    the client-supplied id, this is idempotent — a retry after
            //    a lost response won't create a duplicate, and the alarm's
            //    id is preserved (so alarm_events.alarmId references remain
            //    valid).
            val unsynced = alarmDao.getUnsyncedAlarms()
            for (alarm in unsynced) {
                try {
                    val server = apiService.createAlarm(alarm.toCreateDto())
                    val entity = server.toEntity()
                    alarmDao.insertAlarm(entity)
                    scheduler.schedule(entity)
                } catch (e: HttpException) {
                    Log.w(TAG, "Skipped unsynced alarm ${alarm.id}: HTTP ${e.code()}")
                } catch (e: IOException) {
                    Log.w(TAG, "Network error pushing ${alarm.id}: ${e.message}")
                }
            }

            // 3. Push unsynced events. Drop any whose parent alarm row no
            //    longer exists — they can't be reconciled with the server.
            val unsyncedEvents = alarmDao.getUnsyncedEvents()
            for (event in unsyncedEvents) {
                val parent = event.alarmId
                if (parent == null) {
                    alarmDao.markEventSynced(event.id)
                    continue
                }
                try {
                    apiService.logAlarmEvent(parent, event.toCreateDto())
                    alarmDao.markEventSynced(event.id)
                } catch (e: HttpException) {
                    if (e.code() == 404) {
                        // Parent alarm has been deleted server-side; nothing
                        // we can do. Mark event synced so we stop retrying.
                        alarmDao.markEventSynced(event.id)
                    } else {
                        Log.w(TAG, "Server rejected event ${event.id}: HTTP ${e.code()}")
                    }
                } catch (e: IOException) {
                    Log.w(TAG, "Network error pushing event ${event.id}: ${e.message}")
                }
            }

            // 4. Pull server state and reconcile. Filter out anything we've
            //    tombstoned locally — those rows will be retried for delete
            //    in step 1 next time.
            val tombstonedIds = alarmDao.getTombstonedIds().toSet()
            val serverAlarms = apiService.getAlarms().filter { it.id !in tombstonedIds }
            val serverIds = serverAlarms.map { it.id }.toSet()
            val localSyncedIds = alarmDao.getSyncedAlarmIds()
            for (id in localSyncedIds) {
                if (id !in serverIds) {
                    scheduler.cancel(id)
                    alarmDao.deleteAlarmById(id)
                }
            }
            val newEntities = serverAlarms.map { it.toEntity() }
            alarmDao.insertAlarms(newEntities)
            newEntities.forEach { scheduler.schedule(it) }
        } catch (_: IOException) {
            // Offline; sync will retry on the next cycle.
        } catch (e: Exception) {
            Log.w(TAG, "Unexpected sync error: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "AlarmRepository"
    }
}
