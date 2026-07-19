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
import com.balancesentinel.app.data.engine.ServiceHealthTracker
import com.balancesentinel.app.data.repository.RefreshStatsStore
import com.balancesentinel.app.data.util.Logger
import com.balancesentinel.app.util.FormatUtils
import com.balancesentinel.app.CrashLogger
import com.balancesentinel.app.DeepSeekApp
import com.balancesentinel.app.R
import com.balancesentinel.app.data.api.DeepSeekApiService
import com.balancesentinel.app.data.api.ProviderFactory
import com.balancesentinel.app.data.api.ProviderResult
import com.balancesentinel.app.data.api.cache.ProviderCache
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
        CrashLogger.breadcrumb(TAG, "Service onCreate")
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
        CrashLogger.breadcrumb(TAG, "Service onDestroy")
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
            RefreshStatsStore.recordSkipped(this)
            return
        }
        isRefreshing = true
        CrashLogger.breadcrumb(TAG, "Refresh cycle started")

        // Android 16+ 前台服务超时限制：每个刷新周期开始前重新进入前台状态
        try {
            startForeground(DeepSeekApp.NOTIFICATION_ID,
                notificationHelper.buildForegroundNotification("--", getString(R.string.service_notif_connecting)))
        } catch (e: Exception) {
            Logger.e(TAG, "startForeground failed", e)
        }

        // WakeLock 防止 CPU 在刷新期间休眠（动态超时）
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:refresh")
        wl.setReferenceCounted(false)
        val accounts = apiKeyManager.getAccounts()
        val wakeLockTimeout = (accounts.size * 10_000L + 30_000L)  // 动态计算
        try {
            wl.acquire(wakeLockTimeout)
        } catch (_: Exception) {}

        Thread {
            try {
                try {
                    if (accounts.isEmpty()) {
                        notificationHelper.sendForegroundNotification("--", getString(R.string.service_notif_add_key))
                        return@Thread
                    }

                    val cache = ProviderCache.getInstance(this)
                    val now = System.currentTimeMillis()
                    val currencyTotals = mutableMapOf<String, Double>()
                    var allAvailable = true
                    var hasData = false
                    var alertCount = 0
                    var changeCount = 0

                    for (account in accounts) {
                        try {
                            // 检查缓存
                            val cached = cache.get(account.providerType, account.id)
                            if (cached != null && !cached.isEstimated) {
                                // 使用缓存数据
                                for (balance in cached.balances) {
                                    // Widget 缓存（按账户）
                                    BalanceWidgetDataStore.saveAccountBalance(
                                        this, account.id, account.label,
                                        balance.totalBalance.toString(), balance.currency,
                                        cached.isAvailable,
                                        balance.grantedBalance?.toString() ?: "",
                                        balance.toppedUpBalance?.toString() ?: ""
                                    )

                                    // 按币种汇总
                                    currencyTotals[balance.currency] = (currencyTotals[balance.currency] ?: 0.0) + balance.totalBalance

                                    if (!cached.isAvailable) allAvailable = false
                                    hasData = true
                                }
                            } else {
                                // 使用ProviderFactory获取余额
                                val provider = ProviderFactory.get(account.providerType)
                                val config = account.toConfig()
                                val result = kotlinx.coroutines.runBlocking { provider.getBalance(config) }

                                when (result) {
                                    is ProviderResult.Success -> {
                                        val balance = result.data
                                        // 缓存结果
                                        cache.put(account.providerType, account.id, balance)

                                        for (entry in balance.balances) {
                                            // Widget 缓存（按账户）
                                            BalanceWidgetDataStore.saveAccountBalance(
                                                this, account.id, account.label,
                                                entry.totalBalance.toString(), entry.currency,
                                                balance.isAvailable,
                                                entry.grantedBalance?.toString() ?: "",
                                                entry.toppedUpBalance?.toString() ?: ""
                                            )

                                            // RawRecord
                                            try {
                                                RawRecordStore.addRecord(this, RawRecord(
                                                    accountId = account.id,
                                                    timestamp = now,
                                                    currency = entry.currency,
                                                    totalBalance = entry.totalBalance.toFloat(),
                                                    grantedBalance = entry.grantedBalance?.toFloat() ?: 0f,
                                                    toppedUpBalance = entry.toppedUpBalance?.toFloat() ?: 0f
                                                ))
                                            } catch (_: Exception) {}

                                            // AUTO 日志
                                            RefreshLogStore.addEntry(this, RefreshLogEntry(
                                                id = now, type = RefreshLogType.AUTO,
                                                totalBalance = entry.totalBalance.toString(),
                                                currency = entry.currency,
                                                isAvailable = balance.isAvailable,
                                                grantedBalance = entry.grantedBalance?.toString() ?: "",
                                                toppedUpBalance = entry.toppedUpBalance?.toString() ?: "",
                                                timestamp = now, message = account.label
                                            ))

                                            // 按币种汇总
                                            currencyTotals[entry.currency] = (currencyTotals[entry.currency] ?: 0.0) + entry.totalBalance

                                            if (!balance.isAvailable) allAvailable = false
                                            hasData = true

                                            // 每账户预警
                                            if (AlertChecker.check(this, account.id, entry.totalBalance.toString(), entry.currency, account.label))
                                                alertCount++
                                            if (AlertChecker.checkChange(this, account.id, entry.totalBalance.toString(), entry.currency, account.label))
                                                changeCount++
                                        }
                                    }
                                    is ProviderResult.Failure -> {
                                        Logger.e(TAG, "Auto refresh failed for ${account.label}: ${result.error.message}")
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Logger.e(TAG, "Auto refresh failed for ${account.label}", e)
                        }
                    }

                    // 分组摘要通知（多账户时汇总）
                    if (alertCount + changeCount > 1) {
                        notificationHelper.sendGroupSummary(alertCount, changeCount)
                    }

                    // 通知栏显示：总余额可选，额外钱包按用户排序横向附加
                    if (hasData) {
                        // 取总额最大的两个非零币种
                        val sortedTotals = currencyTotals.entries
                            .filter { it.value > 0.0 }
                            .sortedByDescending { it.value }
                            .take(2)
                        val primaryCurrency = sortedTotals.firstOrNull()?.key ?: "CNY"
                        val total = FormatUtils.formatAmount("%.2f".format(sortedTotals.firstOrNull()?.value ?: 0.0))
                        val secondCurrency = sortedTotals.getOrNull(1)?.key ?: ""
                        val total2 = if (secondCurrency.isNotEmpty())
                            FormatUtils.formatAmount("%.2f".format(sortedTotals[1].value)) else ""

                        val status = if (allAvailable) getString(R.string.service_notif_status_available)
                            else getString(R.string.service_notif_status_partial)
                        val showTotal = widgetPrefs.showTotalBalanceInNotification

                        // 获取用户勾选的钱包，按排序列表排列
                        val allBalances = BalanceWidgetDataStore.getAllBalances(this)
                        val walletOrder = widgetPrefs.getNotificationWalletOrder()
                        val selectedBalances = allBalances.filter { balance ->
                            widgetPrefs.isNotificationWalletSelected(balance.accountId, balance.currency)
                        }
                        // 按用户设定的排序排列
                        val orderedWallets = if (walletOrder.isNotEmpty()) {
                            selectedBalances.sortedBy { balance ->
                                val key = "${balance.accountId}_${balance.currency}"
                                val idx = walletOrder.indexOf(key)
                                if (idx >= 0) idx else Int.MAX_VALUE
                            }
                        } else {
                            selectedBalances
                        }

                        val totalPos = if (showTotal)
                            widgetPrefs.getNotificationWalletPosition(WidgetPrefs.KEY_NOTIFICATION_TOTAL, "") else -1

                        notificationHelper.sendBalanceNotification(
                            total, primaryCurrency, status, orderedWallets, showTotal, totalPos,
                            total2, secondCurrency
                        )
                    } else {
                        notificationHelper.sendForegroundNotification("--", getString(R.string.service_notif_no_data))
                    }

                    // Widget 广播
                    sendWidgetUpdateBroadcast()

                    // 标记 & 心跳
                    RefreshScheduler.markFired(this)
                    RefreshScheduler.heartbeat(this)
                    ServiceHealthTracker.recordSuccess(this)
                    RefreshStatsStore.recordSuccess(this)

                } catch (e: Exception) {
                    Logger.e(TAG, "Auto refresh batch failed", e)
                    ServiceHealthTracker.recordFailure(this)
                    RefreshStatsStore.recordFailure(this)
                    CrashLogger.logNonFatal(TAG, e)
                    notificationHelper.sendForegroundNotification(getString(R.string.service_notif_query_failed), e.message ?: e.javaClass.simpleName)
                }

                scheduleNext()
            } finally {
                // 释放 WakeLock + 清除并发标记（确保所有退出路径都释放）
                isRefreshing = false
                try { if (wl.isHeld) wl.release() } catch (_: Exception) {}
                CrashLogger.breadcrumb(TAG, "Refresh cycle completed")

                // Android 16+ 前台服务超时限制：刷新完成后退出前台状态
                // STOP_FOREGROUND_DETACH 保留通知但解除前台绑定，避免超时被杀
                try {
                    stopForeground(STOP_FOREGROUND_DETACH)
                } catch (_: Exception) {}
            }
        }.start()
    }

    private fun scheduleNext() {
        val baseIntervalSec = widgetPrefs.refreshIntervalSeconds
        val baseIntervalMs = if (baseIntervalSec > 0) baseIntervalSec * 1000L else 30_000L

        // 保护模式下降频到每小时一次
        val inProtection = ServiceHealthTracker.isInProtectionMode(this)
        val intervalMs = if (inProtection) 3_600_000L else baseIntervalMs

        if (inProtection) {
            Logger.w(TAG, "Protection mode active — reduced refresh to every 60 min")
        }

        RefreshScheduler.recordSchedule(
            this,
            if (inProtection) 3600 else baseIntervalSec,
            System.currentTimeMillis() + intervalMs,
            if (inProtection) "protection_mode" else "foreground_service"
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


    companion object {
        private const val TAG = "BalanceRefreshSvc"
    }
}
