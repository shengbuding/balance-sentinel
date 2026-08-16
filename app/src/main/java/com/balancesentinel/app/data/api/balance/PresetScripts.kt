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
                var rawRemaining = data.balance != null ? data.balance :
                    (data.totalBalance != null ? data.totalBalance :
                    (data.total_balance != null ? data.total_balance :
                    (data.remaining != null ? data.remaining :
                    (data.availableBalance != null ? data.availableBalance :
                    (data.available_balance != null ? data.available_balance :
                    (data.credits != null ? data.credits : data.amount))))));
                var remaining = rawRemaining == null ? 0 : Number(rawRemaining);
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

    /** Common NewAPI self-hosted account endpoint; verify the instance response before enabling. */
    fun getNewApiTemplate() = UsageScript(
        code = """
        ({
            request: {
                url: "{{baseUrl}}/api/user/self",
                method: "GET",
                headers: {
                    "Content-Type": "application/json",
                    "Authorization": "Bearer {{accessToken}}",
                    "New-Api-User": "{{userId}}"
                }
            },
            extractor: function(response) {
                var data = response && (response.data || response.result);
                if (response && response.success !== false && data) {
                    var quota = data.quota;
                    var usedQuota = data.used_quota;
                    var remaining = data.remaining != null
                        ? Number(data.remaining)
                        : (quota == null ? Number(data.balance) : Number(quota) / 500000);
                    var used = data.used != null
                        ? Number(data.used)
                        : (usedQuota == null ? 0 : Number(usedQuota) / 500000);
                    var total = data.total != null
                        ? Number(data.total)
                        : (quota == null
                            ? remaining + used
                            : (Number(quota) + Number(usedQuota || 0)) / 500000);
                    return {
                        plan_name: data.group || data.plan_name || data.plan || "NewAPI",
                        remaining: remaining,
                        total: total,
                        used: used,
                        unit: data.currency || data.unit || "USD",
                        is_valid: true,
                        invalid_message: null
                    };
                }
                return {
                    remaining: 0,
                    unit: "USD",
                    is_valid: false,
                    invalid_message: response && response.message
                        ? response.message
                        : "NewAPI account unavailable"
                };
            }
        })
        """.trimIndent(),
        enabled = true,
        timeout = 15
    )
}
