package com.balancesentinel.app.data.api

/**
 * AI供应商抽象接口
 * 所有供应商实现必须实现此接口
 */
interface AiProvider {
    /**
     * 供应商类型
     */
    val providerType: ProviderType

    /**
     * 显示名称
     */
    val displayName: String

    /**
     * 支持的特性
     */
    val supportedFeatures: Set<ProviderFeature>
        get() = setOf(ProviderFeature.BALANCE, ProviderFeature.MODELS)

    /**
     * 获取余额
     * @param config 供应商配置
     * @return 余额结果
     */
    suspend fun getBalance(config: ProviderConfig): ProviderResult<UnifiedBalance>

    /**
     * 获取用量
     * @param config 供应商配置
     * @param startDate 开始日期（可选）
     * @param endDate 结束日期（可选）
     * @return 用量结果
     */
    suspend fun getUsage(
        config: ProviderConfig,
        startDate: String? = null,
        endDate: String? = null
    ): ProviderResult<UnifiedUsage> {
        return ProviderResult.Failure(
            ProviderError.ApiUnavailableError(providerType, "该供应商不支持用量查询")
        )
    }

    /**
     * 验证API Key格式
     * @param apiKey API Key
     * @return 是否格式有效
     */
    fun validateApiKeyFormat(apiKey: String): Boolean {
        return apiKey.isNotBlank()
    }

    /**
     * 获取配置字段定义（用于动态UI生成）
     * @return 配置字段列表
     */
    fun getRequiredFields(): List<ConfigField> {
        return listOf(
            ConfigField(
                key = "apiKey",
                displayName = "API Key",
                type = FieldType.PASSWORD,
                required = true,
                hint = "请输入API Key"
            )
        )
    }
}
