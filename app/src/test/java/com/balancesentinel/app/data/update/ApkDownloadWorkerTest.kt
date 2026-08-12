package com.balancesentinel.app.data.update

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.testing.TestListenableWorkerBuilder
import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.local.update.DownloadState
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class ApkDownloadWorkerTest {
    private lateinit var context: Context
    private lateinit var database: WalletDatabase
    private lateinit var server: MockWebServer
    private lateinit var directory: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, WalletDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        server = MockWebServer().apply { start() }
        directory = kotlin.io.path.createTempDirectory("apk-worker-").toFile()
    }

    @After
    fun tearDown() {
        ApkDownloadWorkerDependencies.storeFactory = DownloadOperationStore::from
        ApkDownloadWorkerDependencies.downloaderFactory = ::ApkDownloader
        server.shutdown()
        database.close()
        directory.deleteRecursively()
    }

    @Test
    fun `worker persists completion only after validated publication`() = runTest {
        server.enqueue(MockResponse().setBody(Buffer().write(validApkBytes())))
        val ids = ArrayDeque(listOf("operation", "owner"))
        val store = DownloadOperationStore(
            database.downloadOperationDao(),
            now = { 10L },
            newId = { ids.removeFirst() }
        )
        val claim = store.claim(
            tag = "v2",
            sourceUrl = server.url("/app.apk").toString(),
            temporaryFile = File(directory, "operation.part"),
            targetFile = File(directory, "operation.apk")
        )
        ApkDownloadWorkerDependencies.storeFactory = { store }
        ApkDownloadWorkerDependencies.downloaderFactory = { ApkDownloader(OkHttpClient()) }
        val worker = TestListenableWorkerBuilder<ApkDownloadWorker>(context)
            .setInputData(Data.Builder().putString(ApkDownloadWorker.KEY_OPERATION_ID, claim.operation.id).build())
            .build()

        worker.doWork()

        val completed = store.get(claim.operation.id)
        assertEquals(DownloadState.COMPLETED, completed?.state)
        assertTrue(File(requireNotNull(completed).targetPath).exists())
        assertFalse(File(completed.temporaryPath).exists())
    }

    @Test
    fun `cancel releases ownership and rejects late worker progress`() = runTest {
        val ids = ArrayDeque(listOf("old", "old-owner", "new", "new-owner"))
        val store = DownloadOperationStore(
            database.downloadOperationDao(),
            now = { 10L },
            newId = { ids.removeFirst() }
        )
        val oldPart = File(directory, "old.part").apply { writeText("partial") }
        val old = store.claim("v2", "https://example.invalid/old", oldPart, File(directory, "old.apk"))

        assertTrue(store.cancel(old.operation.id))
        val newer = store.claim(
            "v2",
            "https://example.invalid/new",
            File(directory, "new.part"),
            File(directory, "new.apk")
        )

        assertTrue(newer.created)
        assertFalse(store.markProgress(old.operation, 99, 100))
        assertFalse(oldPart.exists())
        assertEquals(DownloadState.CANCELLED, store.get(old.operation.id)?.state)
    }

    @Test
    fun `cancelled operation rejects a late completion after publication`() = runTest {
        val ids = ArrayDeque(listOf("operation", "owner"))
        val store = DownloadOperationStore(
            database.downloadOperationDao(),
            now = { 10L },
            newId = { ids.removeFirst() }
        )
        val target = File(directory, "published.apk").apply { writeText("published") }
        val claim = store.claim(
            "v2",
            "https://example.invalid/app.apk",
            File(directory, "operation.part"),
            target
        )
        assertTrue(store.markRunning(claim.operation))

        assertTrue(store.cancel(claim.operation.id))
        assertFalse(store.markCompleted(claim.operation, 9L))

        assertEquals(DownloadState.CANCELLED, store.get(claim.operation.id)?.state)
        assertFalse(target.exists())
    }

    private fun validApkBytes(): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
        }
        return output.toByteArray()
    }
}
