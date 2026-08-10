package com.balancesentinel.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.balancesentinel.app.data.local.settings.SnoozeStateEntity
import com.balancesentinel.app.data.repository.NotificationHelper
import com.balancesentinel.app.data.repository.SettingsRepositoryProvider
import com.balancesentinel.app.data.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Persists snooze state in the Room-backed SettingsRepository consumed by AlertChecker. */
class SnoozeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val accountId = intent.getStringExtra("account_id") ?: return
        val currency = intent.getStringExtra("deep_link_currency")
            ?: intent.getStringExtra("currency") ?: ""
        runCatching {
            val helper = NotificationHelper(context)
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            manager.cancel(helper.alertNotificationId(accountId, currency))
            manager.cancel(helper.changeNotificationId(accountId, currency))
        }.onFailure { Logger.w("SnoozeReceiver", "notification cancellation failed", it) }

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repository = SettingsRepositoryProvider.get(context)
                val snapshot = repository.readSnapshot()
                val durationMs = snapshot.appSettings.snoozeDurationMinutes * 60_000L
                val until = System.currentTimeMillis() + durationMs
                repository.updateSnapshot { current ->
                    current.copy(
                        snoozes = current.snoozes.filterNot { it.accountId == accountId } +
                            SnoozeStateEntity(accountId, until)
                    )
                }
            } catch (e: Exception) {
                Logger.w("SnoozeReceiver", "snooze persistence failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}
