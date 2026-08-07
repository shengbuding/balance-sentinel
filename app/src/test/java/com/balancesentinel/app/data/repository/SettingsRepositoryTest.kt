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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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

    private class FailAfterApplyRepository(
        private val delegate: SettingsRepository
    ) : SettingsRepository {
        override val snapshot = delegate.snapshot
        override suspend fun readSnapshot(): SettingsSnapshot = delegate.readSnapshot()
        override suspend fun publishSnapshot(snapshot: SettingsSnapshot, publishedAt: Long) =
            delegate.publishSnapshot(snapshot, publishedAt)
        override suspend fun hasPersistedSnapshot(): Boolean = delegate.hasPersistedSnapshot()
        override suspend fun updateSnapshot(transform: (SettingsSnapshot) -> SettingsSnapshot): SettingsSnapshot =
            delegate.updateSnapshot(transform)
        override suspend fun applyConfigSettings(settings: ConfigSettings): SettingsSnapshot {
            delegate.applyConfigSettings(settings)
            error("injected publication failure")
        }
    }
}
