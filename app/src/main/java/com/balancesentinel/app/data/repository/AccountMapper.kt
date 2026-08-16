package com.balancesentinel.app.data.repository

import com.balancesentinel.app.data.local.account.AccountEntity
import com.balancesentinel.app.data.local.account.AccountState
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.model.AccountInfo
import com.balancesentinel.app.data.model.decodeUsageDisplayFields
import com.balancesentinel.app.data.model.encodeUsageDisplayFields
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object AccountMapper {
    private val json = Json { ignoreUnknownKeys = true }

    fun toEntity(
        account: AccountInfo,
        accountId: String,
        credentialGeneration: String,
        displayOrder: Int,
        legacyStorageId: String? = account.id,
        state: AccountState = AccountState.PENDING,
        now: Long
    ): AccountEntity = AccountEntity(
        id = accountId,
        displayOrder = displayOrder,
        label = account.label.trim(),
        providerType = account.providerType,
        providerConfigJson = buildJsonObject {
            account.extraSettings.forEach { (key, value) -> put(key, value) }
            account.usageScript?.let { put("usageScript", it) }
            put("usageScriptEnabled", account.usageScriptEnabled)
            put("authorizedScriptOrigins", account.authorizedScriptOrigins.sorted().joinToString(","))
            if (account.usageDisplayFields.isNotEmpty()) {
                put("usageDisplayFields", encodeUsageDisplayFields(account.usageDisplayFields))
            }
            account.usageBalanceField?.takeIf(String::isNotBlank)?.let {
                put("usageBalanceField", it)
            }
        }.toString(),
        activeCredentialGeneration = credentialGeneration,
        state = state,
        legacyStorageId = legacyStorageId,
        createdAt = now,
        updatedAt = now
    )

    fun toRepositoryAccount(entity: AccountEntity): RepositoryAccount = RepositoryAccount(
        id = entity.id,
        displayOrder = entity.displayOrder,
        label = entity.label,
        providerType = entity.providerType,
        providerConfigJson = entity.providerConfigJson,
        activeCredentialGeneration = entity.activeCredentialGeneration,
        revision = entity.revision,
        legacyStorageId = entity.legacyStorageId
    )

    fun toCredentialFreeAccount(entity: AccountEntity): AccountInfo {
        val config = json.parseToJsonElement(entity.providerConfigJson) as? JsonObject
            ?: throw IllegalArgumentException("Account provider config must be a JSON object")
        val usageScriptEnabled = config["usageScriptEnabled"]
            ?.jsonPrimitive
            ?.booleanOrNull
            ?: true
        val authorizedScriptOrigins = config["authorizedScriptOrigins"]
            ?.jsonPrimitive
            ?.contentOrNull
            .orEmpty()
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toSet()
        val usageDisplayFields = decodeUsageDisplayFields(
            config["usageDisplayFields"]?.jsonPrimitive?.contentOrNull
        )
        val usageBalanceField = config["usageBalanceField"]?.jsonPrimitive?.contentOrNull
        val extraSettings = config
            .filterKeys { it !in PROVIDER_METADATA_KEYS }
            .mapValues { (key, value) ->
                (value as? JsonPrimitive)?.contentOrNull
                    ?: throw IllegalArgumentException("Provider setting $key must be a JSON primitive")
            }

        return AccountInfo(
            id = entity.id,
            label = entity.label,
            apiKey = "",
            providerType = entity.providerType,
            extraSettings = extraSettings,
            usageScript = null,
            usageScriptEnabled = usageScriptEnabled,
            authorizedScriptOrigins = authorizedScriptOrigins,
            revision = entity.revision,
            usageDisplayFields = usageDisplayFields,
            usageBalanceField = usageBalanceField
        )
    }

    private val PROVIDER_METADATA_KEYS = setOf(
        "usageScript",
        "usageScriptEnabled",
        "authorizedScriptOrigins",
        "usageDisplayFields",
        "usageBalanceField"
    )
}
