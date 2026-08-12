package com.balancesentinel.app.data.update

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import com.balancesentinel.app.data.local.WalletDatabaseProvider
import com.balancesentinel.app.data.local.update.DownloadOperationDao
import com.balancesentinel.app.data.local.update.DownloadOperationEntity
import com.balancesentinel.app.data.local.update.DownloadState
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.util.UUID

class DownloadOperationStore(
    private val dao: DownloadOperationDao,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() }
) {
    fun observeLatest(tag: String): Flow<DownloadOperationEntity?> = dao.observeLatestForTag(tag)

    suspend fun get(id: String): DownloadOperationEntity? = dao.get(id)

    suspend fun claim(
        tag: String,
        sourceUrl: String,
        temporaryFile: File,
        targetFile: File
    ): DownloadClaimResult {
        dao.getActiveForTag(tag)?.let { return DownloadClaimResult(it, created = false) }
        val timestamp = now()
        val id = newId()
        val operation = DownloadOperationEntity(
            id = id,
            ownerId = newId(),
            tag = tag,
            sourceUrl = sourceUrl,
            temporaryPath = temporaryFile.absolutePath,
            targetPath = targetFile.absolutePath,
            state = DownloadState.QUEUED,
            activeTag = tag,
            activeTargetPath = targetFile.absolutePath,
            createdAt = timestamp,
            updatedAt = timestamp
        )
        return try {
            dao.insertActive(operation)
            DownloadClaimResult(operation, created = true)
        } catch (error: SQLiteConstraintException) {
            val active = dao.getActiveForTag(tag) ?: throw error
            DownloadClaimResult(active, created = false)
        }
    }

    suspend fun markRunning(operation: DownloadOperationEntity): Boolean =
        transition(operation, listOf(DownloadState.QUEUED), DownloadState.RUNNING) > 0

    suspend fun markProgress(
        operation: DownloadOperationEntity,
        downloadedBytes: Long,
        totalBytes: Long?
    ): Boolean = transition(
        operation = operation,
        expectedStates = listOf(DownloadState.RUNNING),
        state = DownloadState.RUNNING,
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes
    ) > 0

    suspend fun markFailed(
        operation: DownloadOperationEntity,
        code: String,
        message: String
    ): Boolean = transition(
        operation = operation,
        expectedStates = listOf(DownloadState.QUEUED, DownloadState.RUNNING),
        state = DownloadState.FAILED,
        errorCode = code,
        errorMessage = message,
        terminal = true
    ) > 0

    suspend fun markCompleted(operation: DownloadOperationEntity, bytes: Long): Boolean = transition(
        operation = operation,
        expectedStates = listOf(DownloadState.RUNNING),
        state = DownloadState.COMPLETED,
        downloadedBytes = bytes,
        totalBytes = bytes,
        terminal = true
    ) > 0

    suspend fun cancel(id: String): Boolean {
        val operation = dao.get(id) ?: return false
        if (operation.state.isTerminal) return true
        val cancelling = if (operation.state == DownloadState.CANCELLING) {
            true
        } else {
            transition(
                operation,
                listOf(DownloadState.QUEUED, DownloadState.RUNNING),
                DownloadState.CANCELLING
            ) > 0
        }
        if (!cancelling) return dao.get(id)?.state?.isTerminal == true
        File(operation.temporaryPath).delete()
        File(operation.targetPath).delete()
        return transition(
            operation,
            listOf(DownloadState.CANCELLING),
            DownloadState.CANCELLED,
            terminal = true
        ) > 0
    }

    suspend fun isActiveOwner(operation: DownloadOperationEntity): Boolean =
        dao.isActiveOwner(operation.id, operation.ownerId) == 1

    private suspend fun transition(
        operation: DownloadOperationEntity,
        expectedStates: List<DownloadState>,
        state: DownloadState,
        downloadedBytes: Long = operation.downloadedBytes,
        totalBytes: Long? = operation.totalBytes,
        errorCode: String? = null,
        errorMessage: String? = null,
        terminal: Boolean = false
    ): Int {
        val timestamp = now()
        return dao.transitionOwnedOperation(
            id = operation.id,
            ownerId = operation.ownerId,
            expectedStates = expectedStates,
            state = state,
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            errorCode = errorCode,
            errorMessage = errorMessage,
            activeTag = if (terminal) null else operation.tag,
            activeTargetPath = if (terminal) null else operation.targetPath,
            updatedAt = timestamp,
            completedAt = if (terminal) timestamp else null
        )
    }

    companion object {
        fun from(context: Context): DownloadOperationStore =
            DownloadOperationStore(WalletDatabaseProvider.get(context).downloadOperationDao())
    }
}
