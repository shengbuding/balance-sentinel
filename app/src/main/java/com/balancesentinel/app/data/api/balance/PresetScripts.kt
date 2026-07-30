package com.balancesentinel.app.data.api.balance

/**
 * 预置脚本模板
 * 为常见供应商提供默认的查询脚本
 */
object PresetScripts {

    /**
     * 获取供应商的预置脚本
     */
    fun getPresetScript(baseUrl: String): UsageScript? {
        val providerType = BalanceProviderType.detectFromUrl(baseUrl) ?: return null

        return when (providerType) {
            BalanceProviderType.DEEPSEEK -> deepseekScript()
            BalanceProviderType.STEPFUN -> stepfunScript()
            BalanceProviderType.SILICONFLOW -> siliconFlowScript()
            BalanceProviderType.SILICONFLOW_EN -> siliconFlowEnScript()
            BalanceProviderType.OPENROUTER -> openRouterScript()
            BalanceProviderType.NOVITA -> novitaScript()
            BalanceProviderType.MODEL_ARK -> modelArkScript()
            BalanceProviderType.CUSTOM -> null
        }
    }

    /**
     * DeepSeek余额查询脚本
     */
    private fun deepseekScript() = UsageScript(
        code = """
        ({
            request: {
                url: "https://api.deepseek.com/user/balance",
                method: "GET",
                headers: {
                    "Authorization": "Bearer {{apiKey}}",
                    "Accept": "application/json"
                }
            },
            extractor: function(response) {
                // DeepSeek响应格式：{ "is_available": true, "balance_infos": [{ "currency": "CNY", "total_balance": "100.00" }] }
                var isAvailable = response.is_available !== false;
                var data = response.balance_infos || [];

                if (data.length > 0) {
                    var info = data[0];
                    var remaining = parseFloat(info.total_balance || info.balance || info.remaining) || 0;
                    var unit = info.currency || "CNY";
                    return {
                        plan_name: "DeepSeek",
                        remaining: remaining,
                        unit: unit,
                        is_valid: isAvailable && remaining > 0,
                        invalid_message: !isAvailable ? "账户不可用" : (remaining > 0 ? null : "余额不足")
                    };
                }

                // 尝试备用格式：直接在response中
                if (response.balance !== undefined) {
                    var remaining = parseFloat(response.balance) || 0;
                    return {
                        plan_name: "DeepSeek",
                        remaining: remaining,
                        unit: response.currency || "CNY",
                        is_valid: isAvailable && remaining > 0,
                        invalid_message: !isAvailable ? "账户不可用" : (remaining > 0 ? null : "余额不足")
                    };
                }

                return { remaining: 0, unit: "CNY", is_valid: false, invalid_message: "无法获取余额信息" };
            }
        })
        """.trimIndent(),
        enabled = true,
        timeout = 15
    )

    /**
     * StepFun余额查询脚本
     */
    private fun stepfunScript() = UsageScript(
        code = """
        ({
            request: {
                url: "https://api.stepfun.com/v1/account/balance",
                method: "GET",
                headers: {
                    "Authorization": "Bearer {{apiKey}}",
                    "Accept": "application/json"
                }
            },
            extractor: function(response) {
                // StepFun响应格式：{ "data": { "balance": "100.00", "total_quota": "500.00", "used_quota": "400.00", "currency": "CNY" } }
                var data = response.data || response;
                var remaining = parseFloat(data.balance || data.remaining || data.totalBalance) || 0;
                var unit = data.currency || "CNY";
                return {
                    plan_name: "StepFun",
                    remaining: remaining,
                    unit: unit,
                    is_valid: remaining > 0,
                    invalid_message: remaining > 0 ? null : "余额不足"
                };
            }
        })
        """.trimIndent(),
        enabled = true,
        timeout = 15
    )

    /**
     * SiliconFlow余额查询脚本（国内版）
     */
    private fun siliconFlowScript() = UsageScript(
        code = """
        ({
            request: {
                url: "https://api.siliconflow.cn/v1/user/info",
                method: "GET",
                headers: {
                    "Authorization": "Bearer {{apiKey}}",
                    "Accept": "application/json"
                }
            },
            extractor: function(response) {
                // SiliconFlow响应格式：{ "data": { "balance": 10.50, "totalBalance": 15.75, "status": "active" } }
                // balance: 当前可用余额（可消费）
                // totalBalance: 总余额（包含已消费的赠送额度）
                var data = response.data || {};
                // 优先使用 balance（可用余额），其次 totalBalance
                var remaining = parseFloat(data.balance || data.totalBalance || data.remaining) || 0;
                var isAvailable = data.status !== "suspended" && data.status !== "disabled";
                return {
                    plan_name: "SiliconFlow",
                    remaining: remaining,
                    unit: "CNY",
                    is_valid: isAvailable && remaining > 0,
                    invalid_message: !isAvailable ? "账户不可用" : (remaining > 0 ? null : "余额不足")
                };
            }
        })
        """.trimIndent(),
        enabled = true,
        timeout = 15
    )

