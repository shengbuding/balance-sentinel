package com.balancesentinel.app.data.model

import kotlinx.serialization.Serializable

/**
 * 单次刷新的原始记录（仅保留当日数据，上限 90000 条）。
 */
@Serializable
data class RawRecord(
    val accountId: String = "",
    val timestamp: Long,
    val currency: String,
    val totalBalance: Float,
    val grantedBalance: Float,
    val toppedUpBalance: Float
)

/**
 * 每日余额摘要（长期保留，每天每条币种一条）。
 * 由午夜闹钟或 App 启动检测触发聚合。
 */
@Serializable
data class DailySummary(
    val accountId: String = "",
    val date: String,        // "yyyy-MM-dd"
    val currency: String,
    /** 当日首次刷新的余额 */
    val open: Float,
    /** 当日最后一次刷新的余额 */
    val close: Float,
    /** 当日消耗总额（负向变化绝对值累加） */
    val consumed: Float,
    /** 当日充值总额（基于 API topped_up_balance 差值，精确） */
    val toppedUp: Float,
    /** 当日赠送金变动（基于 API granted_balance 差值） */
    val granted: Float = 0f,
    /** 当日所有刷新记录的平均余额 */
    val avgBalance: Float,
    /** 当日原始刷新记录数 */
    val sampleCount: Int,
    /** 当日收盘时的 API topped_up_balance 绝对值（用于跨天充值检测） */
    val toppedUpBalanceClose: Float = 0f,
    /** 当日收盘时的 API granted_balance 绝对值（用于跨天赠送检测） */
    val grantedBalanceClose: Float = 0f
)
