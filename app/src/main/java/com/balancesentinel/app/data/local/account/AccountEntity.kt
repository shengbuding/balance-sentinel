package com.balancesentinel.app.data.local.account

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.balancesentinel.app.data.api.ProviderType

enum class AccountState {
    PENDING,
    VERIFIED;

    companion object {
        fun fromStorage(value: String): AccountState = when (value) {
            "PENDING" -> PENDING
            "VERIFIED" -> VERIFIED
            else -> throw IllegalArgumentException("Unknown AccountState: $value")
        }
    }
}

@Entity(
    tableName = "accounts",
    indices = [
        Index(value = ["display_order"]),
        Index(value = ["legacy_storage_id"], unique = true)
    ]
)
data class AccountEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "display_order")
    val displayOrder: Int,
    val label: String,
    @ColumnInfo(name = "provider_type")
    val providerType: ProviderType,
    @ColumnInfo(name = "provider_config_json", defaultValue = "'{}'")
    val providerConfigJson: String = "{}",
    @ColumnInfo(name = "active_credential_generation")
    val activeCredentialGeneration: String,
    @ColumnInfo(defaultValue = "0")
    val revision: Long = 0,
    @ColumnInfo(defaultValue = "'PENDING'")
    val state: AccountState = AccountState.PENDING,
    @ColumnInfo(name = "legacy_storage_id", defaultValue = "NULL")
    val legacyStorageId: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)
