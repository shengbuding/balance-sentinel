package com.balancesentinel.app.data.local

import androidx.room.TypeConverter
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.local.account.AccountState
import com.balancesentinel.app.data.local.history.BalanceRecordSource
import com.balancesentinel.app.data.local.log.EventLogType
import com.balancesentinel.app.data.local.metadata.LegacyMigrationStage
import com.balancesentinel.app.data.local.monitoring.MonitoringObservedState
import com.balancesentinel.app.data.local.monitoring.MonitoringSessionEndReason
import com.balancesentinel.app.data.local.mutation.MutationOperationType
import com.balancesentinel.app.data.local.mutation.MutationStage
import com.balancesentinel.app.data.local.refresh.RefreshAccountResultState
import com.balancesentinel.app.data.local.refresh.RefreshErrorCategory
import com.balancesentinel.app.data.local.refresh.RefreshRunSource
import com.balancesentinel.app.data.local.refresh.RefreshRunState
import com.balancesentinel.app.data.local.update.DownloadState

class DatabaseConverters {
    @TypeConverter fun accountStateToStorage(value: AccountState?): String? = value?.name
    @TypeConverter fun accountStateFromStorage(value: String?): AccountState? = when (value) {
        null -> null
        "PENDING" -> AccountState.PENDING
        "VERIFIED" -> AccountState.VERIFIED
        else -> unknown("AccountState", value)
    }

    @TypeConverter fun mutationOperationTypeToStorage(value: MutationOperationType?): String? = value?.name
    @TypeConverter fun mutationOperationTypeFromStorage(value: String?): MutationOperationType? = when (value) {
        null -> null
        "ACCOUNT_REPLACE" -> MutationOperationType.ACCOUNT_REPLACE
        "ACCOUNT_DELETE" -> MutationOperationType.ACCOUNT_DELETE
        "CONFIG_IMPORT" -> MutationOperationType.CONFIG_IMPORT
        "LEGACY_ACCOUNT_MIGRATION" -> MutationOperationType.LEGACY_ACCOUNT_MIGRATION
        "LEGACY_DATA_MIGRATION" -> MutationOperationType.LEGACY_DATA_MIGRATION
        "HISTORY_DATA_IMPORT" -> MutationOperationType.HISTORY_DATA_IMPORT
        else -> unknown("MutationOperationType", value)
    }

    @TypeConverter fun mutationStageToStorage(value: MutationStage?): String? = value?.name
    @TypeConverter fun mutationStageFromStorage(value: String?): MutationStage? = when (value) {
        null -> null
        "PREPARED" -> MutationStage.PREPARED
        "CREDENTIALS_STAGED" -> MutationStage.CREDENTIALS_STAGED
        "ROOM_WRITTEN" -> MutationStage.ROOM_WRITTEN
        "VERIFIED" -> MutationStage.VERIFIED
        "PUBLISHED" -> MutationStage.PUBLISHED
        "ACTIVE" -> MutationStage.ACTIVE
        "CLEANED" -> MutationStage.CLEANED
        "COMPLETED" -> MutationStage.COMPLETED
        "FAILED" -> MutationStage.FAILED
        else -> unknown("MutationStage", value)
    }

    @TypeConverter fun legacyMigrationStageToStorage(value: LegacyMigrationStage?): String? = value?.name
    @TypeConverter fun legacyMigrationStageFromStorage(value: String?): LegacyMigrationStage? = when (value) {
        null -> null
        "NONE" -> LegacyMigrationStage.NONE
        "DISCOVERED" -> LegacyMigrationStage.DISCOVERED
        "VALIDATED" -> LegacyMigrationStage.VALIDATED
        "CREDENTIALS_STAGED" -> LegacyMigrationStage.CREDENTIALS_STAGED
        "ROOM_WRITTEN" -> LegacyMigrationStage.ROOM_WRITTEN
        "VERIFIED" -> LegacyMigrationStage.VERIFIED
        "ACTIVE" -> LegacyMigrationStage.ACTIVE
        "CLEANED" -> LegacyMigrationStage.CLEANED
        "FAILED" -> LegacyMigrationStage.FAILED
        else -> unknown("LegacyMigrationStage", value)
    }

