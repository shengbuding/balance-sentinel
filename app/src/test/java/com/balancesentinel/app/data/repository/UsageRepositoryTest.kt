package com.balancesentinel.app.data.repository

import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.local.createWalletTestDatabase
import com.balancesentinel.app.data.local.testAccount
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
}
