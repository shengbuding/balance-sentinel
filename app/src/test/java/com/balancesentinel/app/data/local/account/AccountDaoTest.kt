package com.balancesentinel.app.data.local.account

import android.database.sqlite.SQLiteConstraintException
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.local.createWalletTestDatabase
import com.balancesentinel.app.data.local.execSql
import com.balancesentinel.app.data.local.history.BalanceRecordEntity
import com.balancesentinel.app.data.local.history.DailySummaryEntity
import com.balancesentinel.app.data.local.log.EventLogEntity
import com.balancesentinel.app.data.local.log.EventLogType
import com.balancesentinel.app.data.local.mutation.MutationOperationEntity
import com.balancesentinel.app.data.local.mutation.MutationOperationType
import com.balancesentinel.app.data.local.mutation.MutationStage
import com.balancesentinel.app.data.local.queryLong
import com.balancesentinel.app.data.local.refresh.RefreshAccountResultEntity
import com.balancesentinel.app.data.local.refresh.RefreshAccountResultState
import com.balancesentinel.app.data.local.refresh.RefreshErrorCategory
import com.balancesentinel.app.data.local.refresh.RefreshRunEntity
import com.balancesentinel.app.data.local.refresh.RefreshRunSource
import com.balancesentinel.app.data.local.settings.AccountAlertSettingEntity
import com.balancesentinel.app.data.local.settings.AlertRuntimeStateEntity
import com.balancesentinel.app.data.local.settings.NotificationWalletSelectionEntity
import com.balancesentinel.app.data.local.settings.SnoozeStateEntity
import com.balancesentinel.app.data.local.testAccount
import com.balancesentinel.app.data.local.update.DownloadOperationEntity
import com.balancesentinel.app.data.local.usage.UsageRecordEntity
import com.balancesentinel.app.data.local.usage.UsageSnapshotEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AccountDaoTest {
    private lateinit var database: WalletDatabase

    @Before
    fun setUp() {
        database = createWalletTestDatabase()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `account updates use revision CAS and revisions increase monotonically`() = runTest {
        val dao = database.accountDao()
        dao.insertCreate(testAccount("account"))

        assertEquals(
            1,
            dao.updateWhereRevision(
                id = "account",
                expectedRevision = 0,
                displayOrder = 7,
                label = "revision one",
                providerType = ProviderType.ANTHROPIC,
                providerConfigJson = "{\"one\":true}",
                activeCredentialGeneration = "generation-1",
                updatedAt = 200
            )
        )
        assertEquals(1L, dao.get("account")?.revision)

        assertEquals(
            0,
            dao.updateWhereRevision(
                id = "account",
                expectedRevision = 0,
                displayOrder = 8,
                label = "stale",
                providerType = ProviderType.CUSTOM,
                providerConfigJson = "{}",
                activeCredentialGeneration = "stale-generation",
                updatedAt = 300
            )
        )
        assertEquals("revision one", dao.get("account")?.label)

        assertEquals(
            1,
            dao.updateWhereRevision(
                id = "account",
                expectedRevision = 1,
                displayOrder = 9,
                label = "revision two",
                providerType = ProviderType.GEMINI,
                providerConfigJson = "{}",
                activeCredentialGeneration = "generation-2",
                updatedAt = 400
            )
        )
        assertEquals(2L, dao.get("account")?.revision)

        dao.insertCreate(testAccount("same-order-a", displayOrder = 9))
        dao.insertCreate(testAccount("same-order-b", displayOrder = 9))
        assertEquals(3, dao.getAllForMigration().count { it.displayOrder == 9 })
    }

    @Test
    fun `daily summary key replaces one logical day account and currency row`() = runTest {
        database.accountDao().insertCreate(testAccount("summary-account"))
        val first = summary(closeBalance = 20.0)
        val replacement = summary(closeBalance = 18.5)

        database.historyDao().upsertSummaries(listOf(first))
        database.historyDao().upsertSummaries(listOf(replacement))

        assertEquals(1L, database.queryLong("SELECT COUNT(*) FROM daily_summaries"))
        assertEquals(
            18.5,
            requireNotNull(
                database.historyDao().getSummary("2026-08-06", "summary-account", "USD")
            ).closeBalance,
            0.0
        )
    }

    @Test
    fun `account deletion cascades owned rows but retained run result can become stale`() = runTest {
        val accountId = "owned-account"
        database.accountDao().insertCreate(testAccount(accountId))
        database.settingsDao().replaceAccountAlertSettings(
            listOf(AccountAlertSettingEntity(accountId, "USD", true, true))
        )
        database.settingsDao().replaceNotificationSelections(
            listOf(NotificationWalletSelectionEntity(accountId, "USD", 0))
        )
        database.settingsDao().replaceAlertRuntimeStates(
            listOf(AlertRuntimeStateEntity(accountId, "USD", lastAlertedBalance = 9.0))
        )
        database.settingsDao().replaceSnoozes(listOf(SnoozeStateEntity(accountId, 500)))
        database.historyDao().insertBalanceBatch(
            listOf(BalanceRecordEntity(accountId = accountId, currency = "USD", recordedAt = 1, totalBalance = 10.0))
        )
        database.historyDao().upsertSummaries(
            listOf(
                DailySummaryEntity(
                    date = "2026-08-06",
                    accountId = accountId,
                    currency = "USD",
                    openBalance = 10.0,
                    closeBalance = 9.0,
                    consumedBalance = 1.0,
                    toppedUpBalance = 0.0,
                    averageBalance = 9.5,
                    sampleCount = 2,
                    generatedAt = 10
                )
            )
        )
        database.usageDao().upsertSnapshotWithRecords(
            UsageSnapshotEntity("snapshot", accountId, 1),
            listOf(UsageRecordEntity("snapshot", 0, "same-model", totalTokens = 1))
        )
        val eventId = database.eventLogDao().insertAll(
            listOf(EventLogEntity(accountId = accountId, eventType = EventLogType.AUTO, recordedAt = 1))
        ).single()
        database.refreshRunDao().insertRun(
            RefreshRunEntity("run", RefreshRunSource.MANUAL, startedAt = 1)
        )
        database.refreshRunDao().insertRunningResult(
            RefreshAccountResultEntity(
                runId = "run",
                accountId = accountId,
                accountRevision = 0,
                startedAt = 1
            )
        )

        assertEquals(1, database.accountDao().deleteWhereRevision(accountId, 0))

        assertNull(database.accountDao().get(accountId))
        listOf(
            "account_alert_settings",
            "notification_wallet_selections",
            "alert_runtime_state",
            "snooze_state",
            "balance_records",
            "daily_summaries",
            "usage_snapshots",
            "usage_records"
        ).forEach { table -> assertEquals(table, 0L, database.queryLong("SELECT COUNT(*) FROM $table")) }
        assertNull(database.eventLogDao().get(eventId))

        assertNotNull(database.refreshRunDao().getAccountResult("run", accountId))
        assertEquals(
            1,
            database.refreshRunDao().completeAccountAtomically(
                runId = "run",
                accountId = accountId,
                state = RefreshAccountResultState.ACCOUNT_STALE,
                errorCategory = RefreshErrorCategory.ACCOUNT_STALE,
                errorCode = "REVISION_STALE",
                retryable = false,
                retryAfterAt = null,
                dataTimestamp = null,
                stale = true,
                attemptCount = 1,
                completedAt = 2
            )
        )
        assertEquals(
            RefreshAccountResultState.ACCOUNT_STALE,
            database.refreshRunDao().getAccountResult("run", accountId)?.state
        )
    }

    @Test
    fun `nullable download active slots allow inactive rows and reject duplicate active ownership`() = runTest {
        val dao = database.downloadOperationDao()
        dao.insertActive(download("inactive-1"))
        dao.insertActive(download("inactive-2"))
        dao.insertActive(download("active", activeTag = "release", activePath = "/target/app.apk"))

        assertConstraint {
            dao.insertActive(download("duplicate-tag", activeTag = "release", activePath = "/target/other.apk"))
        }
        assertConstraint {
            dao.insertActive(download("duplicate-path", activeTag = "other", activePath = "/target/app.apk"))
        }
        assertEquals(3L, database.queryLong("SELECT COUNT(*) FROM download_operations"))
    }

    @Test
    fun `history import progress duplicate usage models and event raw text survive Room`() = runTest {
        database.mutationOperationDao().insertPrepared(
            MutationOperationEntity(
                id = "history-import",
                operationType = MutationOperationType.HISTORY_DATA_IMPORT,
                stage = MutationStage.ROOM_WRITTEN,
                targetsJson = "[\"history\"]",
                stagedGenerationManifestJson = "[{\"part\":2}]",
                manifestVersion = 3,
                batchCursor = 47,
                baselineRevision = 9,
                createdAt = 1,
                updatedAt = 2
            )
        )
        val operation = database.mutationOperationDao().get("history-import")
        assertEquals(MutationOperationType.HISTORY_DATA_IMPORT, operation?.operationType)
        assertEquals(3, operation?.manifestVersion)
        assertEquals(47L, operation?.batchCursor)
        assertEquals("[{\"part\":2}]", operation?.stagedGenerationManifestJson)

        database.accountDao().insertCreate(testAccount("payload-account"))
        database.usageDao().upsertSnapshotWithRecords(
            UsageSnapshotEntity("payload-snapshot", "payload-account", 5, "source-row"),
            listOf(
                UsageRecordEntity("payload-snapshot", 0, "duplicate", totalTokens = 10),
                UsageRecordEntity("payload-snapshot", 1, "duplicate", totalTokens = 20)
            )
        )
        val records = database.usageDao().getRecords("payload-snapshot")
        assertEquals(listOf(0, 1), records.map { it.recordOrdinal })
        assertEquals(listOf("duplicate", "duplicate"), records.map { it.modelName })
        assertEquals(listOf(10L, 20L), records.map { it.totalTokens })

        val eventId = database.eventLogDao().insertAll(
            listOf(
                EventLogEntity(
                    accountId = "payload-account",
                    eventType = EventLogType.MANUAL,
                    totalBalanceText = "001.2300e+04",
                    currencyText = " usd ",
                    grantedBalanceText = "000.100",
                    toppedUpBalanceText = "+0.0000",
                    recordedAt = 9
                )
            )
        ).single()
        val event = database.eventLogDao().get(eventId)
        assertEquals("001.2300e+04", event?.totalBalanceText)
        assertEquals(" usd ", event?.currencyText)
        assertEquals("000.100", event?.grantedBalanceText)
        assertEquals("+0.0000", event?.toppedUpBalanceText)
    }

    @Test
    fun `history usage and event pages advance stable keysets and expose bounded aggregates`() = runTest {
        val accountId = "paged-account"
        database.accountDao().insertCreate(testAccount(accountId))
        database.historyDao().insertBalanceBatch(
            listOf(
                BalanceRecordEntity(accountId = accountId, currency = "USD", recordedAt = 100, totalBalance = 10.0),
                BalanceRecordEntity(accountId = accountId, currency = "USD", recordedAt = 100, totalBalance = 11.0),
                BalanceRecordEntity(accountId = accountId, currency = "USD", recordedAt = 90, totalBalance = 9.0),
                BalanceRecordEntity(accountId = accountId, currency = "USD", recordedAt = 80, totalBalance = 8.0)
            )
        )
        val firstBalances = database.historyDao().keysetPage(accountId, "USD", 0, 101, null, null, 2)
        val lastBalance = firstBalances.last()
        val secondBalances = database.historyDao().keysetPage(
            accountId,
            "USD",
            0,
            101,
            lastBalance.recordedAt,
            lastBalance.id,
            2
        )
        assertEquals(listOf(100L, 100L), firstBalances.map { it.recordedAt })
        assertEquals(listOf(90L, 80L), secondBalances.map { it.recordedAt })
        assertEquals(4L, database.historyDao().countRange(accountId, "USD", 0, 101))
        assertEquals(
            38.0,
            requireNotNull(database.historyDao().aggregateRange(accountId, "USD", 0, 101).totalBalanceSum),
            0.0
        )
        assertEquals(4, database.historyDao().range(accountId, "USD", 0, 101).size)

        database.usageDao().upsertSnapshotWithRecords(
            UsageSnapshotEntity("usage-100-a", accountId, 100, identityDiscriminator = "a"),
            emptyList()
        )
        database.usageDao().upsertSnapshotWithRecords(
            UsageSnapshotEntity("usage-100-b", accountId, 100, identityDiscriminator = "b"),
            emptyList()
        )
        database.usageDao().upsertSnapshotWithRecords(UsageSnapshotEntity("usage-090", accountId, 90), emptyList())
        val firstUsage = database.usageDao().keysetPage(accountId, 0, 101, null, null, 2)
        val lastUsage = firstUsage.last()
        val secondUsage = database.usageDao().keysetPage(
            accountId,
            0,
            101,
            lastUsage.capturedAt,
            lastUsage.id,
            2
        )
        assertEquals(listOf("usage-090", "usage-100-a"), firstUsage.map { it.id })
        assertEquals(listOf("usage-100-b"), secondUsage.map { it.id })
        assertEquals(3L, database.usageDao().countRange(accountId, 0, 101))
        assertEquals(3, database.usageDao().range(accountId, 0, 101).size)

        database.eventLogDao().insertAll(
            listOf(
                EventLogEntity(eventType = EventLogType.AUTO, recordedAt = 100),
                EventLogEntity(eventType = EventLogType.MANUAL, recordedAt = 100),
                EventLogEntity(eventType = EventLogType.WATCHDOG, recordedAt = 90)
            )
        )
        val firstEvents = database.eventLogDao().newestPage(null, null, 2)
        val lastEvent = firstEvents.last()
        val secondEvents = database.eventLogDao().newestPage(lastEvent.recordedAt, lastEvent.id, 2)
        assertEquals(listOf(100L, 100L), firstEvents.map { it.recordedAt })
        assertEquals(listOf(90L), secondEvents.map { it.recordedAt })
    }

    @Test
    fun `refresh completion rejects stale account revision and premature aggregate`() = runTest {
        val accountId = "refresh-stale"
        database.accountDao().insertCreate(testAccount(accountId))
        database.refreshRunDao().insertRun(RefreshRunEntity("stale-run", RefreshRunSource.MANUAL, startedAt = 1))
        database.refreshRunDao().insertRunningResult(
            RefreshAccountResultEntity("stale-run", accountId, accountRevision = 0, startedAt = 1)
        )
        assertEquals(
            1,
            database.accountDao().updateWhereRevision(
                accountId, 0, 1, "edited", ProviderType.OPENAI, "{}", "generation-1", 2
            )
        )
        assertEquals(
            0,
            database.refreshRunDao().completeAccountAtomically(
                runId = "stale-run",
                accountId = accountId,
                state = RefreshAccountResultState.SUCCEEDED,
                errorCategory = null,
                errorCode = null,
                retryable = false,
                retryAfterAt = null,
                dataTimestamp = 2,
                stale = false,
                attemptCount = 1,
                completedAt = 3
            )
        )

        database.refreshRunDao().insertRun(RefreshRunEntity("premature-run", RefreshRunSource.MANUAL, startedAt = 1))
        database.refreshRunDao().insertRunningResult(
            RefreshAccountResultEntity("premature-run", "unrelated", accountRevision = 0, startedAt = 1)
        )
        assertEquals(
            0,
            database.refreshRunDao().deriveAndUpdateAggregate(
                runId = "premature-run",
                completedAt = 4
            )
        )
    }

    @Test
    fun `singleton DAOs never create a nonzero identity`() = runTest {
        database.appMetadataDao().ensureSingleton(1)
        database.appSettingsDao().ensureSingleton(1)
        assertEquals(0, database.maintenanceCheckpointDao().getOrCreate("UTC").id)
        assertEquals(0, database.monitoringStateDao().getOrCreate(1).id)
        assertEquals(1L, database.queryLong("SELECT COUNT(*) FROM app_metadata"))
        assertEquals(1L, database.queryLong("SELECT COUNT(*) FROM app_settings"))
        assertEquals(1L, database.queryLong("SELECT COUNT(*) FROM maintenance_checkpoint"))
        assertEquals(1L, database.queryLong("SELECT COUNT(*) FROM monitoring_state"))
    }

    private fun summary(closeBalance: Double) = DailySummaryEntity(
        date = "2026-08-06",
        accountId = "summary-account",
        currency = "USD",
        openBalance = 20.0,
        closeBalance = closeBalance,
        consumedBalance = 1.5,
        toppedUpBalance = 0.0,
        averageBalance = 19.0,
        sampleCount = 2,
        generatedAt = 100
    )

    private fun download(
        id: String,
        activeTag: String? = null,
        activePath: String? = null
    ) = DownloadOperationEntity(
        id = id,
        ownerId = "owner-$id",
        tag = "tag-$id",
        sourceUrl = "https://example.invalid/$id",
        temporaryPath = "/tmp/$id",
        targetPath = "/target/$id",
        activeTag = activeTag,
        activeTargetPath = activePath,
        createdAt = 1,
        updatedAt = 1
    )

    private suspend fun assertConstraint(block: suspend () -> Unit) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertTrue("Expected SQLiteConstraintException, got $failure", failure is SQLiteConstraintException)
    }
}
