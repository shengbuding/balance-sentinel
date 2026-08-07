package com.balancesentinel.app.data.local.history

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.balancesentinel.app.data.local.account.AccountEntity

enum class BalanceRecordSource {
    REFRESH,
    IMPORT,
    LEGACY_MIGRATION;

    companion object {
        fun fromStorage(value: String): BalanceRecordSource = entries.firstOrNull {
            it.name == value
        } ?: throw IllegalArgumentException("Unknown BalanceRecordSource: $value")
    }
}

@Entity(
    tableName = "balance_records",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["account_id", "currency", "recorded_at", "id"]),
        Index(value = ["recorded_at", "id"])
    ]
)
data class BalanceRecordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "account_id")
    val accountId: String,
    val currency: String,
    @ColumnInfo(name = "recorded_at")
    val recordedAt: Long,
    @ColumnInfo(name = "total_balance")
    val totalBalance: Double,
    @ColumnInfo(name = "granted_balance", defaultValue = "0.0")
    val grantedBalance: Double = 0.0,
    @ColumnInfo(name = "topped_up_balance", defaultValue = "0.0")
    val toppedUpBalance: Double = 0.0,
    @ColumnInfo(defaultValue = "'REFRESH'")
    val source: BalanceRecordSource = BalanceRecordSource.REFRESH
)
