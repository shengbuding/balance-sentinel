package com.example.deepseekbalance.data.repository

import android.content.Context
import com.example.deepseekbalance.data.engine.RecordAggregator
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
     * 1. 扫描所有 >24h 且未汇总的日期
     * 2. 聚合 → 写入 DailySummaryStore
     * 3. 补零间隙
     * 4. 删除已聚合的旧记录
     */
    suspend fun runCleanup(context: Context) = withContext(Dispatchers.IO) {
        try {
            val today = dateFormat.format(Date())

            // Step 1: 找到所有需要聚合的日期（非今日 + 有 >24h 记录）
            val allDates = RawRecordStore.getDistinctDates(context)
                .filter { it != today }

            val unaggregatedDates = allDates.filter { date ->
                val records = RawRecordStore.getRecordsForDate(context, date)
                val now = System.currentTimeMillis()
                records.any { now - it.timestamp > 24 * 3600_000L }
            }

            if (unaggregatedDates.isEmpty()) return@withContext

            // Step 2: 逐日聚合
            for (date in unaggregatedDates.sorted()) {
                val records = RawRecordStore.getRecordsForDate(context, date)
                if (records.isEmpty()) continue

                val summaries = RecordAggregator.aggregate(records, date)
                for (summary in summaries) {
                    DailySummaryStore.upsert(context, summary)
                }
            }

            // Step 3: 补零 — 从最早日期到昨天
            val allSummaries = DailySummaryStore.getSummaries(context).sortedBy { it.date }
            if (allSummaries.isNotEmpty()) {
                val earliestDate = allSummaries.first().date
                val yesterday = dateFormat.format(Date(System.currentTimeMillis() - 24 * 3600_000L))

                DailySummaryStore.ensureContinuity(context, earliestDate, yesterday)
            }

            // Step 4: 删除已聚合日期的旧记录
            for (date in unaggregatedDates) {
                RawRecordStore.removeByDate(context, date)
            }
        } catch (_: Exception) {
            // 清理失败不应影响 App 正常运行
        }
    }
}
