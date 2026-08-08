package com.balancesentinel.app.receiver
import com.balancesentinel.app.data.util.Logger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.balancesentinel.app.data.repository.NotificationHelper
import com.balancesentinel.app.data.local.settings.SnoozeStateEntity
import com.balancesentinel.app.data.repository.SettingsRepositoryProvider
import com.balancesentinel.app.data.repository.SettingsSnapshotState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 暂停预警广播接收器。
 *
 * 用户点击通知中的 "暂停预警" 按钮触发。
 * 暂停时长由用户设置决定（默认 60 分钟），
 * AlertChecker 在 check()/checkChange() 中检查该标记。
 */
class SnoozeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val accountId = intent.getStringExtra("account_id") ?: return
        val pending = goAsync()
        // 暂停时长由用户设置决定（默认 60 分钟）
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repository = SettingsRepositoryProvider.get(context)
                val published = (repository.snapshot.value as? SettingsSnapshotState.Ready)?.value
                    ?: repository.readSnapshot()
                val until = System.currentTimeMillis() +
                    published.appSettings.snoozeDurationMinutes * 60_000L
                repository.updateSnapshot { current ->
                    current.copy(
                        snoozes = current.snoozes.filterNot { it.accountId == accountId } +
                            SnoozeStateEntity(accountId, until)
                    )
                }
            } catch (error: Exception) {
                Logger.w("SnoozeReceiver", "Failed to persist snooze: ${error.message}")
            } finally {
                pending?.finish()
            }
        }

        // 取消该账户已有的通知
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val helper = NotificationHelper(context)
            val currency = intent.getStringExtra("currency")
            if (currency != null) {
                nm.cancel(helper.alertNotificationId(accountId, currency))
                nm.cancel(helper.changeNotificationId(accountId, currency))
            }
        } catch (_: Exception) {
            Logger.w("SnoozeReceiver", "Failed to cancel snoozed alert notifications")
        }
    }

}
