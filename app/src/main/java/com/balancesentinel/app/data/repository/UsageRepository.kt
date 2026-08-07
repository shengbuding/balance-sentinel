package com.balancesentinel.app.data.repository

import android.content.Context
import com.balancesentinel.app.data.model.UsageSnapshot
import com.balancesentinel.app.data.model.UsageRecord
import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.local.usage.UsageDao
import com.balancesentinel.app.data.local.usage.UsageRecordEntity
import com.balancesentinel.app.data.local.usage.UsageSnapshotEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.util.UUID

data class UsageCursor(val capturedAt: Long, val id: String)

data class RepositoryUsageSnapshot(
    val id: String,
    val value: UsageSnapshot
)

data class UsagePage(
    val snapshots: List<RepositoryUsageSnapshot>,
    val nextCursor: UsageCursor?
)

interface UsageRepository {
    suspend fun upsert(snapshot: UsageSnapshot, identityDiscriminator: String)

    suspend fun page(
        accountId: String,
        fromInclusive: Long,
        toExclusive: Long,
        after: UsageCursor? = null,
        limit: Int = HistoryRepository.MAX_PAGE_SIZE
    ): UsagePage

    suspend fun count(accountId: String, fromInclusive: Long, toExclusive: Long): Long
}

/** Compatibility seam for callers still backed by the legacy usage store. */
class LegacyUsageRepository(
    context: Context
) : UsageRepository {
    private val appContext = context.applicationContext

    override suspend fun upsert(snapshot: UsageSnapshot, identityDiscriminator: String) {
        withContext(Dispatchers.IO) { UsageDataStore.saveSnapshot(appContext, snapshot) }
    }

    override suspend fun page(
        accountId: String,
        fromInclusive: Long,
        toExclusive: Long,
        after: UsageCursor?,
        limit: Int
    ): UsagePage = withContext(Dispatchers.IO) {
        val identified = UsageDataStore.getAllSnapshots(appContext)
            .filter {
                it.accountId == accountId && it.timestamp >= fromInclusive && it.timestamp < toExclusive
            }
            .sortedBy { it.timestamp }
            .mapIndexed { index, snapshot ->
                RepositoryUsageSnapshot("legacy:${snapshot.timestamp}:$index", snapshot)
            }
        val selected = identified.asSequence()
            .filter { row ->
                after == null || row.value.timestamp > after.capturedAt ||
                    (row.value.timestamp == after.capturedAt && row.id > after.id)
            }
            .take(limit.coerceAtMost(HistoryRepository.MAX_PAGE_SIZE))
            .toList()
        UsagePage(
            snapshots = selected,
            nextCursor = selected.lastOrNull()?.let { UsageCursor(it.value.timestamp, it.id) }
        )
    }

    override suspend fun count(accountId: String, fromInclusive: Long, toExclusive: Long): Long =
        withContext(Dispatchers.IO) {
            UsageDataStore.getAllSnapshots(appContext).count {
                it.accountId == accountId && it.timestamp >= fromInclusive && it.timestamp < toExclusive
            }.toLong()
        }
}

class RoomUsageRepository(
    private val usageDao: UsageDao
) : UsageRepository {
    constructor(database: WalletDatabase) : this(database.usageDao())

    override suspend fun upsert(snapshot: UsageSnapshot, identityDiscriminator: String) {
        require(snapshot.accountId.isNotBlank()) { "accountId must not be blank" }
        val id = usageSnapshotId(snapshot, identityDiscriminator)
        val rows = snapshot.records.mapIndexed { ordinal, record ->
            UsageRecordEntity(
                snapshotId = id,
                recordOrdinal = ordinal,
                modelName = record.model_name,
                totalTokens = record.total_tokens,
                promptTokens = record.prompt_tokens,
                completionTokens = record.completion_tokens
            )
        }
        usageDao.upsertSnapshotWithRecords(
            UsageSnapshotEntity(id, snapshot.accountId, snapshot.timestamp, identityDiscriminator),
            rows
        )
    }

    override suspend fun page(
        accountId: String,
        fromInclusive: Long,
        toExclusive: Long,
        after: UsageCursor?,
        limit: Int
    ): UsagePage {
        val rows = usageDao.keysetPage(
            accountId,
            fromInclusive,
            toExclusive,
            after?.capturedAt,
            after?.id,
            limit.coerceIn(1, HistoryRepository.MAX_PAGE_SIZE)
        )
        return UsagePage(
            snapshots = rows.map { row ->
                RepositoryUsageSnapshot(
                    id = row.id,
                    value = UsageSnapshot(
                        accountId = row.accountId,
                        timestamp = row.capturedAt,
                        records = usageDao.getRecords(row.id).map { item ->
                            UsageRecord(
                                model_name = item.modelName,
                                total_tokens = item.totalTokens,
                                prompt_tokens = item.promptTokens,
                                completion_tokens = item.completionTokens
                            )
                        }
                    )
                )
            },
            nextCursor = rows.lastOrNull()?.let { UsageCursor(it.capturedAt, it.id) }
        )
    }

    override suspend fun count(accountId: String, fromInclusive: Long, toExclusive: Long): Long =
        usageDao.countRange(accountId, fromInclusive, toExclusive)
}

private fun usageSnapshotId(snapshot: UsageSnapshot, identityDiscriminator: String): String =
    UUID.nameUUIDFromBytes(
        "wallet-sentinel:usage-snapshot:v1|${snapshot.accountId}|${snapshot.timestamp}|$identityDiscriminator"
            .toByteArray(StandardCharsets.UTF_8)
    ).toString()
