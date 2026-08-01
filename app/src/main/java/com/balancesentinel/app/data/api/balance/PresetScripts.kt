package com.balancesentinel.app.data.api.balance

object PresetScripts {
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
                var data = response.data || response.result || response;
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
                var unit = data.currency || data.unit || "CNY";
                var isAvailable = data.is_available !== false &&
                                  data.status !== "suspended" &&
                                  data.status !== "disabled";

                return {
                    plan_name: data.plan_name || data.plan || "Custom",
                    remaining: remaining,
                    unit: unit,
                    is_valid: isAvailable && remaining > 0,
                    invalid_message: !isAvailable
                        ? "Account unavailable"
                        : (remaining > 0 ? null : "Insufficient balance")
                };
            }
        })
        """.trimIndent(),
        enabled = true,
        timeout = 15
    )
}
