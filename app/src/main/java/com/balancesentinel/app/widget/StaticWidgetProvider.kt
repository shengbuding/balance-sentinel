package com.balancesentinel.app.widget

import android.app.PendingIntent
import android.content.ComponentName
import com.balancesentinel.app.data.util.Logger
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.widget.RemoteViews
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import com.balancesentinel.app.DeepSeekApp
import com.balancesentinel.app.MainActivity
import com.balancesentinel.app.R
import com.balancesentinel.app.ui.navigation.AppRoute
import com.balancesentinel.app.data.repository.RoomHistoryRepository
import com.balancesentinel.app.data.repository.SettingsRepositoryProvider
import com.balancesentinel.app.data.repository.SettingsSnapshotState
import com.balancesentinel.app.service.ForegroundServiceStarter
import com.balancesentinel.app.service.ServiceStarter
import com.balancesentinel.app.data.refresh.RefreshGateway
import com.balancesentinel.app.data.refresh.RefreshBatchResult
import com.balancesentinel.app.data.refresh.RefreshRuntime
import java.util.concurrent.atomic.AtomicBoolean
import com.balancesentinel.app.data.repository.AccountLoadState
import com.balancesentinel.app.data.credentials.EncryptedPreferencesCredentialStore
import com.balancesentinel.app.data.local.WalletDatabaseProvider
import com.balancesentinel.app.data.repository.RoomAccountRepository
import com.balancesentinel.app.data.repository.RoomAccountUiRepository
import com.balancesentinel.app.data.repository.WidgetPrefs
import com.balancesentinel.app.platform.permission.AndroidCapabilityChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

