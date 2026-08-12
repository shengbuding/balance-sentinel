package com.balancesentinel.app.data.update

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.balancesentinel.app.data.local.update.DownloadOperationEntity
import com.balancesentinel.app.data.model.GitHubRelease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

interface ApkDownloadRepositoryContract {
    fun observe(tag: String): Flow<DownloadOperationEntity?>
    suspend fun start(release: GitHubRelease): DownloadOperationEntity
    suspend fun cancel(operationId: String)
}

interface ApkDownloadWorkScheduler {
    suspend fun ensureEnqueued(tag: String, operationId: String)
    suspend fun cancel(tag: String)
}

class WorkManagerApkDownloadScheduler(
    private val workManager: WorkManager
) : ApkDownloadWorkScheduler {
    override suspend fun ensureEnqueued(tag: String, operationId: String) {
        val request = OneTimeWorkRequestBuilder<ApkDownloadWorker>()
            .setInputData(workDataOf(ApkDownloadWorker.KEY_OPERATION_ID to operationId))
            .addTag(ApkDownloadRepository.uniqueWorkName(tag))
            .build()
        withContext(Dispatchers.IO) {
            workManager.enqueueUniqueWork(
                ApkDownloadRepository.uniqueWorkName(tag),
                ExistingWorkPolicy.KEEP,
                request
            ).result.get()
        }
    }

    override suspend fun cancel(tag: String) {
        withContext(Dispatchers.IO) {
            workManager.cancelUniqueWork(ApkDownloadRepository.uniqueWorkName(tag)).result.get()
        }
    }
}

class ApkDownloadRepository(
    context: Context,
    private val store: DownloadOperationStore = DownloadOperationStore.from(context),
    private val workManager: WorkManager = WorkManager.getInstance(context.applicationContext),
    private val downloader: ApkDownloader = ApkDownloader(),
    private val cacheDirectory: File = context.cacheDir,
    private val scheduler: ApkDownloadWorkScheduler = WorkManagerApkDownloadScheduler(workManager)
) : ApkDownloadRepositoryContract {
    override fun observe(tag: String): Flow<DownloadOperationEntity?> = store.observeLatest(tag)

    override suspend fun start(release: GitHubRelease): DownloadOperationEntity {
        val sourceUrl = downloader.resolveDownloadUrl(release)
            ?: throw IllegalArgumentException("APK download URL is missing")
        val directory = File(cacheDirectory, "apk").apply { mkdirs() }
        val safeTag = ApkDownloader.safeTag(release.tagName)
        val fileOwner = UUID.randomUUID().toString()
        val temporaryFile = File(directory, "$fileOwner.apk.part")
        val targetFile = File(directory, "update-$safeTag-$fileOwner.apk")
        val claim = store.claim(release.tagName, sourceUrl, temporaryFile, targetFile)
        try {
            scheduler.ensureEnqueued(release.tagName, claim.operation.id)
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                store.markFailed(claim.operation, "work_enqueue_failed", failure.message ?: "Work enqueue failed")
                File(claim.operation.temporaryPath).delete()
            }
            throw failure
        }
        return claim.operation
    }

    override suspend fun cancel(operationId: String) {
        val operation = store.get(operationId) ?: return
        store.cancel(operationId)
        runCatching { scheduler.cancel(operation.tag) }
    }

    companion object {
        fun uniqueWorkName(tag: String): String = "apk-download:$tag"
    }
}
