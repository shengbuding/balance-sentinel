package com.balancesentinel.app.data.api.balance

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class BalanceProviderType(val displayName: String) {
    DEEPSEEK("DeepSeek"),
    STEPFUN("StepFun"),
    SILICONFLOW("SiliconFlow"),
    SILICONFLOW_EN("SiliconFlow (EN)"),
    OPENROUTER("OpenRouter"),
    NOVITA("Novita AI"),
    MODEL_ARK("ModelArk"),
    CUSTOM("Custom");

    companion object {
        fun detectFromUrl(baseUrl: String): BalanceProviderType? {
            val host = baseUrl.toHttpUrlOrNull()?.host ?: return null
            return when (host) {
                "api.deepseek.com" -> DEEPSEEK
                "api.stepfun.com" -> STEPFUN
                "api.siliconflow.cn" -> SILICONFLOW
                "api.siliconflow.com" -> SILICONFLOW_EN
                "openrouter.ai" -> OPENROUTER
                "api.novita.ai" -> NOVITA
                "ai.gitee.com" -> MODEL_ARK
                else -> null
            }
        }
    }
}
