package com.balancesentinel.app.receiver
import com.balancesentinel.app.data.util.Logger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.balancesentinel.app.data.repository.WidgetPrefs

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
        val prefs = WidgetPrefs(context)
        // 暂停时长由用户设置决定（默认 60 分钟）
        val durationMs = prefs.snoozeDurationMinutes * 60_000L
        prefs.setSnoozeUntil(accountId, System.currentTimeMillis() + durationMs)

        // 取消该账户已有的通知
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.cancel(1002 + (accountId.hashCode() and 0xFFFF))  // alert
            nm.cancel(2002 + (accountId.hashCode() and 0xFFFF))  // change
        } catch (e: Exception) { Logger.w("SnoozeReceiver", "snooze failed", e) }
    }

}