    /**
     * SiliconFlow余额查询脚本（国际版）
     */
    private fun siliconFlowEnScript() = UsageScript(
        code = """
        ({
            request: {
                url: "https://api.siliconflow.com/v1/user/info",
                method: "GET",
                headers: {
                    "Authorization": "Bearer {{apiKey}}",
                    "Accept": "application/json"
                }
            },
            extractor: function(response) {
                // SiliconFlow响应格式：{ "data": { "balance": 10.50, "totalBalance": 15.75, "status": "active" } }
                // balance: 当前可用余额（可消费）
                // totalBalance: 总余额（包含已消费的赠送额度）
                var data = response.data || {};
                // 优先使用 balance（可用余额），其次 totalBalance
                var remaining = parseFloat(data.balance || data.totalBalance || data.remaining) || 0;
                var isAvailable = data.status !== "suspended" && data.status !== "disabled";
                return {
                    plan_name: "SiliconFlow (EN)",
                    remaining: remaining,
                    unit: "USD",
                    is_valid: isAvailable && remaining > 0,
                    invalid_message: !isAvailable ? "账户不可用" : (remaining > 0 ? null : "余额不足")
                };
            }
        })
        """.trimIndent(),
        enabled = true,
        timeout = 15
    )

    /**
     * OpenRouter余额查询脚本
     */
    private fun openRouterScript() = UsageScript(
        code = """
        ({
            request: {
                url: "https://openrouter.ai/api/v1/credits",
                method: "GET",
                headers: {
                    "Authorization": "Bearer {{apiKey}}",
                    "Accept": "application/json"
                }
            },
            extractor: function(response) {
                // OpenRouter响应格式：{ "data": { "total_credits": 100, "total_usage": 42.5, "balance": 57.5 } }
                var data = response.data || response;
                // 优先使用balance字段，如果没有则手动计算
                var remaining = parseFloat(data.balance) || 0;
                if (remaining === 0) {
                    var totalCredits = parseFloat(data.total_credits) || 0;
                    var totalUsage = parseFloat(data.total_usage) || 0;
                    remaining = totalCredits - totalUsage;
                }
                return {
                    plan_name: "OpenRouter",
                    remaining: remaining,
                    unit: "USD",
                    is_valid: remaining > 0,
                    invalid_message: remaining > 0 ? null : "额度已用完"
                };
            }
        })
        """.trimIndent(),
        enabled = true,
        timeout = 15
    )

    /**
     * Novita AI余额查询脚本
     */
    private fun novitaScript() = UsageScript(
        code = """
        ({
            request: {
                url: "https://api.novita.ai/v3/user/balance",
                method: "GET",
                headers: {
                    "Authorization": "Bearer {{apiKey}}",
                    "Accept": "application/json"
                }
            },
            extractor: function(response) {
                // Novita响应格式：{ "data": { "availableBalance": 10.50, "totalBalance": 15.75, "currency": "USD" } }
                // availableBalance: 当前可用余额（已正常单位USD）
                // totalBalance: 总余额
                var data = response.data || response;
                // 优先使用 availableBalance（可用余额）
                var remaining = parseFloat(data.availableBalance || data.balance || data.remaining) || 0;
                var unit = data.currency || "USD";
                return {
                    plan_name: "Novita AI",
                    remaining: remaining,
                    unit: unit,
                    is_valid: remaining > 0,
                    invalid_message: remaining > 0 ? null : "余额不足"
                };
            }
        })
        """.trimIndent(),
        enabled = true,
        timeout = 15
    )

    /**
     * 模力方舟余额查询脚本
     * 文档：https://ai.gitee.com/docs/openapi/v1
     * 端点：GET https://ai.gitee.com/v1/tokens/packages/balance
     */
    private fun modelArkScript() = UsageScript(
        code = """
        ({
            request: {
                url: "https://ai.gitee.com/v1/tokens/packages/balance",
                method: "GET",
                headers: {
                    "Authorization": "Bearer {{apiKey}}",
                    "Accept": "application/json"
                }
            },
            extractor: function(response) {
                // 模力方舟响应格式：
                // { "total_amount": 100, "used_amount": 30, "balance": 70, "details": [...] }
                var remaining = parseFloat(response.balance) || 0;
                var total = parseFloat(response.total_amount) || 0;
                var used = parseFloat(response.used_amount) || 0;
                return {
                    plan_name: "模力方舟",
                    remaining: remaining,
                    total: total,
                    used: used,
                    unit: "Token",
                    is_valid: remaining > 0,
                    invalid_message: remaining > 0 ? null : "余额不足"
                };
            }
        })
        """.trimIndent(),
        enabled = true,
        timeout = 15
    )

    /**
     * 通用脚本模板（用户可修改）
     * 支持多种常见的API响应格式
     */
    fun getCustomTemplate() = UsageScript(
        code = """
        ({
            request: {
                url: "{{baseUrl}}/v1/user/info",
                method: "GET",
                headers: {
                    "Authorization": "Bearer {{apiKey}}",
                    "Accept": "application/json"
                }
            },
            extractor: function(response) {
                // 通用提取器：支持多种常见的API响应格式
                // 请根据实际API响应格式修改以下代码

                // 尝试从不同位置获取数据
                var data = response.data || response.result || response;

                // 尝试多种常见的余额字段名
                var remaining = parseFloat(
                    data.balance ||
                    data.totalBalance ||
                    data.total_balance ||
                    data.remaining ||
                    data.availableBalance ||
                    data.available_balance ||
                    data.credits ||
                    data.amount
                ) || 0;

                // 尝试获取货币单位
                var unit = data.currency || data.unit || "CNY";

                // 尝试获取账户状态
                var isAvailable = data.is_available !== false &&
                                  data.status !== "suspended" &&
                                  data.status !== "disabled";

                return {
                    plan_name: data.plan_name || data.plan || "Custom",
                    remaining: remaining,
                    unit: unit,
                    is_valid: isAvailable && remaining > 0,
                    invalid_message: !isAvailable ? "账户不可用" : (remaining > 0 ? null : "余额不足")
                };
            }
        })
        """.trimIndent(),
        enabled = true,
        timeout = 15
    )
}
