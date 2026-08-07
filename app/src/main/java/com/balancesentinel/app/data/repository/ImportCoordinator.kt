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
    private val publishSettings: suspend (ConfigSettings) -> Unit,
    private val readRevision: suspend () -> Long = { 0L },
    private val publishRevision: suspend (ConfigSettings, Long) -> Unit = { value, _ -> publishSettings(value) }
) {
    suspend fun preview(accounts: List<AccountInfo>, settings: ConfigSettings): ImportPlan {
        val baselineRevision = readRevision()
        return ImportPlan(
            accounts,
            settings,
            baselineRevision,
            ImportFingerprint.sha256(accounts, settings, baselineRevision)
        )
    }

    suspend fun apply(plan: ImportPlan): ImportApplyResult {
        val currentAccounts = readAccounts()
        val currentSettings = readSettings()
        val currentRevision = readRevision()
        val currentFingerprint = ImportFingerprint.sha256(currentAccounts, currentSettings, currentRevision)
        if (currentRevision != plan.baselineRevision || currentFingerprint != plan.fingerprint) {
            return ImportApplyResult.StalePlan
        }
        val oldAccounts = currentAccounts
        val oldSettings = currentSettings
        return try {
            persistAccounts(plan.accounts)
            publishRevision(plan.settings, plan.baselineRevision + 1)
            ImportApplyResult.Applied
        } catch (error: Throwable) {
            runCatching { persistAccounts(oldAccounts) }.onFailure { error.addSuppressed(it) }
            runCatching { publishSettings(oldSettings) }.onFailure { error.addSuppressed(it) }
            ImportApplyResult.Failed(error)
        }
    }
}
