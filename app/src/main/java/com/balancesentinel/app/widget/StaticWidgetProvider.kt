package com.balancesentinel.app.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import com.balancesentinel.app.data.util.Logger
import com.balancesentinel.app.util.FormatUtils
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
import com.balancesentinel.app.data.model.RefreshLogEntry
import com.balancesentinel.app.data.model.RefreshLogType
import com.balancesentinel.app.data.repository.ApiKeyManager
import com.balancesentinel.app.data.repository.DailySummaryStore
import com.balancesentinel.app.data.repository.RefreshLogStore
import com.balancesentinel.app.data.repository.RefreshScheduler
import com.balancesentinel.app.data.repository.SCHEDULE_GRACE_MS
import com.balancesentinel.app.data.repository.WidgetPrefs
import com.balancesentinel.app.service.ForegroundServiceStarter
import com.balancesentinel.app.service.ServiceStarter
import com.balancesentinel.app.data.refresh.RefreshGateway
import com.balancesentinel.app.data.refresh.RefreshRuntime
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

sealed interface WidgetRefreshDecision {
    data object Ignored : WidgetRefreshDecision
    data class Refresh(val watchdog: Boolean) : WidgetRefreshDecision
}

class WidgetRefreshActionHandler {
    fun decide(context: Context, action: String?, now: Long): WidgetRefreshDecision = when (action) {
        StaticWidgetProvider.ACTION_REFRESH_NOW -> WidgetRefreshDecision.Refresh(watchdog = false)
        StaticWidgetProvider.ACTION_WATCHDOG -> {
            if (RefreshScheduler.shouldRestart(context, now)) {
                WidgetRefreshDecision.Refresh(watchdog = true)
            } else {
                WidgetRefreshDecision.Ignored
            }
        }
        else -> WidgetRefreshDecision.Ignored
    }
}

class WidgetRefreshExecution(
    private val gateway: RefreshGateway,
    private val serviceStarter: ServiceStarter
) {
    suspend fun execute(context: Context, decision: WidgetRefreshDecision.Refresh) {
        try {
            WidgetRefreshRunner(gateway).refreshNow(watchdog = decision.watchdog)
        } finally {
            if (decision.watchdog) {
                RefreshScheduler.recordRestart(context)
                serviceStarter.start(context)
            }
        }
    }
}

object WidgetRefreshIntents {
    fun manual(context: Context): Intent =
        manual(context, StaticWidgetProvider_2x1::class.java)

    fun manual(context: Context, receiver: Class<out StaticWidgetProvider>): Intent =
        Intent(context, receiver).apply {
            action = StaticWidgetProvider.ACTION_REFRESH_NOW
        }

    fun watchdog(context: Context): Intent =
        Intent(StaticWidgetProvider.ACTION_WATCHDOG).apply { setPackage(context.packageName) }
}

