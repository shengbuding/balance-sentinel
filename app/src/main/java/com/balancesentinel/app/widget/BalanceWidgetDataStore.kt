package com.balancesentinel.app.widget

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Widget 与 App 共享的余额缓存存储（多账户版）。
 * 使用普通 SharedPreferences，因为 Widget 进程中 EncryptedSharedPreferences 初始化较重。
 * 存储 JSON 序列化的 List<AccountBalance>，Widget 读取后汇总显示。
 */
object BalanceWidgetDataStore {

    private const val PREFS_NAME = "widget_balance_cache"
    private const val KEY_BALANCES = "account_balances"

    private val json = Json { ignoreUnknownKeys = true }

    fun saveAccountBalance(
        context: Context,
        accountId: String,
        label: String,
        totalBalance: String,
        currency: String,
        isAvailable: Boolean,
        grantedBalance: String,
        toppedUpBalance: String
    ) {
        val p = getPrefs(context)
        val balances = getAllBalances(p).toMutableList()
        val idx = balances.indexOfFirst { it.accountId == accountId && it.currency == currency }
        val entry = AccountBalance(
            accountId = accountId,
            label = label,
            totalBalance = totalBalance,
            currency = currency,
            isAvailable = isAvailable,
            grantedBalance = grantedBalance,
            toppedUpBalance = toppedUpBalance,
            lastUpdated = System.currentTimeMillis()
        )
        if (idx >= 0) balances[idx] = entry else balances.add(entry)
        p.edit().putString(KEY_BALANCES, json.encodeToString(balances)).apply()
    }

    fun removeAccountBalance(context: Context, accountId: String) {
        val p = getPrefs(context)
        val balances = getAllBalances(p).filter { it.accountId != accountId }
        p.edit().putString(KEY_BALANCES, json.encodeToString(balances)).apply()
    }

    fun getAllBalances(context: Context): List<AccountBalance> {
        return getAllBalances(getPrefs(context))
    }

    private fun getAllBalances(p: SharedPreferences): List<AccountBalance> {
        val raw = p.getString(KEY_BALANCES, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<AccountBalance>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 汇总所有可用账户的总余额。
     * 按币种汇总后取总额最大的两个币种（0 总额不显示）。
     */
    fun getAggregatedBalance(context: Context): AggregatedBalance? {
        val balances = getAllBalances(context)
        if (balances.isEmpty()) return null
        return aggregateTopTwo(balances)
    }

    /**
     * 从余额列表中提取总额最大的两个币种（总额为 0 的币种不显示）。
     */
    fun aggregateTopTwo(balances: List<AccountBalance>): AggregatedBalance? {
        if (balances.isEmpty()) return null

        // 按币种汇总
        val byCurrency = balances.groupBy { it.currency }
        val currencyTotals = byCurrency.mapValues { (_, entries) ->
            entries.sumOf { it.totalBalance.toDoubleOrNull() ?: 0.0 }
        }
        // 按总额降序，取前两个非零币种
        val sorted = currencyTotals.entries
            .filter { it.value > 0.0 }
            .sortedByDescending { it.value }
            .take(2)

        if (sorted.isEmpty()) {
            // 所有币种总额都为 0，回退使用第一个币种
            val first = byCurrency.keys.first()
            return AggregatedBalance(
                totalBalance = "0.00", currency = first,
                isAvailable = balances.all { it.isAvailable },
                grantedBalance = "0.00", toppedUpBalance = "0.00",
                accountCount = balances.map { it.accountId }.distinct().size,
                lastUpdated = balances.maxOf { it.lastUpdated }
            )
        }

        val primary = sorted[0]
        val total = primary.value
        val currency = primary.key
        val granted = byCurrency[currency]?.sumOf { it.grantedBalance.toDoubleOrNull() ?: 0.0 } ?: 0.0
        val toppedUp = byCurrency[currency]?.sumOf { it.toppedUpBalance.toDoubleOrNull() ?: 0.0 } ?: 0.0

        val secondary = sorted.getOrNull(1)
        val total2 = secondary?.value ?: 0.0
        val currency2 = secondary?.key ?: ""

        return AggregatedBalance(
            totalBalance = "%.2f".format(total),
            currency = currency,
            totalBalance2 = if (total2 > 0) "%.2f".format(total2) else "",
            currency2 = if (total2 > 0) currency2 else "",
            isAvailable = balances.all { it.isAvailable },
            grantedBalance = "%.2f".format(granted),
            toppedUpBalance = "%.2f".format(toppedUp),
            accountCount = balances.map { it.accountId }.distinct().size,
            lastUpdated = balances.maxOf { it.lastUpdated }
        )
    }

    /** 清除所有缓存的账户余额。 */
    fun clearAll(context: Context) {
        getPrefs(context).edit().remove(KEY_BALANCES).apply()
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}

@Serializable
data class AccountBalance(
    val accountId: String,
    val label: String,
    val totalBalance: String,
    val currency: String,
    val isAvailable: Boolean,
    val grantedBalance: String,
    val toppedUpBalance: String,
    val lastUpdated: Long
)

data class AggregatedBalance(
    val totalBalance: String,
    val currency: String,
    val totalBalance2: String = "",
    val currency2: String = "",
    val isAvailable: Boolean,
    val grantedBalance: String,
    val toppedUpBalance: String,
    val accountCount: Int,
    val lastUpdated: Long
)

