package com.balancesentinel.app.data.repository

import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.local.createWalletTestDatabase
import com.balancesentinel.app.data.local.testAccount
import com.balancesentinel.app.data.local.usage.UsageDao
import com.balancesentinel.app.data.local.usage.UsageRecordEntity
import com.balancesentinel.app.data.local.usage.UsageSnapshotEntity
import com.balancesentinel.app.data.model.UsageRecord
import com.balancesentinel.app.data.model.UsageSnapshot
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UsageRepositoryTest {
    private lateinit var database: WalletDatabase
    private lateinit var repository: UsageRepository

    @Before
    fun setUp() {
        database = createWalletTestDatabase()
        runBlocking { database.accountDao().insertCreate(testAccount("usage-repository-account")) }
        repository = RoomUsageRepository(database)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `usage page preserves every record in each snapshot`() = runTest {
        val snapshot = UsageSnapshot(
            accountId = "usage-repository-account",
            timestamp = 100,
            records = listOf(
                UsageRecord("model-a", 100, 40, 60),
                UsageRecord("model-b", 200, 75, 125)
            )
        )
        repository.upsert(snapshot, "refresh-a")

        val result = repository.page("usage-repository-account", 0, 101)
        assertEquals(1, result.snapshots.size)
        assertEquals(snapshot.records, result.snapshots.single().value.records)
        assertTrue(result.snapshots.single().id.isNotBlank())
    }

    @Test
    fun `same timestamp snapshots remain distinct by identity discriminator`() = runTest {
        val first = UsageSnapshot("usage-repository-account", 100, listOf(UsageRecord("a", 1)))
        val second = first.copy(records = listOf(UsageRecord("b", 2)))
        repository.upsert(first, "run-a")
        repository.upsert(second, "run-b")

        val page = repository.page("usage-repository-account", 0, 101)
        assertEquals(2, page.snapshots.size)
        assertEquals(setOf("a", "b"), page.snapshots.flatMap { it.value.records }.map { it.model_name }.toSet())
    }

    @Test
    fun `large usage snapshot is written through batches no larger than five hundred`() = runTest {
        val recorder = RecordingUsageDao()
        val large = UsageSnapshot(
            accountId = "usage-repository-account",
            timestamp = 200,
            records = (0..500).map { ordinal -> UsageRecord("model-$ordinal", ordinal.toLong()) }
        )

        RoomUsageRepository(recorder).upsert(large, "large-refresh")

        assertEquals(listOf(500, 1), recorder.recordBatchSizes)
    }

    private class RecordingUsageDao : UsageDao {
        val recordBatchSizes = mutableListOf<Int>()

        override suspend fun upsertSnapshot(snapshot: UsageSnapshotEntity) = Unit

        override suspend fun upsertRecords(records: List<UsageRecordEntity>) {
            recordBatchSizes += records.size
        }

        override suspend fun deleteRecords(snapshotId: String) = Unit

        override suspend fun rangePage(
            accountId: String,
            fromInclusive: Long,
            limit: Int,
            toExclusive: Long,
            afterCapturedAt: Long?,
            afterId: String?
        ): List<UsageSnapshotEntity> = error("unused")

        override suspend fun keysetPage(
            accountId: String,
            fromInclusive: Long,
            toExclusive: Long,
            afterCapturedAt: Long?,
            afterId: String?,
            limit: Int
        ): List<UsageSnapshotEntity> = error("unused")

        override suspend fun range(
            accountId: String,
            fromInclusive: Long,
            toExclusive: Long
        ): List<UsageSnapshotEntity> = error("unused")

        override suspend fun countRange(accountId: String, fromInclusive: Long, toExclusive: Long): Long =
            error("unused")

        override suspend fun getRecords(snapshotId: String): List<UsageRecordEntity> = error("unused")

        override suspend fun countMigrationSnapshots(operationId: String): Long = error("unused")

        override suspend fun migrationSnapshotPage(
            operationId: String,
            startOrdinal: Int,
            limit: Int
        ): List<UsageSnapshotEntity> = error("unused")

        override suspend fun countSnapshots(): Long = error("unused")

        override suspend fun clearSnapshots(): Int = error("unused")

        override suspend fun deleteByAccount(accountId: String): Int = error("unused")
    }
}
