package com.example.deepseekbalance.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.example.deepseekbalance.data.engine.RecordAggregator
import com.example.deepseekbalance.data.model.DailySummary
import com.example.deepseekbalance.data.model.RawRecord
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 每日余额摘要存储（长期保留）。
 * 每天每条币种一条摘要，由午夜闹钟或启动检测触发聚合。
 */
object DailySummaryStore {

    private const val PREFS_NAME = "daily_summaries"
    private const val KEY_SUMMARIES = "summaries"

    private val json = Json { ignoreUnknownKeys = true }
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /**
     * 添加或覆盖一条日摘要（相同 date+currency 覆盖）。
     */
    fun addSummary(context: Context, summary: DailySummary) {
        try {
            val summaries = getSummaries(context).toMutableList()
            val existingIndex = summaries.indexOfFirst {
                it.date == summary.date && it.currency == summary.currency && it.accountId == summary.accountId
            }
            if (existingIndex >= 0) {
                summaries[existingIndex] = summary
            } else {
                summaries.add(summary)
            }
            // 按日期排序
            summaries.sortBy { it.date }
            val serialized = json.encodeToString(ListSerializer(DailySummary.serializer()), summaries)
            getPrefs(context).edit().putString(KEY_SUMMARIES, serialized).apply()
        } catch (_: Exception) {}
    }

    /**
     * 读取全部日摘要（按日期 ASC）。
     */
    fun getSummaries(context: Context): List<DailySummary> {
        return try {
            val raw = getPrefs(context).getString(KEY_SUMMARIES, null) ?: return emptyList()
            json.decodeFromString(ListSerializer(DailySummary.serializer()), raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 读取指定币种的日摘要。
     */
    fun getSummariesForCurrency(context: Context, currency: String): List<DailySummary> {
        return getSummaries(context).filter { it.currency == currency }
    }

    /**
     * 获取所有有数据的币种列表。
     */
    fun getAvailableCurrencies(context: Context): List<String> {
        return getSummaries(context).map { it.currency }.distinct()
    }

    /**
     * 聚合原始记录 → 日摘要 → 保存 → 清空 RawRecord。
     * 由午夜闹钟和 App 启动检测调用。
     * 按币种分组各自生成一条 DailySummary。
     */
    fun aggregateAndSave(context: Context, rawRecords: List<RawRecord>) {
        if (rawRecords.isEmpty()) return

        // 用记录时间戳确定日期（午夜调度时 Date() 已是新一天，数据属于前一天）
        val summaryDate = dateFormat.format(Date(rawRecords.first().timestamp))
        val summaries = RecordAggregator.aggregate(rawRecords, summaryDate)
        for (summary in summaries) {
            addSummary(context, summary)
        }
    }

    /**
     * 读取指定账户的日摘要。
     */
    fun getSummariesForAccount(context: Context, accountId: String): List<DailySummary> {
        return getSummaries(context).filter { it.accountId == accountId }
    }

    /**
     * 读取指定币种+账户的日摘要。
     */
    fun getSummariesForCurrencyAndAccount(context: Context, currency: String, accountId: String): List<DailySummary> {
        return getSummaries(context).filter { it.currency == currency && it.accountId == accountId }
    }

    /**
     * 获取所有有数据的账户 ID 列表。
     */
    fun getAllAccountIds(context: Context): List<String> {
        return getSummaries(context).map { it.accountId }.filter { it.isNotEmpty() }.distinct()
    }

    /**
     * 添加或覆盖日摘要（date+currency+accountId 唯一）。
     * 用于今日数据实时覆写。与 addSummary 行为一致。
     */
    fun upsert(context: Context, summary: DailySummary) {
        addSummary(context, summary)
    }

    /**
     * 按日期范围查询日摘要（含两端）。
     */
    fun getSummariesInRange(context: Context, from: String, to: String): List<DailySummary> {
        return getSummaries(context).filter { it.date >= from && it.date <= to }
    }

    /**
     * 判断指定日期+币种+账户是否已有日摘要。
     */
    fun hasSummaryForDate(context: Context, date: String, currency: String, accountId: String): Boolean {
        return getSummaries(context).any {
            it.date == date && it.currency == currency && it.accountId == accountId
        }
    }

    /**
     * 确保日期连续性。从 fromDate 到 toDate（不含今日）之间缺失的日期自动补零。
     *
     * 前置条件: fromDate 对应的条目必须在 DailySummaryStore 中存在。
     * DailySummaryStore 为空时不应调用此方法。
     *
     * 补零日特征:
     *   open/close = 前一个有效日的收盘值
     *   consumed/toppedUp/granted = 0
     *   sampleCount = 0（标记为无数据日）
     */
    fun ensureContinuity(context: Context, fromDate: String, toDate: String) {
        try {
            val summaries = getSummaries(context).toMutableList()
            val fromSummary = summaries.find { it.date == fromDate } ?: return
            var carryBalance = fromSummary.close

            val today = dateFormat.format(Date())

            val cal = java.util.Calendar.getInstance()
            dateFormat.parse(fromDate)?.let { cal.time = it }
            cal.add(java.util.Calendar.DAY_OF_MONTH, 1)

            val toCal = java.util.Calendar.getInstance()
            dateFormat.parse(toDate)?.let { toCal.time = it }

            while (!cal.after(toCal)) {
                val date = dateFormat.format(cal.time)
                if (date >= today) break  // 不补今日

                val exists = summaries.any { it.date == date }
                if (!exists) {
                    val currency = fromSummary.currency
                    val accountId = fromSummary.accountId
                    summaries.add(
                        DailySummary(
                            accountId = accountId,
                            date = date,
                            currency = currency,
                            open = carryBalance,
                            close = carryBalance,
                            consumed = 0f,
                            toppedUp = 0f,
                            granted = 0f,
                            avgBalance = carryBalance,
                            sampleCount = 0,
                            toppedUpBalanceClose = 0f,
                            grantedBalanceClose = 0f
                        )
                    )
                } else {
                    // 更新 carryBalance 为该日的 close
                    summaries.find { it.date == date }?.let { carryBalance = it.close }
                }
                cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
            }

            summaries.sortBy { it.date }
            val serialized =
                json.encodeToString(ListSerializer(DailySummary.serializer()), summaries)
            getPrefs(context).edit().putString(KEY_SUMMARIES, serialized).apply()
        } catch (_: Exception) {
        }
    }

    /**
     * 清除全部日摘要。
     */
    fun clear(context: Context) {
        try {
            getPrefs(context).edit().remove(KEY_SUMMARIES).apply()
        } catch (_: Exception) {}
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
