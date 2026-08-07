package com.balancesentinel.app.data.repository

import android.content.Context
import com.balancesentinel.app.data.model.RefreshLogEntry
import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.local.log.EventLogEntity
import com.balancesentinel.app.data.local.log.EventLogType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface EventLogRepository {
    suspend fun append(entries: List<RefreshLogEntry>)
    suspend fun newest(limit: Int): List<RefreshLogEntry>
}

/** Compatibility seam for callers still backed by the legacy event-log store. */
class LegacyEventLogRepository(
    context: Context
) : EventLogRepository {
    private val appContext = context.applicationContext

    override suspend fun append(entries: List<RefreshLogEntry>) {
        withContext(Dispatchers.IO) { RefreshLogStore.addEntriesStrict(appContext, entries) }
    }

    override suspend fun newest(limit: Int): List<RefreshLogEntry> = withContext(Dispatchers.IO) {
        RefreshLogStore.getEntries(appContext)
            .sortedWith(compareByDescending<RefreshLogEntry> { it.timestamp }.thenByDescending { it.id })
            .take(limit)
    }
}

class RoomEventLogRepository(
    private val database: WalletDatabase
) : EventLogRepository {
    override suspend fun append(entries: List<RefreshLogEntry>) {
        entries.chunked(HistoryRepository.INSERT_CHUNK_SIZE).forEach { chunk ->
            database.eventLogDao().insertAll(chunk.map(::toEntity))
        }
    }

    override suspend fun newest(limit: Int): List<RefreshLogEntry> =
        database.eventLogDao().newest(limit.coerceAtLeast(0)).map(::toDomain)
}

private fun toEntity(entry: RefreshLogEntry) = EventLogEntity(
    id = entry.id,
    eventType = EventLogType.valueOf(entry.type.name),
    totalBalanceText = entry.totalBalance,
    currencyText = entry.currency,
    isAvailable = entry.isAvailable,
    grantedBalanceText = entry.grantedBalance,
    toppedUpBalanceText = entry.toppedUpBalance,
    recordedAt = entry.timestamp,
    message = entry.message,
    intervalSeconds = entry.intervalSeconds.takeIf { it != 0 },
    expectedAt = entry.expectedTime.takeIf { it != 0L },
    alarmMethod = entry.alarmMethod.takeIf { it.isNotEmpty() },
    missReason = entry.missReason.takeIf { it.isNotEmpty() }
)

private fun toDomain(entity: EventLogEntity) = RefreshLogEntry(
    id = entity.id,
    type = com.balancesentinel.app.data.model.RefreshLogType.valueOf(entity.eventType.name),
    totalBalance = entity.totalBalanceText,
    currency = entity.currencyText,
    isAvailable = entity.isAvailable,
    grantedBalance = entity.grantedBalanceText,
    toppedUpBalance = entity.toppedUpBalanceText,
    timestamp = entity.recordedAt,
    message = entity.message,
    intervalSeconds = entity.intervalSeconds ?: 0,
    expectedTime = entity.expectedAt ?: 0L,
    alarmMethod = entity.alarmMethod ?: "",
    missReason = entity.missReason ?: ""
)
