package com.balancesentinel.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.credentials.CredentialGeneration
import com.balancesentinel.app.data.credentials.CredentialPayload
import com.balancesentinel.app.data.credentials.CredentialReadResult
import com.balancesentinel.app.data.credentials.ConfigImportRecoveryStore
import com.balancesentinel.app.data.credentials.CredentialStore
import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.local.account.AccountEntity
import com.balancesentinel.app.data.local.account.AccountState
import com.balancesentinel.app.data.local.history.BalanceRecordEntity
import com.balancesentinel.app.data.local.history.BalanceRecordSource
import com.balancesentinel.app.data.local.mutation.MutationOperationType
import com.balancesentinel.app.data.local.mutation.MutationStage
import com.balancesentinel.app.data.local.mutation.MutationOperationEntity
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.model.AccountInfo
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConfigImportCoordinatorTest {
    @Test
    fun `config import operation ledger never persists credential material`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, WalletDatabase::class.java).build()
        val prefs = context.getSharedPreferences("config-import-${System.nanoTime()}", Context.MODE_PRIVATE)
        val manager = ApiKeyManager(context, prefs)
        val widgetPrefs = WidgetPrefs(context)
        val settingsRepository = RoomSettingsRepository(database)
        val oldAccount = AccountInfo(OLD_ACCOUNT_ID, "Old", OLD_SECRET)
        val newAccount = AccountInfo(NEW_ACCOUNT_ID, "New", NEW_SECRET)
        val credentialStore = RecordingCredentialStore().apply {
            payload = CredentialPayload(listOf(oldAccount))
            present = true
        }
        val coordinator = RoomConfigImportCoordinator(database, credentialStore, settingsRepository)
        val planner = BackupImportPlanner(manager, widgetPrefs, settingsRepository, coordinator)

        try {
            insertAccount(database, oldAccount)
            val plan = planner.plan(
                config(accounts = listOf(newAccount)),
                listOf(oldAccount),
                ImportMode.REPLACE_ALL
            )

            planner.applyAsync(plan, confirmedFullReplace = true)

            val cursor = database.openHelper.readableDatabase.query(
                "SELECT targets_json, staged_generation_manifest_json FROM mutation_operations " +
                    "WHERE operation_type = 'CONFIG_IMPORT'"
            )
            val persisted = cursor.use {
                buildString {
                    while (it.moveToNext()) {
                        append(it.getString(0))
                        append(it.getString(1))
                    }
                }
            }
            assertTrue("old secret leaked into Room", OLD_SECRET !in persisted)
            assertTrue("new secret leaked into Room", NEW_SECRET !in persisted)
        } finally {
            database.close()
            prefs.edit().clear().commit()
            widgetPrefs.resetAll()
        }
    }

    @Test
    fun `stale startup recovery restores credentials instead of retaining staged import`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, WalletDatabase::class.java).build()
        val settingsRepository = RoomSettingsRepository(database)
        val oldAccount = AccountInfo(OLD_ACCOUNT_ID, "Old", OLD_SECRET)
        val newAccount = AccountInfo(NEW_ACCOUNT_ID, "New", NEW_SECRET)
        val credentialStore = RecordingCredentialStore().apply {
            payload = CredentialPayload(listOf(newAccount))
            present = true
        }
        val coordinator = RoomConfigImportCoordinator(database, credentialStore, settingsRepository)
        val operationId = "11111111-1111-4111-8111-111111111111"
        try {
            settingsRepository.readSnapshot()
            insertAccount(database, oldAccount)
            database.appMetadataDao().ensureSingleton(1L)
            val baselineRevision = settingsRepository.currentRevision()
            val recoveryManifest = Json.encodeToString(
                ConfigImportManifestForTest(
                    desiredPayload = CredentialPayload(listOf(newAccount)),
                    rollbackPayload = CredentialPayload(listOf(oldAccount)),
                    settings = defaultSettings(),
                    credentialGeneration = "config-import:$operationId"
                )
            )
            credentialStore.manifests[operationId] = recoveryManifest
            database.mutationOperationDao().insertPrepared(
                MutationOperationEntity(
                    id = operationId,
                    operationType = MutationOperationType.CONFIG_IMPORT,
                    stage = MutationStage.VERIFIED,
                    targetsJson = Json.encodeToString(listOf(NEW_ACCOUNT_ID)),
                    stagedGenerationManifestJson = Json.encodeToString(
                        ConfigImportRoomManifestForTest(
                            operationId = operationId,
                            credentialGeneration = "config-import:$operationId",
                            desiredAccountIds = listOf(NEW_ACCOUNT_ID),
                            manifestFingerprint = sha256(recoveryManifest)
                        )
                    ),
                    baselineRevision = baselineRevision,
                    createdAt = 1L,
                    updatedAt = 1L
                )
            )
            assertEquals(
                1,
                database.appMetadataDao().incrementRevisionIfCurrent(baselineRevision, 2L)
            )

            coordinator.recover()

            assertEquals(CredentialPayload(listOf(oldAccount)), credentialStore.payload)
            val operation = requireNotNull(database.mutationOperationDao().get(operationId))
            assertEquals(MutationStage.FAILED, operation.stage)
        } finally {
            database.close()
        }
    }

    @Test
    fun `production import publishes UUID account credentials and settings together`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, WalletDatabase::class.java).build()
        val prefs = context.getSharedPreferences("config-import-${System.nanoTime()}", Context.MODE_PRIVATE)
        val manager = ApiKeyManager(context, prefs)
        val widgetPrefs = WidgetPrefs(context)
        val settingsRepository = RoomSettingsRepository(database)
        val credentialStore = RecordingCredentialStore()
        val coordinator = RoomConfigImportCoordinator(database, credentialStore, settingsRepository)
        val planner = BackupImportPlanner(
            manager,
            widgetPrefs,
            settingsRepository,
            coordinator
        )
        val accountId = "4fdf6c7e-8b6d-4f3b-9cf5-8a8c57ef4521"
        val settings = ConfigSettings(
            refreshIntervalSeconds = 45,
            alertEnabled = true,
            alertThreshold = 12f,
            changeAlertEnabled = false,
            changeAlertThreshold = 0f,
            changeAlertPeriodMinutes = 60,
            logMaxEntries = 25
        )
        val config = AppConfig(
            credentialsIncluded = true,
            exportedAt = "2026-08-11T00:00:00",
            appVersion = "2.0",
            accounts = listOf(AccountInfo(accountId, "Imported", "sk-imported")),
            settings = settings
        )

        try {
            val plan = planner.plan(config, emptyList(), ImportMode.REPLACE_ALL)
            planner.applyAsync(plan, confirmedFullReplace = true)

            val row = requireNotNull(database.accountDao().get(accountId))
            assertEquals("Imported", row.label)
            assertEquals(accountId, credentialStore.payload.accounts.single().id)
            assertEquals(row.revision, credentialStore.payload.accounts.single().revision)
            val published = settingsRepository.readSnapshot().appSettings
            assertTrue(published.alertEnabled)
            assertEquals(25, published.logMaxEntries)
            assertEquals(45, published.foregroundMonitoringIntervalSeconds)
            assertTrue(database.mutationOperationDao().listRecoverableByType(
                com.balancesentinel.app.data.local.mutation.MutationOperationType.CONFIG_IMPORT
            ).isEmpty())
        } finally {
            database.close()
            prefs.edit().clear().commit()
            widgetPrefs.resetAll()
        }
    }

    @Test
    fun `replace all preserves hidden legacy orphan and its migrated history`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, WalletDatabase::class.java).build()
        val prefs = context.getSharedPreferences("config-import-${System.nanoTime()}", Context.MODE_PRIVATE)
        val manager = ApiKeyManager(context, prefs)
        val widgetPrefs = WidgetPrefs(context)
        val settingsRepository = RoomSettingsRepository(database)
        val credentialStore = RecordingCredentialStore()
        val coordinator = RoomConfigImportCoordinator(database, credentialStore, settingsRepository)
        val planner = BackupImportPlanner(manager, widgetPrefs, settingsRepository, coordinator)
        val legacyId = "1ac8657256cd4df2"
        val orphanId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        val operationId = "legacy-orphan-preservation"
        val imported = AccountInfo(NEW_ACCOUNT_ID, "Imported", NEW_SECRET)

        try {
            database.accountDao().insertCreate(
                AccountEntity(
                    id = orphanId,
                    displayOrder = 0,
                    label = "Recovered legacy history",
                    providerType = ProviderType.DEEPSEEK,
                    providerConfigJson = "{}",
                    activeCredentialGeneration = AccountEntity.LEGACY_ORPHAN_GENERATION_PREFIX + legacyId,
                    state = AccountState.PENDING,
                    legacyStorageId = legacyId,
                    createdAt = 1L,
                    updatedAt = 1L
                )
            )
            database.historyDao().insertBalanceBatch(
                listOf(
                    BalanceRecordEntity(
                        accountId = orphanId,
                        currency = "USD",
                        recordedAt = 10L,
                        totalBalance = 5.0,
                        source = BalanceRecordSource.LEGACY_MIGRATION,
                        migrationOperationId = operationId,
                        migrationSourceOrdinal = 0
                    )
                )
            )
            val plan = planner.plan(
                config(accounts = listOf(imported)),
                emptyList(),
                ImportMode.REPLACE_ALL
            )

            planner.applyAsync(plan, confirmedFullReplace = true)

            val orphan = requireNotNull(database.accountDao().get(orphanId))
            assertEquals(AccountState.PENDING, orphan.state)
            assertEquals(1L, database.historyDao().countMigrationRecords(operationId))
            assertEquals(listOf(NEW_ACCOUNT_ID), credentialStore.payload.accounts.map { it.id })
        } finally {
            database.close()
            prefs.edit().clear().commit()
            widgetPrefs.resetAll()
        }
    }

    @Test
    fun `credential rollback failure remains recoverable until next startup`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, WalletDatabase::class.java).build()
        val prefs = context.getSharedPreferences("config-import-${System.nanoTime()}", Context.MODE_PRIVATE)
        val manager = ApiKeyManager(context, prefs)
        val widgetPrefs = WidgetPrefs(context)
        val settingsRepository = RoomSettingsRepository(database)
        val credentialStore = RecordingCredentialStore().apply { failWritesAfterFirstWrite = true }
        val coordinator = RoomConfigImportCoordinator(
            database,
            credentialStore,
            settingsRepository,
            publishPublication = { error("injected publication boundary failure") }
        )
        val planner = BackupImportPlanner(manager, widgetPrefs, settingsRepository, coordinator)
        val accountId = "4fdf6c7e-8b6d-4f3b-9cf5-8a8c57ef4522"
        val config = AppConfig(
            credentialsIncluded = true,
            exportedAt = "2026-08-11T00:00:00",
            appVersion = "2.0",
            accounts = listOf(AccountInfo(accountId, "Retry", "sk-retry")),
            settings = ConfigSettings(refreshIntervalSeconds = 45, alertEnabled = false, alertThreshold = 0f, changeAlertEnabled = false, changeAlertThreshold = 0f, changeAlertPeriodMinutes = 60, logMaxEntries = 25)
        )

        try {
            val plan = planner.plan(config, emptyList(), ImportMode.REPLACE_ALL)
            runCatching { planner.applyAsync(plan, confirmedFullReplace = true) }

            val recoverable = database.mutationOperationDao().listRecoverableByType(
                com.balancesentinel.app.data.local.mutation.MutationOperationType.CONFIG_IMPORT
            )
            assertEquals(1, recoverable.size)
            assertEquals(MutationStage.PREPARED, recoverable.single().stage)
            assertEquals("ROLLBACK_PENDING", recoverable.single().errorCode)

            credentialStore.failWritesAfterFirstWrite = false
            coordinator.recover()
            assertTrue(database.mutationOperationDao().listRecoverableByType(
                com.balancesentinel.app.data.local.mutation.MutationOperationType.CONFIG_IMPORT
            ).isEmpty())
            assertTrue(database.accountDao().get(accountId) == null)
            assertEquals(CredentialPayload(emptyList()), credentialStore.payload)
        } finally {
            database.close()
            prefs.edit().clear().commit()
            widgetPrefs.resetAll()
        }
    }

    @Test
    fun `publication failure rolls credentials back without publishing accounts or settings`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, WalletDatabase::class.java).build()
        val prefs = context.getSharedPreferences("config-import-${System.nanoTime()}", Context.MODE_PRIVATE)
        val manager = ApiKeyManager(context, prefs)
        val widgetPrefs = WidgetPrefs(context)
        val settingsRepository = RoomSettingsRepository(database)
        val baselineLogMaxEntries = settingsRepository.readSnapshot().appSettings.logMaxEntries
        val oldPayload = CredentialPayload(emptyList())
        val credentialStore = RecordingCredentialStore().apply {
            payload = oldPayload
            present = true
        }
        val coordinator = RoomConfigImportCoordinator(
            database = database,
            credentialStore = credentialStore,
            settingsRepository = settingsRepository,
            publishPublication = { error("injected publication boundary failure") }
        )
        val planner = BackupImportPlanner(manager, widgetPrefs, settingsRepository, coordinator)
        val accountId = "4fdf6c7e-8b6d-4f3b-9cf5-8a8c57ef4523"
        val config = AppConfig(
            credentialsIncluded = true,
            exportedAt = "2026-08-11T00:00:00",
            appVersion = "2.0",
            accounts = listOf(AccountInfo(accountId, "No publish", "sk-no-publish")),
            settings = ConfigSettings(refreshIntervalSeconds = 45, alertEnabled = true, alertThreshold = 9f, changeAlertEnabled = false, changeAlertThreshold = 0f, changeAlertPeriodMinutes = 60, logMaxEntries = 25)
        )

        try {
            val plan = planner.plan(config, emptyList(), ImportMode.REPLACE_ALL)
            runCatching { planner.applyAsync(plan, confirmedFullReplace = true) }
            assertTrue(database.accountDao().get(accountId) == null)
            assertEquals(oldPayload, credentialStore.payload)
            assertEquals(baselineLogMaxEntries, settingsRepository.readSnapshot().appSettings.logMaxEntries)
        } finally {
            database.close()
            prefs.edit().clear().commit()
            widgetPrefs.resetAll()
        }
    }

    private class RecordingCredentialStore : CredentialStore, ConfigImportRecoveryStore {
        var payload = CredentialPayload(emptyList())
        var present = false
        var failWrites = false
        var failWritesAfterFirstWrite = false
        var failRecoveryManifestClears = false
        private var writeCount = 0
        val manifests = mutableMapOf<String, String>()

        override fun read(): CredentialReadResult = if (present) {
            CredentialReadResult.Valid(payload, CredentialGeneration.ENCRYPTED_PREFERENCES)
        } else {
            CredentialReadResult.Missing
        }

        override suspend fun write(payload: CredentialPayload) {
            writeCount++
            if (failWrites || (failWritesAfterFirstWrite && writeCount > 1)) error("injected credential write failure")
            this.payload = payload
            present = true
        }

        override suspend fun clear() {
            payload = CredentialPayload(emptyList())
            present = false
        }

        override fun readConfigImportManifest(operationId: String): String? = manifests[operationId]

        override fun listConfigImportManifestIds(): Set<String> = manifests.keys

        override suspend fun writeConfigImportManifest(operationId: String, manifest: String) {
            if (failWrites) error("injected credential write failure")
            manifests[operationId] = manifest
        }

        override suspend fun clearConfigImportManifest(operationId: String) {
            if (failWrites || failRecoveryManifestClears) error("injected credential clear failure")
            manifests.remove(operationId)
        }
    }

    private fun config(accounts: List<AccountInfo>) = AppConfig(
        credentialsIncluded = true,
        exportedAt = "2026-08-12T00:00:00",
        appVersion = "2.0",
        accounts = accounts,
        settings = defaultSettings()
    )

    private fun defaultSettings() = ConfigSettings(
        refreshIntervalSeconds = 45,
        alertEnabled = false,
        alertThreshold = 0f,
        changeAlertEnabled = false,
        changeAlertThreshold = 0f,
        changeAlertPeriodMinutes = 60,
        logMaxEntries = 25
    )

    private suspend fun insertAccount(database: WalletDatabase, account: AccountInfo) {
        database.accountDao().insertCreate(
            AccountEntity(
                id = account.id,
                displayOrder = 0,
                label = account.label,
                providerType = ProviderType.DEEPSEEK,
                providerConfigJson = "{}",
                activeCredentialGeneration = "active",
                state = AccountState.VERIFIED,
                createdAt = 1L,
                updatedAt = 1L
            )
        )
    }

    private companion object {
        const val OLD_ACCOUNT_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val NEW_ACCOUNT_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val OLD_SECRET = "sk-old-secret-material"
        const val NEW_SECRET = "sk-new-secret-material"
    }

    @kotlinx.serialization.Serializable
    private data class ConfigImportManifestForTest(
        val desiredPayload: CredentialPayload,
        val rollbackPayload: CredentialPayload,
        val settings: ConfigSettings,
        val credentialGeneration: String
    )

    @kotlinx.serialization.Serializable
    private data class ConfigImportRoomManifestForTest(
        val operationId: String,
        val credentialGeneration: String,
        val desiredAccountIds: List<String>,
        val manifestFingerprint: String
    )

    private fun sha256(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
