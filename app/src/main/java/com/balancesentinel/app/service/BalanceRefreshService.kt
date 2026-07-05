package com.balancesentinel.app.service

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import com.balancesentinel.app.data.util.Logger
import com.balancesentinel.app.DeepSeekApp
import com.balancesentinel.app.R
import com.balancesentinel.app.data.api.DeepSeekApiService
import com.balancesentinel.app.data.model.RefreshLogEntry
import com.balancesentinel.app.data.model.RefreshLogType
import com.balancesentinel.app.data.model.RawRecord
import com.balancesentinel.app.data.model.UsageSnapshot
import com.balancesentinel.app.data.repository.UsageDataStore
import com.balancesentinel.app.data.repository.AlertChecker
import com.balancesentinel.app.data.repository.ApiKeyManager
import com.balancesentinel.app.data.repository.NotificationHelper
import com.balancesentinel.app.data.repository.RawRecordStore
import com.balancesentinel.app.data.repository.RefreshLogStore
import com.balancesentinel.app.data.repository.RefreshScheduler
import com.balancesentinel.app.data.repository.WidgetPrefs
import com.balancesentinel.app.receiver.KeepAliveReceiver
import com.balancesentinel.app.widget.BalanceWidgetDataStore
import com.balancesentinel.app.widget.StaticWidgetProvider_2x1
import com.balancesentinel.app.widget.StaticWidgetProvider_2x2
import com.balancesentinel.app.widget.StaticWidgetProvider_3x1
import com.balancesentinel.app.widget.StaticWidgetProvider_4x2
import com.balancesentinel.app.widget.StaticWidgetProvider_5x1

