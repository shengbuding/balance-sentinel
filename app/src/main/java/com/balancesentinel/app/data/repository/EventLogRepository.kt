package com.balancesentinel.app.data.repository

import android.content.Context
import com.balancesentinel.app.data.model.RefreshLogEntry
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
