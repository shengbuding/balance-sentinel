package com.balancesentinel.app.data.api.tracking

import android.content.Context
import android.content.SharedPreferences
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.api.UnifiedBalance
import com.balancesentinel.app.data.api.BalanceEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * 本地用量追踪器
 * 为无余额API的供应商提供基于token计数的估算余额
 */
class LocalUsageTracker(private val context: Context) {

    @Serializable
    data class UsageRecord(
        val providerType: ProviderType,
        val accountId: String,
        val timestamp: Long,
        val inputTokens: Long,
        val outputTokens: Long,
        val cost: Double
    )

    @Serializable
    data class ProviderBalance(
        val providerType: ProviderType,
        val accountId: String,
        val totalBalance: Double,
        val usedBalance: Double,
        val lastUpdated: Long
    )

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("local_usage_tracker", Context.MODE_PRIVATE)
    }

    private val json = Json { ignoreUnknownKeys = true }

    // 内存缓存
    private val balanceCache = ConcurrentHashMap<String, ProviderBalance>()

    /**
     * 记录用量
     */
    fun recordUsage(
        providerType: ProviderType,
        accountId: String,
        inputTokens: Long,
        outputTokens: Long
    ) {
        val cost = calculateCost(providerType, inputTokens, outputTokens)
        val record = UsageRecord(
            providerType = providerType,
            accountId = accountId,
            timestamp = System.currentTimeMillis(),
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            cost = cost
        )

        // 保存记录
        saveUsageRecord(record)

        // 更新余额缓存
        updateBalanceCache(providerType, accountId, cost)
    }

    /**
     * 获取估算余额
     */
    fun getEstimatedBalance(
        providerType: ProviderType,
        accountId: String,
        initialBalance: Double = 100.0  // 默认初始余额
    ): UnifiedBalance {
        val balance = getBalanceFromCache(providerType, accountId, initialBalance)

        return UnifiedBalance(
            provider = providerType,
            accountId = accountId,
            isAvailable = true,
            balances = listOf(
                BalanceEntry(
                    currency = "CNY",
                    totalBalance = balance.totalBalance - balance.usedBalance,
                    unit = "元"
                )
            ),
            isEstimated = true
        )
    }

    /**
     * 设置初始余额
     */
    fun setInitialBalance(
        providerType: ProviderType,
        accountId: String,
        initialBalance: Double
    ) {
        val key = "${providerType.id}_$accountId"
        // M8 修复：保留已有的 usedBalance，不重置为 0
        val existing = balanceCache[key]
        val balance = ProviderBalance(
            providerType = providerType,
            accountId = accountId,
            totalBalance = initialBalance,
            usedBalance = existing?.usedBalance ?: 0.0,
            lastUpdated = System.currentTimeMillis()
        )
        balanceCache[key] = balance
        saveBalanceToPrefs(key, balance)
    }

    /**
     * 计算费用
     * M7 修复：区分 input/output token 费率
     */
    private fun calculateCost(
        providerType: ProviderType,
        inputTokens: Long,
        outputTokens: Long
    ): Double {
        // 价格表：(input元/千token, output元/千token)
        val (inputPrice, outputPrice) = when (providerType) {
            ProviderType.MOONSHOT -> 0.012 to 0.012
            ProviderType.DOUBAO -> 0.008 to 0.008
            ProviderType.BAICHUAN -> 0.01 to 0.01
            ProviderType.QWEN -> 0.008 to 0.008
            ProviderType.ZHIPU -> 0.005 to 0.005
            ProviderType.WENXIN -> 0.012 to 0.012
            ProviderType.OPENAI -> 0.03 to 0.06   // GPT-4: input $0.03, output $0.06
            ProviderType.ANTHROPIC -> 0.015 to 0.075  // Claude: input $0.015, output $0.075
            ProviderType.GEMINI -> 0.005 to 0.005
            ProviderType.MISTRAL -> 0.02 to 0.02
            ProviderType.COHERE -> 0.015 to 0.015
            else -> 0.01 to 0.01
        }
        return (inputTokens / 1000.0) * inputPrice + (outputTokens / 1000.0) * outputPrice
    }

    /**
     * 从缓存获取余额
     */
    private fun getBalanceFromCache(
        providerType: ProviderType,
        accountId: String,
        initialBalance: Double
    ): ProviderBalance {
        val key = "${providerType.id}_$accountId"
        return balanceCache.getOrPut(key) {
            loadBalanceFromPrefs(key) ?: ProviderBalance(
                providerType = providerType,
                accountId = accountId,
                totalBalance = initialBalance,
                usedBalance = 0.0,
                lastUpdated = System.currentTimeMillis()
            )
        }
    }

    /**
     * 更新余额缓存
     * C4 修复：使用 ConcurrentHashMap.compute() 保证原子性
     */
    private fun updateBalanceCache(
        providerType: ProviderType,
        accountId: String,
        cost: Double
    ) {
        val key = "${providerType.id}_$accountId"
        balanceCache.compute(key) { _, existing ->
            if (existing == null) return@compute null
            val updated = existing.copy(
                usedBalance = existing.usedBalance + cost,
                lastUpdated = System.currentTimeMillis()
            )
            saveBalanceToPrefs(key, updated)
            updated
        }
    }

    /**
     * 保存用量记录
     * C4 修复：使用 synchronized 保护读-改-写序列
     */
    private fun saveUsageRecord(record: UsageRecord) {
        val key = "usage_${record.providerType.id}_${record.accountId}"
        synchronized(this) {
            val existing = prefs.getString(key, null)
            val records = if (existing != null) {
                json.decodeFromString<List<UsageRecord>>(existing)
            } else {
                emptyList()
            }.toMutableList()

            records.add(record)

            // 只保留最近1000条记录
            if (records.size > 1000) {
                records.removeAt(0)
            }

            prefs.edit().putString(key, json.encodeToString(records)).apply()
        }
    }

    /**
     * 保存余额到SharedPreferences
     */
    private fun saveBalanceToPrefs(key: String, balance: ProviderBalance) {
        prefs.edit().putString("balance_$key", json.encodeToString(balance)).apply()
    }

    /**
     * 从SharedPreferences加载余额
     */
    private fun loadBalanceFromPrefs(key: String): ProviderBalance? {
        val data = prefs.getString("balance_$key", null) ?: return null
        return try {
            json.decodeFromString<ProviderBalance>(data)
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        @Volatile
        private var instance: LocalUsageTracker? = null

        fun getInstance(context: Context): LocalUsageTracker {
            return instance ?: synchronized(this) {
                instance ?: LocalUsageTracker(context.applicationContext).also { instance = it }
            }
        }
    }
}
