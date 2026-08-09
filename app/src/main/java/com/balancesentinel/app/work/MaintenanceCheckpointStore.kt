package com.balancesentinel.app.work

import android.content.Context
import com.balancesentinel.app.data.local.WalletDatabaseProvider
import java.time.LocalDate
import java.time.ZoneId

/** Durable progress for the date-ordered midnight maintenance stream. */
data class MaintenanceCheckpoint(
    val lastCompletedDate: LocalDate?,
    val zoneId: ZoneId,
    val lastSuccessAt: Long?
)

/** Storage contract kept independent from Room so worker behavior is testable. */
interface MaintenanceCheckpointStore {
    suspend fun read(zoneId: ZoneId = ZoneId.systemDefault()): MaintenanceCheckpoint

    /**
     * Marks [date] complete once. Repeating the same or an older date is a no-op
     * and returns false, which keeps worker retries idempotent.
     */
    suspend fun markCompleted(
        date: LocalDate,
        zoneId: ZoneId,
        successAt: Long
    ): Boolean
}

/** Room-backed singleton checkpoint used by production midnight work. */
class RoomMaintenanceCheckpointStore(
    private val context: Context
) : MaintenanceCheckpointStore {
    private val dao
        get() = WalletDatabaseProvider.get(context).maintenanceCheckpointDao()

    override suspend fun read(zoneId: ZoneId): MaintenanceCheckpoint {
        val row = dao.getOrCreate(zoneId.id)
        return MaintenanceCheckpoint(
            lastCompletedDate = row.lastCompletedDate?.let(LocalDate::parse),
            zoneId = runCatching { ZoneId.of(row.zoneId) }.getOrDefault(zoneId),
            lastSuccessAt = row.lastSuccessAt
        )
    }

    override suspend fun markCompleted(
        date: LocalDate,
        zoneId: ZoneId,
        successAt: Long
    ): Boolean {
        val current = dao.getOrCreate(zoneId.id)
        val previous = current.lastCompletedDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (previous != null && !date.isAfter(previous)) return false
        return dao.advanceAfterCompleteDate(
            date = date.toString(),
            zoneId = zoneId.id,
            successAt = successAt
        ) == 1
    }
}