    @TypeConverter fun balanceRecordSourceToStorage(value: BalanceRecordSource?): String? = value?.name
    @TypeConverter fun balanceRecordSourceFromStorage(value: String?): BalanceRecordSource? = when (value) {
        null -> null
        "REFRESH" -> BalanceRecordSource.REFRESH
        "IMPORT" -> BalanceRecordSource.IMPORT
        "LEGACY_MIGRATION" -> BalanceRecordSource.LEGACY_MIGRATION
        else -> unknown("BalanceRecordSource", value)
    }

    @TypeConverter fun refreshRunSourceToStorage(value: RefreshRunSource?): String? = value?.name
    @TypeConverter fun refreshRunSourceFromStorage(value: String?): RefreshRunSource? = when (value) {
        null -> null
        "MANUAL" -> RefreshRunSource.MANUAL
        "BACKGROUND" -> RefreshRunSource.BACKGROUND
        "FOREGROUND" -> RefreshRunSource.FOREGROUND
        "WIDGET" -> RefreshRunSource.WIDGET
        else -> unknown("RefreshRunSource", value)
    }

    @TypeConverter fun refreshRunStateToStorage(value: RefreshRunState?): String? = value?.name
    @TypeConverter fun refreshRunStateFromStorage(value: String?): RefreshRunState? = when (value) {
        null -> null
        "RUNNING" -> RefreshRunState.RUNNING
        "SUCCEEDED" -> RefreshRunState.SUCCEEDED
        "PARTIAL" -> RefreshRunState.PARTIAL
        "FAILED" -> RefreshRunState.FAILED
        "CANCELLED" -> RefreshRunState.CANCELLED
        "INTERRUPTED" -> RefreshRunState.INTERRUPTED
        else -> unknown("RefreshRunState", value)
    }

    @TypeConverter fun refreshAccountResultStateToStorage(value: RefreshAccountResultState?): String? = value?.name
    @TypeConverter fun refreshAccountResultStateFromStorage(value: String?): RefreshAccountResultState? = when (value) {
        null -> null
        "RUNNING" -> RefreshAccountResultState.RUNNING
        "SUCCEEDED" -> RefreshAccountResultState.SUCCEEDED
        "AUTHENTICATION_FAILED" -> RefreshAccountResultState.AUTHENTICATION_FAILED
        "NETWORK_FAILED" -> RefreshAccountResultState.NETWORK_FAILED
        "RATE_LIMITED" -> RefreshAccountResultState.RATE_LIMITED
        "RESPONSE_INVALID" -> RefreshAccountResultState.RESPONSE_INVALID
        "SCRIPT_POLICY_DENIED" -> RefreshAccountResultState.SCRIPT_POLICY_DENIED
        "SCRIPT_TIMEOUT" -> RefreshAccountResultState.SCRIPT_TIMEOUT
        "ACCOUNT_STALE" -> RefreshAccountResultState.ACCOUNT_STALE
        "PERSISTENCE_FAILED" -> RefreshAccountResultState.PERSISTENCE_FAILED
        "CANCELLED" -> RefreshAccountResultState.CANCELLED
        "INTERRUPTED" -> RefreshAccountResultState.INTERRUPTED
        "SKIPPED" -> RefreshAccountResultState.SKIPPED
        else -> unknown("RefreshAccountResultState", value)
    }

    @TypeConverter fun refreshErrorCategoryToStorage(value: RefreshErrorCategory?): String? = value?.name
    @TypeConverter fun refreshErrorCategoryFromStorage(value: String?): RefreshErrorCategory? = when (value) {
        null -> null
        "AUTHENTICATION" -> RefreshErrorCategory.AUTHENTICATION
        "NETWORK" -> RefreshErrorCategory.NETWORK
        "RATE_LIMIT" -> RefreshErrorCategory.RATE_LIMIT
        "RESPONSE" -> RefreshErrorCategory.RESPONSE
        "SCRIPT_POLICY" -> RefreshErrorCategory.SCRIPT_POLICY
        "SCRIPT_TIMEOUT" -> RefreshErrorCategory.SCRIPT_TIMEOUT
        "ACCOUNT_STALE" -> RefreshErrorCategory.ACCOUNT_STALE
        "PERSISTENCE" -> RefreshErrorCategory.PERSISTENCE
        "CANCELLED" -> RefreshErrorCategory.CANCELLED
        "INTERRUPTED" -> RefreshErrorCategory.INTERRUPTED
        "UNKNOWN" -> RefreshErrorCategory.UNKNOWN
        else -> unknown("RefreshErrorCategory", value)
    }

