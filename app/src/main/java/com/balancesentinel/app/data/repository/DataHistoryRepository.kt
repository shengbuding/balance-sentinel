package com.balancesentinel.app.data.repository

import android.content.Context
import android.net.Uri

/** Cancellable history transfer seam owned by the data-management ViewModel. */
interface DataHistoryRepository {
    suspend fun export(
        uri: Uri,
        reportProgress: suspend (Int) -> Unit
    ): Boolean

    suspend fun import(
        uri: Uri,
        reportProgress: suspend (Int) -> Unit
    ): DataExporter.ImportResult?
}

class RoomDataHistoryRepository(
    private val context: Context
) : DataHistoryRepository {
    override suspend fun export(uri: Uri, reportProgress: suspend (Int) -> Unit): Boolean {
        reportProgress(5)
        val succeeded = DataExporter.exportToUri(context, uri)
        if (succeeded) reportProgress(100)
        return succeeded
    }

    override suspend fun import(uri: Uri, reportProgress: suspend (Int) -> Unit): DataExporter.ImportResult? {
        reportProgress(5)
        val result = DataExporter.importAndApply(context, uri)
        if (result != null) reportProgress(100)
        return result
    }
}
