package com.balancesentinel.app.data.repository

import com.balancesentinel.app.data.util.Logger
import android.content.Context
import android.content.SharedPreferences
import com.balancesentinel.app.data.model.DailySummary
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

    private const val TAG = "DailySummaryStore"
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
        } catch (e: Exception) { Logger.w(TAG, "saveSummary failed", e) }
    }

    /**
     * 读取全部日摘要（按日期 ASC）。
     */
    fun getSummaries(context: Context): List<DailySummary> {
        return try {
            val raw = getPrefs(context).getString(KEY_SUMMARIES, null) ?: return emptyList()
            json.decodeFromString(ListSerializer(DailySummary.serializer()), raw)
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to parse daily summaries: ${e.message}")
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
     * 按 (accountId, currency) 分组独立处理，每组有各自的 carryBalance，
     * 避免多账户场景下余额串号和补零遗漏。
     *
     * 补零日特征:
     *   open/close = 前一个有效日的收盘值
     *   consumed/toppedUp/granted = 0
     *   sampleCount = 0（标记为无数据日）
     */
    fun ensureContinuity(context: Context, fromDate: String, toDate: String) {
        try {
            val summaries = getSummaries(context).toMutableList()
            val today = dateFormat.format(Date())

            // 按 (accountId, currency) 分组
            val groups = summaries.groupBy { it.accountId to it.currency }

            for ((_, groupSummaries) in groups) {
                // 找到该组在 fromDate 范围内的最早日期
                val groupEarliest = groupSummaries
                    .filter { it.date >= fromDate }
                    .minByOrNull { it.date } ?: continue

                var carryBalance = groupEarliest.close
                val accountId = groupEarliest.accountId
                val currency = groupEarliest.currency

                // 该组已有日期的快速查找集合
                val existingDates = groupSummaries.map { it.date }.toSet()

                val cal = java.util.Calendar.getInstance()
                dateFormat.parse(groupEarliest.date)?.let { cal.time = it }
                cal.add(java.util.Calendar.DAY_OF_MONTH, 1)

                val toCal = java.util.Calendar.getInstance()
                dateFormat.parse(toDate)?.let { toCal.time = it }

                while (!cal.after(toCal)) {
                    val date = dateFormat.format(cal.time)
                    if (date >= today) break

                    if (date !in existingDates) {
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
                        // 该日存在时，用该组的 close 推进 carryBalance
                        groupSummaries.find { it.date == date }?.let { carryBalance = it.close }
                    }
                    cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
                }
            }

            summaries.sortBy { it.date }
            val serialized =
                json.encodeToString(ListSerializer(DailySummary.serializer()), summaries)
            getPrefs(context).edit().putString(KEY_SUMMARIES, serialized).apply()
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to ensure continuity: ${e.message}")
        }
    }

    /**
     * 删除指定 (date, currency, accountId) 的日摘要。
     */
    fun deleteSummary(context: Context, date: String, currency: String, accountId: String) {
        try {
            val summaries = getSummaries(context).toMutableList()
            summaries.removeAll {
                it.date == date && it.currency == currency && it.accountId == accountId
            }
            val serialized = json.encodeToString(ListSerializer(DailySummary.serializer()), summaries)
            getPrefs(context).edit().putString(KEY_SUMMARIES, serialized).apply()
        } catch (e: Exception) { Logger.w(TAG, "deleteSummary failed", e) }
    }

    /**
     * 清除全部日摘要。
     */
    fun clear(context: Context) {
        try {
            getPrefs(context).edit().remove(KEY_SUMMARIES).apply()
        } catch (e: Exception) { Logger.w(TAG, "clear failed", e) }
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
