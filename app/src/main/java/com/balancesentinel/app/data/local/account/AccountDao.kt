package com.balancesentinel.app.data.local.account

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.balancesentinel.app.data.api.ProviderType
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts WHERE state = 'VERIFIED' ORDER BY display_order, id")
    fun observeVerified(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun get(id: String): AccountEntity?

    @Query("SELECT * FROM accounts ORDER BY display_order, id")
    suspend fun getAllForMigration(): List<AccountEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCreate(account: AccountEntity)

    @Query(
        """
        UPDATE accounts SET
            display_order = :displayOrder,
            label = :label,
            provider_type = :providerType,
            provider_config_json = :providerConfigJson,
            active_credential_generation = :activeCredentialGeneration,
            revision = revision + 1,
            updated_at = :updatedAt
        WHERE id = :id AND revision = :expectedRevision
        """
    )
    suspend fun updateWhereRevision(
        id: String,
        expectedRevision: Long,
        displayOrder: Int,
        label: String,
        providerType: ProviderType,
        providerConfigJson: String,
        activeCredentialGeneration: String,
        updatedAt: Long
    ): Int

    @Query("DELETE FROM accounts WHERE id = :id AND revision = :expectedRevision")
    suspend fun deleteWhereRevision(id: String, expectedRevision: Long): Int
}