    @TypeConverter fun eventLogTypeToStorage(value: EventLogType?): String? = value?.name
    @TypeConverter fun eventLogTypeFromStorage(value: String?): EventLogType? = when (value) {
        null -> null
        "MANUAL" -> EventLogType.MANUAL
        "AUTO" -> EventLogType.AUTO
        "SCHEDULE" -> EventLogType.SCHEDULE
        "MISSED" -> EventLogType.MISSED
        "SERVICE_DIED" -> EventLogType.SERVICE_DIED
        "SERVICE_START" -> EventLogType.SERVICE_START
        "WATCHDOG" -> EventLogType.WATCHDOG
        else -> unknown("EventLogType", value)
    }

    @TypeConverter fun downloadStateToStorage(value: DownloadState?): String? = value?.name
    @TypeConverter fun downloadStateFromStorage(value: String?): DownloadState? = when (value) {
        null -> null
        "QUEUED" -> DownloadState.QUEUED
        "RUNNING" -> DownloadState.RUNNING
        "CANCELLING" -> DownloadState.CANCELLING
        "CANCELLED" -> DownloadState.CANCELLED
        "FAILED" -> DownloadState.FAILED
        "COMPLETED" -> DownloadState.COMPLETED
        else -> unknown("DownloadState", value)
    }

    @TypeConverter fun monitoringObservedStateToStorage(value: MonitoringObservedState?): String? = value?.name
    @TypeConverter fun monitoringObservedStateFromStorage(value: String?): MonitoringObservedState? = when (value) {
        null -> null
        "STOPPED" -> MonitoringObservedState.STOPPED
        "STARTING" -> MonitoringObservedState.STARTING
        "RUNNING" -> MonitoringObservedState.RUNNING
        "ABNORMAL" -> MonitoringObservedState.ABNORMAL
        "PLATFORM_LIMITED" -> MonitoringObservedState.PLATFORM_LIMITED
        "PAUSED" -> MonitoringObservedState.PAUSED
        else -> unknown("MonitoringObservedState", value)
    }

    @TypeConverter fun monitoringSessionEndReasonToStorage(value: MonitoringSessionEndReason?): String? = value?.name
    @TypeConverter fun monitoringSessionEndReasonFromStorage(value: String?): MonitoringSessionEndReason? = when (value) {
        null -> null
        "USER_STOPPED" -> MonitoringSessionEndReason.USER_STOPPED
        "SERVICE_DESTROYED" -> MonitoringSessionEndReason.SERVICE_DESTROYED
        "PLATFORM_TIMEOUT" -> MonitoringSessionEndReason.PLATFORM_TIMEOUT
        "PROCESS_RECOVERY" -> MonitoringSessionEndReason.PROCESS_RECOVERY
        "PLATFORM_LIMITED" -> MonitoringSessionEndReason.PLATFORM_LIMITED
        "PAUSED" -> MonitoringSessionEndReason.PAUSED
        else -> unknown("MonitoringSessionEndReason", value)
    }

    @TypeConverter fun providerTypeToStorage(value: ProviderType?): String? = value?.id
    @TypeConverter fun providerTypeFromStorage(value: String?): ProviderType? = when (value) {
        null -> null
        "openai" -> ProviderType.OPENAI
        "anthropic" -> ProviderType.ANTHROPIC
        "gemini" -> ProviderType.GEMINI
        "mistral" -> ProviderType.MISTRAL
        "cohere" -> ProviderType.COHERE
        "deepseek" -> ProviderType.DEEPSEEK
        "qwen" -> ProviderType.QWEN
        "wenxin" -> ProviderType.WENXIN
        "zhipu" -> ProviderType.ZHIPU
        "moonshot" -> ProviderType.MOONSHOT
        "doubao" -> ProviderType.DOUBAO
        "baichuan" -> ProviderType.BAICHUAN
        "model_ark" -> ProviderType.MODEL_ARK
        "custom" -> ProviderType.CUSTOM
        else -> unknown("ProviderType", value)
    }

    private fun <T> unknown(type: String, value: String): T =
        throw IllegalArgumentException("Unknown $type: $value")
}
