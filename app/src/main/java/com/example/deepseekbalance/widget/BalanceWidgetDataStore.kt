package com.example.deepseekbalance.widget

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
        val idx = balances.indexOfFirst { it.accountId == accountId }
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
     * 优先使用 CNY 币种的汇总，如果没有则使用第一种币种。
     */
    fun getAggregatedBalance(context: Context): AggregatedBalance? {
        val balances = getAllBalances(context)
        if (balances.isEmpty()) return null

        // 按币种分组汇总
        val byCurrency = balances.groupBy { it.currency }
        val primaryCurrency = if (byCurrency.containsKey("CNY")) "CNY" else byCurrency.keys.first()

        val total = byCurrency[primaryCurrency]?.sumOf {
            it.totalBalance.toDoubleOrNull() ?: 0.0
        } ?: 0.0

        val granted = byCurrency[primaryCurrency]?.sumOf {
            it.grantedBalance.toDoubleOrNull() ?: 0.0
        } ?: 0.0

        val toppedUp = byCurrency[primaryCurrency]?.sumOf {
            it.toppedUpBalance.toDoubleOrNull() ?: 0.0
        } ?: 0.0

        val allAvailable = balances.all { it.isAvailable }

        return AggregatedBalance(
            totalBalance = "%.2f".format(total),
            currency = primaryCurrency,
            isAvailable = allAvailable,
            grantedBalance = "%.2f".format(granted),
            toppedUpBalance = "%.2f".format(toppedUp),
            accountCount = balances.size,
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
    val isAvailable: Boolean,
    val grantedBalance: String,
    val toppedUpBalance: String,
    val accountCount: Int,
    val lastUpdated: Long
)

