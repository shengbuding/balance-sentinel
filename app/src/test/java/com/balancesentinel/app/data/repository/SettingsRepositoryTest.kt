package com.balancesentinel.app.data.repository

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.local.createWalletTestDatabase
import com.balancesentinel.app.data.local.settings.AccountAlertSettingEntity
import com.balancesentinel.app.data.local.settings.AlertRuntimeStateEntity
import com.balancesentinel.app.data.local.settings.AppSettingsEntity
import com.balancesentinel.app.data.local.settings.NotificationWalletSelectionEntity
import com.balancesentinel.app.data.local.settings.SnoozeStateEntity
import com.balancesentinel.app.data.local.testAccount
import com.balancesentinel.app.data.model.AccountInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryTest {
    private lateinit var database: WalletDatabase
    private lateinit var repository: RoomSettingsRepository

    @Before
    fun setUp() {
        database = createWalletTestDatabase()
        repository = RoomSettingsRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `publication exposes one snapshot containing every settings table`() = runTest {
        database.accountDao().insertCreate(testAccount("acct"))
        val expected = SettingsSnapshot(
            appSettings = AppSettingsEntity(
                backgroundRefreshIntervalSeconds = 900,
                foregroundMonitoringIntervalSeconds = 45,
                alertEnabled = true,
                alertThreshold = 10.0,
                updatedAt = 100
            ),
            accountAlertSettings = listOf(AccountAlertSettingEntity("acct", "USD", true, true)),
            notificationSelections = listOf(NotificationWalletSelectionEntity("acct", "USD", 0)),
            alertRuntimeStates = listOf(AlertRuntimeStateEntity("acct", "USD", anchorBalance = 4.0)),
            snoozes = listOf(SnoozeStateEntity("acct", 5000))
        )

        repository.publishSnapshot(expected, publishedAt = 100)

        val state = repository.snapshot.first { it is SettingsSnapshotState.Ready }
        val actual = (state as SettingsSnapshotState.Ready).value
        assertEquals(expected, actual)
        assertSame(actual, (repository.snapshot.value as SettingsSnapshotState.Ready).value)
    }

    @Test
    fun `config import publishes imported account settings after account persistence`() = runTest {
        val importedAccountId = "imported-account"
        val settings = ConfigSettings(
            refreshIntervalSeconds = 30,
            alertEnabled = true,
            alertThreshold = 50f,
            changeAlertEnabled = true,
            changeAlertThreshold = 5f,
            changeAlertPeriodMinutes = 60,
            logMaxEntries = 100,
            perCurrencyAlertSettings = listOf(
                PerCurrencyAlertSetting(importedAccountId, "USD", true, true)
            ),
            notificationSelectedWallets = listOf(
                NotificationWalletSelection(importedAccountId, "USD")
            )
        )

        repository.applyConfigImport(settings) {
            database.accountDao().insertCreate(testAccount(importedAccountId))
        }

        val actual = repository.readSnapshot()
        assertEquals(
            listOf(AccountAlertSettingEntity(importedAccountId, "USD", true, true)),
            actual.accountAlertSettings
        )
        assertEquals(
            listOf(NotificationWalletSelectionEntity(importedAccountId, "USD", 0)),
            actual.notificationSelections
        )
    }

    @Test
    fun `a failed Room transaction leaves all settings rows unchanged`() = runTest {
        database.accountDao().insertCreate(testAccount("acct"))
        val before = SettingsSnapshot(
            appSettings = AppSettingsEntity(alertEnabled = false, updatedAt = 1),
            accountAlertSettings = listOf(AccountAlertSettingEntity("acct", "USD", false, false)),
            notificationSelections = emptyList(),
            alertRuntimeStates = emptyList(),
            snoozes = emptyList()
        )
        database.withTransaction {
            database.appSettingsDao().upsert(
                backgroundRefreshIntervalSeconds = before.backgroundRefreshIntervalSeconds,
                foregroundMonitoringIntervalSeconds = before.foregroundMonitoringIntervalSeconds,
                alertEnabled = before.appSettings.alertEnabled,
                alertThreshold = before.appSettings.alertThreshold,
                changeAlertEnabled = before.appSettings.changeAlertEnabled,
                changeAlertThreshold = before.appSettings.changeAlertThreshold,
                changeAlertPeriodMinutes = before.appSettings.changeAlertPeriodMinutes,
                logMaxEntries = before.appSettings.logMaxEntries,
                snoozeDurationMinutes = before.appSettings.snoozeDurationMinutes,
                showTotalBalanceInNotification = before.appSettings.showTotalBalanceInNotification,
                updatedAt = before.appSettings.updatedAt
            )
            database.settingsDao().replaceAccountAlertSettings(before.accountAlertSettings)
        }

        try {
            database.withTransaction {
                database.appSettingsDao().upsert(
                    backgroundRefreshIntervalSeconds = 900,
                    foregroundMonitoringIntervalSeconds = 60,
                    alertEnabled = true,
                    alertThreshold = 20.0,
                    changeAlertEnabled = false,
                    changeAlertThreshold = 0.0,
                    changeAlertPeriodMinutes = 0,
                    logMaxEntries = 100,
                    snoozeDurationMinutes = 60,
                    showTotalBalanceInNotification = true,
                    updatedAt = 2
                )
                database.settingsDao().replaceAccountAlertSettings(
                    listOf(AccountAlertSettingEntity("acct", "EUR", true, false))
                )
                error("injected publication failure")
            }
        } catch (expected: IllegalStateException) {
            assertEquals("injected publication failure", expected.message)
        }

        assertEquals(false, database.appSettingsDao().get()?.alertEnabled)
        assertEquals("USD", database.settingsDao().getAccountAlertSettings().single().currency)
    }

    @Test
    fun `configuration export distinguishes background and foreground cadences`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = WidgetPrefs(context)
        prefs.resetAll()
        prefs.refreshIntervalSeconds = 30

        val json = ConfigManager.buildConfig(context, emptyList(), prefs)

        assertTrue(json.contains("\"backgroundRefreshInterval\": 900"))
        assertTrue(json.contains("\"foregroundMonitoringInterval\": 30"))
    }

    @Test
    fun `Room import rejects a settings change even when metadata revision is unchanged`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val accountStorage = context.getSharedPreferences(
            "settings-stale-plan-${System.nanoTime()}",
            Context.MODE_PRIVATE
        )
        val accountManager = ApiKeyManager(context, accountStorage)
        val local = AccountInfo(
            id = accountManager.computeId("sk-room-stale-old"),
            label = "Local",
            apiKey = "sk-room-stale-old"
        )
        accountManager.replaceAll(listOf(local))
        database.accountDao().insertCreate(testAccount(local.id))
        val settings = ConfigSettings(
            refreshIntervalSeconds = 30,
            alertEnabled = false,
            alertThreshold = 1f,
            changeAlertEnabled = false,
            changeAlertThreshold = 0f,
            changeAlertPeriodMinutes = 60,
            logMaxEntries = 100,
            backgroundRefreshInterval = 900,
            foregroundMonitoringInterval = 30
        )
        repository.applyConfigSettings(settings)
        val planner = BackupImportPlanner(accountManager, WidgetPrefs(context), repository)
        val plan = planner.plan(
            AppConfig(
                exportedAt = "now",
                appVersion = "test",
                credentialsIncluded = true,
                accounts = listOf(local),
                settings = settings
            ),
            accountManager.getAccounts(),
            ImportMode.MERGE
        )
        val baselineRevision = repository.currentRevision()
        val current = requireNotNull(database.appSettingsDao().get())
        database.appSettingsDao().upsert(
            backgroundRefreshIntervalSeconds = current.backgroundRefreshIntervalSeconds,
            foregroundMonitoringIntervalSeconds = current.foregroundMonitoringIntervalSeconds,
            alertEnabled = true,
            alertThreshold = current.alertThreshold,
            changeAlertEnabled = current.changeAlertEnabled,
            changeAlertThreshold = current.changeAlertThreshold,
            changeAlertPeriodMinutes = current.changeAlertPeriodMinutes,
            logMaxEntries = current.logMaxEntries,
            snoozeDurationMinutes = current.snoozeDurationMinutes,
            showTotalBalanceInNotification = current.showTotalBalanceInNotification,
            updatedAt = current.updatedAt + 1
        )

        assertEquals(baselineRevision, repository.currentRevision())
        val failure = runCatching {
            planner.applyAsync(plan, confirmedFullReplace = false)
        }.exceptionOrNull()
        assertTrue(failure is StalePlanException)
        assertEquals(listOf(local), accountManager.getAccounts())
        accountStorage.edit().clear().commit()
    }

    @Test
    fun `failed URI import restores account and Room settings preimages`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val accountStorage = context.getSharedPreferences(
            "settings-round-two-import-${System.nanoTime()}",
            Context.MODE_PRIVATE
        )
        val accountManager = ApiKeyManager(context, accountStorage)
        val original = com.balancesentinel.app.data.model.AccountInfo(
            id = accountManager.computeId("sk-old-secret"),
            label = "Old",
            apiKey = "sk-old-secret"
        )
        val imported = original.copy(
            id = accountManager.computeId("sk-new-secret"),
            label = "Imported",
            apiKey = "sk-new-secret"
        )
        accountManager.replaceAll(listOf(original))
        database.accountDao().insertCreate(testAccount(original.id))

        val beforeSettings = SettingsSnapshot(
            appSettings = AppSettingsEntity(alertEnabled = false, alertThreshold = 1.0, updatedAt = 1),
            accountAlertSettings = listOf(AccountAlertSettingEntity(original.id, "USD", false, false)),
            notificationSelections = emptyList(),
            alertRuntimeStates = emptyList(),
            snoozes = emptyList()
        )
        repository.publishSnapshot(beforeSettings, publishedAt = beforeSettings.appSettings.updatedAt)
        val beforeAccounts = accountManager.getAccounts()
        val file = File(context.cacheDir, "settings-round-two-import-${System.nanoTime()}.json")
        file.writeText(ConfigManager.buildConfig(context, listOf(imported), SettingsSnapshot(
            appSettings = AppSettingsEntity(alertEnabled = true, alertThreshold = 50.0, updatedAt = 2),
            accountAlertSettings = emptyList(),
            notificationSelections = emptyList(),
            alertRuntimeStates = emptyList(),
            snoozes = emptyList()
        ), includeTokens = true))

        val failingRepository = FailAfterApplyRepository(repository)
        val planner = BackupImportPlanner(accountManager, WidgetPrefs(context), failingRepository)
        try {
            val config = checkNotNull(ConfigManager.importFromUri(context, Uri.fromFile(file)))
            val plan = planner.plan(config, beforeAccounts, ImportMode.REPLACE_ALL)
            planner.applyAsync(plan, confirmedFullReplace = true)
        } catch (expected: IllegalStateException) {
            assertEquals("injected publication failure", expected.message)
        } finally {
            file.delete()
        }

        assertEquals(beforeAccounts, accountManager.getAccounts())
        assertEquals(beforeSettings, repository.readSnapshot())
        accountStorage.edit().clear().commit()
    }

    @Test
    fun `failed import rollback preserves concurrent Room runtime update`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val accountStorage = context.getSharedPreferences(
            "settings-round-three-import-${System.nanoTime()}",
            Context.MODE_PRIVATE
        )
        val accountManager = ApiKeyManager(context, accountStorage)
        val original = com.balancesentinel.app.data.model.AccountInfo(
            id = accountManager.computeId("sk-round-three-old"),
            label = "Old",
            apiKey = "sk-round-three-old"
        )
        val imported = original.copy(
            id = accountManager.computeId("sk-round-three-new"),
            label = "Imported",
            apiKey = "sk-round-three-new"
        )
        accountManager.replaceAll(listOf(original))
        database.accountDao().insertCreate(testAccount(original.id))
        val beforeSettings = SettingsSnapshot(
            appSettings = AppSettingsEntity(alertEnabled = false, alertThreshold = 1.0, updatedAt = 1),
            accountAlertSettings = emptyList(),
            notificationSelections = emptyList(),
            alertRuntimeStates = emptyList(),
            snoozes = emptyList()
        )
        repository.publishSnapshot(beforeSettings, publishedAt = beforeSettings.appSettings.updatedAt)
        val concurrentRuntime = AlertRuntimeStateEntity(
            accountId = original.id,
            currency = "USD",
            anchorBalance = 123.0,
            anchorAt = 456L
        )
        val failingRepository = PauseAfterApplyRepository(repository)
        val planner = BackupImportPlanner(accountManager, WidgetPrefs(context), failingRepository)
        val plan = BackupImportPlan(
            mode = ImportMode.REPLACE_ALL,
            finalAccounts = listOf(imported),
            matchedUpdatedCount = 0,
            retainedCredentialCount = 0,
            createdCount = 1,
            skippedCount = 0,
            conflictCount = 0,
            deletedCount = 1,
            scriptAuthorizations = emptyList(),
            canApply = true,
            blockingReasons = emptyList(),
            settings = ConfigSettings(
                refreshIntervalSeconds = 30,
                alertEnabled = true,
                alertThreshold = 50f,
                changeAlertEnabled = false,
                changeAlertThreshold = 0f,
                changeAlertPeriodMinutes = 60,
                logMaxEntries = 100
            )
        )

        val importFailure = async {
            runCatching { planner.applyAsync(plan, confirmedFullReplace = true) }.exceptionOrNull()
        }
        failingRepository.applied.await()
        val concurrentUpdate = async(start = CoroutineStart.UNDISPATCHED) {
            repository.updateSnapshot { current ->
                current.copy(alertRuntimeStates = listOf(concurrentRuntime))
            }
        }
        failingRepository.failImport.complete(Unit)

        assertEquals("injected publication failure", importFailure.await()?.message)
        concurrentUpdate.await()
        val afterFailure = repository.readSnapshot()
        assertEquals(false, afterFailure.appSettings.alertEnabled)
        assertEquals(1.0, afterFailure.appSettings.alertThreshold, 0.0)
        assertEquals(listOf(concurrentRuntime), afterFailure.alertRuntimeStates)
        assertEquals(listOf(original), accountManager.getAccounts())
        accountStorage.edit().clear().commit()
    }

    private class FailAfterApplyRepository(
        private val delegate: SettingsRepository
    ) : SettingsRepository {
        override val snapshot = delegate.snapshot
        override suspend fun readSnapshot(): SettingsSnapshot = delegate.readSnapshot()
        override suspend fun publishSnapshot(snapshot: SettingsSnapshot, publishedAt: Long) =
            delegate.publishSnapshot(snapshot, publishedAt)
        override suspend fun hasPersistedSnapshot(): Boolean = delegate.hasPersistedSnapshot()
        override suspend fun currentRevision(): Long = delegate.currentRevision()
        override suspend fun updateSnapshot(transform: (SettingsSnapshot) -> SettingsSnapshot): SettingsSnapshot =
            delegate.updateSnapshot(transform)
        override suspend fun applyConfigSettings(settings: ConfigSettings): SettingsSnapshot {
            delegate.applyConfigSettings(settings)
            error("injected publication failure")
        }
        override suspend fun applyConfigImport(
            settings: ConfigSettings,
            persistAccounts: suspend () -> Unit
        ): SettingsSnapshot = delegate.applyConfigImport(settings) {
            persistAccounts()
            error("injected publication failure")
        }
    }

    private class PauseAfterApplyRepository(
        private val delegate: SettingsRepository
    ) : SettingsRepository {
        val applied = CompletableDeferred<Unit>()
        val failImport = CompletableDeferred<Unit>()
        override val snapshot = delegate.snapshot
        override suspend fun readSnapshot(): SettingsSnapshot = delegate.readSnapshot()
        override suspend fun publishSnapshot(snapshot: SettingsSnapshot, publishedAt: Long) =
            delegate.publishSnapshot(snapshot, publishedAt)
        override suspend fun hasPersistedSnapshot(): Boolean = delegate.hasPersistedSnapshot()
        override suspend fun currentRevision(): Long = delegate.currentRevision()
        override suspend fun updateSnapshot(transform: (SettingsSnapshot) -> SettingsSnapshot): SettingsSnapshot =
            delegate.updateSnapshot(transform)
        override suspend fun applyConfigSettings(settings: ConfigSettings): SettingsSnapshot {
            delegate.applyConfigSettings(settings)
            applied.complete(Unit)
            failImport.await()
            error("injected publication failure")
        }
        override suspend fun applyConfigImport(
            settings: ConfigSettings,
            persistAccounts: suspend () -> Unit
        ): SettingsSnapshot = delegate.applyConfigImport(settings) {
            persistAccounts()
            applied.complete(Unit)
            failImport.await()
            error("injected publication failure")
        }
    }
}
