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

    @Query(
        """
        UPDATE accounts SET
            display_order = :displayOrder,
            label = :label,
            provider_type = :providerType,
            provider_config_json = :providerConfigJson,
            active_credential_generation = :activeCredentialGeneration,
            updated_at = :updatedAt
        WHERE id = :id
          AND state = 'PENDING'
          AND revision = 0
          AND legacy_storage_id = :legacyStorageId
          AND active_credential_generation = :expectedOrphanGeneration
        """
    )
    suspend fun hydrateLegacyOrphan(
        id: String,
        legacyStorageId: String,
        expectedOrphanGeneration: String,
        displayOrder: Int,
        label: String,
        providerType: ProviderType,
        providerConfigJson: String,
        activeCredentialGeneration: String,
        updatedAt: Long
    ): Int

    /**
     * Re-publish an already verified account after its external credential
     * payload has been repaired. Keeping revision unchanged preserves the
     * payload/Room consistency contract while the UPDATE invalidates Room
     * observers.
     */
    @Query(
        """
        UPDATE accounts SET updated_at = :updatedAt
        WHERE id = :id
          AND state = 'VERIFIED'
          AND revision = :expectedRevision
          AND active_credential_generation = :expectedGeneration
          AND legacy_storage_id = :expectedLegacyStorageId
        """
    )
    suspend fun touchAfterCredentialRepair(
        id: String,
        expectedRevision: Long,
        expectedGeneration: String,
        expectedLegacyStorageId: String,
        updatedAt: Long
    ): Int

    @Query("DELETE FROM accounts WHERE id = :id AND revision = :expectedRevision")
    suspend fun deleteWhereRevision(id: String, expectedRevision: Long): Int
}
