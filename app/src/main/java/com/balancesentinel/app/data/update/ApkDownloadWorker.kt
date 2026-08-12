package com.balancesentinel.app.data.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

class ApkDownloadWorker(
    appContext: Context,
    workerParameters: WorkerParameters
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): ListenableWorker.Result {
        val operationId = inputData.getString(KEY_OPERATION_ID)
            ?: return ListenableWorker.Result.failure(workDataOf(KEY_ERROR_CODE to "missing_operation_id"))
        val store = ApkDownloadWorkerDependencies.storeFactory(applicationContext)
        val downloader = ApkDownloadWorkerDependencies.downloaderFactory()
        val operation = store.get(operationId)
            ?: return ListenableWorker.Result.failure(workDataOf(KEY_ERROR_CODE to "operation_not_found"))
        if (operation.state.isTerminal) return ListenableWorker.Result.success()
        if (!store.markRunning(operation)) return ListenableWorker.Result.failure()

        val target = DownloadTarget(
            operationId = operation.id,
            sourceUrl = operation.sourceUrl,
            temporaryFile = File(operation.temporaryPath),
            targetFile = File(operation.targetPath)
        )
        var lastPersistedAt = 0L
        return try {
            when (val result = downloader.downloadToPart(target) { bytes, total ->
                val now = System.currentTimeMillis()
                if (now - lastPersistedAt >= PROGRESS_WRITE_INTERVAL_MS || bytes == total) {
                    store.markProgress(operation, bytes, total)
                    lastPersistedAt = now
                }
            }) {
                is DownloadFileResult.ReadyToPublish -> {
                    if (!store.isActiveOwner(operation)) {
                        result.temporaryFile.delete()
                        return ListenableWorker.Result.failure(
                            workDataOf(KEY_ERROR_CODE to DownloadFailureCode.OWNERSHIP_LOST)
                        )
                    }
                    when (val published = downloader.publishAtomically(result.temporaryFile, target.targetFile)) {
                        is DownloadFileResult.Published -> {
                            if (store.markCompleted(operation, published.bytes)) {
                                ListenableWorker.Result.success()
                            } else {
                                published.file.delete()
                                ListenableWorker.Result.failure(
                                    workDataOf(KEY_ERROR_CODE to DownloadFailureCode.OWNERSHIP_LOST)
                                )
                            }
                        }
                        is DownloadFileResult.Failure -> fail(store, operation, published)
                        is DownloadFileResult.ReadyToPublish -> error("Unexpected publication state")
                    }
                }
                is DownloadFileResult.Failure -> fail(store, operation, result)
                is DownloadFileResult.Published -> ListenableWorker.Result.success()
            }
        } catch (cancelled: CancellationException) {
            target.temporaryFile.delete()
            withContext(NonCancellable) { store.cancel(operation.id) }
            throw cancelled
        }
    }

    private suspend fun fail(
        store: DownloadOperationStore,
        operation: com.balancesentinel.app.data.local.update.DownloadOperationEntity,
        failure: DownloadFileResult.Failure
    ): ListenableWorker.Result {
        store.markFailed(operation, failure.code, failure.message)
        return ListenableWorker.Result.failure(
            workDataOf(KEY_ERROR_CODE to failure.code, KEY_ERROR_MESSAGE to failure.message)
        )
    }

    companion object {
        const val KEY_OPERATION_ID = "operation_id"
        const val KEY_ERROR_CODE = "error_code"
        const val KEY_ERROR_MESSAGE = "error_message"
        private const val PROGRESS_WRITE_INTERVAL_MS = 250L
    }
}

internal object ApkDownloadWorkerDependencies {
    var storeFactory: (Context) -> DownloadOperationStore = DownloadOperationStore::from
    var downloaderFactory: () -> ApkDownloader = ::ApkDownloader
}
