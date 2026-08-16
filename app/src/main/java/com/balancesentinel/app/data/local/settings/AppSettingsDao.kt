package com.balancesentinel.app.data.local.settings

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
abstract class AppSettingsDao {
    @Query("SELECT * FROM app_settings WHERE id = 0")
    abstract suspend fun get(): AppSettingsEntity?

    @Query("SELECT * FROM app_settings WHERE id = 0")
    abstract fun observe(): Flow<AppSettingsEntity?>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertSingletonRow(settings: AppSettingsEntity): Long

    suspend fun ensureSingleton(updatedAt: Long): Long =
        insertSingletonRow(AppSettingsEntity(updatedAt = updatedAt))

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun replaceSingletonRow(settings: AppSettingsEntity)

    suspend fun upsert(
        backgroundRefreshIntervalSeconds: Int?,
        foregroundMonitoringIntervalSeconds: Int,
        alertEnabled: Boolean,
        alertThreshold: Double,
        changeAlertEnabled: Boolean,
        changeAlertThreshold: Double,
        changeAlertPeriodMinutes: Int,
        logMaxEntries: Int,
        snoozeDurationMinutes: Int,
        showTotalBalanceInNotification: Boolean,
        updatedAt: Long,
        notificationTotalDisplayOrder: Int = 0
    ) {
        replaceSingletonRow(
            AppSettingsEntity(
                id = 0,
                backgroundRefreshIntervalSeconds = backgroundRefreshIntervalSeconds,
                foregroundMonitoringIntervalSeconds = foregroundMonitoringIntervalSeconds,
                alertEnabled = alertEnabled,
                alertThreshold = alertThreshold,
                changeAlertEnabled = changeAlertEnabled,
                changeAlertThreshold = changeAlertThreshold,
                changeAlertPeriodMinutes = changeAlertPeriodMinutes,
                logMaxEntries = logMaxEntries,
                snoozeDurationMinutes = snoozeDurationMinutes,
                showTotalBalanceInNotification = showTotalBalanceInNotification,
                updatedAt = updatedAt,
                notificationTotalDisplayOrder = notificationTotalDisplayOrder
            )
        )
    }
}
