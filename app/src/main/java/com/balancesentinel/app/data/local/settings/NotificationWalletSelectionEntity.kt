package com.balancesentinel.app.data.local.settings

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import com.balancesentinel.app.data.local.account.AccountEntity

@Entity(
    tableName = "notification_wallet_selections",
    primaryKeys = ["account_id", "currency"],
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["display_order"], unique = true)]
)
data class NotificationWalletSelectionEntity(
    @ColumnInfo(name = "account_id")
    val accountId: String,
    val currency: String,
    @ColumnInfo(name = "display_order")
    val displayOrder: Int
)
