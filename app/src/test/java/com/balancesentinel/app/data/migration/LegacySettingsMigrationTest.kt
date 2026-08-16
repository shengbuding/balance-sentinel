package com.balancesentinel.app.data.migration

import com.balancesentinel.app.data.repository.LegacySettings
import com.balancesentinel.app.data.repository.LegacySettingsMigration
import com.balancesentinel.app.data.repository.LegacySettingsSource
import com.balancesentinel.app.data.repository.NotificationWalletSelection
import com.balancesentinel.app.data.repository.RoomSettingsRepository
import com.balancesentinel.app.data.local.createWalletTestDatabase
import com.balancesentinel.app.data.local.testAccount
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LegacySettingsMigrationTest {
    private val database = createWalletTestDatabase()

    @After
    fun tearDown() = database.close()

    @Test
    fun `legacy interval is shared by foreground and background settings`() = runTest {
        val source = source(refreshIntervalSeconds = 899)
        val migration = LegacySettingsMigration(source, RoomSettingsRepository(database))

        val snapshot = migration.migrate()

        assertEquals(899, snapshot.foregroundMonitoringIntervalSeconds)
        assertEquals(899, snapshot.backgroundRefreshIntervalSeconds)
    }

    @Test
    fun `900 seconds remains the shared cadence`() = runTest {
        val migration = LegacySettingsMigration(source(refreshIntervalSeconds = 900), RoomSettingsRepository(database))

        val snapshot = migration.migrate()

        assertEquals(900, snapshot.foregroundMonitoringIntervalSeconds)
        assertEquals(900, snapshot.backgroundRefreshIntervalSeconds)
    }

    @Test
    fun `901 seconds remains the shared cadence`() = runTest {
        val migration = LegacySettingsMigration(source(refreshIntervalSeconds = 901), RoomSettingsRepository(database))

        val snapshot = migration.migrate()

        assertEquals(901, snapshot.foregroundMonitoringIntervalSeconds)
        assertEquals(901, snapshot.backgroundRefreshIntervalSeconds)
    }

    @Test
    fun `legacy total position ignores notification rows that cannot be mapped`() = runTest {
        database.accountDao().insertCreate(testAccount("room-a", displayOrder = 0))
        database.accountDao().insertCreate(testAccount("room-b", displayOrder = 1))
        val migration = LegacySettingsMigration(
            source = source(
                refreshIntervalSeconds = 900,
                notificationSelections = listOf(
                    NotificationWalletSelection("missing", "USD"),
                    NotificationWalletSelection("legacy-a", "CNY"),
                    NotificationWalletSelection("legacy-b", "USD")
                ),
                notificationTotalDisplayOrder = 2
            ),
            repository = RoomSettingsRepository(database),
            resolveAccountId = { legacyId ->
                when (legacyId) {
                    "legacy-a" -> "room-a"
                    "legacy-b" -> "room-b"
                    else -> null
                }
            }
        )

        val snapshot = migration.migrate()

        assertEquals(listOf("room-a", "room-b"), snapshot.notificationSelections.map { it.accountId })
        assertEquals(1, snapshot.appSettings.notificationTotalDisplayOrder)
    }

    private fun source(
        refreshIntervalSeconds: Int,
        notificationSelections: List<NotificationWalletSelection> = emptyList(),
        notificationTotalDisplayOrder: Int = 0
    ): LegacySettingsSource = LegacySettingsSource {
        LegacySettings(
            refreshIntervalSeconds = refreshIntervalSeconds,
            logMaxEntries = 100,
            alertEnabled = false,
            alertThreshold = 0f,
            changeAlertEnabled = false,
            changeAlertThreshold = 0f,
            changeAlertPeriodMinutes = 0,
            snoozeDurationMinutes = 60,
            showTotalBalanceInNotification = true,
            perCurrencyAlertSettings = emptyList(),
            notificationSelections = notificationSelections,
            notificationTotalDisplayOrder = notificationTotalDisplayOrder
        )
    }
}
