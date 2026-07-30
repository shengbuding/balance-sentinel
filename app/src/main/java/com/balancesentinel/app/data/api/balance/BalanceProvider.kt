package com.balancesentinel.app.data.api.balance

/**
 * 余额查询供应商类型
 */
enum class BalanceProviderType(val displayName: String) {
    DEEPSEEK("DeepSeek"),
    STEPFUN("StepFun"),
    SILICONFLOW("SiliconFlow"),
    SILICONFLOW_EN("SiliconFlow (EN)"),
    OPENROUTER("OpenRouter"),
    NOVITA("Novita AI"),
    MODEL_ARK("模力方舟"),
    CUSTOM("自定义");

    companion object {
        /**
         * 根据URL检测供应商类型
         */
        fun detectFromUrl(baseUrl: String): BalanceProviderType? {
            val url = baseUrl.lowercase()
            return when {
                url.contains("api.deepseek.com") -> DEEPSEEK
                url.contains("api.stepfun.ai") || url.contains("api.stepfun.com") -> STEPFUN
                url.contains("api.siliconflow.cn") -> SILICONFLOW
                url.contains("api.siliconflow.com") -> SILICONFLOW_EN
                url.contains("openrouter.ai") -> OPENROUTER
                url.contains("api.novita.ai") -> NOVITA
                url.contains("ai.gitee.com") -> MODEL_ARK
                else -> null
            }
        }
    }
}
