package com.example.deepseekbalance.data.model

import kotlinx.serialization.Serializable

/**
 * /v1/usage 端点返回的单条用量记录。
 * 字段名基于已知 API 格式，ignoreUnknownKeys 保证向前兼容。
 */
@Serializable
data class UsageRecord(
    val model_name: String = "",
    val total_tokens: Long = 0,
    val prompt_tokens: Long = 0,
    val completion_tokens: Long = 0
)

/**
 * /v1/usage 响应体。
 */
@Serializable
data class UsageResponse(
    val data: List<UsageRecord> = emptyList()
)

/**
 * 持久化的用量快照（带时间戳和账户 ID）。
 */
@Serializable
data class UsageSnapshot(
    val accountId: String = "",
    val timestamp: Long,
    val records: List<UsageRecord> = emptyList()
)
