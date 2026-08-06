package com.balancesentinel.app.data.local.publication

import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.local.account.AccountEntity
import com.balancesentinel.app.data.local.account.AccountState
import com.balancesentinel.app.data.local.mutation.MutationStage

class MutationPublisher internal constructor(
    private val database: WalletDatabase,
    private val observer: TransactionStepObserver
) {
    constructor(database: WalletDatabase) : this(database, TransactionStepObserver.NO_OP)

    suspend fun publish(input: MutationPublication): PublicationResult {
        try {
            return database.withTransaction {
                verifyOperation(input)
                publishAccounts(input)
                observer.after(TransactionStep.AFTER_ACCOUNT_ROWS)
                publishSettings(input)
                observer.after(TransactionStep.AFTER_SETTINGS_ROWS)
                publishMetadata(input)
                observer.after(TransactionStep.AFTER_METADATA)
                requirePublished(input)
                observer.after(TransactionStep.AFTER_OPERATION_PUBLISHED)
                PublicationResult(input.operationId, input.baselineRevision + 1)
            }
        } catch (failure: SQLiteException) {
            throw PublicationConflictException(
                "Room publication conflicted for operation ${input.operationId}"
            ).also { it.initCause(failure) }
        }
    }

    private suspend fun verifyOperation(input: MutationPublication) {
        val operation = database.mutationOperationDao().get(input.operationId)
            ?: conflict("Publication operation ${input.operationId} does not exist")
        if (operation.stage != MutationStage.VERIFIED) {
            conflict("Publication operation ${input.operationId} is not verified")
        }
        if (operation.baselineRevision != input.baselineRevision) {
            conflict("Publication operation ${input.operationId} has a stale baseline")
        }
    }

    private suspend fun publishAccounts(input: MutationPublication) {
        input.accountMutations.forEach { mutation ->
            when (mutation) {
                is AccountMutation.Create -> database.accountDao().insertCreate(
                    AccountEntity(
                        id = mutation.id,
                        displayOrder = mutation.displayOrder,
                        label = mutation.label,
                        providerType = mutation.providerType,
                        providerConfigJson = mutation.providerConfigJson,
                        activeCredentialGeneration = mutation.activeCredentialGeneration,
                        revision = 0,
                        state = AccountState.VERIFIED,
                        legacyStorageId = null,
                        createdAt = input.publishedAt,
                        updatedAt = input.publishedAt
                    )
                )

                is AccountMutation.Update -> {
                    val changed = database.accountDao().updateWhereRevision(
                        id = mutation.id,
                        expectedRevision = mutation.expectedRevision,
                        displayOrder = mutation.displayOrder,
                        label = mutation.label,
                        providerType = mutation.providerType,
                        providerConfigJson = mutation.providerConfigJson,
                        activeCredentialGeneration = mutation.activeCredentialGeneration,
                        updatedAt = input.publishedAt
                    )
                    if (changed != 1) conflict("Account ${mutation.id} has a stale revision")
                }

                is AccountMutation.Delete -> {
                    val changed = database.accountDao().deleteWhereRevision(
                        mutation.id,
                        mutation.expectedRevision
                    )
                    if (changed != 1) conflict("Account ${mutation.id} has a stale revision")
                }
            }
        }
    }

    private suspend fun publishSettings(input: MutationPublication) {
        when (val write = input.settings.appSettings) {
            AppSettingsWrite.Unchanged -> Unit
            is AppSettingsWrite.ReplaceAll -> database.appSettingsDao().upsert(
                backgroundRefreshIntervalSeconds = write.value.backgroundRefreshIntervalSeconds,
                foregroundMonitoringIntervalSeconds = write.value.foregroundMonitoringIntervalSeconds,
                alertEnabled = write.value.alertEnabled,
                alertThreshold = write.value.alertThreshold,
                changeAlertEnabled = write.value.changeAlertEnabled,
                changeAlertThreshold = write.value.changeAlertThreshold,
                changeAlertPeriodMinutes = write.value.changeAlertPeriodMinutes,
                logMaxEntries = write.value.logMaxEntries,
                snoozeDurationMinutes = write.value.snoozeDurationMinutes,
                showTotalBalanceInNotification = write.value.showTotalBalanceInNotification,
                updatedAt = input.publishedAt
            )
        }
        when (val write = input.settings.accountAlertSettings) {
            AccountAlertSettingsWrite.Unchanged -> Unit
            is AccountAlertSettingsWrite.ReplaceAll ->
                database.settingsDao().replaceAccountAlertSettings(write.values)
        }
        when (val write = input.settings.notificationSelections) {
            NotificationSelectionsWrite.Unchanged -> Unit
            is NotificationSelectionsWrite.ReplaceAll ->
                database.settingsDao().replaceNotificationSelections(write.values)
        }
        when (val write = input.settings.alertRuntimeStates) {
            AlertRuntimeStatesWrite.Unchanged -> Unit
            is AlertRuntimeStatesWrite.ReplaceAll ->
                database.settingsDao().replaceAlertRuntimeStates(write.values)
        }
        when (val write = input.settings.snoozes) {
            SnoozesWrite.Unchanged -> Unit
            is SnoozesWrite.ReplaceAll -> database.settingsDao().replaceSnoozes(write.values)
        }
    }

    private suspend fun publishMetadata(input: MutationPublication) {
        val changed = when (val publication = input.metadata) {
            MetadataPublication.Unchanged -> database.appMetadataDao().incrementRevisionIfCurrent(
                input.baselineRevision,
                input.publishedAt
            )

            is MetadataPublication.CompareAndSet ->
                database.appMetadataDao().advanceMetadataAndRevisionIfCurrent(
                    expectedRevision = input.baselineRevision,
                    expectedActiveDataGeneration = publication.expectedActiveDataGeneration,
                    expectedLegacyMigrationStage = publication.expectedLegacyMigrationStage,
                    newActiveDataGeneration = publication.newActiveDataGeneration,
                    newLegacyMigrationStage = publication.newLegacyMigrationStage,
                    updatedAt = input.publishedAt
                )
        }
        if (changed != 1) conflict("App metadata has a stale revision or migration state")
    }

    private suspend fun requirePublished(input: MutationPublication) {
        if (database.mutationOperationDao().markPublished(input.operationId, input.publishedAt) != 1) {
            conflict("Publication operation ${input.operationId} could not be published")
        }
    }

    private fun conflict(message: String): Nothing = throw PublicationConflictException(message)
}
