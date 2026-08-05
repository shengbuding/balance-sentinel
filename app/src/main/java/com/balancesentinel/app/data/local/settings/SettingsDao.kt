package com.balancesentinel.app.data.local.settings

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface SettingsDao {
    @Query("SELECT * FROM account_alert_settings ORDER BY account_id, currency")
    suspend fun getAccountAlertSettings(): List<AccountAlertSettingEntity>

    @Query("SELECT * FROM notification_wallet_selections ORDER BY display_order")
    suspend fun getNotificationSelections(): List<NotificationWalletSelectionEntity>

    @Query("SELECT * FROM alert_runtime_state ORDER BY account_id, currency")
    suspend fun getAlertRuntimeStates(): List<AlertRuntimeStateEntity>

    @Query("SELECT * FROM snooze_state ORDER BY account_id")
    suspend fun getSnoozes(): List<SnoozeStateEntity>

    @Query("DELETE FROM account_alert_settings")
    suspend fun clearAccountAlertSettings()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAccountAlertSettings(values: List<AccountAlertSettingEntity>)

    @Transaction
    suspend fun replaceAccountAlertSettings(values: List<AccountAlertSettingEntity>) {
        clearAccountAlertSettings()
        if (values.isNotEmpty()) insertAccountAlertSettings(values)
    }

    @Query("DELETE FROM notification_wallet_selections")
    suspend fun clearNotificationSelections()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotificationSelections(values: List<NotificationWalletSelectionEntity>)

    @Transaction
    suspend fun replaceNotificationSelections(values: List<NotificationWalletSelectionEntity>) {
        clearNotificationSelections()
        if (values.isNotEmpty()) insertNotificationSelections(values)
    }

    @Query("DELETE FROM alert_runtime_state")
    suspend fun clearAlertRuntimeStates()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlertRuntimeStates(values: List<AlertRuntimeStateEntity>)

    @Transaction
    suspend fun replaceAlertRuntimeStates(values: List<AlertRuntimeStateEntity>) {
        clearAlertRuntimeStates()
        if (values.isNotEmpty()) insertAlertRuntimeStates(values)
    }

    @Query("DELETE FROM snooze_state")
    suspend fun clearSnoozes()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSnoozes(values: List<SnoozeStateEntity>)

    @Transaction
    suspend fun replaceSnoozes(values: List<SnoozeStateEntity>) {
        clearSnoozes()
        if (values.isNotEmpty()) insertSnoozes(values)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAlertRuntimeState(value: AlertRuntimeStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setSnooze(value: SnoozeStateEntity)

    @Query("DELETE FROM snooze_state WHERE account_id = :accountId")
    suspend fun clearSnooze(accountId: String)
}
