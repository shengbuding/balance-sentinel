package com.balancesentinel.app.widget

import android.app.PendingIntent
import android.content.ComponentName
import com.balancesentinel.app.data.util.Logger
import com.balancesentinel.app.util.FormatUtils
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
import com.balancesentinel.app.data.model.RefreshLogEntry
import com.balancesentinel.app.data.model.RefreshLogType
import com.balancesentinel.app.data.repository.RoomHistoryRepository
import com.balancesentinel.app.data.repository.appendRoomEvent
import com.balancesentinel.app.data.repository.RefreshScheduler
import com.balancesentinel.app.data.repository.SettingsRepositoryProvider
import com.balancesentinel.app.data.repository.SettingsSnapshotState
import com.balancesentinel.app.service.ForegroundServiceStarter
import com.balancesentinel.app.service.ServiceStarter
import com.balancesentinel.app.data.refresh.RefreshGateway
import com.balancesentinel.app.data.refresh.RefreshBatchResult
import com.balancesentinel.app.data.refresh.RefreshBatchState
import com.balancesentinel.app.data.refresh.RefreshRuntime
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import com.balancesentinel.app.data.repository.AccountLoadState
import com.balancesentinel.app.data.credentials.EncryptedPreferencesCredentialStore
import com.balancesentinel.app.data.local.WalletDatabaseProvider
import com.balancesentinel.app.data.repository.RoomAccountRepository
import com.balancesentinel.app.data.repository.RoomAccountUiRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object WidgetBalanceVisibility {
    fun filter(state: AccountLoadState, balances: List<AccountBalance>): List<AccountBalance> =
        when (state) {
            is AccountLoadState.Ready -> {
                val validIds = state.accounts.map { it.id }.toSet()
                balances.filter { it.accountId in validIds }
            }
            AccountLoadState.Loading, is AccountLoadState.Corrupt -> emptyList()
        }
}

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
    private val serviceStarter: ServiceStarter,
    private val resultConsumer: WidgetRefreshResultConsumer = WidgetRefreshResultConsumer { }
) {
    suspend fun execute(
        context: Context,
        decision: WidgetRefreshDecision.Refresh
    ): RefreshBatchResult {
        try {
            val result = WidgetRefreshRunner(gateway).refreshNow(watchdog = decision.watchdog)
            resultConsumer(result)
            return result
        } finally {
            if (decision.watchdog) {
                RefreshScheduler.recordRestart(context)
                serviceStarter.start(context)
            }
        }
    }
}

fun interface WidgetRefreshResultConsumer {
    suspend operator fun invoke(result: RefreshBatchResult)
}

data class WidgetRefreshStatus(
    val runId: String,
    val state: RefreshBatchState,
    val accountCount: Int,
    val successCount: Int,
    val failureCount: Int,
    val cancelledCount: Int
)

object WidgetRefreshStatusStore {
    private const val PREFS_NAME = "widget_refresh_status"
    private const val KEY_RUN_ID = "run_id"
    private const val KEY_STATE = "state"
    private const val KEY_ACCOUNT_COUNT = "account_count"
    private const val KEY_SUCCESS_COUNT = "success_count"
    private const val KEY_FAILURE_COUNT = "failure_count"
    private const val KEY_CANCELLED_COUNT = "cancelled_count"

    fun record(context: Context, result: RefreshBatchResult) {
        val aggregate = result.aggregate
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_RUN_ID, result.runId)
            .putString(KEY_STATE, aggregate.state.name)
            .putInt(KEY_ACCOUNT_COUNT, aggregate.accountCount)
            .putInt(KEY_SUCCESS_COUNT, aggregate.successCount)
            .putInt(KEY_FAILURE_COUNT, aggregate.failureCount)
            .putInt(KEY_CANCELLED_COUNT, aggregate.cancelledCount)
            .commit()
    }

    fun read(context: Context): WidgetRefreshStatus? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val runId = prefs.getString(KEY_RUN_ID, null) ?: return null
        val state = prefs.getString(KEY_STATE, null)?.let { value ->
            runCatching { RefreshBatchState.valueOf(value) }.getOrNull()
        } ?: return null
        return WidgetRefreshStatus(
            runId = runId,
            state = state,
            accountCount = prefs.getInt(KEY_ACCOUNT_COUNT, 0),
            successCount = prefs.getInt(KEY_SUCCESS_COUNT, 0),
            failureCount = prefs.getInt(KEY_FAILURE_COUNT, 0),
            cancelledCount = prefs.getInt(KEY_CANCELLED_COUNT, 0)
        )
    }

    internal fun clearForTests(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }
}

object WidgetRefreshIntents {
    fun manual(context: Context): Intent =
        Intent(context, WidgetRefreshReceiver::class.java).apply {
            action = StaticWidgetProvider.ACTION_REFRESH_NOW
        }

    fun watchdog(context: Context): Intent =
        Intent(context, WidgetRefreshReceiver::class.java).apply {
            action = StaticWidgetProvider.ACTION_WATCHDOG
        }
}

class WidgetRefreshReceiver : BroadcastReceiver() {
    private val serviceStarter: ServiceStarter = ForegroundServiceStarter()

    override fun onReceive(context: Context, intent: Intent) {
        try {
            val decision = WidgetRefreshActionHandler().decide(
                context,
                intent.action,
                System.currentTimeMillis()
            )
            if (decision is WidgetRefreshDecision.Refresh && processingRefresh.compareAndSet(false, true)) {
                handleRefresh(context, decision)
            }
        } catch (e: Exception) {
            processingRefresh.set(false)
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
        val provider = StaticWidgetProvider()

        provider.setRefreshProgress(context, manager, allIds, visible = true)
        if (decision.watchdog) {
            RefreshScheduler.markFired(context)
        }
        provider.onUpdate(context, manager, widgetIds)

        val pendingResult = goAsync()
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StaticWidget:refresh")
        wakeLock?.setReferenceCounted(false)
        try { wakeLock?.acquire(30_000L) } catch (_: Exception) {}

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        WidgetRefreshCoroutineDispatcher(scope).dispatch(
                action = {
                    WidgetRefreshExecution(
                        gateway = refreshGatewayProvider(context),
                        serviceStarter = serviceStarter,
                        resultConsumer = WidgetRefreshResultConsumer { result ->
                            WidgetRefreshStatusStore.record(context, result)
                        }
                    ).execute(context, decision)
                    provider.setRefreshProgress(context, manager, allIds, visible = false)
                    provider.onUpdate(context, manager, widgetIds)
                },
                finish = {
                    pendingResult.finish()
                    processingRefresh.set(false)
                    try { if (wakeLock?.isHeld == true) wakeLock.release() } catch (_: Exception) {}
                    scope.cancel()
                }
            )
    }

    companion object {
        private val processingRefresh = AtomicBoolean(false)

        internal var refreshGatewayProvider: (Context) -> RefreshGateway = RefreshRuntime::from

        internal fun resetTestOverrides() {
            processingRefresh.set(false)
            refreshGatewayProvider = RefreshRuntime::from
        }
    }
}
