package com.balancesentinel.app.data.repository

import com.balancesentinel.app.data.api.ConfigFieldStorage
import com.balancesentinel.app.data.api.balance.ScriptInspection
import com.balancesentinel.app.data.api.balance.UsageScript
import com.balancesentinel.app.data.api.balance.UsageScriptExecutor
import com.balancesentinel.app.data.api.balance.WebOrigin
import com.balancesentinel.app.data.api.providers.ProviderConfigs
import com.balancesentinel.app.data.model.AccountInfo
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

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
    val settings: ConfigSettings,
    val baselineRevision: Long = 0L,
    val fingerprint: String = ""
)

class StalePlanException(message: String = "Import preview is stale; preview again") : IllegalStateException(message)

data class ScriptAuthorization(
    val accountId: String,
    val requiredExtraOrigins: Set<WebOrigin>,
    val staticallyDeterminable: Boolean
)

class BackupImportPlanner(
    private val apiKeyManager: ApiKeyManager,
    @Suppress("UNUSED_PARAMETER") private val widgetPrefs: WidgetPrefs,
    private val settingsRepository: SettingsRepository? = null,
    private val inspectScript: suspend (UsageScript, AccountInfo) -> ScriptInspection =
        { script, account -> UsageScriptExecutor.inspect(script, account) }
) {
    constructor(
        apiKeyManager: ApiKeyManager,
        widgetPrefs: WidgetPrefs,
        inspectScript: suspend (UsageScript, AccountInfo) -> ScriptInspection
    ) : this(apiKeyManager, widgetPrefs, null, inspectScript)

    internal val usesAtomicSettingsPublication: Boolean
        get() = settingsRepository != null

    suspend fun plan(
        config: AppConfig,
        localAccounts: List<AccountInfo>,
        mode: ImportMode
    ): BackupImportPlan {
        val hasFullCredentials = when (config.version) {
            1 -> config.accounts.isNotEmpty() && config.accounts.all(::hasCompleteCredentials)
            else -> config.credentialsIncluded
        }
        val normalized = config.accounts.map { incoming ->
            if (hasFullCredentials) {
                normalizeFullAccount(incoming, config.version)
            } else {
                normalizeSanitizedAccount(incoming, config.version, localAccounts)
            }
        }
        val duplicateSourceIds = config.accounts
            .groupingBy { it.id }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        val duplicateIds = normalized
            .mapNotNull { it.account?.id }
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys

        val finalAccounts = if (mode == ImportMode.MERGE) {
            localAccounts.toMutableList()
        } else {
            mutableListOf()
        }
        val scriptAuthorizations = mutableListOf<ScriptAuthorization>()
        var matchedUpdatedCount = 0
        var retainedCredentialCount = 0
        var createdCount = 0
        var skippedCount = 0
        var conflictCount = normalized.count { it.account == null }

        for (entry in normalized) {
            val incoming = entry.account ?: continue
            if (entry.sourceId in duplicateSourceIds || incoming.id in duplicateIds) {
                conflictCount++
                continue
            }

            val localIndex = findLocalIndex(
                localAccounts,
                incoming,
                entry.sourceId,
                hasFullCredentials
            )
            if (!hasFullCredentials) {
                if (mode != ImportMode.MERGE || localIndex < 0) {
                    skippedCount++
                    continue
                }
                val local = localAccounts[localIndex]
                if (!hasCompleteCredentials(local, incoming.providerType)) {
                    conflictCount++
                    continue
                }
                val allowedSettingKeys = ProviderConfigs.getConfigFields(incoming.providerType)
                    .asSequence()
                    .filter { it.storage == ConfigFieldStorage.SETTING }
                    .map { it.key }
                    .toSet()
                val importedSettings = incoming.extraSettings.filterKeys { it in allowedSettingKeys }
                finalAccounts[localIndex] = local.copy(
                    label = incoming.label,
                    providerType = incoming.providerType,
                    extraSettings = local.extraSettings + importedSettings
                )
                matchedUpdatedCount++
                retainedCredentialCount++
                continue
            }

            if (!hasCompleteCredentials(incoming)) {
                conflictCount++
                continue
            }
            val prepared = incoming.copy(
                apiKey = incoming.apiKey.trim(),
                usageScriptEnabled = false,
                authorizedScriptOrigins = emptySet(),
                // The backup revision is source metadata; local account revisions remain local.
                revision = if (localIndex >= 0) localAccounts[localIndex].revision else 0L
            )
            prepared.usageScript?.takeIf { it.isNotBlank() }?.let { code ->
                val inspection = runCatching {
                    inspectScript(UsageScript(code = code, enabled = false), prepared)
                }.getOrElse {
                    ScriptInspection(
                        request = null,
                        requiredExtraOrigins = emptySet(),
                        staticallyDeterminable = false
                    )
                }
                scriptAuthorizations += ScriptAuthorization(
                    accountId = prepared.id,
                    requiredExtraOrigins = inspection.requiredExtraOrigins.toSet(),
                    staticallyDeterminable = inspection.allowsImportedEnablement()
                )
            }

            if (localIndex >= 0) {
                if (mode == ImportMode.MERGE) {
                    finalAccounts[localIndex] = prepared
                } else {
                    finalAccounts += prepared
                }
                matchedUpdatedCount++
            } else {
                finalAccounts += prepared
                createdCount++
            }
        }

        val finalIds = finalAccounts.mapTo(mutableSetOf()) { it.id }
        val deletedCount = if (mode == ImportMode.REPLACE_ALL) {
            localAccounts.count { it.id !in finalIds }
        } else {
            0
        }
        val blockingReasons = if (mode == ImportMode.REPLACE_ALL) {
            buildList {
                if (!hasFullCredentials) add(BLOCK_CREDENTIALS_REQUIRED)
                if (conflictCount > 0) add(BLOCK_CONFLICTS)
                if (finalAccounts.any { !hasCompleteCredentials(it) }) add(BLOCK_INCOMPLETE_ACCOUNTS)
                val authorizationsById = scriptAuthorizations.associateBy { it.accountId }
                if (finalAccounts.any { account ->
                        account.usageScriptEnabled &&
                            authorizationsById[account.id]?.staticallyDeterminable != true
                    }
                ) {
                    add(BLOCK_SCRIPT_INSPECTION)
                }
            }
        } else {
            emptyList()
        }

        val baselineRevision = settingsRepository?.currentRevision() ?: 0L
        val baselineSettings = settingsRepository
            ?.readSnapshot()
            ?.let(ConfigManager::toConfigSettings)
            ?: config.settings
        return BackupImportPlan(
            mode = mode,
            finalAccounts = finalAccounts.toList(),
            matchedUpdatedCount = matchedUpdatedCount,
            retainedCredentialCount = retainedCredentialCount,
            createdCount = createdCount,
            skippedCount = skippedCount,
            conflictCount = conflictCount,
            deletedCount = deletedCount,
            scriptAuthorizations = scriptAuthorizations.toList(),
            canApply = blockingReasons.isEmpty(),
            blockingReasons = blockingReasons,
            settings = config.settings,
            baselineRevision = baselineRevision,
            fingerprint = ImportFingerprint.sha256(localAccounts, baselineSettings, baselineRevision)
        )
    }

    fun withScriptAuthorizations(
        plan: BackupImportPlan,
        enabledAccountIds: Set<String>,
        authorizedOrigins: Map<String, Set<WebOrigin>>
    ): BackupImportPlan {
        val authorizationById = plan.scriptAuthorizations.associateBy { it.accountId }
        val accounts = plan.finalAccounts.map { account ->
            val authorization = authorizationById[account.id] ?: return@map account
            val selectedOrigins = authorizedOrigins[account.id].orEmpty()
            val enable = account.id in enabledAccountIds &&
                authorization.staticallyDeterminable &&
                selectedOrigins.containsAll(authorization.requiredExtraOrigins)
            account.copy(
                usageScriptEnabled = enable,
                authorizedScriptOrigins = if (enable) {
                    authorization.requiredExtraOrigins
                        .map(::canonicalOrigin)
                        .toSortedSet()
                } else {
                    emptySet()
                }
            )
        }
        return plan.copy(finalAccounts = accounts)
    }

    /**
     * Synchronous imports cannot participate in the Room publication protocol.
     * Keep the source-compatible entry point, but fail before touching either
     * account credentials or configuration settings.
     */
    fun apply(plan: BackupImportPlan, confirmedFullReplace: Boolean) {
        validateApply(plan, confirmedFullReplace)
        // Legacy callers constructed without a Room repository have no settings
        // publication seam. Preserve their synchronous account/settings adapter,
        // while keeping the injected Room path async-only so it cannot bypass the
        // atomic publication protocol.
        if (settingsRepository == null) {
            val before = apiKeyManager.getAccounts()
            apiKeyManager.replaceAll(plan.finalAccounts)
            try {
                widgetPrefs.refreshIntervalSeconds = plan.settings.refreshIntervalSeconds
            } catch (failure: Throwable) {
                runCatching { apiKeyManager.replaceAll(before) }
                    .onFailure { failure.addSuppressed(it) }
                throw failure
            }
            return
        }
        error("Synchronous configuration imports are not supported; use applyAsync")
    }

    suspend fun applyAsync(plan: BackupImportPlan, confirmedFullReplace: Boolean) {
        validateApply(plan, confirmedFullReplace)
        val repository = settingsRepository
        if (repository == null) {
            // Source-compatible tests and legacy callers have no Room seam. Keep this
            // adapter deliberately narrow; production constructors always inject Room.
            val before = apiKeyManager.getAccounts()
            apiKeyManager.replaceAll(plan.finalAccounts)
            try {
                widgetPrefs.refreshIntervalSeconds = plan.settings.refreshIntervalSeconds
            } catch (failure: Throwable) {
                runCatching { apiKeyManager.replaceAll(before) }
                    .onFailure { failure.addSuppressed(it) }
                throw failure
            }
            return
        }
        val previousAccounts = apiKeyManager.getAccounts()
        val currentRevision = repository.currentRevision()
        val currentSettings = ConfigManager.toConfigSettings(repository.readSnapshot())
        if (currentRevision != plan.baselineRevision ||
            ImportFingerprint.sha256(previousAccounts, currentSettings, currentRevision) != plan.fingerprint
        ) {
            throw StalePlanException()
        }
        try {
            repository.applyConfigImport(plan.settings) {
                apiKeyManager.replaceAll(plan.finalAccounts)
            }
        } catch (failure: Throwable) {
            runCatching { apiKeyManager.replaceAll(previousAccounts) }
                .onFailure { rollbackError -> failure.addSuppressed(rollbackError) }
            throw failure
        }
    }

    private fun validateApply(plan: BackupImportPlan, confirmedFullReplace: Boolean) {
        check(plan.canApply) {
            "Backup import is blocked: ${plan.blockingReasons.joinToString()}"
        }
        check(plan.mode != ImportMode.REPLACE_ALL || confirmedFullReplace) {
            "Replacing all accounts requires explicit destructive confirmation"
        }
    }

    private fun findLocalIndex(
        localAccounts: List<AccountInfo>,
        incoming: AccountInfo,
        sourceId: String,
        hasFullCredentials: Boolean
    ): Int = localAccounts.indexOfFirst { local ->
        local.id == incoming.id ||
            (hasFullCredentials &&
                sourceId.matches(LEGACY_ID) &&
                local.id == sourceId &&
                apiKeyManager.computeId(local.apiKey) == incoming.id)
    }

    private fun ScriptInspection.allowsImportedEnablement(): Boolean =
        staticallyDeterminable &&
            (request == null || request.url.toHttpUrlOrNull()?.scheme == "https") &&
            requiredExtraOrigins.all { it.scheme == "https" }

    private fun normalizeFullAccount(account: AccountInfo, version: Int): NormalizedAccount {
        if (!hasCompleteCredentials(account)) return NormalizedAccount(account.id, null)
        val fullId = apiKeyManager.computeId(account.apiKey)
        val normalizedId = when {
            account.id.matches(FULL_ID) && account.id == fullId -> fullId
            version == 1 && account.id.matches(LEGACY_ID) &&
                account.id == apiKeyManager.computeLegacyId(account.apiKey) -> fullId
            else -> return NormalizedAccount(account.id, null)
        }
        return NormalizedAccount(account.id, account.copy(id = normalizedId))
    }

    private fun normalizeSanitizedAccount(
        account: AccountInfo,
        version: Int,
        localAccounts: List<AccountInfo>
    ): NormalizedAccount {
        if (account.id.matches(FULL_ID)) return NormalizedAccount(account.id, account)
        if (version != 1 || !account.id.matches(LEGACY_ID)) {
            return NormalizedAccount(account.id, null)
        }
        val matchingLocals = localAccounts.filter { local ->
            local.id == account.id ||
                (local.id.matches(FULL_ID) && local.id.startsWith(account.id))
        }
        return if (matchingLocals.size == 1) {
            NormalizedAccount(account.id, account.copy(id = matchingLocals.single().id))
        } else {
            NormalizedAccount(account.id, null)
        }
    }

    private fun hasCompleteCredentials(account: AccountInfo): Boolean =
        hasCompleteCredentials(account, account.providerType)

    private fun hasCompleteCredentials(
        account: AccountInfo,
        providerType: com.balancesentinel.app.data.api.ProviderType
    ): Boolean = ProviderConfigs.getConfigFields(providerType)
        .asSequence()
        .filter { it.storage != ConfigFieldStorage.SETTING && it.required }
        .all { field ->
            val value = when (field.storage) {
                ConfigFieldStorage.PRIMARY_CREDENTIAL -> account.apiKey
                ConfigFieldStorage.EXTRA_CREDENTIAL -> account.extraCredentials[field.key].orEmpty()
                ConfigFieldStorage.SETTING -> error("Setting fields are filtered above")
            }
            value.isNotBlank() && !ConfigManager.isRedactedApiKey(value)
        }

    private data class NormalizedAccount(
        val sourceId: String,
        val account: AccountInfo?
    )

    private companion object {
        val FULL_ID = Regex("[0-9a-f]{16}")
        val LEGACY_ID = Regex("[0-9a-f]{8}")
        const val BLOCK_CREDENTIALS_REQUIRED = "credentials_required"
        const val BLOCK_CONFLICTS = "conflicts_present"
        const val BLOCK_INCOMPLETE_ACCOUNTS = "incomplete_accounts"
        const val BLOCK_SCRIPT_INSPECTION = "script_inspection_required"

        fun canonicalOrigin(origin: WebOrigin): String {
            val host = if (':' in origin.host) "[${origin.host}]" else origin.host
            val defaultPort = (origin.scheme == "https" && origin.port == 443) ||
                (origin.scheme == "http" && origin.port == 80)
            return buildString {
                append(origin.scheme)
                append("://")
                append(host)
                if (!defaultPort) append(":${origin.port}")
            }
        }
    }
}
