package com.balancesentinel.app.data.local.history

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import com.balancesentinel.app.data.local.account.AccountEntity

@Entity(
    tableName = "daily_summaries",
    primaryKeys = ["date", "account_id", "currency"],
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["account_id", "currency", "date"])]
)
data class DailySummaryEntity(
    val date: String,
    @ColumnInfo(name = "account_id")
    val accountId: String,
    val currency: String,
    @ColumnInfo(name = "open_balance")
    val openBalance: Double,
    @ColumnInfo(name = "close_balance")
    val closeBalance: Double,
    @ColumnInfo(name = "consumed_balance")
    val consumedBalance: Double,
    @ColumnInfo(name = "topped_up_balance")
    val toppedUpBalance: Double,
    @ColumnInfo(name = "granted_balance", defaultValue = "0.0")
    val grantedBalance: Double = 0.0,
    @ColumnInfo(name = "average_balance")
    val averageBalance: Double,
    @ColumnInfo(name = "sample_count")
    val sampleCount: Int,
    @ColumnInfo(name = "topped_up_balance_close", defaultValue = "0.0")
    val toppedUpBalanceClose: Double = 0.0,
    @ColumnInfo(name = "granted_balance_close", defaultValue = "0.0")
    val grantedBalanceClose: Double = 0.0,
    @ColumnInfo(name = "generated_at")
    val generatedAt: Long
)
