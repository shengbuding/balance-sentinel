package com.balancesentinel.app.data.repository

import com.balancesentinel.app.data.api.balance.ScriptInspection
import com.balancesentinel.app.data.api.balance.UsageScript
import com.balancesentinel.app.data.api.balance.UsageScriptExecutor
import com.balancesentinel.app.data.api.balance.WebOrigin
import com.balancesentinel.app.data.model.AccountInfo

enum class ImportMode { MERGE, REPLACE_ALL }

data class BackupImportPlan(
    val mode: ImportMode,
    val finalAccounts: List<AccountInfo>,
    val matchedUpdatedCount: Int,
    val retainedCredentialCount: Int,
    val createdCount: Int,
    val skippedCount: Int,
    val conflictCount: Int,
    val deletedCount: Int,
    val scriptAuthorizations: List<ScriptAuthorization>,
    val canApply: Boolean,
    val blockingReasons: List<String>,
    val settings: ConfigSettings
)

data class ScriptAuthorization(
    val accountId: String,
    val requiredExtraOrigins: Set<WebOrigin>,
    val staticallyDeterminable: Boolean
)

class BackupImportPlanner(
    private val apiKeyManager: ApiKeyManager,
    private val widgetPrefs: WidgetPrefs,
    private val inspectScript: suspend (UsageScript, AccountInfo) -> ScriptInspection =
        { script, account -> UsageScriptExecutor.inspect(script, account) }
) {
    suspend fun plan(
        config: AppConfig,
        localAccounts: List<AccountInfo>,
        mode: ImportMode
    ): BackupImportPlan = BackupImportPlan(
        mode = mode,
        finalAccounts = emptyList(),
        matchedUpdatedCount = 0,
        retainedCredentialCount = 0,
        createdCount = 0,
        skippedCount = 0,
        conflictCount = 0,
        deletedCount = 0,
        scriptAuthorizations = emptyList(),
        canApply = true,
        blockingReasons = emptyList(),
        settings = config.settings
    )

    fun withScriptAuthorizations(
        plan: BackupImportPlan,
        enabledAccountIds: Set<String>,
        authorizedOrigins: Map<String, Set<WebOrigin>>
    ): BackupImportPlan = plan

    fun apply(plan: BackupImportPlan, confirmedFullReplace: Boolean) = Unit
}
