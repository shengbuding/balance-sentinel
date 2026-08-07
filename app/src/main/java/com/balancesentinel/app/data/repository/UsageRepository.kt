package com.balancesentinel.app.data.repository

import android.content.Context
import com.balancesentinel.app.data.model.UsageSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
