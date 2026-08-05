package com.balancesentinel.app.data.local.publication

import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.local.account.AccountState
import com.balancesentinel.app.data.local.createWalletTestDatabase
import com.balancesentinel.app.data.local.metadata.AppMetadataEntity
import com.balancesentinel.app.data.local.metadata.LegacyMigrationStage
import com.balancesentinel.app.data.local.mutation.MutationOperationEntity
import com.balancesentinel.app.data.local.mutation.MutationOperationType
import com.balancesentinel.app.data.local.mutation.MutationStage
import com.balancesentinel.app.data.local.queryLong
import com.balancesentinel.app.data.local.queryString
import com.balancesentinel.app.data.local.settings.AccountAlertSettingEntity
import com.balancesentinel.app.data.local.settings.AlertRuntimeStateEntity
import com.balancesentinel.app.data.local.settings.AppSettingsEntity
import com.balancesentinel.app.data.local.settings.NotificationWalletSelectionEntity
import com.balancesentinel.app.data.local.settings.SnoozeStateEntity
import com.balancesentinel.app.data.local.testAccount
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MutationPublisherTest {
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
    fun `publish applies typed create update delete CAS and marks operation published`() = runTest {
        seedMetadata()
        seedOperation("typed", baselineRevision = 0)
        database.accountDao().insertCreate(testAccount("update", revision = 3))
        database.accountDao().insertCreate(testAccount("delete", revision = 5))

        val result = MutationPublisher(database).publish(
            publication(
                operationId = "typed",
                accountMutations = listOf(
                    AccountMutation.Create(
                        id = "create",
                        displayOrder = 4,
                        label = "Created",
                        providerType = ProviderType.GEMINI,
                        providerConfigJson = "{\"created\":true}",
                        activeCredentialGeneration = "created-generation"
                    ),
                    AccountMutation.Update(
                        id = "update",
                        expectedRevision = 3,
                        displayOrder = 8,
                        label = "Updated",
                        providerType = ProviderType.ANTHROPIC,
                        providerConfigJson = "{\"updated\":true}",
                        activeCredentialGeneration = "updated-generation"
                    ),
                    AccountMutation.Delete("delete", expectedRevision = 5)
                ),
                publishedAt = 900
            )
        )

        assertEquals(PublicationResult("typed", 1), result)
        val created = database.accountDao().get("create")
        assertEquals(0L, created?.revision)
        assertEquals(AccountState.VERIFIED, created?.state)
        assertNull(created?.legacyStorageId)
        assertEquals(900L, created?.createdAt)
        assertEquals(900L, created?.updatedAt)
        assertEquals("created-generation", created?.activeCredentialGeneration)
        val updated = database.accountDao().get("update")
        assertEquals(4L, updated?.revision)
        assertEquals("Updated", updated?.label)
        assertEquals(900L, updated?.updatedAt)
        assertNull(database.accountDao().get("delete"))
        assertEquals(1L, database.appMetadataDao().get()?.localRevision)
        assertEquals(MutationStage.PUBLISHED, database.mutationOperationDao().get("typed")?.stage)
        assertEquals(900L, database.mutationOperationDao().get("typed")?.publishedAt)
    }

    @Test
    fun `publish replaces each selected settings table as a complete independent table`() = runTest {
        seedMetadata()
        seedOperation("settings", baselineRevision = 0)
        database.accountDao().insertCreate(testAccount("settings-account"))
        database.appSettingsDao().upsert(AppSettingsEntity(alertEnabled = false, updatedAt = 1))
        database.settingsDao().replaceAccountAlertSettings(
            listOf(AccountAlertSettingEntity("settings-account", "OLD", true, false))
        )
        database.settingsDao().replaceNotificationSelections(
            listOf(NotificationWalletSelectionEntity("settings-account", "OLD", 0))
        )
        database.settingsDao().replaceAlertRuntimeStates(
            listOf(AlertRuntimeStateEntity("settings-account", "OLD", lastAlertedBalance = 1.0))
        )
        database.settingsDao().replaceSnoozes(listOf(SnoozeStateEntity("settings-account", 10)))

        MutationPublisher(database).publish(
            publication(
                operationId = "settings",
                settings = SettingsPublication(
                    appSettings = AppSettingsWrite.ReplaceAll(
                        AppSettingsValues(
                            backgroundRefreshIntervalSeconds = null,
                            foregroundMonitoringIntervalSeconds = 45,
                            alertEnabled = true,
                            alertThreshold = 11.5,
                            changeAlertEnabled = true,
                            changeAlertThreshold = 2.5,
                            changeAlertPeriodMinutes = 30,
                            logMaxEntries = 321,
                            snoozeDurationMinutes = 75,
                            showTotalBalanceInNotification = false
                        )
                    ),
                    accountAlertSettings = AccountAlertSettingsWrite.ReplaceAll(
                        listOf(AccountAlertSettingEntity("settings-account", "USD", true, true))
                    ),
                    notificationSelections = NotificationSelectionsWrite.ReplaceAll(
                        listOf(NotificationWalletSelectionEntity("settings-account", "EUR", 7))
                    ),
                    alertRuntimeStates = AlertRuntimeStatesWrite.ReplaceAll(
                        listOf(AlertRuntimeStateEntity("settings-account", "JPY", anchorBalance = 4.5))
                    ),
                    snoozes = SnoozesWrite.ReplaceAll(
                        listOf(SnoozeStateEntity("settings-account", 999))
                    )
                ),
                publishedAt = 800
            )
        )

        val appSettings = database.appSettingsDao().get()
        assertEquals(0, appSettings?.id)
        assertNull(appSettings?.backgroundRefreshIntervalSeconds)
        assertEquals(45, appSettings?.foregroundMonitoringIntervalSeconds)
        assertTrue(appSettings?.alertEnabled == true)
        assertFalse(appSettings?.showTotalBalanceInNotification ?: true)
        assertEquals(800L, appSettings?.updatedAt)
        assertEquals("USD", database.queryString("SELECT currency FROM account_alert_settings"))
        assertEquals("EUR", database.queryString("SELECT currency FROM notification_wallet_selections"))
        assertEquals("JPY", database.queryString("SELECT currency FROM alert_runtime_state"))
        assertEquals(999L, database.queryLong("SELECT snoozed_until FROM snooze_state"))
        listOf(
            "account_alert_settings",
            "notification_wallet_selections",
            "alert_runtime_state",
            "snooze_state"
        ).forEach { table -> assertEquals(table, 1L, database.queryLong("SELECT COUNT(*) FROM $table")) }
    }

    @Test
    fun `metadata compare and set advances generation stage and revision atomically`() = runTest {
        seedMetadata(
            revision = 7,
            generation = "legacy-generation",
            stage = LegacyMigrationStage.VALIDATED
        )
        seedOperation("metadata", baselineRevision = 7)

        val result = MutationPublisher(database).publish(
            publication(
                operationId = "metadata",
                baselineRevision = 7,
                metadata = MetadataPublication.CompareAndSet(
                    expectedActiveDataGeneration = "legacy-generation",
                    expectedLegacyMigrationStage = LegacyMigrationStage.VALIDATED,
                    newActiveDataGeneration = "room-generation",
                    newLegacyMigrationStage = LegacyMigrationStage.ACTIVE
                ),
                publishedAt = 700
            )
        )

        assertEquals(PublicationResult("metadata", 8), result)
        val metadata = database.appMetadataDao().get()
        assertEquals(8L, metadata?.localRevision)
        assertEquals("room-generation", metadata?.activeDataGeneration)
        assertEquals(LegacyMigrationStage.ACTIVE, metadata?.legacyMigrationStage)
        assertEquals(700L, metadata?.updatedAt)
    }

    @Test
    fun `stale account CAS aborts earlier account rows and all publication state`() = runTest {
        seedMetadata()
        seedOperation("account-conflict", baselineRevision = 0)
        database.accountDao().insertCreate(testAccount("stale", revision = 2))

        expectPublicationConflict {
            MutationPublisher(database).publish(
                publication(
                    operationId = "account-conflict",
                    accountMutations = listOf(
                        AccountMutation.Create(
                            "rolled-back",
                            1,
                            "Rolled back",
                            ProviderType.OPENAI,
                            "{}",
                            "generation"
                        ),
                        AccountMutation.Update(
                            "stale",
                            expectedRevision = 1,
                            displayOrder = 2,
                            label = "must not apply",
                            providerType = ProviderType.OPENAI,
                            providerConfigJson = "{}",
                            activeCredentialGeneration = "generation"
                        )
                    )
                )
            )
        }

        assertNull(database.accountDao().get("rolled-back"))
        assertEquals("Account stale", database.accountDao().get("stale")?.label)
        assertEquals(0L, database.appMetadataDao().get()?.localRevision)
        assertEquals(MutationStage.VERIFIED, database.mutationOperationDao().get("account-conflict")?.stage)
    }

    @Test
    fun `stale metadata CAS rolls back account settings revision and operation writes`() = runTest {
        seedMetadata(generation = "current", stage = LegacyMigrationStage.DISCOVERED)
        seedOperation("metadata-conflict", baselineRevision = 0)
        database.appSettingsDao().upsert(AppSettingsEntity(alertEnabled = false, updatedAt = 1))

        expectPublicationConflict {
            MutationPublisher(database).publish(
                publication(
                    operationId = "metadata-conflict",
                    accountMutations = listOf(
                        AccountMutation.Create(
                            "metadata-rolled-back",
                            1,
                            "Rolled back",
                            ProviderType.OPENAI,
                            "{}",
                            "generation"
                        )
                    ),
                    settings = settingsWithAppAlert(enabled = true),
                    metadata = MetadataPublication.CompareAndSet(
                        expectedActiveDataGeneration = "stale",
                        expectedLegacyMigrationStage = LegacyMigrationStage.DISCOVERED,
                        newActiveDataGeneration = "new",
                        newLegacyMigrationStage = LegacyMigrationStage.ACTIVE
                    )
                )
            )
        }

        assertNull(database.accountDao().get("metadata-rolled-back"))
        assertFalse(database.appSettingsDao().get()?.alertEnabled ?: true)
        assertEquals(0L, database.appMetadataDao().get()?.localRevision)
        assertEquals("current", database.appMetadataDao().get()?.activeDataGeneration)
        assertEquals(MutationStage.VERIFIED, database.mutationOperationDao().get("metadata-conflict")?.stage)
    }

    @Test
    fun `failure after every durable publication step rolls back the real Room transaction`() = runTest {
        seedMetadata()
        database.appSettingsDao().upsert(AppSettingsEntity(alertEnabled = false, updatedAt = 1))

        listOf(
            TransactionStep.AFTER_ACCOUNT_ROWS,
            TransactionStep.AFTER_SETTINGS_ROWS,
            TransactionStep.AFTER_METADATA,
            TransactionStep.AFTER_OPERATION_PUBLISHED
        ).forEachIndexed { index, failurePoint ->
            val operationId = "fault-$index"
            val accountId = "fault-account-$index"
            seedOperation(operationId, baselineRevision = 0)
            val publisher = MutationPublisher(
                database,
                TransactionStepObserver { step ->
                    if (step == failurePoint) throw InjectedTransactionFailure(failurePoint)
                }
            )

            expectInjectedFailure {
                publisher.publish(
                    publication(
                        operationId = operationId,
                        accountMutations = listOf(
                            AccountMutation.Create(
                                accountId,
                                index,
                                "Fault $index",
                                ProviderType.OPENAI,
                                "{}",
                                "generation-$index"
                            )
                        ),
                        settings = settingsWithAppAlert(enabled = true),
                        publishedAt = 1000L + index
                    )
                )
            }

            assertNull(database.accountDao().get(accountId))
            assertFalse(database.appSettingsDao().get()?.alertEnabled ?: true)
            assertEquals(0L, database.appMetadataDao().get()?.localRevision)
            assertEquals(MutationStage.VERIFIED, database.mutationOperationDao().get(operationId)?.stage)
            assertNull(database.mutationOperationDao().get(operationId)?.publishedAt)
        }
    }

    private suspend fun seedMetadata(
        revision: Long = 0,
        generation: String = "LEGACY",
        stage: LegacyMigrationStage = LegacyMigrationStage.NONE
    ) {
        database.appMetadataDao().ensureSingleton(
            AppMetadataEntity(
                localRevision = revision,
                activeDataGeneration = generation,
                legacyMigrationStage = stage,
                updatedAt = 1
            )
        )
    }

    private suspend fun seedOperation(id: String, baselineRevision: Long) {
        database.mutationOperationDao().insertPrepared(
            MutationOperationEntity(
                id = id,
                operationType = MutationOperationType.ACCOUNT_REPLACE,
                stage = MutationStage.VERIFIED,
                baselineRevision = baselineRevision,
                createdAt = 1,
                updatedAt = 1
            )
        )
    }

    private fun publication(
        operationId: String,
        baselineRevision: Long = 0,
        accountMutations: List<AccountMutation> = emptyList(),
        settings: SettingsPublication = unchangedSettings(),
        metadata: MetadataPublication = MetadataPublication.Unchanged,
        publishedAt: Long = 500
    ) = MutationPublication(
        operationId = operationId,
        baselineRevision = baselineRevision,
        accountMutations = accountMutations,
        settings = settings,
        metadata = metadata,
        publishedAt = publishedAt
    )

    private fun unchangedSettings() = SettingsPublication(
        appSettings = AppSettingsWrite.Unchanged,
        accountAlertSettings = AccountAlertSettingsWrite.Unchanged,
        notificationSelections = NotificationSelectionsWrite.Unchanged,
        alertRuntimeStates = AlertRuntimeStatesWrite.Unchanged,
        snoozes = SnoozesWrite.Unchanged
    )

    private fun settingsWithAppAlert(enabled: Boolean) = SettingsPublication(
        appSettings = AppSettingsWrite.ReplaceAll(
            AppSettingsValues(
                backgroundRefreshIntervalSeconds = 900,
                foregroundMonitoringIntervalSeconds = 30,
                alertEnabled = enabled,
                alertThreshold = 10.0,
                changeAlertEnabled = false,
                changeAlertThreshold = 0.0,
                changeAlertPeriodMinutes = 0,
                logMaxEntries = 100,
                snoozeDurationMinutes = 60,
                showTotalBalanceInNotification = true
            )
        ),
        accountAlertSettings = AccountAlertSettingsWrite.Unchanged,
        notificationSelections = NotificationSelectionsWrite.Unchanged,
        alertRuntimeStates = AlertRuntimeStatesWrite.Unchanged,
        snoozes = SnoozesWrite.Unchanged
    )

    private suspend fun expectPublicationConflict(block: suspend () -> Unit) {
        val failure = runCatching { block() }.exceptionOrNull()
        if (failure is UnsupportedOperationException) throw failure
        assertTrue("Expected PublicationConflictException, got $failure", failure is PublicationConflictException)
    }

    private suspend fun expectInjectedFailure(block: suspend () -> Unit) {
        val failure = runCatching { block() }.exceptionOrNull()
        if (failure is UnsupportedOperationException) throw failure
        assertTrue("Expected InjectedTransactionFailure, got $failure", failure is InjectedTransactionFailure)
    }

    private class InjectedTransactionFailure(step: TransactionStep) : RuntimeException(step.name)
}
