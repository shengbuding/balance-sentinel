package com.balancesentinel.app.data.local.publication

import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.local.metadata.LegacyMigrationStage
import com.balancesentinel.app.data.local.settings.AccountAlertSettingEntity
import com.balancesentinel.app.data.local.settings.AlertRuntimeStateEntity
import com.balancesentinel.app.data.local.settings.NotificationWalletSelectionEntity
import com.balancesentinel.app.data.local.settings.SnoozeStateEntity

sealed interface AccountMutation {
    data class Create(
        val id: String,
        val displayOrder: Int,
        val label: String,
        val providerType: ProviderType,
        val providerConfigJson: String,
        val activeCredentialGeneration: String
    ) : AccountMutation

    data class Update(
        val id: String,
        val expectedRevision: Long,
        val displayOrder: Int,
        val label: String,
        val providerType: ProviderType,
        val providerConfigJson: String,
        val activeCredentialGeneration: String
    ) : AccountMutation

    data class Delete(
        val id: String,
        val expectedRevision: Long
    ) : AccountMutation
}

data class AppSettingsValues(
    val backgroundRefreshIntervalSeconds: Int?,
    val foregroundMonitoringIntervalSeconds: Int,
    val alertEnabled: Boolean,
    val alertThreshold: Double,
    val changeAlertEnabled: Boolean,
    val changeAlertThreshold: Double,
    val changeAlertPeriodMinutes: Int,
    val logMaxEntries: Int,
    val snoozeDurationMinutes: Int,
    val showTotalBalanceInNotification: Boolean,
    val notificationTotalDisplayOrder: Int = 0
)

sealed interface AppSettingsWrite {
    data object Unchanged : AppSettingsWrite
    data class ReplaceAll(val value: AppSettingsValues) : AppSettingsWrite
}

sealed interface AccountAlertSettingsWrite {
    data object Unchanged : AccountAlertSettingsWrite
    data class ReplaceAll(
        val values: List<AccountAlertSettingEntity>
    ) : AccountAlertSettingsWrite
}

sealed interface NotificationSelectionsWrite {
    data object Unchanged : NotificationSelectionsWrite
    data class ReplaceAll(
        val values: List<NotificationWalletSelectionEntity>
    ) : NotificationSelectionsWrite
}

sealed interface AlertRuntimeStatesWrite {
    data object Unchanged : AlertRuntimeStatesWrite
    data class ReplaceAll(
        val values: List<AlertRuntimeStateEntity>
    ) : AlertRuntimeStatesWrite
}

sealed interface SnoozesWrite {
    data object Unchanged : SnoozesWrite
    data class ReplaceAll(val values: List<SnoozeStateEntity>) : SnoozesWrite
}

data class SettingsPublication(
    val appSettings: AppSettingsWrite,
    val accountAlertSettings: AccountAlertSettingsWrite,
    val notificationSelections: NotificationSelectionsWrite,
    val alertRuntimeStates: AlertRuntimeStatesWrite,
    val snoozes: SnoozesWrite
)

sealed interface MetadataPublication {
    data object Unchanged : MetadataPublication
    data class CompareAndSet(
        val expectedActiveDataGeneration: String,
        val expectedLegacyMigrationStage: LegacyMigrationStage,
        val newActiveDataGeneration: String,
        val newLegacyMigrationStage: LegacyMigrationStage
    ) : MetadataPublication
}

data class MutationPublication(
    val operationId: String,
    val baselineRevision: Long,
    val accountMutations: List<AccountMutation>,
    val settings: SettingsPublication,
    val metadata: MetadataPublication,
    val publishedAt: Long
)

data class PublicationResult(
    val operationId: String,
    val newLocalRevision: Long
)
