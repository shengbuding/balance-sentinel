package com.balancesentinel.app.data.repository

import android.content.Context
import com.balancesentinel.app.data.engine.RecordAggregator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 清理调度器：聚合旧原始记录 → 日摘要 → 补零 → 删除。
 * 在午夜闹钟和 App 启动时调用，两者执行相同逻辑互为冗余。
 */
object CleanupScheduler {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /**
     * 执行一轮完整清理：
     * 1. 非今日的日期立即聚合 → 写入 DailySummaryStore
     * 2. 补零间隙
     * 3. 删除超过 24 小时的旧记录（已聚合 + 安全延迟）
     */
    suspend fun runCleanup(context: Context) = withContext(Dispatchers.IO) {
        try {
            val today = dateFormat.format(Date())
            val now = System.currentTimeMillis()

            // 缓存已有摘要，避免循环内重复反序列化
            val existingSummaries = DailySummaryStore.getSummaries(context)

            // Step 1: 非今日的日期立即聚合（不延迟）
            val pastDates = RawRecordStore.getDistinctDates(context)
                .filter { it != today }

            if (pastDates.isNotEmpty()) {
                for (date in pastDates.sorted()) {
                    val records = RawRecordStore.getRecordsForDate(context, date)
                    if (records.isEmpty()) continue

                    // 已有摘要的 (currency, accountId) 跳过，避免用删减后的记录覆盖完整摘要
                    val existingKeys = existingSummaries
                        .filter { it.date == date }
                        .map { it.currency to it.accountId }
                        .toSet()

                    val summaries = RecordAggregator.aggregate(records, date)
                    for (summary in summaries) {
                        val key = summary.currency to summary.accountId
                        if (key in existingKeys) {
                            val existing = existingSummaries
                                .find { it.date == date && it.currency == summary.currency && it.accountId == summary.accountId }
                            if (existing != null && summary.sampleCount > existing.sampleCount) {
                                DailySummaryStore.upsert(context, summary)
                            }
                        } else {
                            DailySummaryStore.upsert(context, summary)
                        }
                    }
                }
            }

            // Step 2: 补零 — 从最早日期到昨天
            val allSummaries = DailySummaryStore.getSummaries(context).sortedBy { it.date }
            if (allSummaries.isNotEmpty()) {
                val earliestDate = allSummaries.first().date
                val yesterday = dateFormat.format(Date(now - 24 * 3600_000L))

                DailySummaryStore.ensureContinuity(context, earliestDate, yesterday)
            }

            // Step 3: 只删除 >24h 的旧记录（保留至少一天原始数据）
            for (date in pastDates) {
                val records = RawRecordStore.getRecordsForDate(context, date)
                val allAged = records.isNotEmpty() && records.all { now - it.timestamp > 24 * 3600_000L }
                if (allAged) {
                    RawRecordStore.removeByDate(context, date)
                }
            }
        } catch (_: Exception) {
            // 清理失败不应影响 App 正常运行
        }
    }
}
