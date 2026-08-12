package com.balancesentinel.app.data.update

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.local.update.DownloadState
import com.balancesentinel.app.data.model.GitHubAsset
import com.balancesentinel.app.data.model.GitHubRelease
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ApkDownloadRepositoryTest {
    @Test
    fun `enqueue failure terminalizes and releases a newly persisted claim`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, WalletDatabase::class.java).build()
        val directory = kotlin.io.path.createTempDirectory("apk-repository-").toFile()
        val store = DownloadOperationStore(database.downloadOperationDao())
        val scheduler = object : ApkDownloadWorkScheduler {
            override suspend fun ensureEnqueued(tag: String, operationId: String) {
                error("injected enqueue failure")
            }

            override suspend fun cancel(tag: String) = Unit
        }
        val repository = ApkDownloadRepository(
            context = context,
            store = store,
            workManager = mockk<WorkManager>(relaxed = true),
            cacheDirectory = directory,
            scheduler = scheduler
        )
        val release = GitHubRelease(
            tagName = "v2.0.0",
            htmlUrl = "https://example.invalid/releases/v2.0.0",
            assets = listOf(
                GitHubAsset("wallet.apk", "https://example.invalid/wallet.apk")
            )
        )

        try {
            assertTrue(runCatching { repository.start(release) }.isFailure)
            val operation = requireNotNull(database.downloadOperationDao().getLatestForTag(release.tagName))
            assertEquals(DownloadState.FAILED, operation.state)
            assertNull(database.downloadOperationDao().getActiveForTag(release.tagName))
            assertTrue(File(operation.temporaryPath).notExists())
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `retry with an existing active claim re-enqueues the same operation`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, WalletDatabase::class.java).build()
        val directory = kotlin.io.path.createTempDirectory("apk-retry-").toFile()
        val store = DownloadOperationStore(
            database.downloadOperationDao(),
            now = { 10L },
            newId = { "existing-operation" }
        )
        val release = GitHubRelease(
            tagName = "v2.1.0",
            htmlUrl = "https://example.invalid/releases/v2.1.0",
            assets = listOf(GitHubAsset("wallet.apk", "https://example.invalid/wallet.apk"))
        )
        val existing = store.claim(
            release.tagName,
            release.assets.single().downloadUrl,
            File(directory, "existing.part"),
            File(directory, "existing.apk")
        )
        val enqueued = mutableListOf<Pair<String, String>>()
        val scheduler = object : ApkDownloadWorkScheduler {
            override suspend fun ensureEnqueued(tag: String, operationId: String) {
                enqueued += tag to operationId
            }

            override suspend fun cancel(tag: String) = Unit
        }
        val repository = ApkDownloadRepository(
            context = context,
            store = store,
            workManager = mockk<WorkManager>(relaxed = true),
            cacheDirectory = directory,
            scheduler = scheduler
        )

        try {
            val retried = repository.start(release)
            assertEquals(existing.operation.id, retried.id)
            assertEquals(listOf(release.tagName to existing.operation.id), enqueued)
            assertEquals(existing.operation.ownerId, database.downloadOperationDao().getActiveForTag(release.tagName)?.ownerId)
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }
}

private fun File.notExists(): Boolean = !exists()
