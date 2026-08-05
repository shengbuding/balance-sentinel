package com.balancesentinel.app.data.local

import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.local.account.AccountState
import com.balancesentinel.app.data.local.history.BalanceRecordSource
import com.balancesentinel.app.data.local.log.EventLogType
import com.balancesentinel.app.data.local.metadata.LegacyMigrationStage
import com.balancesentinel.app.data.local.monitoring.MonitoringObservedState
import com.balancesentinel.app.data.local.monitoring.MonitoringSessionEndReason
import com.balancesentinel.app.data.local.monitoring.MonitoringStateEntity
import com.balancesentinel.app.data.local.mutation.MutationOperationType
import com.balancesentinel.app.data.local.mutation.MutationStage
import com.balancesentinel.app.data.local.refresh.RefreshAccountResultState
import com.balancesentinel.app.data.local.refresh.RefreshErrorCategory
import com.balancesentinel.app.data.local.refresh.RefreshRunSource
import com.balancesentinel.app.data.local.refresh.RefreshRunState
import com.balancesentinel.app.data.local.update.DownloadState
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DatabaseConvertersTest {
    private val converters = DatabaseConverters()
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
    fun `all stable enum and provider literals round trip exactly`() {
        assertRoundTrips(
            listOf("PENDING" to AccountState.PENDING, "VERIFIED" to AccountState.VERIFIED),
            converters::accountStateToStorage,
            converters::accountStateFromStorage
        )
        assertRoundTrips(
            listOf(
                "ACCOUNT_REPLACE" to MutationOperationType.ACCOUNT_REPLACE,
                "ACCOUNT_DELETE" to MutationOperationType.ACCOUNT_DELETE,
                "CONFIG_IMPORT" to MutationOperationType.CONFIG_IMPORT,
                "LEGACY_ACCOUNT_MIGRATION" to MutationOperationType.LEGACY_ACCOUNT_MIGRATION,
                "LEGACY_DATA_MIGRATION" to MutationOperationType.LEGACY_DATA_MIGRATION,
                "HISTORY_DATA_IMPORT" to MutationOperationType.HISTORY_DATA_IMPORT
            ),
            converters::mutationOperationTypeToStorage,
            converters::mutationOperationTypeFromStorage
        )
        assertRoundTrips(
            listOf(
                "PREPARED" to MutationStage.PREPARED,
                "CREDENTIALS_STAGED" to MutationStage.CREDENTIALS_STAGED,
                "ROOM_WRITTEN" to MutationStage.ROOM_WRITTEN,
                "VERIFIED" to MutationStage.VERIFIED,
                "PUBLISHED" to MutationStage.PUBLISHED,
                "ACTIVE" to MutationStage.ACTIVE,
                "CLEANED" to MutationStage.CLEANED,
                "COMPLETED" to MutationStage.COMPLETED,
                "FAILED" to MutationStage.FAILED
            ),
            converters::mutationStageToStorage,
            converters::mutationStageFromStorage
        )
        assertRoundTrips(
            listOf(
                "NONE" to LegacyMigrationStage.NONE,
                "DISCOVERED" to LegacyMigrationStage.DISCOVERED,
                "VALIDATED" to LegacyMigrationStage.VALIDATED,
                "CREDENTIALS_STAGED" to LegacyMigrationStage.CREDENTIALS_STAGED,
                "ROOM_WRITTEN" to LegacyMigrationStage.ROOM_WRITTEN,
                "VERIFIED" to LegacyMigrationStage.VERIFIED,
                "ACTIVE" to LegacyMigrationStage.ACTIVE,
                "CLEANED" to LegacyMigrationStage.CLEANED,
                "FAILED" to LegacyMigrationStage.FAILED
            ),
            converters::legacyMigrationStageToStorage,
            converters::legacyMigrationStageFromStorage
        )
        assertRoundTrips(
            listOf(
                "REFRESH" to BalanceRecordSource.REFRESH,
                "IMPORT" to BalanceRecordSource.IMPORT,
                "LEGACY_MIGRATION" to BalanceRecordSource.LEGACY_MIGRATION
            ),
            converters::balanceRecordSourceToStorage,
            converters::balanceRecordSourceFromStorage
        )
        assertRoundTrips(
            listOf(
                "MANUAL" to RefreshRunSource.MANUAL,
                "BACKGROUND" to RefreshRunSource.BACKGROUND,
                "FOREGROUND" to RefreshRunSource.FOREGROUND,
                "WIDGET" to RefreshRunSource.WIDGET
            ),
            converters::refreshRunSourceToStorage,
            converters::refreshRunSourceFromStorage
        )
        assertRoundTrips(
            listOf(
                "RUNNING" to RefreshRunState.RUNNING,
                "SUCCEEDED" to RefreshRunState.SUCCEEDED,
                "PARTIAL" to RefreshRunState.PARTIAL,
                "FAILED" to RefreshRunState.FAILED,
                "CANCELLED" to RefreshRunState.CANCELLED,
                "INTERRUPTED" to RefreshRunState.INTERRUPTED
            ),
            converters::refreshRunStateToStorage,
            converters::refreshRunStateFromStorage
        )
        assertRoundTrips(
            listOf(
                "RUNNING" to RefreshAccountResultState.RUNNING,
                "SUCCEEDED" to RefreshAccountResultState.SUCCEEDED,
                "AUTHENTICATION_FAILED" to RefreshAccountResultState.AUTHENTICATION_FAILED,
                "NETWORK_FAILED" to RefreshAccountResultState.NETWORK_FAILED,
                "RATE_LIMITED" to RefreshAccountResultState.RATE_LIMITED,
                "RESPONSE_INVALID" to RefreshAccountResultState.RESPONSE_INVALID,
                "SCRIPT_POLICY_DENIED" to RefreshAccountResultState.SCRIPT_POLICY_DENIED,
                "SCRIPT_TIMEOUT" to RefreshAccountResultState.SCRIPT_TIMEOUT,
                "ACCOUNT_STALE" to RefreshAccountResultState.ACCOUNT_STALE,
                "PERSISTENCE_FAILED" to RefreshAccountResultState.PERSISTENCE_FAILED,
                "CANCELLED" to RefreshAccountResultState.CANCELLED,
                "INTERRUPTED" to RefreshAccountResultState.INTERRUPTED,
                "SKIPPED" to RefreshAccountResultState.SKIPPED
            ),
            converters::refreshAccountResultStateToStorage,
            converters::refreshAccountResultStateFromStorage
        )
        assertRoundTrips(
            listOf(
                "AUTHENTICATION" to RefreshErrorCategory.AUTHENTICATION,
                "NETWORK" to RefreshErrorCategory.NETWORK,
                "RATE_LIMIT" to RefreshErrorCategory.RATE_LIMIT,
                "RESPONSE" to RefreshErrorCategory.RESPONSE,
                "SCRIPT_POLICY" to RefreshErrorCategory.SCRIPT_POLICY,
                "SCRIPT_TIMEOUT" to RefreshErrorCategory.SCRIPT_TIMEOUT,
                "ACCOUNT_STALE" to RefreshErrorCategory.ACCOUNT_STALE,
                "PERSISTENCE" to RefreshErrorCategory.PERSISTENCE,
                "CANCELLED" to RefreshErrorCategory.CANCELLED,
                "INTERRUPTED" to RefreshErrorCategory.INTERRUPTED,
                "UNKNOWN" to RefreshErrorCategory.UNKNOWN
            ),
            converters::refreshErrorCategoryToStorage,
            converters::refreshErrorCategoryFromStorage
        )
        assertRoundTrips(
            listOf(
                "MANUAL" to EventLogType.MANUAL,
                "AUTO" to EventLogType.AUTO,
                "SCHEDULE" to EventLogType.SCHEDULE,
                "MISSED" to EventLogType.MISSED,
                "SERVICE_DIED" to EventLogType.SERVICE_DIED,
                "SERVICE_START" to EventLogType.SERVICE_START,
                "WATCHDOG" to EventLogType.WATCHDOG
            ),
            converters::eventLogTypeToStorage,
            converters::eventLogTypeFromStorage
        )
        assertRoundTrips(
            listOf(
                "QUEUED" to DownloadState.QUEUED,
                "RUNNING" to DownloadState.RUNNING,
                "CANCELLING" to DownloadState.CANCELLING,
                "CANCELLED" to DownloadState.CANCELLED,
                "FAILED" to DownloadState.FAILED,
                "COMPLETED" to DownloadState.COMPLETED
            ),
            converters::downloadStateToStorage,
            converters::downloadStateFromStorage
        )
        assertRoundTrips(
            listOf(
                "STOPPED" to MonitoringObservedState.STOPPED,
                "STARTING" to MonitoringObservedState.STARTING,
                "RUNNING" to MonitoringObservedState.RUNNING,
                "ABNORMAL" to MonitoringObservedState.ABNORMAL,
                "PLATFORM_LIMITED" to MonitoringObservedState.PLATFORM_LIMITED,
                "PAUSED" to MonitoringObservedState.PAUSED
            ),
            converters::monitoringObservedStateToStorage,
            converters::monitoringObservedStateFromStorage
        )
        assertRoundTrips(
            listOf(
                "USER_STOPPED" to MonitoringSessionEndReason.USER_STOPPED,
                "SERVICE_DESTROYED" to MonitoringSessionEndReason.SERVICE_DESTROYED,
                "PLATFORM_TIMEOUT" to MonitoringSessionEndReason.PLATFORM_TIMEOUT,
                "PROCESS_RECOVERY" to MonitoringSessionEndReason.PROCESS_RECOVERY,
                "PLATFORM_LIMITED" to MonitoringSessionEndReason.PLATFORM_LIMITED,
                "PAUSED" to MonitoringSessionEndReason.PAUSED
            ),
            converters::monitoringSessionEndReasonToStorage,
            converters::monitoringSessionEndReasonFromStorage
        )
        assertRoundTrips(
            listOf(
                "openai" to ProviderType.OPENAI,
                "anthropic" to ProviderType.ANTHROPIC,
                "gemini" to ProviderType.GEMINI,
                "mistral" to ProviderType.MISTRAL,
                "cohere" to ProviderType.COHERE,
                "deepseek" to ProviderType.DEEPSEEK,
                "qwen" to ProviderType.QWEN,
                "wenxin" to ProviderType.WENXIN,
                "zhipu" to ProviderType.ZHIPU,
                "moonshot" to ProviderType.MOONSHOT,
                "doubao" to ProviderType.DOUBAO,
                "baichuan" to ProviderType.BAICHUAN,
                "model_ark" to ProviderType.MODEL_ARK,
                "custom" to ProviderType.CUSTOM
            ),
            converters::providerTypeToStorage,
            converters::providerTypeFromStorage
        )
    }

    @Test
    fun `every converter rejects an unknown non-null literal`() {
        listOf<(String) -> Any?>(
            converters::accountStateFromStorage,
            converters::mutationOperationTypeFromStorage,
            converters::mutationStageFromStorage,
            converters::legacyMigrationStageFromStorage,
            converters::balanceRecordSourceFromStorage,
            converters::refreshRunSourceFromStorage,
            converters::refreshRunStateFromStorage,
            converters::refreshAccountResultStateFromStorage,
            converters::refreshErrorCategoryFromStorage,
            converters::eventLogTypeFromStorage,
            converters::downloadStateFromStorage,
            converters::monitoringObservedStateFromStorage,
            converters::monitoringSessionEndReasonFromStorage,
            converters::providerTypeFromStorage
        ).forEach { fromStorage ->
            assertThrows(IllegalArgumentException::class.java) { fromStorage("NOT_A_LITERAL") }
        }
    }

    @Test
    fun `raw unknown enum and provider literals fail through their Room DAO readers`() = runTest {
        database.execSql(
            "INSERT INTO accounts " +
                "(id, display_order, label, provider_type, active_credential_generation, state, created_at, updated_at) " +
                "VALUES ('bad-provider', 0, 'bad', 'not-a-provider', 'g', 'VERIFIED', 1, 1)"
        )
        assertDaoLiteralFailure { database.accountDao().get("bad-provider") }

        database.execSql(
            "INSERT INTO accounts " +
                "(id, display_order, label, provider_type, active_credential_generation, state, created_at, updated_at) " +
                "VALUES ('bad-account-state', 1, 'bad', 'openai', 'g', 'NOT_A_STATE', 1, 1)"
        )
        assertDaoLiteralFailure { database.accountDao().get("bad-account-state") }

        database.execSql(
            "INSERT INTO mutation_operations " +
                "(id, operation_type, stage, baseline_revision, created_at, updated_at) " +
                "VALUES ('bad-operation-type', 'NOT_AN_OPERATION', 'PREPARED', 0, 1, 1)"
        )
        assertDaoLiteralFailure { database.mutationOperationDao().get("bad-operation-type") }

        database.execSql(
            "INSERT INTO mutation_operations " +
                "(id, operation_type, stage, baseline_revision, created_at, updated_at) " +
                "VALUES ('bad-operation-stage', 'ACCOUNT_REPLACE', 'NOT_A_STAGE', 0, 1, 1)"
        )
        assertDaoLiteralFailure { database.mutationOperationDao().get("bad-operation-stage") }

        database.execSql(
            "INSERT INTO app_metadata (id, legacy_migration_stage, updated_at) " +
                "VALUES (0, 'NOT_A_MIGRATION_STAGE', 1)"
        )
        assertDaoLiteralFailure { database.appMetadataDao().get() }

        database.accountDao().insertCreate(testAccount("enum-parent"))
        database.execSql(
            "INSERT INTO balance_records " +
                "(account_id, currency, recorded_at, total_balance, source) " +
                "VALUES ('enum-parent', 'USD', 1, 1.0, 'NOT_A_SOURCE')"
        )
        assertDaoLiteralFailure {
            database.historyDao().rangePage("enum-parent", "USD", 0, 2, 1)
        }

        database.execSql(
            "INSERT INTO event_logs (id, event_type, recorded_at) " +
                "VALUES (101, 'NOT_AN_EVENT', 1)"
        )
        assertDaoLiteralFailure { database.eventLogDao().get(101) }

        database.execSql(
            "INSERT INTO refresh_runs (id, source, state, started_at) " +
                "VALUES ('bad-run-source', 'NOT_A_RUN_SOURCE', 'RUNNING', 1)"
        )
        assertDaoLiteralFailure { database.refreshRunDao().getRun("bad-run-source") }

        database.execSql(
            "INSERT INTO refresh_runs (id, source, state, started_at) " +
                "VALUES ('bad-run-state', 'MANUAL', 'NOT_A_RUN_STATE', 1)"
        )
        assertDaoLiteralFailure { database.refreshRunDao().getRun("bad-run-state") }

        database.execSql(
            "INSERT INTO refresh_runs (id, source, state, started_at) " +
                "VALUES ('result-parent', 'MANUAL', 'RUNNING', 1)"
        )
        database.execSql(
            "INSERT INTO refresh_account_results " +
                "(run_id, account_id, account_revision, state, started_at) " +
                "VALUES ('result-parent', 'bad-result-state', 0, 'NOT_A_RESULT_STATE', 1)"
        )
        assertDaoLiteralFailure {
            database.refreshRunDao().getAccountResult("result-parent", "bad-result-state")
        }
        database.execSql(
            "INSERT INTO refresh_account_results " +
                "(run_id, account_id, account_revision, state, error_category, started_at) " +
                "VALUES ('result-parent', 'bad-error-category', 0, 'RUNNING', 'NOT_A_CATEGORY', 1)"
        )
        assertDaoLiteralFailure {
            database.refreshRunDao().getAccountResult("result-parent", "bad-error-category")
        }

        database.execSql(
            "INSERT INTO download_operations " +
                "(id, owner_id, tag, source_url, temporary_path, target_path, state, created_at, updated_at) " +
                "VALUES ('bad-download', 'owner', 'tag', 'source', 'temp', 'target', 'NOT_A_DOWNLOAD_STATE', 1, 1)"
        )
        assertDaoLiteralFailure { database.downloadOperationDao().get("bad-download") }

        database.execSql(
            "INSERT INTO monitoring_state (id, observed_state, updated_at) " +
                "VALUES (0, 'NOT_AN_OBSERVED_STATE', 1)"
        )
        assertDaoLiteralFailure {
            database.monitoringStateDao().getOrCreate(MonitoringStateEntity(updatedAt = 1))
        }

        database.execSql(
            "INSERT INTO monitoring_sessions " +
                "(id, process_session_id, started_at, ended_at, end_reason) " +
                "VALUES ('bad-end-reason', 'process', 1, 2, 'NOT_AN_END_REASON')"
        )
        assertDaoLiteralFailure { database.monitoringSessionDao().get("bad-end-reason") }
    }

    private fun <T> assertRoundTrips(
        literals: List<Pair<String, T>>,
        toStorage: (T?) -> String?,
        fromStorage: (String?) -> T?
    ) {
        literals.forEach { (literal, value) ->
            assertEquals(literal, toStorage(value))
            assertEquals(value, fromStorage(literal))
        }
        assertEquals(null, toStorage(null))
        assertEquals(null, fromStorage(null))
    }

    private suspend fun assertDaoLiteralFailure(read: suspend () -> Any?) {
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { read() }
        }
    }
}