class BalanceRefreshService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val apiService = DeepSeekApiService()
    private lateinit var apiKeyManager: ApiKeyManager
    private lateinit var widgetPrefs: WidgetPrefs
    private var isLoopRunning = false
    @Volatile private var isRefreshing = false  // 防并发刷新风暴
    private lateinit var notificationHelper: NotificationHelper

    // 指数退避自毁：3h → 6h → 12h，基于重启次数
    private val restartRunnable = object : Runnable {
        override fun run() {
            Logger.i(TAG, "Scheduled self-destruct — stopping service")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private val refreshTask = object : Runnable {
        override fun run() {
            doRefresh()
        }
    }

    override fun onCreate() {
        super.onCreate()
        apiKeyManager = ApiKeyManager(this)
        widgetPrefs = WidgetPrefs(this)
        notificationHelper = NotificationHelper(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(DeepSeekApp.NOTIFICATION_ID,
                notificationHelper.buildForegroundNotification("--", getString(R.string.service_notif_connecting)))
        } catch (e: Exception) {
            Logger.e(TAG, "startForeground failed", e)
            stopSelf()
            return START_NOT_STICKY
        }
        RefreshScheduler.heartbeat(this)

        if (!isLoopRunning) {
            try {
                val now = System.currentTimeMillis()
                RefreshLogStore.addEntry(this, RefreshLogEntry(
                    id = now, type = RefreshLogType.SERVICE_START, timestamp = now,
                    message = "前台刷新服务已启动", alarmMethod = "foreground_service"
                ))
            } catch (_: Exception) {}
            startLoop()
        } else {
            // 服务已在运行 — 用最新间隔重新调度（用户可能改了设置）
            handler.removeCallbacks(refreshTask)
            handler.post(refreshTask)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopLoop()
        KeepAliveReceiver.cancel(this)
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        super.onDestroy()
    }

    // 用户从最近任务滑动清除 → 1 秒后通过 AlarmManager 重启
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        try {
            val alarm = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val restartIntent = Intent(this, BalanceRefreshService::class.java)
            val pending = PendingIntent.getForegroundService(
                this, 0, restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            alarm.setExactAndAllowWhileIdle(
                AlarmManager.RTC,
                System.currentTimeMillis() + 1000L, // 1 秒后重启
                pending
            )
            Logger.i(TAG, "onTaskRemoved — scheduled restart via AlarmManager in 1s")
        } catch (e: Exception) {
            Logger.e(TAG, "onTaskRemoved — failed to schedule restart", e)
        }
    }

    private fun startLoop() {
        isLoopRunning = true
        Logger.i(TAG, "Refresh loop started")
        handler.post(refreshTask)

        // 指数退避自毁：3h → 6h → 12h（基于重启次数）
        val restartCount = RefreshScheduler.getRestartCount(this)
        val selfDestructMs = when {
            restartCount <= 1 -> 3 * 3_600_000L   // 3 小时
            restartCount == 2 -> 6 * 3_600_000L   // 6 小时
            else              -> 12 * 3_600_000L  // 12 小时（上限）
        }
        Logger.i(TAG, "Self-destruct scheduled in ${selfDestructMs / 3_600_000}h (restart #$restartCount)")
        handler.postDelayed(restartRunnable, selfDestructMs)
    }

    private fun stopLoop() {
        isLoopRunning = false
        handler.removeCallbacks(refreshTask)
        handler.removeCallbacks(restartRunnable)
        Logger.i(TAG, "Refresh loop stopped")
    }

    // ── 核心刷新（多账户） ──

    private fun doRefresh() {
        if (!isLoopRunning) return

        // 防并发刷新风暴：上一轮刷新未结束时忽略新请求
        if (isRefreshing) {
            Logger.w(TAG, "Skipping refresh — previous round still in progress")
            return
        }
        isRefreshing = true

        // WakeLock 防止 CPU 在刷新期间休眠
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:refresh")
        wl.setReferenceCounted(false)
        try {
            wl.acquire(30_000L) // 30 秒超时兜底
        } catch (_: Exception) {}

        Thread {
            try {
                try {
                    val accounts = apiKeyManager.getAccounts()
                    if (accounts.isEmpty()) {
                        notificationHelper.sendForegroundNotification("--", getString(R.string.service_notif_add_key))
                        return@Thread
                    }

                    val now = System.currentTimeMillis()
                    var totalAggregated = 0.0
                    var primaryCurrency = "CNY"
                    var allAvailable = true
                    var hasData = false
                    var alertCount = 0
                    var changeCount = 0

                    for (account in accounts) {
                        try {
                            val response = apiService.getBalance(account.apiKey)
                            for (info in response.balanceInfos) {
                                // Widget 缓存（按账户）
                                BalanceWidgetDataStore.saveAccountBalance(
                                    this, account.id, account.label,
                                    info.totalBalance, info.currency,
                                    response.isAvailable, info.grantedBalance, info.toppedUpBalance
                                )

                                // RawRecord
                                try {
                                    RawRecordStore.addRecord(this, RawRecord(
                                        accountId = account.id,
                                        timestamp = now,
                                        currency = info.currency,
                                        totalBalance = info.totalBalance.toFloatOrNull() ?: 0f,
                                        grantedBalance = info.grantedBalance.toFloatOrNull() ?: 0f,
                                        toppedUpBalance = info.toppedUpBalance.toFloatOrNull() ?: 0f
                                    ))
                                } catch (_: Exception) {}

                                // AUTO 日志
                                RefreshLogStore.addEntry(this, RefreshLogEntry(
                                    id = now, type = RefreshLogType.AUTO,
                                    totalBalance = info.totalBalance, currency = info.currency,
                                    isAvailable = response.isAvailable,
                                    grantedBalance = info.grantedBalance,
                                    toppedUpBalance = info.toppedUpBalance,
                                    timestamp = now, message = account.label
                                ))

                                // 汇总统计（首选 CNY 币种）
                                if (!hasData || info.currency == "CNY") {
                                    if (info.currency == "CNY" && !hasData) {
                                        primaryCurrency = "CNY"
                                        totalAggregated = 0.0
                                    }
                                    if (info.currency == primaryCurrency) {
                                        totalAggregated += info.totalBalance.toDoubleOrNull() ?: 0.0
                                    }
                                }
                                if (!response.isAvailable) allAvailable = false
                                hasData = true

                                // 每账户预警（捕获返回值用于分组摘要）
                                if (AlertChecker.check(this, account.id, info.totalBalance, info.currency, account.label))
                                    alertCount++
                                if (AlertChecker.checkChange(this, account.id, info.totalBalance, info.currency, account.label))
                                    changeCount++
                            }
                        } catch (e: Exception) {
                            Logger.e(TAG, "Auto refresh failed for ${account.label}", e)
                        }
                    }

                    // 分组摘要通知（多账户时汇总）
                    if (alertCount + changeCount > 1) {
                        notificationHelper.sendGroupSummary(alertCount, changeCount)
                    }

                    // 拉取用量统计（异步，失败不影响余额刷新）
                    for (account in accounts) {
                        try {
                            val usage = apiService.getUsage(account.apiKey)
                            UsageDataStore.saveSnapshot(this, UsageSnapshot(
                                accountId = account.id,
                                timestamp = now,
                                records = usage.data
                            ))
                        } catch (_: Exception) {}
                    }

                    // 通知栏显示汇总
                    if (hasData) {
                        val symbol = currencySymbol(primaryCurrency)
                        val total = formatAmount("%.2f".format(totalAggregated))
                        val status = if (allAvailable) getString(R.string.service_notif_status_available)
                            else getString(R.string.service_notif_status_partial)
                        notificationHelper.sendForegroundNotification("$symbol$total", status)
                    } else {
                        notificationHelper.sendForegroundNotification("--", getString(R.string.service_notif_no_data))
                    }

                    // Widget 广播
                    sendWidgetUpdateBroadcast()

                    // 标记 & 心跳
                    RefreshScheduler.markFired(this)
                    RefreshScheduler.heartbeat(this)

                } catch (e: Exception) {
                    Logger.e(TAG, "Auto refresh batch failed", e)
                    notificationHelper.sendForegroundNotification(getString(R.string.service_notif_query_failed), e.message ?: e.javaClass.simpleName)
                }

                scheduleNext()
            } finally {
                // 释放 WakeLock + 清除并发标记（确保所有退出路径都释放）
                isRefreshing = false
                try { if (wl.isHeld) wl.release() } catch (_: Exception) {}
            }
        }.start()
    }

    private fun scheduleNext() {
        val intervalSec = widgetPrefs.refreshIntervalSeconds
        val intervalMs = if (intervalSec > 0) intervalSec * 1000L else 30_000L

        RefreshScheduler.recordSchedule(
            this, intervalSec, System.currentTimeMillis() + intervalMs, "foreground_service"
        )
        KeepAliveReceiver.schedule(this)
        handler.removeCallbacks(refreshTask)
        handler.postDelayed(refreshTask, intervalMs)
    }

    private fun sendWidgetUpdateBroadcast() {
        try {
            val providerClasses = listOf(
                StaticWidgetProvider_2x1::class.java, StaticWidgetProvider_2x2::class.java,
                StaticWidgetProvider_3x1::class.java, StaticWidgetProvider_4x2::class.java,
                StaticWidgetProvider_5x1::class.java
            )
            val manager = AppWidgetManager.getInstance(this)
            for (clazz in providerClasses) {
                try {
                    val component = ComponentName(this, clazz)
                    val ids = manager.getAppWidgetIds(component)
                    if (ids.isNotEmpty()) {
                        val intent = Intent(this, clazz).apply {
                            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                        }
                        sendBroadcast(intent)
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            DeepSeekApp.CHANNEL_ID, getString(R.string.channel_service_name), NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_service_desc)
            setShowBadge(false)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    // buildNotification / updateNotification replaced by NotificationHelper

    private fun currencySymbol(currency: String) = when (currency.uppercase()) {
        "CNY" -> "¥"; "USD" -> "$"; "EUR" -> "€"; else -> currency
    }

    private fun formatAmount(amount: String): String {
        return try { "%.2f".format(amount.toDouble()) } catch (_: Exception) { amount }
    }

    companion object {
        private const val TAG = "BalanceRefreshSvc"
    }
}
