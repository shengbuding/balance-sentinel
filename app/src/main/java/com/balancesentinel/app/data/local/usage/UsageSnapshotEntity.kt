package com.balancesentinel.app.data.local.usage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.balancesentinel.app.data.local.account.AccountEntity

@Entity(
    tableName = "usage_snapshots",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(
            value = ["account_id", "captured_at", "identity_discriminator"],
            unique = true
        )
    ]
)
data class UsageSnapshotEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "account_id")
    val accountId: String,
    @ColumnInfo(name = "captured_at")
    val capturedAt: Long,
    @ColumnInfo(name = "identity_discriminator", defaultValue = "''")
    val identityDiscriminator: String = ""
)
