package com.balancesentinel.app.data.repository

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.balancesentinel.app.DeepSeekApp
import com.balancesentinel.app.MainActivity
import com.balancesentinel.app.R
import com.balancesentinel.app.receiver.SnoozeReceiver

/**
 * 统一通知工厂。
 *
 * 职责：
 * - 构建 alert / change / foreground / group-summary 通知
 * - 生成 PendingIntent（打开 App、Deep-link、Snooze）
 * - 按 accountId 计算隔离的通知 ID
 * - 批量跟踪——记录本轮发送的通知，用于分组摘要
 */
class NotificationHelper(private val context: Context) {

    // ── 通知 ID 计算 ──

    /** 每账户 alert 通知 ID：1002 + hash，范围 1002-66537 */
    fun alertNotificationId(accountId: String): Int =
        1002 + (accountId.hashCode() and 0xFFFF)

    /** 每账户 change 通知 ID：2002 + hash，范围 2002-67537 */
    fun changeNotificationId(accountId: String): Int =
        2002 + (accountId.hashCode() and 0xFFFF)

    // ── PendingIntent 工厂 ──

    fun createOpenAppIntent(): PendingIntent =
        PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun createDeepLinkIntent(accountId: String, currency: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("deep_link_target", "insights")
            putExtra("deep_link_account_id", accountId)
            putExtra("deep_link_currency", currency)
        }
        return PendingIntent.getActivity(
            context, accountId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun createSnoozeIntent(accountId: String): PendingIntent {
        val intent = Intent(context, SnoozeReceiver::class.java).apply {
            putExtra("account_id", accountId)
        }
        return PendingIntent.getBroadcast(
            context, accountId.hashCode() + 1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // ── 高级通知 API ──

    /** 低余额预警通知，含 "查看详情" + "暂停预警" 操作按钮 */
    fun sendLowBalanceAlert(accountId: String, balance: Float, threshold: Float, currency: String, label: String) {
        val symbol = currencySymbol(currency)
        val accountLabel = if (label.isNotEmpty()) "[$label] " else ""
        val title = context.getString(R.string.alert_low_title)
        val content = context.getString(
            R.string.alert_low_content, accountLabel, symbol, balance, threshold
        )

        val notification = NotificationCompat.Builder(context, DeepSeekApp.CHANNEL_ID_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(createOpenAppIntent())
            .setGroup("balance_alerts")
            .addAction(
                android.R.drawable.ic_menu_view,
                context.getString(R.string.alert_action_view),
                createDeepLinkIntent(accountId, currency)
            )
            .addAction(
                android.R.drawable.ic_media_pause,
                context.getString(R.string.alert_action_snooze),
                createSnoozeIntent(accountId)
            )
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(alertNotificationId(accountId), notification)
    }

    /** 余额异动通知，含 "查看详情" + "暂停预警" 操作按钮 */
    fun sendChangeAlert(
        accountId: String, current: Float, previous: Float, diff: Float,
        periodMin: Int, currency: String, label: String
    ) {
        val symbol = currencySymbol(currency)
        val direction = if (current < previous)
            context.getString(R.string.alert_change_decreased)
        else
            context.getString(R.string.alert_change_increased)
        val accountLabel = if (label.isNotEmpty()) "[$label] " else ""
        val title = context.getString(R.string.alert_change_title)
        val content = context.getString(
            R.string.alert_change_content,
            accountLabel, direction, symbol, diff,
            symbol, previous, current, periodMin
        )

        val notification = NotificationCompat.Builder(context, DeepSeekApp.CHANNEL_ID_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(createOpenAppIntent())
            .setGroup("balance_alerts")
            .addAction(
                android.R.drawable.ic_menu_view,
                context.getString(R.string.alert_action_view),
                createDeepLinkIntent(accountId, currency)
            )
            .addAction(
                android.R.drawable.ic_media_pause,
                context.getString(R.string.alert_action_snooze),
                createSnoozeIntent(accountId)
            )
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(changeNotificationId(accountId), notification)
    }

    /** 构建前台 Service 通知（返回 Notification 对象，用于 startForeground） */
    fun buildForegroundNotification(title: String, content: String) =
        NotificationCompat.Builder(context, DeepSeekApp.CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setContentIntent(createOpenAppIntent())
            .build()

    /** 更新前台 Service 常驻通知 */
    fun sendForegroundNotification(title: String, content: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(DeepSeekApp.NOTIFICATION_ID, buildForegroundNotification(title, content))
    }

    /** 分组摘要通知：在多账户触发预警后汇总显示 */
    fun sendGroupSummary(alertCount: Int, changeCount: Int) {
        if (alertCount + changeCount == 0) return

        val title = context.getString(R.string.alert_group_title)
        val content = context.getString(R.string.alert_group_content, alertCount, changeCount)

        val summary = NotificationCompat.Builder(context, DeepSeekApp.CHANNEL_ID_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setGroup("balance_alerts")
            .setGroupSummary(true)
            .setContentIntent(createOpenAppIntent())
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(DeepSeekApp.NOTIFICATION_ID_GROUP_SUMMARY, summary)
    }

    // ── 工具 ──

    private fun currencySymbol(currency: String) = when (currency.uppercase()) {
        "CNY" -> "¥"; "USD" -> "$"; "EUR" -> "€"; else -> currency
    }
}
