package com.balancesentinel.app.data.api.health

import android.content.Context
import android.content.SharedPreferences
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.api.ProviderConfig
import com.balancesentinel.app.data.api.ProviderFactory
import com.balancesentinel.app.data.api.ProviderResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 供应商健康检查器
 * 定期检查供应商API的可用性
 */
class ProviderHealthChecker(private val context: Context) {

    @kotlinx.serialization.Serializable
    data class HealthStatus(
        val providerType: ProviderType,
        val isHealthy: Boolean,
        val lastCheckTime: Long,
        val responseTime: Long,  // 毫秒
        val errorMessage: String? = null
    )

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("provider_health", Context.MODE_PRIVATE)
    }

    private val json = Json { ignoreUnknownKeys = true }

    // 内存缓存
    private val healthCache = mutableMapOf<ProviderType, HealthStatus>()

    /**
     * 检查供应商健康状态
     * @param providerType 供应商类型
     * @param config 供应商配置
     * @return 健康状态
     */
    suspend fun check(
        providerType: ProviderType,
        config: ProviderConfig
    ): HealthStatus = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        try {
            val provider = ProviderFactory.get(providerType)
            val result = provider.getBalance(config)

            val responseTime = System.currentTimeMillis() - startTime
            val status = when (result) {
                is ProviderResult.Success -> HealthStatus(
                    providerType = providerType,
                    isHealthy = true,
                    lastCheckTime = System.currentTimeMillis(),
                    responseTime = responseTime
                )
                is ProviderResult.Failure -> HealthStatus(
                    providerType = providerType,
                    isHealthy = false,
                    lastCheckTime = System.currentTimeMillis(),
                    responseTime = responseTime,
                    errorMessage = result.error.message
                )
            }

            // 保存状态
            saveStatus(status)
            healthCache[providerType] = status

            status
        } catch (e: Exception) {
            val responseTime = System.currentTimeMillis() - startTime
            val status = HealthStatus(
                providerType = providerType,
                isHealthy = false,
                lastCheckTime = System.currentTimeMillis(),
                responseTime = responseTime,
                errorMessage = e.message ?: "未知错误"
            )

            saveStatus(status)
            healthCache[providerType] = status

            status
        }
    }

    /**
     * 获取缓存的健康状态
     * @param providerType 供应商类型
     * @return 健康状态，如果不存在则返回null
     */
    fun getStatus(providerType: ProviderType): HealthStatus? {
        // 先检查内存缓存
        val memoryStatus = healthCache[providerType]
        if (memoryStatus != null) {
            return memoryStatus
        }

        // 再检查SharedPreferences
        val data = prefs.getString(providerType.id, null) ?: return null
        return try {
            val status = json.decodeFromString<HealthStatus>(data)
            healthCache[providerType] = status
            status
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 检查供应商是否健康
     * @param providerType 供应商类型
     * @return 是否健康
     */
    fun isHealthy(providerType: ProviderType): Boolean {
        val status = getStatus(providerType) ?: return true  // 未检查过默认为健康
        return status.isHealthy
    }

    /**
     * 获取所有供应商的健康状态
     * @return 健康状态列表
     */
    fun getAllStatuses(): List<HealthStatus> {
        return ProviderType.entries.mapNotNull { getStatus(it) }
    }

    /**
     * 保存健康状态
     */
    private fun saveStatus(status: HealthStatus) {
        prefs.edit().putString(status.providerType.id, json.encodeToString(status)).apply()
    }

    /**
     * 清除所有健康状态
     */
    fun clearAll() {
        healthCache.clear()
        prefs.edit().clear().apply()
    }

    companion object {
        @Volatile
        private var instance: ProviderHealthChecker? = null

        fun getInstance(context: Context): ProviderHealthChecker {
            return instance ?: synchronized(this) {
                instance ?: ProviderHealthChecker(context.applicationContext).also { instance = it }
            }
        }
    }
}
