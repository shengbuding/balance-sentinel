package com.balancesentinel.app.data.local.settings

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import com.balancesentinel.app.data.local.account.AccountEntity

@Entity(
    tableName = "account_alert_settings",
    primaryKeys = ["account_id", "currency"],
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class AccountAlertSettingEntity(
    @ColumnInfo(name = "account_id")
    val accountId: String,
    val currency: String,
    @ColumnInfo(name = "balance_alert_enabled", defaultValue = "0")
    val balanceAlertEnabled: Boolean = false,
    @ColumnInfo(name = "change_alert_enabled", defaultValue = "0")
    val changeAlertEnabled: Boolean = false
)
