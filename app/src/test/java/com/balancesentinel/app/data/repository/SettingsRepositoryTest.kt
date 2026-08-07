package com.balancesentinel.app.data.repository

import androidx.room.withTransaction
import android.content.Context
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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
    fun `transaction failure cannot leave imported prefs newer than Room`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = WidgetPrefs(context)
        prefs.resetAll()
        prefs.alertEnabled = false
        database.appSettingsDao().upsert(
            backgroundRefreshIntervalSeconds = 900,
            foregroundMonitoringIntervalSeconds = 30,
            alertEnabled = false,
            alertThreshold = 0.0,
            changeAlertEnabled = false,
            changeAlertThreshold = 0.0,
            changeAlertPeriodMinutes = 0,
            logMaxEntries = 100,
            snoozeDurationMinutes = 60,
            showTotalBalanceInNotification = true,
            updatedAt = 1
        )

        try {
            database.withTransaction {
                ConfigManager.applySettings(
                    ConfigSettings(
                        refreshIntervalSeconds = 30,
                        alertEnabled = true,
                        alertThreshold = 10f,
                        changeAlertEnabled = false,
                        changeAlertThreshold = 0f,
                        changeAlertPeriodMinutes = 0,
                        logMaxEntries = 100
                    ),
                    prefs
                )
                error("crash after external settings write")
            }
        } catch (expected: IllegalStateException) {
            assertEquals("crash after external settings write", expected.message)
        }

        assertFalse(prefs.alertEnabled)
        assertFalse(database.appSettingsDao().get()?.alertEnabled ?: true)
    }
}
