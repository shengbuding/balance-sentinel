package com.balancesentinel.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.local.settings.AccountAlertSettingEntity
import com.balancesentinel.app.data.local.settings.AlertRuntimeStateEntity
import com.balancesentinel.app.data.local.settings.AppSettingsEntity
import com.balancesentinel.app.data.local.settings.NotificationWalletSelectionEntity
import com.balancesentinel.app.data.local.settings.SnoozeStateEntity
import com.balancesentinel.app.data.model.AccountInfo
import com.balancesentinel.app.ui.screen.AlertSettingsContentMode
import com.balancesentinel.app.ui.screen.alertSettingsContentMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SettingsFixRound1RedTest {
    private lateinit var context: Context
    private lateinit var widgetPrefs: WidgetPrefs

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        widgetPrefs = WidgetPrefs(context)
        widgetPrefs.resetAll()
    }

    @After
    fun tearDown() {
        widgetPrefs.resetAll()
        SettingsRepositoryProvider.resetForTests()
    }

    @Test
    fun `failed Room settings publication restores imported accounts`() = runTest {
        val storage = context.getSharedPreferences("settings-fix-rollback-${System.nanoTime()}", Context.MODE_PRIVATE)
        val manager = ApiKeyManager(context, storage)
        val original = account("old-id", "sk-old-secret")
        val imported = account("new-id", "sk-new-secret")
        manager.replaceAll(listOf(original))
        val repository = FailingSettingsRepository()
        val planner = BackupImportPlanner(manager, widgetPrefs, repository)
        val plan = readyPlan(imported)

        try {
            planner.applyAsync(plan, confirmedFullReplace = false)
        } catch (_: IllegalStateException) {
            // The publication failure is the injected fault under test.
        }

        assertEquals(listOf(original), manager.getAccounts())
        storage.edit().clear().commit()
    }

    @Test
    fun `synchronous import entrypoint cannot bypass Room settings publication`() {
        val storage = context.getSharedPreferences("settings-fix-sync-${System.nanoTime()}", Context.MODE_PRIVATE)
        val manager = ApiKeyManager(context, storage)
        val original = account("old-id", "sk-old-secret")
        manager.replaceAll(listOf(original))
        val planner = BackupImportPlanner(manager, widgetPrefs, FailingSettingsRepository())

        assertThrows(IllegalStateException::class.java) {
            planner.apply(readyPlan(account("new-id", "sk-new-secret")), confirmedFullReplace = false)
        }

        assertEquals(listOf(original), manager.getAccounts())
        storage.edit().clear().commit()
    }

    @Test
    fun `legacy balance alert entrypoint uses published Room settings`() {
        val repository = RecordingSettingsRepository(
            initial = snapshot(
                appSettings = AppSettingsEntity(alertEnabled = true, alertThreshold = 10.0, updatedAt = 1L)
            )
        )
        SettingsRepositoryProvider.factory = { repository }
        widgetPrefs.alertEnabled = false
        widgetPrefs.alertThreshold = 100f

        assertTrue(AlertChecker.check(context, "account", "5", "USD", "A"))
        assertFalse(widgetPrefs.getLastAlertedBalance("account", "USD") >= 0f)
    }

    @Test
    fun `legacy change alert entrypoint uses published Room settings and runtime state`() {
        val now = System.currentTimeMillis()
        val repository = RecordingSettingsRepository(
            initial = snapshot(
                appSettings = AppSettingsEntity(
                    changeAlertEnabled = true,
                    changeAlertThreshold = 1.0,
                    changeAlertPeriodMinutes = 60,
                    updatedAt = 1L
                ),
                alertRuntimeStates = listOf(
                    AlertRuntimeStateEntity("account", "USD", anchorBalance = 100.0, anchorAt = now)
                )
            )
        )
        SettingsRepositoryProvider.factory = { repository }
        widgetPrefs.changeAlertEnabled = false

        assertTrue(AlertChecker.checkChange(context, "account", "95", "USD", "A"))
        var attempts = 0
        while (!repository.updated && attempts++ < 100) Thread.sleep(10)
        assertTrue(repository.updated)
    }

    @Test
    fun `importFromUri rejects a valid configuration above the size limit`() {
        val file = File(context.cacheDir, "settings-fix-large-${System.nanoTime()}.json")
        val padding = "x".repeat(1_048_577)
        file.writeText(
            """{"version":2,"credentialsIncluded":false,"exportedAt":"2026-01-01T00:00:00","appVersion":"1.0","accounts":[],"settings":{"refreshIntervalSeconds":30,"alertEnabled":false,"alertThreshold":0.0,"changeAlertEnabled":false,"changeAlertThreshold":0.0,"changeAlertPeriodMinutes":0,"logMaxEntries":100},"padding":"$padding"}"""
        )

        try {
            assertNull(ConfigManager.importFromUri(context, Uri.fromFile(file)))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `compatibility export overload reads Room snapshot instead of WidgetPrefs`() {
        val repository = RecordingSettingsRepository(
            initial = snapshot(
                appSettings = AppSettingsEntity(
                    backgroundRefreshIntervalSeconds = 900,
                    foregroundMonitoringIntervalSeconds = 123,
                    updatedAt = 1L
                )
            )
        )
        SettingsRepositoryProvider.factory = { repository }
        widgetPrefs.refreshIntervalSeconds = 7

        val config = ConfigManager.buildConfig(context, emptyList(), widgetPrefs)
        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString<AppConfig>(config)

        assertEquals(900, decoded.settings.backgroundRefreshInterval)
        assertEquals(123, decoded.settings.foregroundMonitoringInterval)
        assertEquals(123, decoded.settings.refreshIntervalSeconds)
    }

    @Test
    fun `alert settings routes loading and ready snapshots to distinct content`() {
        assertEquals(AlertSettingsContentMode.LOADING, alertSettingsContentMode(settingsLoading = true))
        assertEquals(AlertSettingsContentMode.READY, alertSettingsContentMode(settingsLoading = false))
    }

    private fun snapshot(
        appSettings: AppSettingsEntity = AppSettingsEntity(updatedAt = 1L),
        accountAlertSettings: List<AccountAlertSettingEntity> = emptyList(),
        notificationSelections: List<NotificationWalletSelectionEntity> = emptyList(),
        alertRuntimeStates: List<AlertRuntimeStateEntity> = emptyList(),
        snoozes: List<SnoozeStateEntity> = emptyList()
    ) = SettingsSnapshot(appSettings, accountAlertSettings, notificationSelections, alertRuntimeStates, snoozes)

    private fun account(id: String, key: String) = AccountInfo(id = id, label = id, apiKey = key)

    private fun readyPlan(account: AccountInfo) = BackupImportPlan(
        mode = ImportMode.MERGE,
        finalAccounts = listOf(account),
        matchedUpdatedCount = 0,
        retainedCredentialCount = 0,
        createdCount = 1,
        skippedCount = 0,
        conflictCount = 0,
        deletedCount = 0,
        scriptAuthorizations = emptyList(),
        canApply = true,
        blockingReasons = emptyList(),
        settings = ConfigSettings(
            refreshIntervalSeconds = 30,
            alertEnabled = true,
            alertThreshold = 10f,
            changeAlertEnabled = false,
            changeAlertThreshold = 0f,
            changeAlertPeriodMinutes = 60,
            logMaxEntries = 100
        )
    )

    private class FailingSettingsRepository : SettingsRepository {
        override val snapshot = MutableStateFlow<SettingsSnapshotState>(SettingsSnapshotState.Ready(SettingsSnapshot(AppSettingsEntity(updatedAt = 1L))))
        override suspend fun readSnapshot(): SettingsSnapshot = error("not used")
        override suspend fun publishSnapshot(snapshot: SettingsSnapshot, publishedAt: Long): Unit = error("settings publish failed")
        override suspend fun hasPersistedSnapshot(): Boolean = true
        override suspend fun updateSnapshot(transform: (SettingsSnapshot) -> SettingsSnapshot): SettingsSnapshot = error("settings publish failed")
        override suspend fun applyConfigSettings(settings: ConfigSettings): SettingsSnapshot = error("settings publish failed")
    }

    private class RecordingSettingsRepository(initial: SettingsSnapshot) : SettingsRepository {
        private val state = MutableStateFlow<SettingsSnapshotState>(SettingsSnapshotState.Ready(initial))
        override val snapshot: StateFlow<SettingsSnapshotState> = state
        var updated: Boolean = false
            private set
        override suspend fun readSnapshot(): SettingsSnapshot = (state.value as SettingsSnapshotState.Ready).value
        override suspend fun publishSnapshot(snapshot: SettingsSnapshot, publishedAt: Long) {
            state.value = SettingsSnapshotState.Ready(snapshot)
            updated = true
        }
        override suspend fun hasPersistedSnapshot(): Boolean = true
        override suspend fun updateSnapshot(transform: (SettingsSnapshot) -> SettingsSnapshot): SettingsSnapshot {
            val next = transform(readSnapshot())
            publishSnapshot(next, 1L)
            return next
        }
        override suspend fun applyConfigSettings(settings: ConfigSettings): SettingsSnapshot = error("not used")
    }
}
