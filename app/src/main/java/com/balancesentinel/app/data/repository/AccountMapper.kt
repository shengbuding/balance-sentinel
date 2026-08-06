package com.balancesentinel.app.data.repository

import com.balancesentinel.app.data.local.account.AccountEntity
import com.balancesentinel.app.data.local.account.AccountState
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.model.AccountInfo
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object AccountMapper {
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
}
