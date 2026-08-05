package com.balancesentinel.app.data.local.log

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.balancesentinel.app.data.local.account.AccountEntity
import com.balancesentinel.app.data.local.refresh.RefreshRunEntity

enum class EventLogType {
    MANUAL,
    AUTO,
    SCHEDULE,
    MISSED,
    SERVICE_DIED,
    SERVICE_START,
    WATCHDOG;

    companion object {
        fun fromStorage(value: String): EventLogType = entries.firstOrNull {
            it.name == value
        } ?: throw IllegalArgumentException("Unknown EventLogType: $value")
    }
}

@Entity(
    tableName = "event_logs",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = RefreshRunEntity::class,
            parentColumns = ["id"],
            childColumns = ["refresh_run_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index(value = ["recorded_at", "id"])]
)
data class EventLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "account_id", defaultValue = "NULL")
    val accountId: String? = null,
    @ColumnInfo(name = "refresh_run_id", defaultValue = "NULL")
    val refreshRunId: String? = null,
    @ColumnInfo(name = "event_type")
    val eventType: EventLogType,
    @ColumnInfo(name = "total_balance_text", defaultValue = "''")
    val totalBalanceText: String = "",
    @ColumnInfo(name = "currency_text", defaultValue = "''")
    val currencyText: String = "",
    @ColumnInfo(name = "is_available", defaultValue = "0")
    val isAvailable: Boolean = false,
    @ColumnInfo(name = "granted_balance_text", defaultValue = "''")
    val grantedBalanceText: String = "",
    @ColumnInfo(name = "topped_up_balance_text", defaultValue = "''")
    val toppedUpBalanceText: String = "",
    @ColumnInfo(name = "recorded_at")
    val recordedAt: Long,
    @ColumnInfo(defaultValue = "''")
    val message: String = "",
    @ColumnInfo(name = "interval_seconds", defaultValue = "NULL")
    val intervalSeconds: Int? = null,
    @ColumnInfo(name = "expected_at", defaultValue = "NULL")
    val expectedAt: Long? = null,
    @ColumnInfo(name = "alarm_method", defaultValue = "NULL")
    val alarmMethod: String? = null,
    @ColumnInfo(name = "miss_reason", defaultValue = "NULL")
    val missReason: String? = null
)
