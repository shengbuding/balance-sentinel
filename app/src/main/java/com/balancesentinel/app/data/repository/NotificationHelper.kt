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
import com.balancesentinel.app.util.FormatUtils
import com.balancesentinel.app.widget.AccountBalance

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
        val symbol = FormatUtils.currencySymbol(currency)
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
        val symbol = FormatUtils.currencySymbol(currency)
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

    /**
     * 构建余额通知（v2.5）。
     * 所有余额条目（含总余额）按用户排序平级处理。
     * 排序第一位的金额显示为标题，其余横向排列在正文行。
     * 超出长度限制的进 BigTextStyle 展开视图。
     *
     * @param totalBalance 总余额格式化字符串（如 "1,234.56"）
     * @param totalCurrency 总余额币种代码
     * @param status 可用状态文字（无余额条目时作为正文）
     * @param extraWallets 按用户排序后的选中钱包列表（可能含 total 在任意位置）
     * @param showTotal 是否在列表中包含总余额条目
     * @param totalPosition 总余额在排序列表中的位置（-1 表示不含）
     */
    fun buildBalanceNotification(
        totalBalance: String,
        totalCurrency: String,
        status: String,
        extraWallets: List<AccountBalance>,
        showTotal: Boolean = true,
        totalPosition: Int = 0,
        totalBalance2: String = "",
        totalCurrency2: String = ""
    ): android.app.Notification {
        val symbol = FormatUtils.currencySymbol(totalCurrency)

        // 构建总余额条目文本（可能包含两个币种）
        val totalEntryText = buildString {
            append(context.getString(R.string.notification_total_entry, symbol, totalBalance))
            if (totalBalance2.isNotEmpty() && (totalBalance2.toDoubleOrNull() ?: 0.0) > 0) {
                val symbol2 = FormatUtils.currencySymbol(totalCurrency2)
                append(" · $symbol2$totalBalance2")
            }
        }

        // 构建余额条目列表：总余额按排序位置插入
        val entries = mutableListOf<String>()
        var walletIdx = 0
        var totalInserted = false
        for (pos in 0 until (extraWallets.size + (if (showTotal) 1 else 0))) {
            if (showTotal && !totalInserted && pos == totalPosition.coerceIn(0, extraWallets.size)) {
                entries.add(totalEntryText)
                totalInserted = true
            } else {
                if (walletIdx < extraWallets.size) {
                    val w = extraWallets[walletIdx]
                    val ws = FormatUtils.currencySymbol(w.currency)
                    entries.add("${w.label} $ws${FormatUtils.formatAmount(w.totalBalance)}")
                    walletIdx++
                }
            }
        }

        val builder = NotificationCompat.Builder(context, DeepSeekApp.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setContentIntent(createOpenAppIntent())

        if (entries.isEmpty()) {
            builder.setContentTitle(context.getString(R.string.app_name))
            builder.setContentText(status)
        } else {
            // 第一个条目作为标题（金额最显眼）
            builder.setContentTitle(entries.first())

            if (entries.size == 1) {
                builder.setContentText(status)
            } else {
                val rest = entries.drop(1)

                // 估算通知栏正文区可容纳字符数
                val dm = context.resources.displayMetrics
                val screenWidthDp = (dm.widthPixels / dm.density).toInt()
                val maxChars = ((screenWidthDp * 0.65) / 7).toInt().coerceIn(25, 55)

                var truncated = false
                val body = buildString {
                    var remaining = maxChars
                    for (i in rest.indices) {
                        val segment = if (i == 0) rest[i] else " · ${rest[i]}"
                        if (segment.length <= remaining) {
                            append(segment)
                            remaining -= segment.length
                        } else {
                            val overflow = rest.size - i
                            if (overflow > 0) {
                                append("  +$overflow")
                                truncated = true
                            }
                            break
                        }
                    }
                }

                builder.setContentText(body)

                // 溢出时启用 BigTextStyle 展开视图（显示全部条目）
                if (truncated) {
                    val expandedText = entries.joinToString("\n")
                    builder.setStyle(NotificationCompat.BigTextStyle().bigText(expandedText))
                }
            }
        }

        return builder.build()
    }

    /** 更新前台 Service 常驻通知（v2.7 双币种签名）。 */
    fun sendBalanceNotification(
        totalBalance: String,
        totalCurrency: String,
        status: String,
        extraWallets: List<AccountBalance>,
        showTotal: Boolean = true,
        totalPosition: Int = 0,
        totalBalance2: String = "",
        totalCurrency2: String = ""
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(
            DeepSeekApp.NOTIFICATION_ID,
            buildBalanceNotification(totalBalance, totalCurrency, status, extraWallets, showTotal, totalPosition,
                totalBalance2, totalCurrency2)
        )
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

}
