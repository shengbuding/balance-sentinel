package com.balancesentinel.app.data.local.settings

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import com.balancesentinel.app.data.local.account.AccountEntity

@Entity(
    tableName = "alert_runtime_state",
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
data class AlertRuntimeStateEntity(
    @ColumnInfo(name = "account_id")
    val accountId: String,
    val currency: String,
    @ColumnInfo(name = "last_alerted_balance", defaultValue = "NULL")
    val lastAlertedBalance: Double? = null,
    @ColumnInfo(name = "anchor_balance", defaultValue = "NULL")
    val anchorBalance: Double? = null,
    @ColumnInfo(name = "anchor_at", defaultValue = "NULL")
    val anchorAt: Long? = null,
    @ColumnInfo(name = "last_change_alerted_balance", defaultValue = "NULL")
    val lastChangeAlertedBalance: Double? = null,
    @ColumnInfo(name = "last_change_alerted_at", defaultValue = "NULL")
    val lastChangeAlertedAt: Long? = null
)
