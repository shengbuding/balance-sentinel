package com.balancesentinel.app.data.api.balance

import com.balancesentinel.app.data.model.AccountInfo
import com.balancesentinel.app.data.refresh.RefreshFailure
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

sealed interface ScriptExecutionResult {
    data class Success(val balances: List<BalanceData>) : ScriptExecutionResult
    data class Failure(val failure: RefreshFailure) : ScriptExecutionResult
}

data class ScriptInspection(
    val request: RequestConfig?,
    val requiredExtraOrigins: Set<WebOrigin>,
    val staticallyDeterminable: Boolean,
    val failure: RefreshFailure? = null
)

suspend fun UsageScriptExecutor.inspect(
    @Suppress("UNUSED_PARAMETER") script: UsageScript,
    @Suppress("UNUSED_PARAMETER") account: AccountInfo
): ScriptInspection = ScriptInspection(
    request = null,
    requiredExtraOrigins = emptySet(),
    staticallyDeterminable = false,
    failure = RefreshFailure.ResponseSchemaFailure("Script inspection is unavailable")
)

suspend fun UsageScriptExecutor.extractForTest(
    @Suppress("UNUSED_PARAMETER") script: UsageScript,
    @Suppress("UNUSED_PARAMETER") account: AccountInfo,
    @Suppress("UNUSED_PARAMETER") responseBody: String
): ScriptExecutionResult = ScriptExecutionResult.Failure(
    RefreshFailure.ResponseSchemaFailure("Script extraction is unavailable")
)

suspend fun UsageScriptExecutor.execute(
    @Suppress("UNUSED_PARAMETER") script: UsageScript,
    @Suppress("UNUSED_PARAMETER") account: AccountInfo,
    @Suppress("UNUSED_PARAMETER") resolver: HostResolver = HostResolver { emptyList() },
    @Suppress("UNUSED_PARAMETER") client: OkHttpClient = OkHttpClient(),
    @Suppress("UNUSED_PARAMETER") connectionUrlOverride: ((HttpUrl) -> HttpUrl)? = null
): ScriptExecutionResult = ScriptExecutionResult.Failure(
    RefreshFailure.ResponseSchemaFailure("Script execution is unavailable")
)
