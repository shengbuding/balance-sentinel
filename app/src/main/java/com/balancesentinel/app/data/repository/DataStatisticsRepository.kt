package com.balancesentinel.app.data.repository

/** A bounded, read-only projection used by the data-management first screen. */
data class DataStatisticsSnapshot(
    val rawRecordCount: Long = 0,
    val rawRecordDistinctDates: Long = 0,
    val dailySummaryCount: Long = 0,
    val usageSnapshotCount: Long = 0,
    val refreshLogCount: Long = 0,
    val widgetErrorCount: Int = 0,
    val widgetBalanceCount: Int = 0,
    val alarmCounters: DataAlarmCounterSnapshot = DataAlarmCounterSnapshot(),
    val crashCount: Int = 0
)

data class DataAlarmCounterSnapshot(
    val totalSet: Int = 0,
    val totalFired: Int = 0,
    val totalCancelled: Int = 0,
    val totalDropped: Int = 0
)

fun interface DataStatisticsRepository {
    suspend fun loadSummary(): DataStatisticsSnapshot
}
