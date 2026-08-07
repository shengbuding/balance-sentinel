package com.balancesentinel.app.data.repository

import com.balancesentinel.app.data.model.AccountInfo

data class ImportPlan(
    val accounts: List<AccountInfo>,
    val settings: ConfigSettings,
    val baselineRevision: Long = 0,
    val fingerprint: String = ""
)

sealed interface ImportApplyResult {
    data object Applied : ImportApplyResult
    data object StalePlan : ImportApplyResult
    data class Failed(val error: Throwable) : ImportApplyResult
}

class ImportCoordinator(
    private val readAccounts: suspend () -> List<AccountInfo>,
    private val readSettings: suspend () -> ConfigSettings,
    private val persistAccounts: suspend (List<AccountInfo>) -> Unit,
    private val publishSettings: suspend (ConfigSettings) -> Unit
) {
    suspend fun preview(accounts: List<AccountInfo>, settings: ConfigSettings): ImportPlan =
        ImportPlan(accounts, settings)

    suspend fun apply(plan: ImportPlan): ImportApplyResult = error("Task 8 RED")
}
