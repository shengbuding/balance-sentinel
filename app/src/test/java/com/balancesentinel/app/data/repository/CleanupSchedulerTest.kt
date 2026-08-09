package com.balancesentinel.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.local.account.AccountEntity
import com.balancesentinel.app.data.local.history.BalanceRecordEntity
import com.balancesentinel.app.data.local.history.BalanceRecordSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneOffset
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class CleanupSchedulerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, WalletDatabase::class.java).build()
    @Before fun installRoom() = com.balancesentinel.app.data.local.WalletDatabaseProvider.installForTests(db)
    @After fun close() = com.balancesentinel.app.data.local.WalletDatabaseProvider.clearForTests()

    @Test fun `cleanup writes summary before deleting exact archived raw ids`() = runBlocking {
        db.accountDao().insertCreate(AccountEntity("acct", 0, "Primary", ProviderType.DEEPSEEK, activeCredentialGeneration = "test", createdAt = 1L, updatedAt = 1L, state = com.balancesentinel.app.data.local.account.AccountState.VERIFIED))
        db.accountDao().insertCreate(AccountEntity("other", 1, "Other", ProviderType.DEEPSEEK, activeCredentialGeneration = "test", createdAt = 1L, updatedAt = 1L, state = com.balancesentinel.app.data.local.account.AccountState.VERIFIED))
        db.historyDao().insertBalanceBatch(listOf(
            BalanceRecordEntity(accountId = "acct", currency = "USD", recordedAt = 1_000L, totalBalance = 10.0, source = BalanceRecordSource.REFRESH),
            BalanceRecordEntity(accountId = "acct", currency = "USD", recordedAt = 2_000L, totalBalance = 9.0, source = BalanceRecordSource.REFRESH)
        ))
        val report = CleanupScheduler.runCleanup(context, 3 * 86_400_000L, ZoneOffset.UTC)
        assertTrue(report.archivedDates.contains("1970-01-01"))
        assertEquals(0, db.historyDao().countRecords())
        assertTrue(db.historyDao().countSummaries() >= 1)
    }

    @Test fun `cleanup preserves continuity through yesterday`() = runBlocking {
        db.accountDao().insertCreate(AccountEntity("acct", 0, "Primary", ProviderType.DEEPSEEK, activeCredentialGeneration = "test", createdAt = 1L, updatedAt = 1L, state = com.balancesentinel.app.data.local.account.AccountState.VERIFIED))
        db.accountDao().insertCreate(AccountEntity("other", 1, "Other", ProviderType.DEEPSEEK, activeCredentialGeneration = "test", createdAt = 1L, updatedAt = 1L, state = com.balancesentinel.app.data.local.account.AccountState.VERIFIED))
        db.historyDao().upsertSummaries(listOf(
            com.balancesentinel.app.data.local.history.DailySummaryEntity("1970-01-01", "acct", "USD", 5.0, 5.0, 0.0, 2.0, grantedBalance = 1.0, averageBalance = 5.0, sampleCount = 1, toppedUpBalanceClose = 2.0, grantedBalanceClose = 1.0, generatedAt = 1L),
            com.balancesentinel.app.data.local.history.DailySummaryEntity("1970-01-01", "other", "CNY", 8.0, 8.0, 0.0, 3.0, grantedBalance = 2.0, averageBalance = 8.0, sampleCount = 1, toppedUpBalanceClose = 3.0, grantedBalanceClose = 2.0, generatedAt = 1L)
        ))
        CleanupScheduler.runCleanup(context, 4 * 86_400_000L, java.time.ZoneOffset.UTC)
        val carry = db.historyDao().querySummaries("acct", "USD", "1970-01-02", "1970-01-03")
        assertEquals(2, carry.size)
        assertTrue(carry.all { it.openBalance == 5.0 && it.closeBalance == 5.0 && it.averageBalance == 5.0 && it.toppedUpBalanceClose == 2.0 && it.grantedBalanceClose == 1.0 })
        assertEquals(2, db.historyDao().querySummaries("other", "CNY", "1970-01-02", "1970-01-03").size)
    }

    @Test fun `cleanup deletes more than sqlite bind limit in chunks`() = runBlocking {
        db.accountDao().insertCreate(AccountEntity("acct", 0, "Primary", ProviderType.DEEPSEEK, activeCredentialGeneration = "test", createdAt = 1L, updatedAt = 1L, state = com.balancesentinel.app.data.local.account.AccountState.VERIFIED))
        val rows = (1..1200).map { BalanceRecordEntity(accountId = "acct", currency = "USD", recordedAt = it.toLong(), totalBalance = it.toDouble(), source = com.balancesentinel.app.data.local.history.BalanceRecordSource.REFRESH) }
        rows.chunked(500).forEach { db.historyDao().insertBalanceBatch(it) }
        val report = CleanupScheduler.runCleanup(context, 3 * 86_400_000L, java.time.ZoneOffset.UTC)
        assertEquals(1200, report.deletedRecordCount)
        assertEquals(0, db.historyDao().countRecords())
    }

    @Test fun `exact id deletion preserves a late arrival on same date`() = runBlocking {
        db.accountDao().insertCreate(AccountEntity("acct", 0, "Primary", ProviderType.DEEPSEEK, activeCredentialGeneration = "test", createdAt = 1L, updatedAt = 1L, state = com.balancesentinel.app.data.local.account.AccountState.VERIFIED))
        val ids = db.historyDao().insertBalanceBatch(listOf(
            BalanceRecordEntity(accountId = "acct", currency = "USD", recordedAt = 1L, totalBalance = 10.0, source = BalanceRecordSource.REFRESH),
            BalanceRecordEntity(accountId = "acct", currency = "USD", recordedAt = 20 * 3_600_000L, totalBalance = 9.0, source = BalanceRecordSource.REFRESH)
        ))
        val report = CleanupScheduler.runCleanup(context, 36 * 3_600_000L, java.time.ZoneOffset.UTC)
        assertEquals(1, report.retainedRecordCount)
    }

    @Test fun `date scoped cleanup only archives requested date`() = runBlocking {
        db.accountDao().insertCreate(AccountEntity("acct", 0, "Primary", ProviderType.DEEPSEEK, activeCredentialGeneration = "test", createdAt = 1L, updatedAt = 1L, state = com.balancesentinel.app.data.local.account.AccountState.VERIFIED))
        db.historyDao().insertBalanceBatch(listOf(
            BalanceRecordEntity(accountId = "acct", currency = "USD", recordedAt = 1_000L, totalBalance = 10.0, source = BalanceRecordSource.REFRESH),
            BalanceRecordEntity(accountId = "acct", currency = "USD", recordedAt = 86_401_000L, totalBalance = 9.0, source = BalanceRecordSource.REFRESH)
        ))

        val report = CleanupScheduler.runCleanupForDate(
            context = context,
            date = LocalDate.of(1970, 1, 1),
            now = 4 * 86_400_000L,
            zoneId = ZoneOffset.UTC
        )

        assertEquals(setOf("1970-01-01"), report.archivedDates)
        assertEquals(1, report.retainedRecordCount)
        assertEquals(1, db.historyDao().countRecords())
    }

    @Test fun `date scoped cleanup passes local-day bounds to Room repository`() = runBlocking {
        db.accountDao().insertCreate(AccountEntity("acct", 0, "Primary", ProviderType.DEEPSEEK, activeCredentialGeneration = "test", createdAt = 1L, updatedAt = 1L, state = com.balancesentinel.app.data.local.account.AccountState.VERIFIED))
        val target = LocalDate.of(1970, 1, 1)
        db.historyDao().insertBalanceBatch(
            listOf(BalanceRecordEntity(accountId = "acct", currency = "USD", recordedAt = 1_000L, totalBalance = 10.0, source = BalanceRecordSource.REFRESH)) +
                (1..201).map { index ->
                    BalanceRecordEntity(accountId = "acct", currency = "USD", recordedAt = 86_401_000L + index, totalBalance = index.toDouble(), source = BalanceRecordSource.REFRESH)
                }
        )
        val observedRanges = mutableListOf<LongRange>()
        val repository = object : RoomHistoryRepository(db) {
            override suspend fun pageAll(
                fromInclusive: Long,
                toExclusive: Long,
                after: HistoryCursor?,
                limit: Int
            ): HistoryPage {
                observedRanges += fromInclusive until toExclusive
                return super.pageAll(fromInclusive, toExclusive, after, limit)
            }
        }

        CleanupScheduler.runCleanupForDate(
            context = context,
            date = target,
            now = 4 * 86_400_000L,
            zoneId = ZoneOffset.UTC,
            historyRepository = repository
        )

        assertTrue(observedRanges.isNotEmpty())
        assertTrue(observedRanges.all { it.first == 0L && it.last == 86_400_000L - 1L })
    }

    @Test fun `next date cleanup preserves frozen summary and sweeps expired retained tail`() = runBlocking {
        db.accountDao().insertCreate(AccountEntity("acct", 0, "Primary", ProviderType.DEEPSEEK, activeCredentialGeneration = "test", createdAt = 1L, updatedAt = 1L, state = com.balancesentinel.app.data.local.account.AccountState.VERIFIED))
        db.historyDao().insertBalanceBatch(listOf(
            BalanceRecordEntity(accountId = "acct", currency = "USD", recordedAt = 1_000L, totalBalance = 10.0, source = BalanceRecordSource.REFRESH),
            BalanceRecordEntity(accountId = "acct", currency = "USD", recordedAt = 23 * 3_600_000L, totalBalance = 9.0, source = BalanceRecordSource.REFRESH)
        ))

        CleanupScheduler.runCleanupForDate(
            context = context,
            date = LocalDate.of(1970, 1, 1),
            now = 25 * 3_600_000L,
            zoneId = ZoneOffset.UTC
        )

        val frozen = requireNotNull(db.historyDao().getSummary("1970-01-01", "acct", "USD"))
        assertEquals(1, db.historyDao().countRecords())

        val second = CleanupScheduler.runCleanupForDate(
            context = context,
            date = LocalDate.of(1970, 1, 2),
            now = 50 * 3_600_000L,
            zoneId = ZoneOffset.UTC
        )

        assertEquals(1, second.deletedRecordCount)
        assertEquals(0, db.historyDao().countRecords())
        val afterSweep = requireNotNull(db.historyDao().getSummary("1970-01-01", "acct", "USD"))
        assertEquals(frozen, afterSweep)
    }

    @Test fun `expired sweep only removes raw rows covered by a published summary key`() = runBlocking {
        db.accountDao().insertCreate(AccountEntity("acct", 0, "Primary", ProviderType.DEEPSEEK, activeCredentialGeneration = "test", createdAt = 1L, updatedAt = 1L, state = com.balancesentinel.app.data.local.account.AccountState.VERIFIED))
        db.accountDao().insertCreate(AccountEntity("other", 1, "Other", ProviderType.DEEPSEEK, activeCredentialGeneration = "test", createdAt = 1L, updatedAt = 1L, state = com.balancesentinel.app.data.local.account.AccountState.VERIFIED))
        db.historyDao().insertBalanceBatch(listOf(
            BalanceRecordEntity(accountId = "acct", currency = "USD", recordedAt = 1_000L, totalBalance = 10.0, source = BalanceRecordSource.REFRESH),
            BalanceRecordEntity(accountId = "other", currency = "USD", recordedAt = 2_000L, totalBalance = 20.0, source = BalanceRecordSource.REFRESH)
        ))
        db.historyDao().upsertSummaries(listOf(
            com.balancesentinel.app.data.local.history.DailySummaryEntity(
                date = "1970-01-01", accountId = "acct", currency = "USD",
                openBalance = 10.0, closeBalance = 10.0, consumedBalance = 0.0,
                toppedUpBalance = 0.0, grantedBalance = 0.0, averageBalance = 10.0,
                sampleCount = 1, generatedAt = 1L
            )
        ))

        val report = CleanupScheduler.runCleanupForDate(
            context = context,
            date = LocalDate.of(1970, 1, 2),
            now = 3 * 86_400_000L,
            zoneId = ZoneOffset.UTC
        )

        assertEquals(1, report.deletedRecordCount)
        assertEquals(1, db.historyDao().countRecords())
        assertEquals(1L, db.historyDao().countRange("other", "USD", 0L, 86_400_000L))
    }

    @Test fun `expired sweep preserves current local date across a 25 hour day`() = runBlocking {
        val zone = ZoneId.of("America/New_York")
        val today = LocalDate.of(2026, 11, 1)
        val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val now = today.atTime(23, 30).atZone(zone).toInstant().toEpochMilli()
        db.accountDao().insertCreate(AccountEntity("acct", 0, "Primary", ProviderType.DEEPSEEK, activeCredentialGeneration = "test", createdAt = 1L, updatedAt = 1L, state = com.balancesentinel.app.data.local.account.AccountState.VERIFIED))
        db.historyDao().insertBalanceBatch(listOf(
            BalanceRecordEntity(accountId = "acct", currency = "USD", recordedAt = start, totalBalance = 10.0, source = BalanceRecordSource.REFRESH)
        ))
        db.historyDao().upsertSummaries(listOf(
            com.balancesentinel.app.data.local.history.DailySummaryEntity(
                date = today.toString(), accountId = "acct", currency = "USD",
                openBalance = 10.0, closeBalance = 10.0, consumedBalance = 0.0,
                toppedUpBalance = 0.0, grantedBalance = 0.0, averageBalance = 10.0,
                sampleCount = 1, generatedAt = start
            )
        ))

        val report = CleanupScheduler.runCleanupForDate(
            context = context,
            date = today.minusDays(1),
            now = now,
            zoneId = zone
        )

        assertEquals(0, report.deletedRecordCount)
        assertEquals(1, db.historyDao().countRecords())
    }

    private class RoomTestContext(base: Context, val database: WalletDatabase) : android.content.ContextWrapper(base)
}
