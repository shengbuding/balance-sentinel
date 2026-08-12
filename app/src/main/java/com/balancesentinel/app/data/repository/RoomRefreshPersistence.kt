package com.balancesentinel.app.data.repository

import androidx.room.withTransaction
import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.local.history.BalanceRecordEntity
import com.balancesentinel.app.data.local.history.BalanceRecordSource
import com.balancesentinel.app.data.local.log.EventLogEntity
import com.balancesentinel.app.data.local.log.EventLogType
import com.balancesentinel.app.data.local.usage.UsageRecordEntity
import com.balancesentinel.app.data.local.usage.UsageSnapshotEntity
import com.balancesentinel.app.data.model.RawRecord
import com.balancesentinel.app.data.model.RefreshLogEntry
import com.balancesentinel.app.data.model.UsageSnapshot
import java.nio.charset.StandardCharsets
import java.util.UUID

/** Atomic Room write boundary for refresh records, usage and event logs. */
class RoomRefreshPersistence(private val database: WalletDatabase) {
    suspend fun commit(
        records: List<RawRecord>,
        snapshots: List<UsageSnapshot>,
        logs: List<RefreshLogEntry>,
        identityDiscriminator: String,
        accountId: String? = null
    ) = database.withTransaction {
        if (records.isNotEmpty()) {
            database.historyDao().insertBalanceBatch(records.map {
                BalanceRecordEntity(
                    accountId = it.accountId,
                    currency = it.currency.uppercase(),
                    recordedAt = it.timestamp,
                    totalBalance = it.totalBalance.toDouble(),
                    grantedBalance = it.grantedBalance.toDouble(),
                    toppedUpBalance = it.toppedUpBalance.toDouble(),
                    source = BalanceRecordSource.REFRESH
                )
            })
        }
        snapshots.forEach { snapshot ->
            val id = snapshotId(snapshot, identityDiscriminator)
            database.usageDao().upsertSnapshotWithRecords(
                UsageSnapshotEntity(id, snapshot.accountId, snapshot.timestamp, identityDiscriminator),
                snapshot.records.mapIndexed { ordinal, record ->
                    UsageRecordEntity(id, ordinal, record.model_name, record.total_tokens, record.prompt_tokens, record.completion_tokens)
                }
            )
        }
        if (logs.isNotEmpty()) {
            database.eventLogDao().insertAll(logs.map { it.toEntity(accountId) })
            val maximum = (database.appSettingsDao().get()?.logMaxEntries ?: 100).coerceIn(10, 1000)
            database.eventLogDao().trimToLatest(maximum)
        }
    }

    suspend fun archiveAndDelete(
        summaries: List<com.balancesentinel.app.data.model.DailySummary>,
        recordIds: List<Long>
    ) = database.withTransaction {
        summaries.forEach { summary ->
            database.historyDao().deleteSummaryIdentity(
                date = summary.date,
                accountId = summary.accountId,
                currency = summary.currency.uppercase(),
                identityDiscriminator = CONTINUITY_SUMMARY_IDENTITY
            )
        }
        database.historyDao().upsertSummaries(summaries.map { it.toEntity() })
        recordIds.chunked(500).forEach { ids -> database.historyDao().deleteByIds(ids) }
    }

    private fun snapshotId(snapshot: UsageSnapshot, discriminator: String): String = UUID.nameUUIDFromBytes(
        "wallet-sentinel:usage-snapshot:v1|${snapshot.accountId}|${snapshot.timestamp}|$discriminator"
            .toByteArray(StandardCharsets.UTF_8)
    ).toString()

    private fun RefreshLogEntry.toEntity(accountId: String?) = EventLogEntity(
        id = id,
        accountId = accountId,
        eventType = EventLogType.valueOf(type.name),
        totalBalanceText = totalBalance,
        currencyText = currency,
        isAvailable = isAvailable,
        grantedBalanceText = grantedBalance,
        toppedUpBalanceText = toppedUpBalance,
        recordedAt = timestamp,
        message = message,
        intervalSeconds = intervalSeconds.takeIf { it != 0 },
        expectedAt = expectedTime.takeIf { it != 0L },
        alarmMethod = alarmMethod.takeIf { it.isNotEmpty() },
        missReason = missReason.takeIf { it.isNotEmpty() }
    )

    private fun com.balancesentinel.app.data.model.DailySummary.toEntity() =
        com.balancesentinel.app.data.local.history.DailySummaryEntity(
            date = date, accountId = accountId, currency = currency.uppercase(),
            openBalance = open.toDouble(), closeBalance = close.toDouble(),
            consumedBalance = consumed.toDouble(), toppedUpBalance = toppedUp.toDouble(),
            grantedBalance = granted.toDouble(), averageBalance = avgBalance.toDouble(),
            sampleCount = sampleCount, toppedUpBalanceClose = toppedUpBalanceClose.toDouble(),
            grantedBalanceClose = grantedBalanceClose.toDouble(), generatedAt = generatedAt
        )
}
