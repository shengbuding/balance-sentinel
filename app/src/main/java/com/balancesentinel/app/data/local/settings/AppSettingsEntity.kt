package com.balancesentinel.app.data.local.settings

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_settings")
data class AppSettingsEntity(
    @PrimaryKey
    @ColumnInfo(defaultValue = "0")
    val id: Int = 0,
    @ColumnInfo(name = "background_refresh_interval_seconds", defaultValue = "900")
    val backgroundRefreshIntervalSeconds: Int? = 900,
    @ColumnInfo(name = "foreground_monitoring_interval_seconds", defaultValue = "30")
    val foregroundMonitoringIntervalSeconds: Int = 30,
    @ColumnInfo(name = "alert_enabled", defaultValue = "0")
    val alertEnabled: Boolean = false,
    @ColumnInfo(name = "alert_threshold", defaultValue = "0.0")
    val alertThreshold: Double = 0.0,
    @ColumnInfo(name = "change_alert_enabled", defaultValue = "0")
    val changeAlertEnabled: Boolean = false,
    @ColumnInfo(name = "change_alert_threshold", defaultValue = "0.0")
    val changeAlertThreshold: Double = 0.0,
    @ColumnInfo(name = "change_alert_period_minutes", defaultValue = "0")
    val changeAlertPeriodMinutes: Int = 0,
    @ColumnInfo(name = "log_max_entries", defaultValue = "100")
    val logMaxEntries: Int = 100,
    @ColumnInfo(name = "snooze_duration_minutes", defaultValue = "60")
    val snoozeDurationMinutes: Int = 60,
    @ColumnInfo(name = "show_total_balance_in_notification", defaultValue = "1")
    val showTotalBalanceInNotification: Boolean = true,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "notification_total_display_order", defaultValue = "0")
    val notificationTotalDisplayOrder: Int = 0
)
