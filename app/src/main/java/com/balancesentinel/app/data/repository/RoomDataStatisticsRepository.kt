package com.balancesentinel.app.data.repository

import android.app.Application
import com.balancesentinel.app.CrashLogger
import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.local.WalletDatabaseProvider
import com.balancesentinel.app.widget.BalanceWidgetDataStore
import com.balancesentinel.app.widget.WidgetErrorLogger

/** Reads only aggregate values. It never materializes history rows. */
class RoomDataStatisticsRepository(
    private val context: Application,
    private val database: WalletDatabase = WalletDatabaseProvider.get(context)
) : DataStatisticsRepository {
    override suspend fun loadSummary(): DataStatisticsSnapshot {
        val history = database.historyDao()
        val usage = database.usageDao()
        val events = database.eventLogDao()
        val refreshState = RefreshScheduler.getState(context)
        return DataStatisticsSnapshot(
            rawRecordCount = history.countRecords(),
            rawRecordDistinctDates = history.countDistinctDates(),
            dailySummaryCount = history.countSummaries(),
            usageSnapshotCount = usage.countSnapshots(),
            refreshLogCount = events.countLogs(),
            widgetErrorCount = WidgetErrorLogger.getLogs(context).size,
            widgetBalanceCount = BalanceWidgetDataStore.getSummaryBalances(context).size,
            alarmCounters = DataAlarmCounterSnapshot(
                totalSet = refreshState.totalAlarmsSet,
                totalFired = refreshState.totalAlarmsFired,
                totalCancelled = refreshState.totalCancelled,
                totalDropped = refreshState.totalDropped
            ),
            crashCount = CrashLogger.getCrashes(context).size
        )
    }
}
