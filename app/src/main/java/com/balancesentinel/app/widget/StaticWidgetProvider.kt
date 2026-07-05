package com.balancesentinel.app.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import com.balancesentinel.app.data.util.Logger
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.widget.RemoteViews
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import com.balancesentinel.app.DeepSeekApp
import com.balancesentinel.app.MainActivity
import com.balancesentinel.app.R
import com.balancesentinel.app.data.api.DeepSeekApiService
import com.balancesentinel.app.data.model.RefreshLogEntry
import com.balancesentinel.app.data.model.RefreshLogType
import com.balancesentinel.app.data.repository.ApiKeyManager
import com.balancesentinel.app.data.repository.DailySummaryStore
import com.balancesentinel.app.data.repository.RefreshLogStore
import com.balancesentinel.app.data.repository.RefreshScheduler
import com.balancesentinel.app.data.repository.WidgetPrefs
import com.balancesentinel.app.service.BalanceRefreshService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

open class StaticWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (id in appWidgetIds) {
            val options = appWidgetManager.getAppWidgetOptions(id)
            updateWidget(context, appWidgetManager, id, options)
        }
        scheduleRefresh(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, appWidgetManager: AppWidgetManager,
        appWidgetId: Int, newOptions: Bundle
    ) {
        updateWidget(context, appWidgetManager, appWidgetId, newOptions)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        for (id in appWidgetIds) {
            WidgetConfigStore.removeConfig(context, id)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        try {
            if (intent.action == ACTION_REFRESH) {
                if (!processingRefresh.compareAndSet(false, true)) return
                val fromButton = intent.getBooleanExtra(EXTRA_FROM_BUTTON, false)
                try {
                    handleRefresh(context, fromButton)
                } finally {
                    android.os.Handler(android.os.Looper.getMainLooper())
                        .postDelayed({ processingRefresh.set(false) }, 500L)
                }
            } else {
                super.onReceive(context, intent)
            }
        } catch (e: Exception) {
            Logger.e("StaticWidget", "onReceive error", e)
            WidgetErrorLogger.log(context, e)
        }
    }

    private fun handleRefresh(context: Context, fromButton: Boolean = false) {
        val manager = AppWidgetManager.getInstance(context)
        val allClasses = listOf(
            StaticWidgetProvider_2x1::class.java, StaticWidgetProvider_2x2::class.java,
            StaticWidgetProvider_3x1::class.java, StaticWidgetProvider_4x2::class.java,
            StaticWidgetProvider_5x1::class.java
        )
        val allIds = allClasses.flatMap { manager.getAppWidgetIds(ComponentName(context, it)).toList() }
        if (allIds.isEmpty()) return
        val widgetIds = allIds.toIntArray()

        // 显示刷新进度条
        setRefreshProgress(context, manager, allIds, visible = true)

        RefreshScheduler.markFired(context)
        onUpdate(context, manager, widgetIds)

        val svcDead = RefreshScheduler.isServiceDead(context)

        // 用户手动点击刷新按钮 → 无论如何都执行
        // 闹钟自动触发 → 仅当 Service 死亡时才接管刷新，否则 Service 自己会刷新
        if (!fromButton && !svcDead) {
            return  // Service 健在，闹钟仅做备份，不重复刷新
        }

        val pendingResult = goAsync()

        // WakeLock 防止 Widget 刷新期间 CPU 休眠
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wl = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StaticWidget:refresh")
        wl?.setReferenceCounted(false)
        try { wl?.acquire(30_000L) } catch (_: Exception) {}

        Thread {
            try {
                val keyManager = ApiKeyManager(context)
                val accounts = keyManager.getAccounts()
                if (accounts.isNotEmpty()) {
                    val api = DeepSeekApiService()
                    var hasData = false
                    var totalStr = "0"
                    var currencyStr = "CNY"
                    for (account in accounts) {
                        try {
                            val response = api.getBalance(account.apiKey)
                            for (info in response.balanceInfos) {
                                BalanceWidgetDataStore.saveAccountBalance(
                                    context, account.id, account.label,
                                    info.totalBalance, info.currency,
                                    response.isAvailable, info.grantedBalance, info.toppedUpBalance
                                )
                                if (!hasData || info.currency == "CNY") {
                                    totalStr = info.totalBalance
                                    currencyStr = info.currency
                                }
                                hasData = true
                            }
                        } catch (_: Exception) {}
                    }
                    if (hasData) {
                        setRefreshProgress(context, manager, allIds, visible = false)
                        onUpdate(context, manager, widgetIds)
                        val now = System.currentTimeMillis()
                        val logType = if (fromButton) RefreshLogType.MANUAL else RefreshLogType.WATCHDOG
                        val logMsg = if (fromButton) "小组件手动刷新 (${accounts.size} 个账户)"
                            else "看门狗：已刷新 ${accounts.size} 个账户"
                        RefreshLogStore.addEntry(context, RefreshLogEntry(
                            id = now, type = logType, timestamp = now,
                            totalBalance = totalStr, currency = currencyStr, isAvailable = true,
                            message = logMsg
                        ))
                    }
                }
            } catch (e: Exception) {
                Logger.e("StaticWidget", "Manual refresh failed", e)
            } finally {
                pendingResult.finish()
                if (svcDead) {
                    restartServiceNow(context)
                }
                // 释放 WakeLock
                try { if (wl?.isHeld == true) wl.release() } catch (_: Exception) {}
            }
        }.start()
    }

    // ── Widget 渲染（汇总显示） ──

    private fun updateWidget(
        context: Context, manager: AppWidgetManager, widgetId: Int,
        options: Bundle = manager.getAppWidgetOptions(widgetId)
    ) {
        val minW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 260)
        val minH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 100)
        val isExpanded = minH >= 140
        val nightModeFlags = context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
        val isNight = nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val layoutRes = when {
            isNight && isExpanded  -> R.layout.widget_balance_dark
            isNight && !isExpanded -> R.layout.widget_balance_compact_dark
            isExpanded             -> R.layout.widget_balance
            else                   -> R.layout.widget_balance_compact
        }
        val views = RemoteViews(context.packageName, layoutRes)

        // 读取 per-widget 配置
        val config = WidgetConfigStore.getConfig(context, widgetId)
        val agg = if (config != null) {
            // 仅显示选定账户+币种
            val accountBalances = BalanceWidgetDataStore.getAllBalances(context)
            val matching = accountBalances.filter {
                it.accountId == config.accountId && it.currency == config.currency
            }
            if (matching.isNotEmpty()) {
                val acc = matching.first()
                AggregatedBalance(
                    totalBalance = acc.totalBalance,
                    currency = acc.currency,
                    isAvailable = acc.isAvailable,
                    grantedBalance = acc.grantedBalance,
                    toppedUpBalance = acc.toppedUpBalance,
                    accountCount = 1,
                    lastUpdated = acc.lastUpdated
                )
            } else null
        } else {
            // 未配置 → 汇总显示（legacy）
            BalanceWidgetDataStore.getAggregatedBalance(context)
        }

        if (agg != null) {
            val symbol = when (agg.currency.uppercase()) { "CNY" -> "¥"; "USD" -> "$"; "EUR" -> "€"; else -> agg.currency }
            val total = formatAmount(agg.totalBalance)
            val timeText = formatRefreshTime(context, agg.lastUpdated)
            val label = if (agg.accountCount > 1) context.getString(R.string.widget_title_multi, agg.accountCount)
                else context.getString(R.string.widget_default_title)

            if (isExpanded) {
                views.setTextViewText(R.id.widget_title, label)
                views.setTextViewText(R.id.widget_status, if (agg.isAvailable) context.getString(R.string.widget_status_available)
                    else context.getString(R.string.widget_status_partial))
                views.setTextViewText(R.id.widget_balance, "$symbol$total")
                views.setTextViewText(R.id.widget_granted, context.getString(R.string.balance_granted, "$symbol${formatAmount(agg.grantedBalance)}"))
                views.setTextViewText(R.id.widget_topped_up, context.getString(R.string.balance_topped_up, "$symbol${formatAmount(agg.toppedUpBalance)}"))
                views.setTextViewText(R.id.widget_refresh_time, timeText)
                views.setViewVisibility(R.id.widget_detail_row, android.view.View.VISIBLE)
            } else {
                views.setTextViewText(R.id.widget_title, if (agg.accountCount > 1) context.getString(R.string.widget_title_compact_multi, agg.accountCount)
                    else context.getString(R.string.widget_title_compact))
                views.setTextViewText(R.id.widget_balance, "$symbol$total")
                views.setTextViewText(R.id.widget_status, if (agg.isAvailable) context.getString(R.string.widget_status_available)
                    else context.getString(R.string.widget_status_insufficient))
                views.setTextViewText(R.id.widget_refresh_time, timeText)
            }
        } else {
            views.setTextViewText(R.id.widget_balance, context.getString(R.string.widget_query_balance))
            views.setTextViewText(R.id.widget_title, context.getString(R.string.widget_title_compact))
            views.setTextViewText(R.id.widget_status, "--")
            views.setTextViewText(R.id.widget_refresh_time, "")
            if (isExpanded) {
                views.setViewVisibility(R.id.widget_detail_row, android.view.View.GONE)
            }
        }

        // Sparkline 迷你趋势线（仅 expanded layout）
        if (isExpanded && agg != null) {
            try {
                val summaries = DailySummaryStore.getSummariesForCurrency(context, agg.currency)
                if (summaries.size >= 2) {
                    val recent = summaries.takeLast(7)
                    val values = recent.map { it.close }
                    val isNightSpark = isNight
                    val lineColor = if (isNightSpark) 0xFFB8C4FF.toInt() else 0xFF4D6BFE.toInt()
                    val fillColor = if (isNightSpark) 0x40B8C4FF.toInt() else 0x404D6BFE.toInt()
                    val bitmap = SparklineDrawer.draw(values, 300, 48, lineColor, fillColor)
                    if (bitmap != null) {
                        views.setImageViewBitmap(R.id.widget_sparkline, bitmap)
                        views.setViewVisibility(R.id.widget_sparkline, android.view.View.VISIBLE)
                    }
                }
            } catch (_: Exception) {}
        }

        // 点击余额/标题 → deep-link 到 Insights 页面
        val appIntent = Intent(context, MainActivity::class.java).apply {
            putExtra("deep_link_target", "insights")
            putExtra("deep_link_currency", agg?.currency ?: "CNY")
        }
        val appPending = PendingIntent.getActivity(
            context, widgetId, appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_balance, appPending)
        views.setOnClickPendingIntent(R.id.widget_title, appPending)

        // 点击刷新按钮 → 立即触发看门狗刷新（不打开 App）
        // 使用显式 Intent 指定接收者，避免和闹钟的隐式 PendingIntent 冲突去重
        // 携带 EXTRA_FROM_BUTTON 标记以区分真手动点击和自动闹钟
        val refreshIntent = Intent(context, StaticWidgetProvider_2x1::class.java).apply {
            action = ACTION_REFRESH
            putExtra(EXTRA_FROM_BUTTON, true)
        }
        val refreshPending = PendingIntent.getBroadcast(
            context, widgetId + 1000, refreshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_refresh_btn, refreshPending)

        views.setViewVisibility(R.id.widget_refresh_progress, android.view.View.GONE)

        manager.updateAppWidget(widgetId, views)
    }

    private fun restartServiceNow(context: Context) {
        try {
            Logger.w("StaticWidget", "Watchdog restarting Foreground Service")
            RefreshScheduler.recordRestart(context)
            val svcIntent = Intent(context, BalanceRefreshService::class.java)
            val now = System.currentTimeMillis()
            try {
                context.startService(svcIntent)
                RefreshLogStore.addEntry(context, RefreshLogEntry(
                    id = now, type = RefreshLogType.WATCHDOG, timestamp = now,
                    message = "看门狗：服务已死，已强制重启"
                ))
            } catch (e: Exception) {
                RefreshLogStore.addEntry(context, RefreshLogEntry(
                    id = now, type = RefreshLogType.WATCHDOG, timestamp = now,
                    message = "看门狗：重启失败（系统阻止: ${e.message?.take(40)}）"
                ))
            }
        } catch (_: Exception) {}
    }

    private fun scheduleRefresh(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastScheduleTime < 2000L) return
        lastScheduleTime = now

        val prefs = WidgetPrefs(context)
        val intervalSec = prefs.refreshIntervalSeconds
        if (intervalSec <= 0) return

        val alarm = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: run {
            logSchedule(context, intervalSec, 0, "failed", "无法获取 AlarmManager")
            return
        }

        val oldState = RefreshScheduler.getState(context)
        if (oldState.expectedNextAt > 0) RefreshScheduler.markCancelled(context)

        val intent = Intent(ACTION_REFRESH).apply { setPackage(context.packageName) }
        val pending = PendingIntent.getBroadcast(
            context, 100, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarm.cancel(pending)

        val triggerTime = now + intervalSec * 1000L
        var method = "alarm_clock"
        var message = ""

        try {
            val showPending = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarm.setAlarmClock(AlarmManager.AlarmClockInfo(triggerTime, showPending), pending)
            message = "看门狗闹钟已设定"
        } catch (_: SecurityException) {
            try {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC, triggerTime, pending)
                method = "exact"
            } catch (_: SecurityException) {
                try {
                    alarm.set(AlarmManager.RTC, triggerTime, pending)
                    method = "inexact"
                } catch (e: Exception) {
                    method = "failed"
                    message = "✗ 闹钟设置失败"
                }
            }
        } catch (e: Exception) {
            method = "failed"
        }

        RefreshScheduler.recordSchedule(context, intervalSec, triggerTime, method)
        logSchedule(context, intervalSec, triggerTime, method, message)
    }

    private fun logSchedule(context: Context, intervalSec: Int, triggerTime: Long, method: String, message: String) {
        try {
            RefreshLogStore.addEntry(context, RefreshLogEntry(
                id = System.currentTimeMillis(), type = RefreshLogType.SCHEDULE,
                timestamp = System.currentTimeMillis(), message = message,
                intervalSeconds = intervalSec, expectedTime = triggerTime, alarmMethod = method
            ))
        } catch (_: Exception) {}
    }

    private fun setRefreshProgress(context: Context, manager: AppWidgetManager, widgetIds: List<Int>, visible: Boolean) {
        val visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE
        for (id in widgetIds) {
            try {
                val options = manager.getAppWidgetOptions(id)
                val minH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 100)
                val isExpanded = minH >= 140
                val nightModeFlags = context.resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK
                val isNight = nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES
                val layoutRes = when {
                    isNight && isExpanded  -> R.layout.widget_balance_dark
                    isNight && !isExpanded -> R.layout.widget_balance_compact_dark
                    isExpanded             -> R.layout.widget_balance
                    else                   -> R.layout.widget_balance_compact
                }
                val views = RemoteViews(context.packageName, layoutRes)
                views.setViewVisibility(R.id.widget_refresh_progress, visibility)
                manager.partiallyUpdateAppWidget(id, views)
            } catch (_: Exception) {}
        }
    }

    private fun formatAmount(amount: String) = try { "%.2f".format(amount.toDouble()) } catch (_: Exception) { amount }

    private fun formatRefreshTime(context: Context, timestamp: Long): String {
        if (timestamp <= 0) return ""
        val diff = System.currentTimeMillis() - timestamp
        return when {
            diff < 60_000 -> context.getString(R.string.time_just_now)
            diff < 3_600_000 -> context.getString(R.string.time_minutes_ago, (diff / 60_000).toInt())
            diff < 86_400_000 -> context.getString(R.string.time_hours_ago, (diff / 3_600_000).toInt())
            else -> SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.balancesentinel.app.WIDGET_REFRESH"
        const val EXTRA_FROM_BUTTON = "from_button"
        private val processingRefresh = AtomicBoolean(false)
        @Volatile private var lastScheduleTime: Long = 0L
    }
}
