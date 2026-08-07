package com.balancesentinel.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
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
import com.balancesentinel.app.CrashLogger
import com.balancesentinel.app.DeepSeekApp
import com.balancesentinel.app.R
import com.balancesentinel.app.data.model.RefreshLogEntry
import com.balancesentinel.app.data.model.RefreshLogType
import com.balancesentinel.app.data.repository.NotificationHelper
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
import com.balancesentinel.app.data.refresh.RefreshGateway
import com.balancesentinel.app.data.refresh.RefreshRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class BalanceRefreshService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var refreshGateway: RefreshGateway
    private lateinit var widgetPrefs: WidgetPrefs
    private var isLoopRunning = false
    @Volatile private var isRefreshing = false  // 防并发刷新风暴
    private var isSelfDestructing = false
    private lateinit var notificationHelper: NotificationHelper
    internal var serviceStarter: ServiceStarter = ForegroundServiceStarter()

    // 指数退避自毁：3h → 6h → 12h，基于重启次数
    private val restartRunnable = object : Runnable {
        override fun run() {
            Logger.i(TAG, "Scheduled self-destruct — stopping service")
            isSelfDestructing = true
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
        widgetPrefs = WidgetPrefs(this)
        notificationHelper = NotificationHelper(this)
        refreshGateway = RefreshRuntime.from(this)
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
        refreshScope.cancel()
        if (!isSelfDestructing) {
            KeepAliveReceiver.cancel(this)
        }
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        super.onDestroy()
    }

    // A task-removal restart uses the same compliant foreground-service boundary.
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        when (serviceStarter.start(this)) {
            ServiceStartResult.Started -> Logger.i(TAG, "task_removal_service_started")
            is ServiceStartResult.Deferred -> Logger.w(TAG, "task_removal_service_start_deferred")
            is ServiceStartResult.Failed -> Logger.w(TAG, "task_removal_service_start_failed")
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
        refreshScope.launch {
            try {
                val accountReader = ServiceAccountSnapshotReader {
                    refreshGateway.readAccountSnapshot()
                }
                val snapshot = accountReader.read()
                val accountCount = (snapshot as? com.balancesentinel.app.data.refresh.AccountStoreRead.Ready)
                    ?.accounts?.size ?: 0
                val wakeLockTimeout = (accountCount * 10_000L + 30_000L).coerceAtLeast(30_000L)
                try { wl.acquire(wakeLockTimeout) } catch (_: Exception) {}
                val deadlineLifecycle = object : RefreshDeadlineLifecycle {
                    override fun markStarted() {
                        RefreshScheduler.markRefreshStarted(
                            this@BalanceRefreshService,
                            accountCount
                        )
                    }

                    override fun clear() {
                        RefreshScheduler.clearRefreshDeadline(this@BalanceRefreshService)
                    }
                }
                val runner = BalanceRefreshRunner(
                    gateway = refreshGateway,
                    refreshDeadline = deadlineLifecycle,
                    accountSnapshotReader = ServiceAccountSnapshotReader { snapshot },
                    committedBalanceReader = {
                        BalanceWidgetDataStore.getAllBalances(this@BalanceRefreshService)
                    }
                )
                val committedBalances = runner.refreshBatch().committedBalances
                val showTotal = widgetPrefs.showTotalBalanceInNotification
                val notification = BalanceNotificationDeriver.derive(
                    committedBalances = committedBalances,
                    walletOrder = widgetPrefs.getNotificationWalletOrder(),
                    showTotal = showTotal
                )
                if (notification == null) {
                    notificationHelper.sendForegroundNotification("--", getString(R.string.service_notif_no_data))
                } else {
                    val status = if (notification.isAvailable) getString(R.string.service_notif_status_available)
                        else getString(R.string.service_notif_status_partial)
                    notificationHelper.sendBalanceNotification(
                        notification.totalBalance,
                        notification.totalCurrency,
                        status,
                        notification.wallets,
                        notification.showTotal,
                        notification.totalPosition,
                        notification.totalBalance2,
                        notification.totalCurrency2
                    )
                }
                sendWidgetUpdateBroadcast()
                RefreshScheduler.markFired(this@BalanceRefreshService)
                RefreshScheduler.heartbeat(this@BalanceRefreshService)
                ServiceHealthTracker.recordSuccess(this@BalanceRefreshService)
                RefreshStatsStore.recordSuccess(this@BalanceRefreshService)
            } catch (e: Exception) {
                Logger.e(TAG, "Auto refresh batch failed", e)
                ServiceHealthTracker.recordFailure(this@BalanceRefreshService)
                RefreshStatsStore.recordFailure(this@BalanceRefreshService)
                CrashLogger.logNonFatal(TAG, e)
                notificationHelper.sendForegroundNotification(getString(R.string.service_notif_query_failed), e.message ?: e.javaClass.simpleName)
            } finally {
                scheduleNext()
                isRefreshing = false
                try { if (wl.isHeld) wl.release() } catch (_: Exception) {}
                CrashLogger.breadcrumb(TAG, "Refresh cycle completed")
                try {
                    stopForeground(STOP_FOREGROUND_DETACH)
                } catch (_: Exception) {}
            }
        }
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
