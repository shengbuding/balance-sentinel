package com.balancesentinel.app.data.repository

import com.balancesentinel.app.data.local.account.AccountEntity

object AccountMapper {
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