open class StaticWidgetProvider(
    private val serviceStarter: ServiceStarter = ForegroundServiceStarter()
) : AppWidgetProvider() {

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
            val decision = WidgetRefreshActionHandler().decide(
                context,
                intent.action,
                System.currentTimeMillis()
            )
            if (decision is WidgetRefreshDecision.Refresh) {
                if (processingRefresh.compareAndSet(false, true)) {
                    handleRefresh(context, decision)
                }
            } else if (intent.action != ACTION_REFRESH_NOW && intent.action != ACTION_WATCHDOG) {
                super.onReceive(context, intent)
            }
        } catch (e: Exception) {
            Logger.e("StaticWidget", "onReceive error", e)
            WidgetErrorLogger.log(context, e)
        }
    }

    private fun handleRefresh(context: Context, decision: WidgetRefreshDecision.Refresh) {
        val manager = AppWidgetManager.getInstance(context)
        val allClasses = listOf(
            StaticWidgetProvider_2x1::class.java, StaticWidgetProvider_2x2::class.java,
            StaticWidgetProvider_3x1::class.java, StaticWidgetProvider_4x2::class.java,
            StaticWidgetProvider_5x1::class.java
        )
        val allIds = allClasses.flatMap { manager.getAppWidgetIds(ComponentName(context, it)).toList() }
        if (allIds.isEmpty()) {
            processingRefresh.set(false)
            return
        }
        val widgetIds = allIds.toIntArray()

        // 显示刷新进度条
        setRefreshProgress(context, manager, allIds, visible = true)

        if (decision.watchdog) {
            RefreshScheduler.markFired(context)
        }
        onUpdate(context, manager, widgetIds)

        val pendingResult = goAsync()

        // WakeLock 防止 Widget 刷新期间 CPU 休眠
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wl = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StaticWidget:refresh")
        wl?.setReferenceCounted(false)
        try { wl?.acquire(30_000L) } catch (_: Exception) {}

        Thread {
            WidgetRefreshDispatcher(
                action = {
                    kotlinx.coroutines.runBlocking {
                        WidgetRefreshExecution(
                            RefreshRuntime.from(context),
                            serviceStarter
                        ).execute(context, decision)
                    }
                    setRefreshProgress(context, manager, allIds, visible = false)
                    onUpdate(context, manager, widgetIds)
                },
                finish = {
                    pendingResult.finish()
                    processingRefresh.set(false)
                    // 释放 WakeLock
                    try { if (wl?.isHeld == true) wl.release() } catch (_: Exception) {}
                }
            ).dispatch()
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
        val agg = if (config != null && config.accountId == WidgetConfig.TOTAL_ACCOUNT_ID) {
            // 总余额模式：仅聚合当前有效账户
            val keyManager = ApiKeyManager(context)
            val validAccountIds = keyManager.getAccounts().map { it.id }.toSet()
            val validBalances = BalanceWidgetDataStore.getAllBalances(context)
                .filter { it.accountId in validAccountIds }
            if (validBalances.isEmpty()) null else aggregateBalances(validBalances)
        } else if (config != null) {
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
            // 未配置 → 汇总显示（legacy），同样仅聚合有效账户
            val keyManager = ApiKeyManager(context)
            val validAccountIds = keyManager.getAccounts().map { it.id }.toSet()
            val validBalances = BalanceWidgetDataStore.getAllBalances(context)
                .filter { it.accountId in validAccountIds }
            if (validBalances.isEmpty()) null else aggregateBalances(validBalances)
        }

        if (agg != null) {
            val balanceText = formatBalanceDisplay(agg)
            val timeText = formatRefreshTime(context, agg.lastUpdated)

            // 标题显示钱包来源
            val label = when {
                config != null && config.accountId == WidgetConfig.TOTAL_ACCOUNT_ID ->
                    context.getString(R.string.widget_title_total)
                config != null -> {
                    val accountBalances = BalanceWidgetDataStore.getAllBalances(context)
                    val accLabel = accountBalances.find { it.accountId == config.accountId }?.label ?: ""
                    accLabel.ifEmpty { context.getString(R.string.widget_default_title) }
                }
                agg.accountCount > 1 -> context.getString(R.string.widget_title_multi, agg.accountCount)
                else -> context.getString(R.string.widget_default_title)
            }

            if (isExpanded) {
                views.setTextViewText(R.id.widget_title, label)
                views.setTextViewText(R.id.widget_status, if (agg.isAvailable) context.getString(R.string.widget_status_available)
                    else context.getString(R.string.widget_status_partial))
                views.setTextViewText(R.id.widget_balance, balanceText)
                val symbol = currencySymbol(agg.currency)
                views.setTextViewText(R.id.widget_granted, context.getString(R.string.balance_granted, "$symbol${FormatUtils.formatAmount(agg.grantedBalance)}"))
                views.setTextViewText(R.id.widget_topped_up, context.getString(R.string.balance_topped_up, "$symbol${FormatUtils.formatAmount(agg.toppedUpBalance)}"))
                views.setTextViewText(R.id.widget_refresh_time, timeText)
                views.setViewVisibility(R.id.widget_detail_row, android.view.View.VISIBLE)
            } else {
                // 紧凑模式标题同样显示钱包来源
                val compactLabel = when {
                    config != null && config.accountId == WidgetConfig.TOTAL_ACCOUNT_ID ->
                        context.getString(R.string.widget_title_total)
                    config != null -> {
                        val accountBalances = BalanceWidgetDataStore.getAllBalances(context)
                        val accLabel = accountBalances.find { it.accountId == config.accountId }?.label ?: ""
                        accLabel.ifEmpty { context.getString(R.string.widget_title_compact) }
                    }
                    agg.accountCount > 1 -> context.getString(R.string.widget_title_compact_multi, agg.accountCount)
                    else -> context.getString(R.string.widget_title_compact)
                }
                views.setTextViewText(R.id.widget_title, compactLabel)
                views.setTextViewText(R.id.widget_balance, balanceText)
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

        val refreshIntent = WidgetRefreshIntents.manual(context, StaticWidgetProvider_2x1::class.java)
        val refreshPending = PendingIntent.getBroadcast(
            context, widgetId + 1000, refreshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_refresh_btn, refreshPending)

        views.setViewVisibility(R.id.widget_refresh_progress, android.view.View.GONE)

        manager.updateAppWidget(widgetId, views)
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

        val intent = WidgetRefreshIntents.watchdog(context)
        val pending = PendingIntent.getBroadcast(
            context, 100, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarm.cancel(pending)

        val expectedRefreshTime = now + intervalSec * 1000L
        val triggerTime = expectedRefreshTime + SCHEDULE_GRACE_MS + 1L
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
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pending)
                method = "exact"
            } catch (_: SecurityException) {
                try {
                    alarm.set(AlarmManager.RTC_WAKEUP, triggerTime, pending)
                    method = "inexact"
                } catch (e: Exception) {
                    method = "failed"
                    message = "✗ 闹钟设置失败"
                }
            }
        } catch (e: Exception) {
            method = "failed"
        }

        RefreshScheduler.recordSchedule(context, intervalSec, expectedRefreshTime, method)
        logSchedule(context, intervalSec, expectedRefreshTime, method, message)
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

    /**
     * 从有效余额列表中聚合总余额（最多两个非零币种）。
     */
    private fun aggregateBalances(balances: List<AccountBalance>): AggregatedBalance? {
        return BalanceWidgetDataStore.aggregateTopTwo(balances)
    }

    /** 格式化余额展示文本，双币种用 · 分隔 */
    private fun formatBalanceDisplay(agg: AggregatedBalance): String {
        val sb = StringBuilder()
        sb.append("${currencySymbol(agg.currency)}${FormatUtils.formatAmount(agg.totalBalance)}")
        val hasSecond = agg.totalBalance2.isNotEmpty() && (agg.totalBalance2.toDoubleOrNull() ?: 0.0) > 0
        if (hasSecond) {
            sb.append(" · ${currencySymbol(agg.currency2)}${FormatUtils.formatAmount(agg.totalBalance2)}")
        }
        return sb.toString()
    }

    private fun currencySymbol(currency: String): String =
        when (currency.uppercase()) { "CNY" -> "¥"; "USD" -> "$"; "EUR" -> "€"; else -> currency }

    companion object {
        const val ACTION_REFRESH_NOW = "com.balancesentinel.app.WIDGET_REFRESH_NOW"
        const val ACTION_WATCHDOG = "com.balancesentinel.app.WIDGET_WATCHDOG"
        private val processingRefresh = AtomicBoolean(false)
        @Volatile private var lastScheduleTime: Long = 0L
    }
}