open class StaticWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        scheduleRefresh(context)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val state = accountStateLoaderOverride?.invoke(context) ?: loadAccountState(context)
                withContext(Dispatchers.Main) {
                    appWidgetIds.forEach { id ->
                        updateWidget(context, appWidgetManager, id, appWidgetManager.getAppWidgetOptions(id), state)
                    }
                }
            } finally {
                pending?.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, appWidgetManager: AppWidgetManager,
        appWidgetId: Int, newOptions: Bundle
    ) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val state = accountStateLoaderOverride?.invoke(context) ?: loadAccountState(context)
                withContext(Dispatchers.Main) {
                    updateWidget(context, appWidgetManager, appWidgetId, newOptions, state)
                }
            } finally {
                pending?.finish()
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        for (id in appWidgetIds) {
            WidgetConfigStore.removeConfig(context, id)
        }
    }

    private suspend fun loadAccountState(context: Context): AccountLoadState = runCatching {
        val repository = RoomAccountRepository(WalletDatabaseProvider.get(context))
        RoomAccountUiRepository(repository, EncryptedPreferencesCredentialStore(context))
            .observe().first { it !is AccountLoadState.Loading }
    }.getOrElse {
        AccountLoadState.Corrupt(
            com.balancesentinel.app.data.credentials.DataCorruptionException(
                "Account state unavailable", it
            )
        )
    }

    // ── Widget 渲染（汇总显示） ──

    private suspend fun updateWidget(
        context: Context, manager: AppWidgetManager, widgetId: Int,
        options: Bundle = manager.getAppWidgetOptions(widgetId),
        accountState: AccountLoadState = AccountLoadState.Corrupt(
            com.balancesentinel.app.data.credentials.DataCorruptionException("Account state unavailable")
        )
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
        // Read each bounded summary snapshot once, then resolve and render one explicit state.
        val config = WidgetConfigStore.getConfig(context, widgetId)
        val activeAccounts = (accountState as? AccountLoadState.Ready)
            ?.accounts?.associate { it.id to it.label }.orEmpty()
        val activeAccountIds = activeAccounts.keys
        val lastRefreshStatus = WidgetRefreshStatusStore.read(context)
        val summaryBalances = WidgetBalanceVisibility.filter(
            accountState,
            BalanceWidgetDataStore.getSummaryBalances(context)
        )
        val capability = AndroidCapabilityChecker(context).read(
            WidgetPrefs(context).notificationPermissionPermanentlyDenied
        )
        val state = WidgetStateResolver.resolve(
            WidgetStateInput(
                config = config,
                activeAccounts = activeAccounts,
                balances = summaryBalances,
                lastRefresh = lastRefreshStatus,
                capabilityRestricted = !capability.monitoringAllowed
            )
        )
        val (views, renderModel) = WidgetRemoteViewsRenderer.render(
            context = context,
            layoutRes = layoutRes,
            state = state,
            expanded = isExpanded
        )
        val agg = when (state) {
            is WidgetViewState.Fresh -> state.balance
            is WidgetViewState.Stale -> state.balance
            else -> null
        }

        // Sparkline 迷你趋势线（仅 expanded layout）
        if (isExpanded && agg != null) {
            try {
                val summaries = withContext(Dispatchers.IO) {
                    RoomHistoryRepository(WalletDatabaseProvider.get(context))
                        .summaries(currency = agg.currency)
                }
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
        val appRoute = renderModel.route
        val appIntent = primaryActionIntent(
            context = context,
            widgetId = widgetId,
            primaryAction = renderModel.primaryAction,
            route = appRoute
        )
        val appPending = PendingIntent.getActivity(
            context, widgetId, appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_balance, appPending)
        views.setOnClickPendingIntent(R.id.widget_title, appPending)

        val refreshIntent = WidgetRefreshIntents.manual(context)
        val refreshPending = PendingIntent.getBroadcast(
            context, widgetId + 1000, refreshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_refresh_btn, refreshPending)

        views.setViewVisibility(
            R.id.widget_refresh_progress,
            if (WidgetRefreshProgressState.isRefreshing()) {
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            }
        )

        manager.updateAppWidget(widgetId, views)
    }

    private fun scheduleRefresh(context: Context) {
        val published = SettingsRepositoryProvider.get(context).snapshot.value
            as? SettingsSnapshotState.Ready ?: return
        val intervalSec = published.value.effectiveBackgroundCadenceSeconds ?: run {
            workSchedulerFactory(context).reconcile(context, null, widgetEnabled = true)
            return
        }
        runCatching {
            workSchedulerFactory(context).reconcile(
                context = context,
                backgroundIntervalSeconds = intervalSec.toLong(),
                widgetEnabled = true
            )
        }.onFailure { error ->
            Logger.w("StaticWidget", "Failed to reconcile widget refresh work", error)
        }
    }

    internal fun setRefreshProgress(context: Context, manager: AppWidgetManager, widgetIds: List<Int>, visible: Boolean) {
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

    companion object {
        const val LEGACY_WIDGET_ALARM_REQUEST_CODE = 100
        fun canonicalDeepLinkUri(accountId: String, currency: String): Uri =
            AppRoute.Insights(accountId, currency).toUri()
        internal fun primaryActionIntent(
            context: Context,
            widgetId: Int,
            primaryAction: WidgetPrimaryAction,
            route: AppRoute
        ): Intent = when {
            primaryAction == WidgetPrimaryAction.CONFIGURE ->
                Intent(context, WidgetConfigActivity::class.java).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                }
            route is AppRoute.Insights ->
                Intent(Intent.ACTION_VIEW, route.toUri(), context, MainActivity::class.java)
            else -> Intent(context, MainActivity::class.java)
        }
        fun configuredDeepLinkAccountId(configAccountId: String?, activeAccountIds: Set<String>): String? =
            configAccountId
                ?.takeUnless { it == WidgetConfig.TOTAL_ACCOUNT_ID }
                ?.takeIf { it in activeAccountIds }
        const val ACTION_REFRESH_NOW = "com.balancesentinel.app.WIDGET_REFRESH_NOW"
        const val ACTION_WATCHDOG = "com.balancesentinel.app.WIDGET_WATCHDOG"
        @Volatile private var lastScheduleTime: Long = 0L

        internal var accountStateLoaderOverride: (suspend (Context) -> AccountLoadState)? = null
        internal var workSchedulerFactory: (Context) -> com.balancesentinel.app.work.RefreshWorkScheduler = { com.balancesentinel.app.work.RefreshWorkScheduler() }
    }
}
