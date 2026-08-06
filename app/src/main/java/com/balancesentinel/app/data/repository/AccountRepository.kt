package com.balancesentinel.app.data.repository

import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.local.WalletDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class RepositoryAccount(
    val id: String,
    val displayOrder: Int,
    val label: String,
    val providerType: ProviderType,
    val providerConfigJson: String,
    val activeCredentialGeneration: String,
    val revision: Long,
    val legacyStorageId: String?
)

interface AccountRepository {
    fun observeVerified(): Flow<List<RepositoryAccount>>
    suspend fun get(id: String): RepositoryAccount?
    suspend fun getAllForMigration(): List<RepositoryAccount>
}

class RoomAccountRepository(
    private val database: WalletDatabase
) : AccountRepository {
    override fun observeVerified(): Flow<List<RepositoryAccount>> =
        database.accountDao().observeVerified().map { rows -> rows.map(AccountMapper::toRepositoryAccount) }

    override suspend fun get(id: String): RepositoryAccount? =
        database.accountDao().get(id)?.takeIf { it.state.name == "VERIFIED" }?.let(AccountMapper::toRepositoryAccount)

    override suspend fun getAllForMigration(): List<RepositoryAccount> =
        database.accountDao().getAllForMigration().filter { it.state.name == "VERIFIED" }.map(AccountMapper::toRepositoryAccount)
}
