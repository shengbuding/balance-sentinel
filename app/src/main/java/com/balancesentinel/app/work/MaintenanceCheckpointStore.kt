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

    /**
     * Compare-and-set completion for a worker that previously read
     * [expected]. The default keeps older test/legacy seams source-compatible;
     * the Room implementation overrides it with an atomic preimage check.
     */
    suspend fun markCompletedIfCurrent(
        expected: MaintenanceCheckpoint,
        date: LocalDate,
        zoneId: ZoneId,
        successAt: Long
    ): Boolean = markCompleted(date, zoneId, successAt)
}

/** Raised when durable maintenance progress cannot be parsed safely. */
class MaintenanceCheckpointCorruptionException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/** Room-backed singleton checkpoint used by production midnight work. */
class RoomMaintenanceCheckpointStore(
    private val context: Context
) : MaintenanceCheckpointStore {
    private val dao
        get() = WalletDatabaseProvider.get(context).maintenanceCheckpointDao()

    override suspend fun read(zoneId: ZoneId): MaintenanceCheckpoint {
        val row = dao.getOrCreate(zoneId.id)
        val lastCompletedDate = row.lastCompletedDate?.let { value ->
            try {
                LocalDate.parse(value)
            } catch (error: Exception) {
                throw MaintenanceCheckpointCorruptionException(
                    "Invalid maintenance checkpoint date",
                    error
                )
            }
        }
        val storedZone = try {
            ZoneId.of(row.zoneId)
        } catch (error: Exception) {
            throw MaintenanceCheckpointCorruptionException(
                "Invalid maintenance checkpoint zone",
                error
            )
        }
        return MaintenanceCheckpoint(
            lastCompletedDate = lastCompletedDate,
            zoneId = storedZone,
            lastSuccessAt = row.lastSuccessAt
        )
    }

    override suspend fun markCompleted(
        date: LocalDate,
        zoneId: ZoneId,
        successAt: Long
    ): Boolean {
        return markCompletedIfCurrent(read(zoneId), date, zoneId, successAt)
    }

    override suspend fun markCompletedIfCurrent(
        expected: MaintenanceCheckpoint,
        date: LocalDate,
        zoneId: ZoneId,
        successAt: Long
    ): Boolean {
        dao.getOrCreate(zoneId.id)
        return dao.advanceAfterCompleteDateIfCurrent(
            date = date.toString(),
            zoneId = zoneId.id,
            successAt = successAt,
            expectedDate = expected.lastCompletedDate?.toString(),
            expectedZoneId = expected.zoneId.id,
            expectedSuccessAt = expected.lastSuccessAt
        ) == 1
    }
}
