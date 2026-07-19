package com.balancesentinel.app.data.api.cache

import android.content.Context
import android.content.SharedPreferences
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.api.UnifiedBalance
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * 供应商缓存层
 * 为每个供应商提供独立的缓存策略
 */
class ProviderCache(private val context: Context) {

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("provider_cache", Context.MODE_PRIVATE)
    }

    private val json = Json { ignoreUnknownKeys = true }

    // 内存缓存
    private val memoryCache = ConcurrentHashMap<String, CachedBalance>()

    /**
     * 缓存的余额数据
     */
    @kotlinx.serialization.Serializable
    data class CachedBalance(
        val balance: UnifiedBalance,
        val cachedAt: Long,
        val ttl: Long
    ) {
        val isExpired: Boolean
            get() = System.currentTimeMillis() > cachedAt + ttl
    }

    /**
     * 获取缓存的余额
     * @param providerType 供应商类型
     * @param accountId 账户ID
     * @return 缓存的余额，如果过期或不存在则返回null
     */
    fun get(providerType: ProviderType, accountId: String): UnifiedBalance? {
        val key = "${providerType.id}_$accountId"

        // 先检查内存缓存
        val memoryCached = memoryCache[key]
        if (memoryCached != null && !memoryCached.isExpired) {
            return memoryCached.balance
        }

        // 再检查SharedPreferences
        val prefsData = prefs.getString(key, null)
        if (prefsData != null) {
            try {
                val cached = json.decodeFromString<CachedBalance>(prefsData)
                if (!cached.isExpired) {
                    // 加载到内存缓存
                    memoryCache[key] = cached
                    return cached.balance
                }
            } catch (e: Exception) {
                // 解析失败，删除缓存
                prefs.edit().remove(key).apply()
            }
        }

        return null
    }

    /**
     * 设置缓存
     * @param providerType 供应商类型
     * @param accountId 账户ID
     * @param balance 余额数据
     * @param ttl 缓存时间（毫秒）
     */
    fun put(
        providerType: ProviderType,
        accountId: String,
        balance: UnifiedBalance,
        ttl: Long = getDefaultTtl(providerType)
    ) {
        val key = "${providerType.id}_$accountId"
        val cached = CachedBalance(
            balance = balance,
            cachedAt = System.currentTimeMillis(),
            ttl = ttl
        )

        // 保存到内存缓存
        memoryCache[key] = cached

        // 保存到SharedPreferences
        prefs.edit().putString(key, json.encodeToString(cached)).apply()
    }

    /**
     * 清除指定供应商的缓存
     */
    fun clear(providerType: ProviderType, accountId: String) {
        val key = "${providerType.id}_$accountId"
        memoryCache.remove(key)
        prefs.edit().remove(key).apply()
    }

    /**
     * 清除所有缓存
     */
    fun clearAll() {
        memoryCache.clear()
        prefs.edit().clear().apply()
    }

    /**
     * 清除过期缓存
     */
    fun clearExpired() {
        val keysToRemove = mutableListOf<String>()

        // 检查内存缓存
        memoryCache.forEach { (key, cached) ->
            if (cached.isExpired) {
                keysToRemove.add(key)
            }
        }

        keysToRemove.forEach { memoryCache.remove(it) }

        // 检查SharedPreferences
        val allKeys = prefs.all.keys
        allKeys.forEach { key ->
            val data = prefs.getString(key, null) ?: return@forEach
            try {
                val cached = json.decodeFromString<CachedBalance>(data)
                if (cached.isExpired) {
                    prefs.edit().remove(key).apply()
                }
            } catch (e: Exception) {
                prefs.edit().remove(key).apply()
            }
        }
    }

    /**
     * 获取默认TTL（根据供应商类型）
     */
    private fun getDefaultTtl(providerType: ProviderType): Long {
        return when (providerType) {
            ProviderType.DEEPSEEK -> 60_000L  // 1分钟
            ProviderType.MOONSHOT -> 120_000L  // 2分钟
            ProviderType.DOUBAO -> 120_000L
            ProviderType.BAICHUAN -> 120_000L
            ProviderType.QWEN -> 120_000L
            ProviderType.ZHIPU -> 120_000L
            ProviderType.WENXIN -> 120_000L
            ProviderType.OPENAI -> 60_000L
            ProviderType.ANTHROPIC -> 60_000L
            ProviderType.GEMINI -> 120_000L
            ProviderType.MISTRAL -> 120_000L
            ProviderType.COHERE -> 120_000L
            ProviderType.CUSTOM -> 120_000L
        }
    }

    companion object {
        @Volatile
        private var instance: ProviderCache? = null

        fun getInstance(context: Context): ProviderCache {
            return instance ?: synchronized(this) {
                instance ?: ProviderCache(context.applicationContext).also { instance = it }
            }
        }
    }
}
